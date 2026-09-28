# Vendored third-party native code

| Directory | Upstream | License | Why |
|---|---|---|---|
| `libusb/` | https://github.com/libusb/libusb (commit 578ab76b) | LGPL-2.1+ | The Tryptify bit-perfect USB-DAC driver (app/src/main/cpp/tryptify/usb) claims the USB Audio Class streaming interface through libusb and drives the isochronous endpoint directly. |
| `soxr/` | https://github.com/chirlu/soxr (0.1.3) | LGPL-2.1+ | The LastWave-native audio engine's (app/src/main/cpp/lastwave) HQ sinc resampler, built as its own shared library exactly as upstream builds it. |

The ported Kotlin/C++ code under `app/src/main/kotlin/tf/` (Tryptify,
https://github.com/tryptz/Tryptify, GPL-3.0) and
`app/src/main/kotlin/com/lastwave/` + `audio/decent-usb-audio-driver/`
(LastWave-native, https://github.com/Clash-Projects/LastWave-native) keeps
its upstream package names so the JNI symbols bind unchanged; see the repo
worklog for the port map.
