using Ryujinx.Common.Logging;
using Silk.NET.Core.Contexts;
using Silk.NET.Vulkan;
using System;
using System.Runtime.InteropServices;

namespace LibRyubing
{
    /// <summary>
    /// Wraps a libvulkan.so handle opened via adrenotools so Silk.NET resolves symbols from
    /// a custom Turnip driver instead of the system loader.
    /// </summary>
    internal sealed class VulkanLoader : IDisposable
    {
        private delegate nint GetInstanceProcAddress(nint instance, nint name);
        private delegate nint GetDeviceProcAddress(nint device, nint name);

        private nint _loadedLibrary;
        private GetInstanceProcAddress? _getInstanceProcAddr;
        private GetDeviceProcAddress? _getDeviceProcAddr;

        public VulkanLoader(nint driverHandle)
        {
            _loadedLibrary = driverHandle;

            if (_loadedLibrary != nint.Zero)
            {
                nint instanceGetProc = NativeLibrary.GetExport(_loadedLibrary, "vkGetInstanceProcAddr");
                nint deviceGetProc = NativeLibrary.GetExport(_loadedLibrary, "vkGetDeviceProcAddr");

                _getInstanceProcAddr = Marshal.GetDelegateForFunctionPointer<GetInstanceProcAddress>(instanceGetProc);
                _getDeviceProcAddr = Marshal.GetDelegateForFunctionPointer<GetDeviceProcAddress>(deviceGetProc);
            }
        }

        public void Dispose()
        {
            if (_loadedLibrary != nint.Zero)
            {
                NativeLibrary.Free(_loadedLibrary);
                _loadedLibrary = nint.Zero;
            }
        }

        public unsafe Vk GetApi()
        {
            if (_loadedLibrary == nint.Zero || _getInstanceProcAddr == null || _getDeviceProcAddr == null)
            {
                return Vk.GetApi();
            }

            var ctx = new MultiNativeContext(Array.Empty<INativeContext>());
            var api = new Vk(ctx);
            ctx.Contexts = new INativeContext[]
            {
                new LamdaNativeContext(name =>
                {
                    nint namePtr = Marshal.StringToHGlobalAnsi(name);
                    try
                    {
                        nint ptr = _getInstanceProcAddr!(api.CurrentInstance.GetValueOrDefault().Handle, namePtr);
                        if (ptr == nint.Zero)
                        {
                            ptr = _getInstanceProcAddr!(nint.Zero, namePtr);
                        }

                        if (ptr == nint.Zero)
                        {
                            nint device = api.CurrentDevice.GetValueOrDefault().Handle;
                            if (device != nint.Zero)
                            {
                                ptr = _getDeviceProcAddr!(device, namePtr);
                            }
                        }

                        if (ptr == nint.Zero)
                        {
                            Logger.Warning?.Print(LogClass.Gpu, $"Failed to resolve Vulkan symbol: {name}");
                        }

                        return ptr;
                    }
                    finally
                    {
                        Marshal.FreeHGlobal(namePtr);
                    }
                }),
            };

            return api;
        }
    }
}
