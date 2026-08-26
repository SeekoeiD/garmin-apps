# garmin-apps

Connect IQ apps for Daniel's Garmin devices, plus the Android companion that
serves both:

| Directory | What |
|---|---|
| `edge-app/` | **Edge Music Control** - music remote widget for a Garmin Edge 830 |
| `treadmill-app/` | **Treadmill Link** - FR30Z treadmill runs on a Forerunner 965, with real speed/incline/elevation (see its README for the full story) |
| `android-companion/` | One Android app hosting the Connect IQ phone link for both: media control for the Edge, FIT building + Garmin Connect upload for the watch |
| `tools/` | Local toolchains (Connect IQ SDK, Gradle) and the signing key - untracked |

## Edge Music Control

Control your phone's music from a Garmin Edge 830: play/pause, previous/next
track, and volume up/down on a dedicated full-screen page, with track name,
play state, and volume shown on the Edge. Works with any media app (YouTube
Music, Spotify, podcasts, local files) whether audio plays through Bluetooth
headphones or the phone speaker.

Connect IQ has no API for controlling phone media playback, so it is a
two-part system: the Edge widget sends commands over the Connect IQ mobile
channel, and the companion's foreground service executes them against the
phone's active media session - the same mechanism Bluetooth headphone buttons
use - plus `AudioManager` for volume, reporting playback status back to the
Edge. Android only; iOS cannot grant background apps control over other apps'
playback.

## Treadmill Link

Records Reebok FR30Z treadmill runs on a Forerunner 965 with real speed,
incline and elevation gain. The watch reads the treadmill over BLE FTMS,
records compactly, and at save sends the run to the companion app, which
builds the FIT and uploads it to Garmin Connect (which syncs it to Strava and
back to the watch). Full details, including why simpler routes do not work,
in [treadmill-app/README.md](treadmill-app/README.md).

## Building

**Edge app** — needs the [Connect IQ SDK](https://developer.garmin.com/connect-iq/sdk/) with Edge 830 device files (via SDK Manager) and a developer key:

```
monkeyc -o bin\EdgeMusicControl.prg -f monkey.jungle -y <developer_key.der> -d edge830
```

**Android app** — standard Gradle build (AGP 8.5, compileSdk 34):

```
gradle assembleDebug
```

## Installing

1. Copy `EdgeMusicControl.prg` to the Edge's `Garmin\Apps` folder over USB and restart the Edge. The app appears in the IQ menu as **Music Control**.
2. Install the companion APK on the phone. In its setup screen: grant **Notification access** (this is what authorizes media-session control), exempt it from battery optimization, and start the service.
3. Garmin Connect Mobile must be installed and paired with the Edge — it relays all messages.

The Edge app and companion are bound by a shared Connect IQ app UUID (`36e2af37-6039-4d5e-995f-8ebf715014f2`); if you fork this, keep the two in sync.

Tested on an Edge 830 (Connect IQ API 3.3). Other touchscreen Edge models should work by adding them to `edge-app/manifest.xml`, but are untested.
