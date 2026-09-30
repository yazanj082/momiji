# Orange Pi 5 Pro PS5 Remote Play image

Turns Orange Pi's Android 12 TV box firmware for the Orange Pi 5 Pro (RK3588S) into an
SD card image with Momiji preinstalled, from Linux and without root. Orange Pi only
ships this firmware as a Rockchip `update.img` for its Windows SDDiskTool.

The image adds to the firmware:

- Momiji as a system app (`/product/app/Momiji`).
- Read/write access to `/dev/hidraw*` for apps, for adaptive triggers and the lightbar.
- Input configs for the DualSense/DualShock 4 touchpad and motion sensors.
- ADB over the network (port 5555, already on in this firmware), authorized for your
  key only (`~/.android/adbkey.pub`, or `ADB_PUBKEY`); others need approval on the TV.
- A small FAT partition with a U-Boot script that clears the SPI flash if a Linux
  bootloader in it takes over the boot, so the board can boot the SD card.

## Build

1. Download `OrangePi5Pro_RK3588S_Android12-box_v1.0.1.tar.gz` from the Orange Pi 5 Pro
   downloads (Android image, "TF card and eMMC boot image").
2. Build the app (`android/`, `./gradlew assembleRelease`).
3. Prepare the firmware and tools, then build the image for your card's size in 512 byte
   sectors (`cat /sys/block/sdX/size`):

   ```sh
   ./prepare.sh OrangePi5Pro_RK3588S_Android12-box_v1.0.1.tar.gz
   ./build-image.sh app-release.apk "$(cat /sys/block/sdX/size)" opi5pro.img
   ```

4. Write it with `./flash-sd.py opi5pro.img /dev/sdX` (through UDisks, the desktop asks
   for the password) and optionally check it with `./verify-sd.py opi5pro.img /dev/sdX`.
   The image is sparse, so this only writes the ~3.3 GB of data.

The first boot runs recovery, which formats the data partition, and takes a few minutes.

## Known limitations of Orange Pi's firmware

- The kernel lacks `CONFIG_LEDS_CLASS_MULTICOLOR`, so its DualSense driver fails to
  probe and DualSense controllers don't work, over USB or Bluetooth. Xbox controllers
  work fully, including rumble.
- No Google Play Services.
- AAudio streams get disconnected over and over, Momiji falls back to OpenSL ES.
