using Ryujinx.Common.Logging;
using Ryujinx.Cpu;
using Ryujinx.Memory;

namespace LibRyubing.Nce
{
    /// <summary>
    /// Reserves the identity-mapped host window that backs the NCE guest address space.
    ///
    /// Under NCE the guest VA <b>is</b> the host pointer (Eden's <c>EnableDirectMappedAddress</c>):
    /// the kernel lays the whole 39-bit process address space out inside this window, so
    /// natively executing guest code dereferences every pointer directly and no guest/host
    /// translation exists anywhere. The window must therefore exist before the application
    /// process is created; <c>ArmProcessContextFactory</c> consumes it from
    /// <c>HleConfiguration.NceAddressSpaceWindow</c>.
    /// </summary>
    internal static class NceAddressSpace
    {
        /// <summary>
        /// Tries to reserve an identity window using Eden's placement policy
        /// (random 2 MiB aligned base in <c>[2^36, 2^39 - size)</c>, 2^38 preferred, 2^37 minimum).
        /// </summary>
        /// <param name="window">Reserved window on success</param>
        /// <returns>True on success</returns>
        internal static bool TryReserve(out MemoryBlock window)
        {
            if (!AddressSpace.TryCreateIdentityWindow(
                    IdentityWindowPlacement.PreferredSize,
                    IdentityWindowPlacement.MinimumSize,
                    out window))
            {
                Logger.Error?.Print(LogClass.Cpu,
                    $"NCE|AS reserve FAILED preferred=0x{IdentityWindowPlacement.PreferredSize:X} minimum=0x{IdentityWindowPlacement.MinimumSize:X}");

                return false;
            }

            ulong baseAddress = (ulong)window.Pointer;

            Logger.Info?.Print(LogClass.Cpu,
                $"NCE|AS reserved base=0x{baseAddress:X} size=0x{window.Size:X} end=0x{baseAddress + window.Size:X} " +
                $"valid={IdentityWindowPlacement.IsValidWindow(baseAddress, window.Size)}");

            return true;
        }
    }
}
