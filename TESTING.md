# Device test plan: vivo X80 + iQOO 7 + OnePlus Pad 3 + realme Buds Air 8

Goal: fill the paper's device table (Table X) and find out whether the earbuds really follow
the device that plays. Do the steps in order; step 2 decides whether the rest is worth running.

**Device roles**

| Pair | Devices | Why |
|---|---|---|
| Primary | vivo X80 (Android 15) ↔ iQOO 7 (Android 13) | Two phones: what the paper's title claims |
| Secondary | vivo X80 ↔ OnePlus Pad 3 (Android 16) | Phone to tablet |
| Third-device check | All three | The device not in a handoff must stay silent and track the owner |

The buds support three devices at once, so keep all three connected throughout.

## 1. Set up the earbuds and devices (about 20 min)

- **Earbud firmware.** Update the buds in the realme Link app. Turn on dual/triple-device connection there if it is off.
- **Connect all three devices.** Connect the buds to all three devices. In each device's Bluetooth settings the buds must show as *connected* at the same time.
- **Wear detection.** If realme Link offers wear detection, turn it off. Taking an earbud out sends a pause, which would break the microphone runs.
- **Low-latency (game) mode.** Keep it off for all runs, or on for all runs, and write down which.
- **Google audio switching.** Devices on one Google account share Fast Pair headsets. Open the buds' entry in Bluetooth settings on each device. If there is an *Audio switching* option, turn it off for Segue runs, because it moves the earbuds between devices by itself. If it exists, keep a note: it gives a vendor comparison condition later.
- **Codec.** Developer options › *Bluetooth audio codec* shows the codec in use (the buds support SBC, AAC and LHDC). Write down what each device uses. If they differ, set both to AAC for the measured runs.
- **USB debugging.** Turn on Developer options and USB debugging on all three devices.
- **Builds.** You don't need to note them: Segue logs each device's Android version and build (`device` line).

## 2. Phase 0: does the headset follow? (no Segue, about 15 min)

1. Play music on the X80 in any app. Pause it, then press play on the iQOO 7.
   - Do you hear the iQOO 7 within about 2 s? Time it with a stopwatch.
   - Do this 10 times each way, then repeat with the X80 and the Pad 3.
2. With the X80 playing, press play on the iQOO 7 *without* pausing the X80. Which device do you hear? Does the switch happen at all?
3. With all three connected, check that a device that is *not* playing never grabs the audio.

**Stop and tell me** if the buds don't move to the device that starts playing after the other one has paused. Segue coordinates the devices but cannot force the earbuds to switch.

## 3. Build, install, pair (about 30 min)

```
./gradlew :core:selfTest :engine:selfTest :app:assembleDebug
adb -s <X80-serial>  install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <iQOO7-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <Pad3-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

On each device, open Segue, grant *Nearby devices*, and tap **Allow background use**. Then apply that brand's extra settings. Menu names vary by OS version; the app lists the steps too.

- **vivo X80 and iQOO 7 (Funtouch OS):**
  - Battery › Background power consumption management (or *High background power consumption*): allow Segue.
  - Autostart: on.
  - Lock Segue in Recent apps.
- **OnePlus Pad 3 (OxygenOS):**
  - App info › Battery: allow background activity.
  - Lock Segue in Recent apps.

Pair each two devices directly, in this order. Tap **Pair a phone** on both devices, check the 6-digit codes match, and confirm.

1. X80 + iQOO 7
2. Pad 3 + X80 (the Pad 3 joins the group)
3. Pad 3 + iQOO 7

Every device should then list the other two as connected.

## 4. Smoke test (5 min)

1. Tap **Play test track** on the X80, then **Send to** the iQOO 7.
2. On the X80, tap **Move playback here**.
3. Listen: there is one chirp per second, and its pitch rises step by step through each 10-second cycle. A handoff should leave a short silence but never repeat or skip a pitch.
4. All three devices should show *Earbuds: ready*, and the Pad 3 should show where it is playing.

## 5. Measurement campaign

| Run | How | Feeds |
|---|---|---|
| **A. Logged handoffs, primary** | X80 › Diagnostics › *With iQOO 7* › **Run 400**: one handoff every 20 s, alternating direction, about 2 h 15 min. Devices about 1 m apart, screens on. The Pad 3 stays on and connected; it must never play (third-device check). | Request-to-audio, gap, player latencies, handshake |
| **A2. Logged handoffs, secondary** | Same, *With OnePlus Pad 3* › **Run 400**, with the iQOO 7 as the bystander. | Phone-to-tablet table |
| **B. Audible gap** | During run A, record about 40 handoffs: one earbud held against a laptop microphone in a quiet room, Audacity, 48 kHz 16-bit WAV. Then `python3 scripts/measure_audio.py rec.wav`. | Audible gap, position error |
| **C. Faults** (primary pair) | Before a handoff, pick a fault in Diagnostics on the device that will be the **target** (Drop READY, Kill: provisional) or the **source** (Drop STATE, Kill: transferring). After a kill, reopen the app. Also turn Bluetooth off on one device mid-handoff, and try a handoff after 30 min with the screen off (Doze). Aim for 30 runs of each, at least 150 in total. | Fault rows, dual-playback count |
| **D. Battery** | Unplugged, Segue on, test track playing for 4 h. Record `adb shell dumpsys battery` (charge counter) before and after on both devices. Repeat with Segue stopped. | Battery row |

## 6. Collect and send

```
adb -s <X80-serial>   pull /sdcard/Android/data/com.segue.app/files/logs/ x80/
adb -s <iQOO7-serial> pull /sdcard/Android/data/com.segue.app/files/logs/ iqoo7/
adb -s <Pad3-serial>  pull /sdcard/Android/data/com.segue.app/files/logs/ pad3/
python3 scripts/analyze_handoffs.py x80/segue-*.csv iqoo7/segue-*.csv --out primary.csv
python3 scripts/analyze_handoffs.py x80/segue-*.csv pad3/segue-*.csv  --out secondary.csv
```

Send the three log folders, the WAV files, both CSVs, and your notes from steps 1 and 2: codec, low-latency mode, Phase 0 timings, and whether audio switching exists. I'll fill Table X and update the paper.

## Notes specific to this setup

- **Check the iQOO 7's Android version.** It got Android 13 in India in December 2022. If yours is still on Android 12, the stronger route check falls back to "headset connected"; the app logs which one applies.
- **Phone and tablet results are separate.** The phone pair carries the paper's main claim; the tablet pair is reported as a separate result.
- **No Continue On comparison.** Android 17's Continue On can't be compared here: none of the three devices runs Android 17.
- **Possible Bluetooth contention.** The buds' triple-device multipoint and Nearby's own Bluetooth use share each device's radio. Listen for stutter during handoffs and note it.
