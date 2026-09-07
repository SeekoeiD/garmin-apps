# Treadmill Link

A Connect IQ **watch app** for the Forerunner 965 that reads speed and incline
from a Bluetooth FTMS treadmill (Reebok FR30Z), shows them live, and records the
run to a FIT file with elevation derived from the incline.

## Why a watch app and not a data field

The goal is a native elevation gain in Garmin Connect and Strava, and a data
field cannot produce one. `WatchUi.DataField.createField()` accepts only
`:count`, `:mesgType` and `:units` — everything it writes is a plain developer
field. The `:nativeNum` option, which tags a developer field with the native FIT
field number it stands in for, exists solely on
`ActivityRecording.Session.createField()`. Only an app that owns its own
recording session can use it, so this is a watch app.

The barometer route is a dead end regardless: treadmill incline does not change
air pressure, so the watch's own ascent on an indoor run is always ~0.

`Positioning` is deliberately **not** requested. With GPS off, the watch has no
native speed or distance source of its own, which gives the mapped developer
fields the clearest run at being the authoritative values.

## The nativeNum finding

`:nativeNum` does not work. It was the reason this is a watch app, and the
first real run settled it: Garmin Connect and Strava both ignore the
`native_field_num` override entirely.

The FIT file is written correctly - all nine developer field descriptions
carry their `native_field_num`, and every record has real data. But the
activity arrived in Garmin Connect and Strava with distance, speed and
elevation all zero, and only time and heart rate intact.

The tempting explanation is that the override loses to the watch's own native
`distance = 0`. That is not it: session `total_ascent` has **no** native field
in the file at all, our override says 35 m, and Connect still reported 0.
Nothing was competing with it. The override is simply not read.

Native field numbers the app still writes (harmless, and they document intent):

| Field | Message | Native num |
|---|---|---|
| `altitude` | record | 78 (`enhanced_altitude`) |
| `speed` | record | 73 (`enhanced_speed`) |
| `distance` | record | 5 |
| `grade` | record | 9 |
| `incline` | record | *(unmapped)* |
| `total_ascent` | session | 22 |
| `total_descent` | session | 23 |
| `treadmill_ascent` | session | *(unmapped)* |
| `lap_ascent` | lap | 21 |

The fix is `tools/` - see **Correcting activities** below. The watch app needs
no changes: it already captures a complete and correct dataset.

## Screen

```
              SEARCHING
      TIME              DIST
      4:12              0.85
   HR      INCLINE      SPEED
  142        5.0         12.5
  AVG        ELEV        PACE
  138        8 m         5:30
```

Recording state is the coloured dot left of the status word — red while
recording, yellow when paused. It is a dot rather than more words because the
status line sits high up where the circle is narrow: appending " / PAUSED" to
"SUBSCRIBING" ran off the rim.

`HR` is the watch's own optical reading (`Activity.Info.currentHeartRate`),
falling back to a strap relayed by the treadmill over FTMS if the watch has
none. `AVG` is the session average and only populates once recording starts.

Font tiers are fixed (MEDIUM / LARGE / XTINY) but each row measures its own
text and pulls its columns inward to stay inside the circle at its own depth.
That geometry matters more than it sounds — measured on this screen:

| font | height | "12.5" |
|---|---|---|
| XTINY | 37 | 59 |
| SMALL | 53 | 87 |
| MEDIUM | 61 | 101 |
| LARGE | 71 | 118 |
| NUMBER_MILD | 103 | 170 |
| NUMBER_MEDIUM | 128 | 215 |

A two-up row of `FONT_NUMBER_MEDIUM` needs 454px of chord, which a 454px circle
offers only at the exact centre line — so the number fonts are unusable for
anything but a single centred value.

## Controls

| Key | Action |
|---|---|
| START | start / pause the timer |
| UP / DOWN | switch between the run screen and the diagnostics page |
| MENU (hold UP) | add a lap, or re-scan when not recording |
| BACK | save / resume / discard menu |

## Treadmill link

Standard FTMS is assumed: service `0x1826`, Treadmill Data characteristic
`0x2ACD`, notifications enabled through the CCCD. The decoder walks the flags
field defensively — a packet that runs out of bytes returns what it decoded
rather than reading past the end.

The app connects to the first device that advertises `0x1826`, or whose name
contains `FR30`, `REEBOK` or `TREADMILL`. If the FR30Z advertises something
else, set the **Treadmill BLE name** app setting in Garmin Connect to a
substring of its actual name; the diagnostics page lists every device name seen
while scanning.

The diagnostics page also shows the raw notification bytes and the decoded flags
word, so an unexpected packet layout can be read straight off the watch.

## Elevation maths

Treadmill speed is belt speed, i.e. along the incline. For grade `g`
(incline % / 100):

```
vertical speed = v * sin(atan(g)) = v * g / sqrt(1 + g^2)
```

integrated at 1 Hz. Distance is integrated along the belt, matching the
treadmill console. If the treadmill reports its own elevation gain (FTMS flags
bit 4) that value is shown on the diagnostics page as a cross-check but is not
used for the recording.

## Build

```powershell
.\build.ps1                # bin\TreadmillLink.prg
.\build.ps1 -Test          # bin\TreadmillTest.prg
.\build.ps1 -Run           # build and load into a running simulator
.\build.ps1 -Test -Run     # run the unit tests
```

The SDK and developer key default to `../tools/` in this repository;
override with `-Sdk` and `-Key`.

Start the simulator first if using `-Run`:

```powershell
& "..\tools\connectiq-sdk\bin\connectiq.bat"
```

## Install on the watch

