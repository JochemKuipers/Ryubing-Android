using System;
using System.Runtime.InteropServices;
using System.Text;

namespace LibRyubing
{
    /// <summary>
    /// Small helpers for marshaling UTF-8 strings across the C ABI without relying on
    /// runtime marshalling attributes (kept explicit for NativeAOT clarity).
    /// </summary>
    internal static unsafe class Utf8
    {
        /// <summary>Allocates a NUL-terminated UTF-8 copy of <paramref name="value"/> in native memory.</summary>
        public static byte* Alloc(string value)
        {
            value ??= string.Empty;

            int byteCount = Encoding.UTF8.GetByteCount(value);
            byte* ptr = (byte*)NativeMemory.Alloc((nuint)(byteCount + 1));

            fixed (char* chars = value)
            {
                Encoding.UTF8.GetBytes(chars, value.Length, ptr, byteCount);
            }

            ptr[byteCount] = 0;
            return ptr;
        }

        public static void Free(byte* ptr)
        {
            if (ptr != null)
            {
                NativeMemory.Free(ptr);
            }
        }

        /// <summary>Reads a NUL-terminated UTF-8 string. Does not free the pointer.</summary>
        public static string ToString(byte* ptr)
        {
            return ptr == null ? null : Marshal.PtrToStringUTF8((nint)ptr);
        }

        /// <summary>Reads a NUL-terminated UTF-8 string, then frees the source pointer.</summary>
        public static string ToStringAndFree(byte* ptr)
        {
            if (ptr == null)
            {
                return null;
            }

            string result = Marshal.PtrToStringUTF8((nint)ptr);
            NativeMemory.Free(ptr);
            return result;
        }
    }
}
