using System;
using System.Runtime.InteropServices;

namespace LibRyubing
{
    /// <summary>
    /// The outbound callback surface: how the managed core asks the Kotlin/Compose
    /// layer to do things it cannot do itself (show the software keyboard, present a
    /// dialog, report load progress).
    ///
    /// Rather than reverse-JNI into the JVM (as some forks do), we take a table of
    /// C function pointers from the native/JNI shim at startup. The shim implements
    /// them by marshaling to Kotlin. This keeps the ABI explicit, testable, and free
    /// of a JVM dependency inside the AOT image.
    ///
    /// The layout MUST match the C struct in
    /// src/RyubingAndroid/app/src/main/cpp/ryubing_interop.h.
    /// </summary>
    [StructLayout(LayoutKind.Sequential)]
    public unsafe struct RyubingCallbacks
    {
        // (title, message, initialText, maxLength) -> heap-allocated UTF8 string or null.
        // Blocks until the user finishes. The returned pointer is freed by the shim.
        public delegate* unmanaged<byte*, byte*, byte*, int, byte*> RequestTextInput;

        // (title, message) -> 1 = OK/accepted, 0 = cancelled.
        public delegate* unmanaged<byte*, byte*, int> ShowMessageDialog;

        // (title, message, buttonsJoinedByNewline) -> 1 = a non-OK button was pressed.
        public delegate* unmanaged<byte*, byte*, byte*, int> ShowErrorDialog;

        // (stage, current, total): progress for PTC/shader cache loading.
        public delegate* unmanaged<byte*, int, int, void> ReportProgress;

        // () -> selected user index, or -1 to cancel.
        public delegate* unmanaged<int> ShowUserSelector;
    }

    internal static unsafe class Interop
    {
        private static RyubingCallbacks _callbacks;
        private static bool _hasCallbacks;

        public static void SetCallbacks(RyubingCallbacks callbacks)
        {
            _callbacks = callbacks;
            _hasCallbacks = true;
        }

        public static bool RequestTextInput(string title, string message, string initialText, int maxLength, out string result)
        {
            result = null;

            if (!_hasCallbacks || _callbacks.RequestTextInput == null)
            {
                return false;
            }

            byte* titlePtr = Utf8.Alloc(title);
            byte* messagePtr = Utf8.Alloc(message);
            byte* initialPtr = Utf8.Alloc(initialText);

            try
            {
                byte* returned = _callbacks.RequestTextInput(titlePtr, messagePtr, initialPtr, maxLength);

                if (returned == null)
                {
                    return false;
                }

                result = Utf8.ToStringAndFree(returned);
                return true;
            }
            finally
            {
                Utf8.Free(titlePtr);
                Utf8.Free(messagePtr);
                Utf8.Free(initialPtr);
            }
        }

        public static bool ShowMessageDialog(string title, string message)
        {
            if (!_hasCallbacks || _callbacks.ShowMessageDialog == null)
            {
                return true;
            }

            byte* titlePtr = Utf8.Alloc(title);
            byte* messagePtr = Utf8.Alloc(message);

            try
            {
                return _callbacks.ShowMessageDialog(titlePtr, messagePtr) != 0;
            }
            finally
            {
                Utf8.Free(titlePtr);
                Utf8.Free(messagePtr);
            }
        }

        public static bool ShowErrorDialog(string title, string message, string[] buttons)
        {
            if (!_hasCallbacks || _callbacks.ShowErrorDialog == null)
            {
                return false;
            }

            byte* titlePtr = Utf8.Alloc(title);
            byte* messagePtr = Utf8.Alloc(message);
            byte* buttonsPtr = Utf8.Alloc(buttons is { Length: > 0 } ? string.Join('\n', buttons) : string.Empty);

            try
            {
                return _callbacks.ShowErrorDialog(titlePtr, messagePtr, buttonsPtr) != 0;
            }
            finally
            {
                Utf8.Free(titlePtr);
                Utf8.Free(messagePtr);
                Utf8.Free(buttonsPtr);
            }
        }

        public static void ReportProgress(string stage, int current, int total)
        {
            if (!_hasCallbacks || _callbacks.ReportProgress == null)
            {
                return;
            }

            byte* stagePtr = Utf8.Alloc(stage);

            try
            {
                _callbacks.ReportProgress(stagePtr, current, total);
            }
            finally
            {
                Utf8.Free(stagePtr);
            }
        }

        public static int ShowUserSelector()
        {
            if (!_hasCallbacks || _callbacks.ShowUserSelector == null)
            {
                return -1;
            }

            return _callbacks.ShowUserSelector();
        }
    }
}
