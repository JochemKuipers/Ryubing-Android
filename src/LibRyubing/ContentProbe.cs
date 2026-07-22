using LibHac.Common;
using LibHac.Fs;
using LibHac.Fs.Fsa;
using LibHac.FsSystem;
using LibHac.Ncm;
using LibHac.Ns;
using LibHac.Tools.Fs;
using LibHac.Tools.FsSystem;
using LibHac.Tools.FsSystem.NcaUtils;
using Ryujinx.Common.Configuration;
using Ryujinx.Common.Logging;
using Ryujinx.Common.Utilities;
using Ryujinx.HLE.FileSystem;
using Ryujinx.HLE.Loaders.Processes.Extensions;
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.Json;
using ContentType = LibHac.Ncm.ContentType;
using Path = System.IO.Path;
using SpanHelpers = LibHac.Common.SpanHelpers;

namespace LibRyubing
{
    /// <summary>
    /// Probes NSP/XCI containers for base application metadata, title updates, and DLC
    /// without loading a full Switch device. Results are written as JSON for the Kotlin layer.
    /// </summary>
    internal static class ContentProbe
    {
        private static readonly TitleUpdateMetadataJsonSerializerContext UpdateSerializer =
            new(JsonHelper.GetDefaultSerializerOptions());

        private static readonly DownloadableContentJsonSerializerContext DlcSerializer =
            new(JsonHelper.GetDefaultSerializerOptions());

        public static bool QueryApplicationInfo(VirtualFileSystem vfs, string path, string displayName, string outJsonPath)
        {
            try
            {
                using IFileSystem pfs = OpenContainer(vfs, path, displayName);

                Dictionary<ulong, ContentMetaData> apps =
                    pfs.GetContentData(ContentMetaType.Application, vfs, IntegrityCheckLevel.None);

                if (apps.Count == 0)
                {
                    return false;
                }

                foreach ((ulong applicationId, ContentMetaData content) in apps)
                {
                    Nca controlNca = content.GetNcaByType(vfs.KeySet, ContentType.Control);
                    string titleName = Path.GetFileNameWithoutExtension(
                        string.IsNullOrEmpty(displayName) ? path : displayName);
                    string version = "0";
                    string developer = string.Empty;

                    if (controlNca != null)
                    {
                        ApplicationControlProperty control = new();
                        using UniqueRef<IFile> nacpFile = new();
                        controlNca.OpenFileSystem(NcaSectionType.Data, IntegrityCheckLevel.None)
                            .OpenFile(ref nacpFile.Ref, "/control.nacp".ToU8Span(), OpenMode.Read)
                            .ThrowIfFailure();
                        nacpFile.Get.Read(out _, 0, SpanHelpers.AsByteSpan(ref control), ReadOption.None)
                            .ThrowIfFailure();

                        version = control.DisplayVersionString.ToString();
                        for (int i = 0; i < control.Title.Length; i++)
                        {
                            ApplicationControlProperty.ApplicationTitle title = control.Title[i];
                            if (!title.NameString.IsEmpty())
                            {
                                titleName = title.NameString.ToString();
                                developer = title.PublisherString.ToString();
                                break;
                            }
                        }
                    }

                    string json = JsonSerializer.Serialize(new Dictionary<string, string>
                    {
                        ["titleId"] = applicationId.ToString("x16"),
                        ["titleName"] = titleName ?? string.Empty,
                        ["version"] = version ?? "0",
                        ["developer"] = developer ?? string.Empty,
                    });
                    File.WriteAllText(outJsonPath, json, Encoding.UTF8);
                    return true;
                }
            }
            catch (Exception ex)
            {
                Logger.Warning?.Print(LogClass.Application, $"QueryApplicationInfo failed for '{path}': {ex.Message}");
            }

            return false;
        }

        public static bool ProbeTitleUpdate(VirtualFileSystem vfs, string path, string displayName, string outJsonPath)
        {
            try
            {
                using IFileSystem pfs = OpenContainer(vfs, path, displayName);
                Dictionary<ulong, ContentMetaData> updates =
                    pfs.GetContentData(ContentMetaType.Patch, vfs, IntegrityCheckLevel.None);

                if (updates.Count == 0)
                {
                    return false;
                }

                foreach ((ulong applicationId, ContentMetaData content) in updates)
                {
                    Nca patchNca = content.GetNcaByType(vfs.KeySet, ContentType.Program);
                    Nca controlNca = content.GetNcaByType(vfs.KeySet, ContentType.Control);
                    if (patchNca == null || controlNca == null)
                    {
                        continue;
                    }

                    ApplicationControlProperty control = new();
                    using UniqueRef<IFile> nacpFile = new();
                    controlNca.OpenFileSystem(NcaSectionType.Data, IntegrityCheckLevel.None)
                        .OpenFile(ref nacpFile.Ref, "/control.nacp".ToU8Span(), OpenMode.Read)
                        .ThrowIfFailure();
                    nacpFile.Get.Read(out _, 0, SpanHelpers.AsByteSpan(ref control), ReadOption.None)
                        .ThrowIfFailure();

                    string displayVersion = control.DisplayVersionString.ToString();

                    string json = JsonSerializer.Serialize(new Dictionary<string, object>
                    {
                        ["titleId"] = applicationId.ToString("x16"),
                        ["version"] = content.Version.Version,
                        ["displayVersion"] = displayVersion ?? "0",
                        ["path"] = path,
                    });
                    File.WriteAllText(outJsonPath, json, Encoding.UTF8);
                    return true;
                }
            }
            catch (Exception ex)
            {
                Logger.Warning?.Print(LogClass.Application, $"ProbeTitleUpdate failed for '{path}': {ex.Message}");
            }

            return false;
        }

