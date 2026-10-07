# Momiji

Momiji streams games from your PS5 or PS4 to Android phones, tablets, TV boxes and Samsung DeX. It's free and open source, with no ads or tracking.

## Features

- **Finds your consoles** on your network and wakes them up from rest mode.
- **Easy registration**: sign in with Sony in your browser, and Momiji fills in your Account ID. Passkeys work too.
- **Play away from home**: sign in with Sony in Settings → Play away from home, and your consoles connect through PlayStation Network when they aren't on your network.
- **One tap**: play a console from shortcuts on the app's icon or the home screen, a Quick Settings tile, a widget, or a row on the Android TV home screen.
- **Controllers**: DualSense, DualShock 4, Xbox and other gamepads, with rumble. Button mapping, stick dead zone and a test for each controller, and its battery level in the stream menu. On-screen controls are hidden while a controller is connected.
- **DualSense**: touch haptics on the controller's own actuators over USB, gyro and touchpad, plus adaptive triggers where Android gives access to the controller, and the light bar on Android 12 and later.
- **Picture**: up to 1080p at 60 fps, H.264 or HEVC, up to 100 Mbps. Quality presets (Smooth, Balanced, Sharp), also for each console and for playing away from home, and an optional debanding filter for smooth gradients.
- **Super resolution**: an optional upscaler sharpens the stream on screens with more pixels than it, such as a 720p stream on a 1080p phone, or a 4K TV.
- **Low latency**: hardware decoding in low latency mode, the display's refresh rate matched to the stream, and the phone's low latency Wi-Fi mode. Optional statistics show the bitrate, packet loss, ping and decoding time.
- **Picture-in-picture**: leaving the app keeps the stream going in a small window.
- **Dual-screen handhelds** such as the AYN Thor: the second screen becomes the DualSense touchpad while you play, with the PS, Create and Options buttons and the stream menu.
- **TV boxes**: works with a TV remote or controller, and can start right into Momiji. Press **L1 + R1 + Options + Create** together to open the stream menu with just a controller.
- **Samsung DeX**: the stream fills the whole screen, and its sound plays on the TV even with a DualSense plugged in by USB.
- **Languages**: English and Arabic. On Android 13 and later, Momiji's language can be set apart from the device's.

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

Momiji is free and has no ads. It's made by one developer in Palestine, where PayPal, Stripe and similar services don't work, so donations are only possible in crypto. If you enjoy Momiji, a donation helps keep it going. Thank you!

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
- [Snapdragon Game Super Resolution](https://github.com/SnapdragonGameStudios/snapdragon-gsr) by Qualcomm, the upscaler behind super resolution

## Privacy

Momiji doesn't collect any personal data. See the [privacy policy](PRIVACY.md).

## License

Momiji is licensed under the GNU Affero General Public License v3.0, with an exception for OpenSSL. See [COPYING](COPYING) and [LICENSES](LICENSES).

The super resolution shader, [`sgsr1_shader_mobile_edge_direction.frag`](android/app/src/main/assets/sgsr1_shader_mobile_edge_direction.frag), is Qualcomm's Snapdragon Game Super Resolution with small changes, under the [BSD 3-Clause License](LICENSES/BSD-3-Clause.txt).

## Disclaimer

Momiji is not endorsed or certified by Sony Interactive Entertainment LLC. PlayStation, PS4, PS5, DualShock and DualSense are trademarks of Sony Interactive Entertainment LLC.
