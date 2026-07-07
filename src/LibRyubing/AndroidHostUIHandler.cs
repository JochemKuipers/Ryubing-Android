using Ryujinx.HLE;
using Ryujinx.HLE.HOS.Applets;
using Ryujinx.HLE.HOS.Services.Account.Acc;
using Ryujinx.HLE.HOS.Services.Am.AppletOE.ApplicationProxyService.ApplicationProxy.Types;
using Ryujinx.HLE.UI;

namespace LibRyubing
{
    /// <summary>
    /// Ryubing-native <see cref="IHostUIHandler"/> for Android. It bridges HLE UI
    /// requests (software keyboard, dialogs, user selection) to the Compose layer via
    /// the <see cref="Interop"/> callback table.
    ///
    /// This is the correct integration point that Ryubing already defines; we implement
    /// the interface directly instead of inventing a parallel handler.
    /// </summary>
    internal sealed class AndroidHostUIHandler : IHostUIHandler
    {
        private readonly AccountManager _accountManager;

        public IHostUITheme HostUITheme { get; } = new AndroidHostUITheme();

        public AndroidHostUIHandler(AccountManager accountManager)
        {
            _accountManager = accountManager;
        }

        public bool DisplayInputDialog(SoftwareKeyboardUIArgs args, out string userText)
        {
            string header = args.HeaderText ?? string.Empty;
            string subtitle = args.SubtitleText ?? args.GuideText ?? string.Empty;

            if (Interop.RequestTextInput(header, subtitle, args.InitialText ?? string.Empty, args.StringLengthMax, out userText))
            {
                return true;
            }

            // On internal error / no handler, the interface contract asks us to return
            // true with null text so the applet can fall back gracefully.
            userText = null;
            return true;
        }

        public bool DisplayMessageDialog(string title, string message)
        {
            return Interop.ShowMessageDialog(title ?? string.Empty, message ?? string.Empty);
        }

        public bool DisplayMessageDialog(ControllerAppletUIArgs args)
        {
            string message =
                $"Application requests {args.PlayerCountMin}" +
                (args.PlayerCountMin != args.PlayerCountMax ? $"-{args.PlayerCountMax}" : string.Empty) +
                $" player(s) with: {args.SupportedStyles}. Configure controllers, then continue.";

            return Interop.ShowMessageDialog("Controller Applet", message);
        }

        public bool DisplayCabinetDialog(out string userText)
        {
            if (Interop.RequestTextInput("Amiibo", "Enter the Amiibo's new name", string.Empty, 32, out userText))
            {
                return true;
            }

            userText = string.Empty;
            return true;
        }

        public void DisplayCabinetMessageDialog()
        {
            Interop.ShowMessageDialog("Cabinet", "Please scan your Amiibo now.");
        }

        public void ExecuteProgram(Switch device, ProgramSpecifyKind kind, ulong value)
        {
            device.Configuration.UserChannelPersistence.ExecuteProgram(kind, value);
            AndroidHost.RequestStop();
        }

        public bool DisplayErrorAppletDialog(string title, string message, string[] buttonsText, (uint Module, uint Description)? errorCode = null)
        {
            return Interop.ShowErrorDialog(title ?? string.Empty, message ?? string.Empty, buttonsText ?? []);
        }

        public IDynamicTextInputHandler CreateDynamicTextInputHandler()
        {
            return new AndroidDynamicTextInputHandler();
        }

        public UserProfile ShowPlayerSelectDialog()
        {
            // Defer to the last-used profile; a richer selector can be wired through
            // Interop.ShowUserSelector() once the account list is surfaced to Kotlin.
            if (AccountSaveDataManager.GetLastUsedUser().TryGet(out UserProfile lastUsed))
            {
                return lastUsed;
            }

            return _accountManager?.LastOpenedUser;
        }

        public void TakeScreenshot()
        {
            // Screenshot capture is handled on the Kotlin side from the surface;
            // nothing to do in the managed host for now.
        }
    }
}
