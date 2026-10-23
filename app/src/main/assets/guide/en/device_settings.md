# Check device settings

The device answers, but not with the data the app expects. The device usually works normally; only the connection settings don't match.

## Common reasons

- The **device type** chosen in the app doesn't match the device (for example after replacing an inverter).
- The **unit id** (Modbus address) is wrong.
- A firmware update changed the device's settings.

## What to do (installer)

1. Open the plant's **Devices** tab and edit the device.
2. Use **Search network** to find it again, or check type, port and unit id against the device's settings.
3. The text under the message shows the device's own answer, which helps with the diagnosis.