        public static bool GetDlcContentList(VirtualFileSystem vfs, string path, string displayName, ulong titleIdBase, string outJsonPath)
        {
            try
            {
                using IFileSystem pfs = OpenContainer(vfs, path, displayName);

                List<DownloadableContentNca> ncas = [];
                ulong titleIdMasked = titleIdBase & 0xFFFFFFFFFFFFE000UL;

                foreach (DirectoryEntryEx fileEntry in pfs.EnumerateEntries("/", "*.nca"))
                {
                    using var ncaFile = new UniqueRef<IFile>();
                    pfs.OpenFile(ref ncaFile.Ref, fileEntry.FullPath.ToU8Span(), OpenMode.Read).ThrowIfFailure();

                    Nca nca;
                    try
                    {
                        nca = new Nca(vfs.KeySet, ncaFile.Get.AsStorage());
                    }
                    catch
                    {
                        continue;
                    }

                    if (nca.Header.ContentType != NcaContentType.PublicData)
                    {
                        continue;
                    }

                    if ((nca.Header.TitleId & 0xFFFFFFFFFFFFE000UL) != titleIdMasked)
                    {
                        continue;
                    }

                    ncas.Add(new DownloadableContentNca
                    {
                        FullPath = fileEntry.FullPath,
                        TitleId = nca.Header.TitleId,
                        Enabled = true,
                    });
                }

                if (ncas.Count == 0)
                {
                    return false;
                }

                var container = new List<DownloadableContentContainer>
                {
                    new()
                    {
                        ContainerPath = path,
                        DownloadableContentNcaList = ncas,
                    },
                };

                JsonHelper.SerializeToFile(outJsonPath, container, DlcSerializer.ListDownloadableContentContainer);
                return true;
            }
            catch (Exception ex)
            {
                Logger.Warning?.Print(LogClass.Application, $"GetDlcContentList failed for '{path}': {ex.Message}");
            }

            return false;
        }

        public static void SaveTitleUpdates(string titleIdBase, TitleUpdateMetadata metadata)
        {
            string dir = Path.Combine(AppDataManager.GamesDirPath, titleIdBase.ToLowerInvariant());
            Directory.CreateDirectory(dir);
            string path = Path.Combine(dir, "updates.json");
            JsonHelper.SerializeToFile(path, metadata, UpdateSerializer.TitleUpdateMetadata);
        }

        public static void SaveDownloadableContents(string titleIdBase, List<DownloadableContentContainer> containers)
        {
            string dir = Path.Combine(AppDataManager.GamesDirPath, titleIdBase.ToLowerInvariant());
            Directory.CreateDirectory(dir);
            string path = Path.Combine(dir, "dlc.json");
            JsonHelper.SerializeToFile(path, containers, DlcSerializer.ListDownloadableContentContainer);
        }

        /// <summary>
        /// Like <see cref="Ryujinx.HLE.Utilities.PartitionFileSystemUtils.OpenApplicationFileSystem"/>,
        /// but uses <paramref name="displayName"/> for extension detection when <paramref name="path"/>
        /// is an fd path without a suffix.
        /// </summary>
        private static IFileSystem OpenContainer(VirtualFileSystem vfs, string path, string displayName)
        {
            FileStream file = File.OpenRead(path);
            string extension = Path.GetExtension(string.IsNullOrEmpty(displayName) ? path : displayName);

            IFileSystem partitionFileSystem;
            if (extension.Equals(".xci", StringComparison.OrdinalIgnoreCase))
            {
                partitionFileSystem = new Xci(vfs.KeySet, file.AsStorage()).OpenPartition(XciPartitionType.Secure);
            }
            else
            {
                PartitionFileSystem pfsTemp = new();
                pfsTemp.Initialize(file.AsStorage()).ThrowIfFailure();
                partitionFileSystem = pfsTemp;
            }

            vfs.ImportTickets(partitionFileSystem);
            return partitionFileSystem;
        }
    }
}
