using Ryujinx.Common.Configuration.Hid;
using Ryujinx.Common.Configuration.Hid.Controller;
using Ryujinx.Input;
using System.Collections.Generic;

namespace LibRyubing
{
    internal static class AndroidInputDefaults
    {
        public static List<InputConfig> CreateDefaultConfigs()
        {
            StandardControllerInputConfig player1 = InputConfigDefaults.CreateDefaultControllerConfiguration(
                AndroidGamepadDriver.PrimaryGamepadId,
                "Android Controller 1",
                ControllerType.ProController,
                PlayerIndex.Player1,
                isNintendoStyle: true);

            // Virtual Android gamepad; SDL3 backend enum is unused on bionic.
            player1.Backend = InputBackendType.GamepadSDL3;

            return [player1];
        }
    }
}
