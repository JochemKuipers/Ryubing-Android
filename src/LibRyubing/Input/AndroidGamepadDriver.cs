using Ryujinx.Input;
using System;
using System.Collections.Generic;

namespace LibRyubing.Input
{
    /// <summary>
    /// Exposes the Android virtual gamepad(s) to the emulator's input pipeline. For the
    /// initial port a single controller ("0") is presented; additional local players can
    /// be added later by registering more <see cref="AndroidGamepad"/> instances.
    /// </summary>
    internal sealed class AndroidGamepadDriver : IGamepadDriver
    {
        public const string PrimaryGamepadId = "0";

        private readonly Dictionary<string, AndroidGamepad> _gamepads = new();

        public event Action<string> OnGamepadConnected;
        public event Action<string> OnGamepadDisconnected;

        public AndroidGamepadDriver()
        {
            _gamepads[PrimaryGamepadId] = new AndroidGamepad(PrimaryGamepadId, "Android Controller 1");
        }

        public string DriverName => "Android";

        public ReadOnlySpan<string> GamepadsIds
        {
            get
            {
                string[] ids = new string[_gamepads.Count];
                _gamepads.Keys.CopyTo(ids, 0);
                return ids;
            }
        }

        public IGamepad GetGamepad(string id) => _gamepads.GetValueOrDefault(id);

        public IEnumerable<IGamepad> GetGamepads() => _gamepads.Values;

        /// <summary>Fetches the concrete gamepad for state injection from native exports.</summary>
        public AndroidGamepad GetAndroidGamepad(string id) => _gamepads.GetValueOrDefault(id);

        public void Dispose()
        {
            foreach (AndroidGamepad gamepad in _gamepads.Values)
            {
                gamepad.Dispose();
            }

            _gamepads.Clear();
        }
    }
}
