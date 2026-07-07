namespace LibRyubing
{
    /// <summary>
    /// Managed entry point. Only used for the non-bionic sanity build (e.g. building on a
    /// desktop RID to type-check against upstream). On <c>linux-bionic-arm64</c> the
    /// project is published as a shared library (<c>NativeLib=Shared</c>) and the real
    /// entry points are the <see cref="Native"/> exports invoked via JNA.
    /// </summary>
    internal static class Program
    {
        public static void Main()
        {
            // Intentionally empty: libryubing.so is a library, not an executable.
        }
    }
}
