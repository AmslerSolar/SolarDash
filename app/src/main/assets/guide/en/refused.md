# Connection refused

The device (or its network adapter) is there, but it turns the connection down.

## Common reasons

- **Another app or device is connected** to the inverter. Many inverters and Wi-Fi adapters accept only one connection at a time. Close other monitoring apps, or switch off a second tablet reading the same inverter.
- The device is **still starting** after a power cut. Wait 5 minutes.
- The network function (Modbus TCP) was **switched off**, for example by a firmware update or a reset.

## What to do

1. Close other apps that read the same inverter, then wait 2 minutes.
2. Unplug the device's network adapter for 10 seconds and plug it in again.
3. If it keeps coming back: call your installer. They check that Modbus TCP is switched on and which port it uses.
