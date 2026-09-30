# Momiji

Momiji streams games from your PS5 or PS4 to Android phones, tablets, TV boxes and Samsung DeX. It's free and open source, with no ads or tracking.

## Features

- **Finds your consoles** on your network and wakes them up from rest mode.
- **Easy registration**: sign in with Sony in your browser, and Momiji fills in your Account ID. Passkeys work too.
- **Controllers**: DualSense, DualShock 4, Xbox and other gamepads, with rumble. On-screen controls are hidden while a controller is connected.
- **DualSense**: touch haptics on the controller's own actuators over USB, gyro and touchpad, plus adaptive triggers and the lightbar where Android gives access to the controller.
- **Picture**: up to 1080p at 60 fps, H.264 or HEVC, up to 100 Mbps, with an optional debanding filter for smooth gradients.
- **Low latency**: hardware decoding in low latency mode and the phone's low latency Wi-Fi mode.
- **TV boxes**: works with a TV remote or controller, and can start right into Momiji. Press **L1 + R1 + Options + Create** together to open the stream menu with just a controller.
- **Samsung DeX**: the stream fills the whole screen, and its sound plays on the TV even with a DualSense plugged in by USB.

## Getting started

1. On your console, turn on Remote Play: **Settings → System → Remote Play → Enable Remote Play** on a PS5, or **Settings → Remote Play Connection Settings** on a PS4. To wake the console from rest mode, also turn on **Stay Connected to the Internet** and **Enable Turning On PS5 from Network** in its power saving settings.
2. Install Momiji from the [releases](https://github.com/yazanj082/momiji/releases).
3. Open Momiji, tap your console and follow the three registration steps.

If your console isn't found, check that your phone is on the same network, and that no VPN is on.

### Finding your Account ID without signing in

[`scripts/psn-account-id.py`](scripts/psn-account-id.py) finds your Account ID from a computer:

```bash
python3 scripts/psn-account-id.py
```

## Orange Pi 5 Pro image

[`scripts/orangepi5pro`](scripts/orangepi5pro) builds an SD card image that turns an Orange Pi 5 Pro into a PS5 Remote Play box, from Orange Pi's Android 12 TV image, with Momiji preinstalled.

## Building

1. Clone the repository with its submodules:
   ```bash
   git clone --recursive https://github.com/yazanj082/momiji.git
   ```
2. Open the `android` directory in Android Studio, with the Android NDK and CMake installed.
3. Build with Gradle, in the `android` directory:
   - F-Droid version (APK): `./gradlew assembleFdroidRelease`
   - Google Play version (App Bundle): `./gradlew bundlePlayRelease`

Both run on Android 7.0 and newer.

## Support

Momiji is free and has no ads. If you enjoy it, you can support its development with a crypto donation. Thank you!

| Coin | Network | Address | QR code |
| --- | --- | --- | --- |
| USDT | TRON (TRC20) | `TE8aCakZvwLApQ6G1NtDsSQ7zLF77TRiih` | <img src="docs/support/usdt-trc20.svg" width="120" alt="QR code of the TRON address"> |
| USDT | BNB Smart Chain (BEP20) | `0x6d27c717294a2693d752f5440a27CDfC9c966CFb` | <img src="docs/support/usdt-bep20.svg" width="120" alt="QR code of the BNB Smart Chain address"> |
| Bitcoin | Bitcoin | `bc1q972yfl3l32wsunarcs0c72vm9qx0eyx53s48zd` | <img src="docs/support/bitcoin.svg" width="120" alt="QR code of the Bitcoin address"> |

Send each coin only on the network next to it, as coins sent on another network can be lost.

## Credits

Momiji is built on the work of these projects and all their contributors:

- [Chiaki](https://git.sr.ht/~thestr4ng3r/chiaki) by Florian Märkl
- [chiaki-ng](https://github.com/streetpea/chiaki-ng) by Street Pea
- [chiaki-ng-android-extended](https://github.com/SalamiTheMan/chiaki-ng-android-extended) by SalamiTheMan

## Privacy

Momiji doesn't collect any personal data. See the [privacy policy](PRIVACY.md).

## License

Momiji is licensed under the GNU Affero General Public License v3.0, with an exception for OpenSSL. See [COPYING](COPYING) and [LICENSES](LICENSES).

## Disclaimer

Momiji is not endorsed or certified by Sony Interactive Entertainment LLC. PlayStation, PS4, PS5, DualShock and DualSense are trademarks of Sony Interactive Entertainment LLC.