Connect the FR965 over USB and copy `bin\TreadmillLink.prg` into
`GARMIN\Apps\` on the device, then eject. The app appears in the activity /
app list.

## v3: phone-upload pipeline (current)

The watch no longer records a FIT at all. In the default cloud mode it records
the run compactly (incline change-points + one HR byte and one speed value per
second - a 90-minute run is a few KB), and at save transmits it to the Android companion
app (the android-companion APK in this repository) over Connect IQ phone messaging. The phone
expands the run back to 1 Hz, builds the FIT natively in Kotlin - a port of
server/fit_builder.py, verified byte-for-byte against
server/testdata_reference.fit - and uploads it to Garmin Connect with OAuth
tokens pasted into the app once. Garmin syncs the activity onward to Strava
and back to the watch's own history.

Wire protocol: "tl_run" parts (part 0 = header + change lists, later parts = 60
seconds each, carrying an "hr" array and an equally long "v" array of speeds in
cm/s - a 1500-value part never reached the phone on a real 44-minute run, while
the ~300-byte header did), replied to with "tl_result". Runs are keyed by their
start epoch; the phone dedupes on it, so watch-side retries are always safe.

Speed goes on the wire twice. The per-second "v" arrays are what the chart
draws; the header's "sp" change-points are the fallback the phone uses when a
part has no "v", which is how a run parked in storage by an older build still
uploads - as the flat staircase that motivated adding "v" in the first place.
An unreachable phone at save time parks the run in watch storage for the
menu's "Retry upload".

Legacy on-watch recording (v1, nativeNum developer fields) remains selectable
via the recordMode setting.

The server/ directory is the v2 HTTP collector, kept as the reference
implementation and test-vector generator for the Kotlin port. It is not
deployed anywhere: no machine on the network has public HTTPS ingress (the
UniFi gateway owns 443; port 80 is firewalled), which is what forced v3.

## Correcting activities

`tools/` replaces the broken activity in Garmin Connect with a corrected one.
**Garmin Connect is fixed automatically. Strava is not** - see *The Strava
snag* below.

| Script | Role |
|---|---|
| `fitlib.py` | minimal FIT parse / re-emit, including CRCs |
| `fitfix.py` | moves developer values into native record/lap/session fields |
| `fitverify.py` | checks CRCs, sizes and the injected fields of a corrected file |
| `garmin_login.py` | one-time login, saves OAuth tokens |
| `treadmill_sync.py` | finds broken activities and replaces them |

### One-time setup

Credentials are read from the environment, used once, and never written to
disk — only the resulting OAuth tokens are stored, in `tools/tokens/`
(gitignored). Set them in PowerShell, run the login, then clear the password:

```powershell
$env:GARMIN_EMAIL = "you@example.com"
$env:GARMIN_PASSWORD = "..."
.\.venv\Scripts\python.exe tools\garmin_login.py
$env:GARMIN_PASSWORD = $null
```

If Garmin asks for an MFA code it will prompt on the console.

### Running it

```powershell
.\.venv\Scripts\python.exe tools\treadmill_sync.py            # dry run
.\.venv\Scripts\python.exe tools\treadmill_sync.py --apply    # replace for real
```

Dry run is the default and reports exactly what it would do. Schedule the
`--apply` form to run daily once you trust it.

To fix a FIT by hand, with no Garmin login at all:

```powershell
.\.venv\Scripts\python.exe tools\treadmill_sync.py --file activity.fit
```

### Why it is safe to let it delete

Replacing an activity means deleting the original, so there are three guards:

- **Identity, not heuristics.** A file is only rewritten if its FIT actually
  carries this app's developer fields (`incline`, `altitude`, `speed`,
  `distance`). A zero-distance treadmill run from any other source has no
  developer fields and is skipped — verified against a real MTB activity.
- **Archive before delete.** Both the original and the corrected FIT are
  written to `tools/archive/` before the remote activity is touched, so a
  failed upload is always recoverable by re-uploading the original.
- **Explicit opt-in.** Nothing destructive happens without `--apply`.

The delete-then-upload order is deliberate: Garmin rejects an upload that
duplicates an existing activity, so the original has to go first.

### The Strava snag

The original plan assumed Garmin would sync the corrected activity on to Strava
by itself. It does not. By the time the job runs, Garmin has already pushed the
broken version to Strava, and Strava refuses any later activity overlapping one
it already holds. Its public API has no delete endpoint, so this cannot be
automated away.

Verified on the first run: Garmin Connect went from 0 to 295.69 m and 35 m of
climb, while Strava kept the zeroed copy.

Per treadmill run, Strava therefore needs:

1. delete the zeroed activity in Strava (app or web - no API for this)
2. upload `tools/archive/<id>-corrected.fit` at strava.com/upload

Fully automating step 2 is possible via the Strava upload API, but step 1 never
can be. The alternative is to stop the watch app saving a Garmin session at all,
so nothing broken ever reaches Strava - at the cost of the run vanishing from
Garmin Connect entirely.

### What the rewrite produces

From the first real activity (91 records, 90 s):

```
records            : 91
total distance     : 295.7 m
avg / max speed    : 3.249 / 4.167 m/s
ascent / descent   : 35 / 0 m

session:
  total_distance = 295.7 m      avg_speed = 11.70 km/h
  total_ascent   = 35 m         max_speed = 15.00 km/h
  avg_hr         = 92           sport/sub = 1/1
```

Native altitude climbs 1482.8 m → 1517.4 m across the records, a 34.6 m gain
that agrees with the 35 m `total_ascent`. Heart rate, calories, sport and
sub-sport are carried through untouched.

The developer fields that had a native mapping are dropped once their data is
native, so Garmin Connect does not draw every trace twice. `incline` and
`treadmill_ascent` are kept, since nothing native carries them.
