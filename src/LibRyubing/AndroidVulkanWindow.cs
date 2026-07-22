using Ryujinx.Common.Logging;
using Silk.NET.Vulkan;
using System;
using System.Threading;

namespace LibRyubing
{
    /// <summary>
    /// Provides the Vulkan surface for the emulator on Android.
    ///
    /// On desktop, Ryubing's <c>VulkanWindow</c> asks SDL to create the surface. On
    /// Android there is no SDL window: the surface is backed by an <c>ANativeWindow</c>
    /// obtained from the Compose <c>SurfaceView</c>. The C++/JNI shim
    /// (libryubingjni.so) owns the <c>ANativeWindow</c> and creates the
    /// <c>VkSurfaceKHR</c> via <c>vkCreateAndroidSurfaceKHR</c>. We inject that as a
    /// function pointer so the managed side never touches JNI directly.
    /// </summary>
    internal static unsafe class AndroidVulkanWindow
    {
        // (VkInstance) -> VkSurfaceKHR handle (ulong). Implemented in libryubingjni.so.
        private static delegate* unmanaged<nint, ulong> _createSurface;

        // Required instance extensions on Android: VK_KHR_surface + VK_KHR_android_surface.
        private static readonly string[] RequiredExtensions =
        [
            "VK_KHR_surface",
            "VK_KHR_android_surface",
        ];

        public static bool HasSurfaceProvider => _createSurface != null;

        public static void SetSurfaceProvider(delegate* unmanaged<nint, ulong> createSurface)
        {
            _createSurface = createSurface;
        }

        public static SurfaceKHR CreateSurface(Instance instance, Vk _)
        {
            if (_createSurface == null)
            {
                throw new InvalidOperationException(
                    "No Android Vulkan surface provider registered. The JNI shim must call " +
                    "set_surface_provider before the renderer is created.");
            }

            ulong handle = _createSurface(instance.Handle);

            // Relaunch can race the ANativeWindow briefly; retry once after a short wait.
            if (handle == 0)
            {
                Thread.Sleep(50);
                handle = _createSurface(instance.Handle);
            }

            if (handle == 0)
            {
                Logger.Error?.Print(LogClass.Gpu, "vkCreateAndroidSurfaceKHR returned a null surface.");
                throw new Exception("Failed to create Android Vulkan surface.");
            }

            return new SurfaceKHR(handle);
        }

        public static string[] GetRequiredInstanceExtensions() => RequiredExtensions;
    }
}
