using Ryujinx.HLE.UI;

namespace LibRyubing
{
    /// <summary>
    /// Implements Ryubing's <see cref="IDynamicTextInputHandler"/>. On Android there is
    /// no physical keyboard event stream to forward, so this mirrors the desktop
    /// headless handler: the inline software-keyboard applet is serviced through
    /// <see cref="AndroidHostUIHandler.DisplayInputDialog"/> instead, and this handler
    /// stays inert unless/until on-screen key events are wired up.
    /// </summary>
    internal sealed class AndroidDynamicTextInputHandler : IDynamicTextInputHandler
    {
        public event DynamicTextChangedHandler TextChangedEvent;
        public event KeyPressedHandler KeyPressedEvent { add { } remove { } }
        public event KeyReleasedHandler KeyReleasedEvent { add { } remove { } }

        public bool TextProcessingEnabled { get; set; }

        public void SetText(string text, int cursorBegin)
        {
            TextChangedEvent?.Invoke(text, cursorBegin, cursorBegin, false);
        }

        public void SetText(string text, int cursorBegin, int cursorEnd)
        {
            TextChangedEvent?.Invoke(text, cursorBegin, cursorEnd, false);
        }

        public void Dispose() { }
    }
}
