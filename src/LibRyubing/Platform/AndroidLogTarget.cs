using Ryujinx.Common.Logging;
using Ryujinx.Common.Logging.Targets;
using System;
using System.Runtime.InteropServices;
using System.Text;

namespace LibRyubing.Platform
{
    /// <summary>
    /// Routes Ryubing's logging system to the Android logcat via <c>__android_log_write</c>.
    /// This is the Ryubing-native equivalent of the desktop <c>ConsoleLogTarget</c>.
    /// </summary>
    internal sealed class AndroidLogTarget : ILogTarget
    {
        // android/log.h priorities.
        private enum AndroidLogPriority
        {
            Verbose = 2,
            Debug = 3,
            Info = 4,
            Warn = 5,
            Error = 6,
        }

        private const string LibLog = "log";
        private const string Tag = "Ryubing";

        [DllImport(LibLog, EntryPoint = "__android_log_write")]
        private static extern int AndroidLogWrite(int priority, string tag, string message);

        private readonly string _name;

        string ILogTarget.Name => _name;

        public AndroidLogTarget(string name)
        {
            _name = name;
        }

        public void Log(object sender, LogEventArgs args)
        {
            AndroidLogWrite((int)MapPriority(args.Level), Tag, Format(args));
        }

        // logcat already prefixes each line with a timestamp and the tag, so we only
        // format the class/thread/message payload here.
        private static string Format(LogEventArgs args)
        {
            StringBuilder sb = new();

            sb.Append('|').Append(args.Level.ToString()[0]).Append("| ");

            if (args.ThreadName != null)
            {
                sb.Append(args.ThreadName).Append(' ');
            }

            sb.Append(args.Message);

            if (args.Data is not null)
            {
                sb.Append(' ').Append(args.Data);
            }

            return sb.ToString();
        }

        private static AndroidLogPriority MapPriority(LogLevel level) => level switch
        {
            LogLevel.Debug => AndroidLogPriority.Debug,
            LogLevel.Info => AndroidLogPriority.Info,
            LogLevel.Notice => AndroidLogPriority.Info,
            LogLevel.Warning => AndroidLogPriority.Warn,
            LogLevel.Error => AndroidLogPriority.Error,
            LogLevel.Trace => AndroidLogPriority.Verbose,
            LogLevel.Stub => AndroidLogPriority.Debug,
            _ => AndroidLogPriority.Info,
        };

        public void Dispose() => GC.SuppressFinalize(this);
    }
}
