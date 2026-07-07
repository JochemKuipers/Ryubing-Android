using Ryujinx.Common.Configuration.Hid;
using Ryujinx.HLE.HOS.Services.Hid;
using Ryujinx.Input;
using System;
using System.Numerics;
using System.Threading;

namespace LibRyubing.Input
{
    /// <summary>
    /// A single virtual gamepad whose state is pushed from the Android UI (on-screen
    /// controls or a physical controller surfaced through Kotlin) via native exports.
    ///
    /// The Compose/JNI layer sets a button bitmask and stick/motion values; the HLE
    /// input pipeline (NpadManager) reads them each frame through the IGamepad API.
    /// </summary>
    internal sealed class AndroidGamepad : IGamepad
    {
        private int _buttons;
        private float _leftStickX, _leftStickY;
        private float _rightStickX, _rightStickY;
        private Vector3 _accelerometer;
        private Vector3 _gyroscope;
        private float _triggerThreshold;

        public AndroidGamepad(string id, string name)
        {
            Id = id;
            Name = name;
        }

        public GamepadFeaturesFlag Features => GamepadFeaturesFlag.Motion | GamepadFeaturesFlag.Rumble;
        public string Id { get; }
        public string Name { get; }
        public bool IsConnected => true;

        /// <summary>Set the pressed-button bitmask (bit index == <see cref="GamepadButtonInputId"/>).</summary>
        public void SetButtons(int mask) => Volatile.Write(ref _buttons, mask);

        public void SetStick(bool right, float x, float y)
        {
            if (right)
            {
                _rightStickX = x;
                _rightStickY = y;
            }
            else
            {
                _leftStickX = x;
                _leftStickY = y;
            }
        }

        public void SetMotion(Vector3 accelerometer, Vector3 gyroscope)
        {
            _accelerometer = accelerometer;
            _gyroscope = gyroscope;
        }

        public bool IsPressed(GamepadButtonInputId inputId)
        {
            int mask = Volatile.Read(ref _buttons);
            return (mask & (1 << (int)inputId)) != 0;
        }

        public (float, float) GetStick(StickInputId inputId) => inputId switch
        {
            StickInputId.Left => (_leftStickX, _leftStickY),
            StickInputId.Right => (_rightStickX, _rightStickY),
            _ => (0f, 0f),
        };

        public Vector3 GetMotionData(MotionInputId inputId) => inputId switch
        {
            MotionInputId.Accelerometer => _accelerometer,
            MotionInputId.Gyroscope => _gyroscope,
            _ => Vector3.Zero,
        };

        public void SetTriggerThreshold(float triggerThreshold) => _triggerThreshold = triggerThreshold;
        public void SetConfiguration(InputConfig configuration) { }
        public void SetLed(uint packedRgb) { }
        public bool HDRumble(VibrationValue left, VibrationValue right) => false;
        public bool Rumble(float lowFrequency, float highFrequency, uint durationMs) => false;

        public GamepadStateSnapshot GetMappedStateSnapshot() => IGamepad.GetStateSnapshot(this);
        public GamepadStateSnapshot GetStateSnapshot() => IGamepad.GetStateSnapshot(this);

        public void Dispose() { }
    }
}
