# LineageOS on Lenovo Tab TB311FU

Nov 3, 2026 · @Khan

## Summary

Two Lenovo Tab TB311FU tablets run LineageOS 23.2 (Android 16) as a GSI, with no Google or Lenovo apps. The bootloader was unlocked with mtkclient through the chip's boot ROM, because Lenovo's fastboot unlock needs a device-specific sn.img file.

| Item | Value |
| --- | --- |
| Tablets | 2, same firmware |
| Stock firmware | ZUI 17.5.36.204, Android 16, vendor Android 15, patch 2026-01-05 |
| Chip | MediaTek MT8786 (mt6768 platform), arm64, 4 GB RAM, 64 GB eMMC |
| Layout | Treble, A/B slots (active slot a), dynamic partitions in super, virtual A/B |
| Installed | lineage-23.2-20260210-UNOFFICIAL-gsi\_arm64 (Andy Yan, userdebug, no Google apps) |
| Time per tablet | about 15 min once the PC is set up |

## Prerequisites

On the PC (Debian, done once):

1. Install adb and fastboot (Debian packages).
2. Install mtkclient in a virtual environment: `git clone https://github.com/bkerler/mtkclient`, then `python3 -m venv mtkclient-venv` and `mtkclient-venv/bin/pip install ./mtkclient`.
3. Copy `50-android.rules` and `52-mtk.rules` from `mtkclient/Setup/Linux/` to `/etc/udev/rules.d/`, then `sudo udevadm control --reload-rules && sudo udevadm trigger`.
4. Stop ModemManager while working (`sudo systemctl stop ModemManager`), or it grabs the tablet's serial port.
5. Download the GSI from [Andy Yan's SourceForge](https://sourceforge.net/projects/andyyan-gsi/files/lineage-pure-userdebug/) (1.13 GB), check its SHA1 and unpack it: `gunzip -k lineage-23.2-20260210-UNOFFICIAL-gsi_arm64.img.gz` (2.4 GB).

On the tablet:

1. In the first setup, skip the Google account.
2. Settings → About tablet → tap Build number 7 times.
3. Developer options: turn on USB debugging and OEM unlocking.
4. Connect with a data cable and allow the PC's adb key.

## Step 1: unlock the bootloader

mtkclient uses the Kamakiri boot-ROM exploit, which still works on this firmware. In boot-ROM mode the screen stays black. Release the volume keys right after plugging in: holding them for about 13 seconds resets the tablet and breaks the mtkclient session.

1. Power the tablet off (`adb shell reboot -p`).
2. Start the command first, so it waits for the tablet: `mtk da seccfg unlock`.
3. Unplug the cable, hold both volume keys, plug it back in, and release the keys at once.
4. Wait for `Successfully wrote seccfg`, then run `mtk reset` so the tablet doesn't stay stuck in the loader.
5. Unplug, hold power until it boots. It shows an orange "unlocked" warning; `adb shell getprop ro.boot.verifiedbootstate` now returns `orange`.

Optional backup before unlocking: `mtk r <partitions> <files>` for the device-specific partitions (nvram, nvdata, nvcfg, proinfo, persist, protect1/2, seccfg) and the boot chain.

## Step 2: flash the GSI

The system partition sits inside super, so it is written from fastbootd (Android's userspace fastboot). The wipe erases everything on the tablet.

1. `adb reboot bootloader`, check `fastboot getvar unlocked` says yes.
2. Turn off verified boot: `fastboot --disable-verity --disable-verification flash vbmeta_a vbmeta_a.bin` (the stock vbmeta\_a, read with mtkclient).
3. `fastboot reboot fastboot`, wait until `fastboot getvar is-userspace` says yes.
4. `fastboot delete-logical-partition product_a` removes Lenovo's and Google's preinstalled apps.
5. `fastboot flash system lineage-23.2-20260210-UNOFFICIAL-gsi_arm64.img` (about 80 s).
6. `fastboot -w`, then `fastboot reboot`. The first boot takes about 1 minute and ends in the LineageOS setup wizard.

Wi-Fi, touch, display and charging work. The stock system\_ext and vendor partitions stay in place.

## Step 3: optional tweaks

| Tweak | How | Note |
| --- | --- | --- |
| Double-tap to wake | Write `1` to `/proc/gesture_control` at every boot: an init script `/system/etc/init/tap2wake.rc` running `exec_background u:r:su:s0 root root -- /system/bin/sh -c "echo 1 > /proc/gesture_control"` on `sys.boot_completed=1` | Stock Lenovo's touchscreen service set this; the GSI does not. SELinux blocks init from writing it directly. Added to a copy of the GSI image with `debugfs -w` (mode 0644, label `u:object_r:system_file:s0`), then flashed as in step 2. `adb remount` does not survive a reboot here. |
| Boot when power returns | In the bootloader: `fastboot oem off-mode-charge 0` | Without it the tablet shows the charging screen instead of starting. |
| Remove apps | `adb shell pm uninstall -k --user 0 <package>` | Survives reboots; `pm disable-user` did not on this GSI. Undo with `adb shell cmd package install-existing <package>`. |

## Pitfalls and going back to stock

- **Volume keys held too long:** holding them for about 13 seconds in boot-ROM mode resets the tablet, and a running read breaks off with "USB read failed mid-packet". Release them right after plugging in.
- **Stuck after unlock:** if the tablet stays black after mtkclient, run `mtk reset`, unplug, then hold power.
- **Stale session:** stopping mtkclient mid-session leaves its loader running on the tablet; the next command then fails. Reset and re-enter boot-ROM mode.
- **Settings lost on reboot:** Android saves package settings lazily; wait about 30 s after changing them before rebooting.
- **vbmeta:** the stock vbmeta\_a from one tablet also worked on the second (same firmware).
- **Back to stock:** write the backed-up seccfg back with mtkclient to relock, and restore the firmware with Lenovo's LMSA tool. The super partition (stock system) was not backed up.

Sources: [mtkclient](https://github.com/bkerler/mtkclient), [Andy Yan's GSI builds](https://sourceforge.net/projects/andyyan-gsi/files/).
