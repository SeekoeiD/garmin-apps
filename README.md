# Edge Music Control

Control your phone's music from a Garmin Edge 830: play/pause, previous/next track, and volume up/down on a dedicated full-screen page, with track name, play state, and volume shown on the Edge. Works with any media app (YouTube Music, Spotify, podcasts, local files) whether audio plays through Bluetooth headphones or the phone speaker.

Connect IQ has no API for controlling phone media playback, so this is a two-part system:

- **`edge-app/`** — a Connect IQ device app (Monkey C) for the Edge 830. Launched from the IQ menu mid-ride; sends commands over the Connect IQ mobile channel (relayed by Garmin Connect Mobile).
- **`android-companion/`** — an Android app (Kotlin) whose foreground service receives the commands and executes them against the phone's active media session — the same mechanism Bluetooth headphone buttons use — plus `AudioManager` for volume. It reports playback status back to the Edge.

Android only; iOS cannot grant background apps control over other apps' playback.

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
