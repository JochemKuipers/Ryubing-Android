using System.Runtime.InteropServices;

namespace LibRyubing
{
    /// <summary>
    /// P/Invoke into libryubingjni.so for ANativeWindow buffer transform sync.
    /// </summary>
    internal static class NativeJni
    {
        [DllImport("ryubingjni", EntryPoint = "ryubingjni_set_current_transform")]
        internal static extern void SetCurrentTransform(int transform);
    }
}
