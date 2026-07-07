using Ryujinx.HLE.UI;

namespace LibRyubing
{
    /// <summary>
    /// Minimal <see cref="IHostUITheme"/> for the on-device software keyboard/dialog
    /// applet rendering. Mirrors the desktop HeadlessHostUiTheme; the actual UI is drawn
    /// by the Compose layer, so these values are only fallbacks.
    /// </summary>
    internal sealed class AndroidHostUITheme : IHostUITheme
    {
        public string FontFamily => "sans-serif";

        public ThemeColor DefaultBackgroundColor => new(1, 0, 0, 0);
        public ThemeColor DefaultForegroundColor => new(1, 1, 1, 1);
        public ThemeColor DefaultBorderColor => new(1, 1, 1, 1);
        public ThemeColor SelectionBackgroundColor => new(1, 1, 1, 1);
        public ThemeColor SelectionForegroundColor => new(1, 0, 0, 0);
    }
}
