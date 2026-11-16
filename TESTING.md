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

## 3. Install, pair (about 30 min)

Download the APK from the repository's **Releases** page: the release named *Segue … (latest build)*
always holds the newest build of `main`, as `segue-<version>-debug.apk`. Open it on each device and
allow installing from that source, or install over USB:

```
adb -s <X80-serial>   install -r segue-0.2.0-debug.apk
adb -s <iQOO7-serial> install -r segue-0.2.0-debug.apk
adb -s <Pad3-serial>  install -r segue-0.2.0-debug.apk
```

Every build is signed with the same debug key, so a newer APK installs over an older one and keeps
the pairing. To build it yourself instead: `./gradlew :app:assembleDebug`.

On each device, open Segue, grant *Nearby devices*, and tap **Allow background use**. Then apply that brand's extra settings. Menu names vary by OS version; the app lists the steps too.

- **vivo X80 and iQOO 7 (Funtouch OS):**
  - Battery › Background power consumption management (or *High background power consumption*): allow Segue.
  - Autostart: on.
  - Lock Segue in Recent apps.
- **OnePlus Pad 3 (OxygenOS):**
  - App info › Battery: allow background activity.
  - Lock Segue in Recent apps.

Pair each two devices directly, in this order. On both devices open **Devices** and tap **Add**, check the 6-digit codes match, and confirm.

1. X80 + iQOO 7
2. Pad 3 + X80 (the Pad 3 joins the group)
3. Pad 3 + iQOO 7

Every device should then list the other two as connected.

## 4. Smoke test (5 min)

1. Tap **Play test track** on the X80, then **Send to** the iQOO 7.
2. On the X80, tap **Move playback here**.
3. Listen: there is one chirp per second, and its pitch rises step by step through each 10-second cycle. A handoff should leave a short silence but never repeat or skip a pitch.
4. All three devices should show *Earbuds: ready*, and the Pad 3 should show where it is playing.

## 4b. Spotify (Premium)

Segue controls the Spotify app through Spotify's own SDK, which needs a Spotify developer app. Once:

1. Go to developer.spotify.com/dashboard, log in with the Premium account, and choose **Create app**.
2. Name and description: anything (for example *Segue*). **Redirect URI:** `segue://callback`
3. Under *Which API/SDKs are you planning to use?* tick **Android**, then add the Android package:
   - Package name: `com.segue.app`
   - SHA1 fingerprint: `F9:E2:E7:56:12:1C:2C:3A:FF:11:6B:B6:75:C1:CF:C5:A4:E2:91:B3`
4. Save, open the app's **Settings**, and copy the **Client ID** (32 characters).
5. If the devices use Spotify accounts other than the one that created the app, add them under **User Management**.

On each device:

1. Install Spotify and log in. Play any song once, so Spotify has something loaded.
2. In Segue, open **Devices** and choose **Spotify** under *Music source*. Then on **Listen**, tap **Connect Spotify**.
3. Spotify opens its approval screen once. Tap **Agree**. Segue then shows *Spotify: Connected*.
   If it says the approval failed, check that the dashboard lists the Android package `com.segue.app`
   with the SHA1 fingerprint above.

Then play a song in Spotify on one device. Segue shows the song and *Playing on this phone*. Tap **Send to …**, or on the
other device tap **Move playback here**. The music should stop on the first device and continue on the second from the same place.

Things to know:

- Choose the same *Music source* on every device. The automated tests in section 5 need **Test track**.
- With one Spotify account on all devices, Segue asks Spotify to move its own session, so the queue comes along.
  With different accounts, only the current track and position move.
- Spotify takes longer to start than the test track, so expect a gap of about a second.
- This part has not been tried on real devices yet. If it misbehaves, export the logs: they record every Spotify state change
  and every command Segue sent.

## 5. Automated test runs

The app runs the test cases itself. One device, the **conductor**, drives the run; the other is the
**peer**. The conductor arms faults on both devices over the authenticated link, triggers each
handoff, checks the outcome, and writes every case to both devices' logs.

1. On **both** devices of the pair, open the **Lab** tab and turn on **Test mode**. A device with
   test mode off refuses every test command, so a run cannot start by accident.
2. On the X80 (conductor), in **Lab → Automated tests → With iQOO 7**, tap a preset:

| Preset | Contents | Time |
|---|---|---|
| **Quick check** | 4 normal handoffs and one round of fault cases. Run this first. | about 25 min |
| **Timing** | 400 normal handoffs, alternating direction, every 20 s or so | about 75 min |
| **Faults** | Three rounds of every fault case | about 60 min |
| **Everything** | Timing then Faults | about 2 h 15 min |

3. Keep both devices about 1 m apart, screens on, earbuds connected to both. The third device stays on
   and connected as a bystander and must never play. The card shows progress and a pass/fail count.
   **Stop the run** ends it at any point.
4. Turn **Test mode** off on both devices afterwards.

What a fault round covers, first under the rollback policy, then again under safe-pause:

- **Lost and delayed messages:** PREPARE, READY, STATE_TRANSFER, TARGET_PLAYING and COMMIT dropped once,
  several times or always; 300 ms delay; every message duplicated.
- **Rejections:** the target reports no audio route; the user paused manually.
- **Link outages:** during the transfer and during the provisional window.
- **Crashes:** the coordinator killed on the source while preparing or transferring, and on the target
  while starting or in the provisional window (short and long restarts). The player keeps running,
  as when Android kills the app's process.
- **Claims:** a manual claim, two devices pressing play at the same moment, and a claim while partitioned.

Run order:

| Run | Pair (conductor first) | Preset |
|---|---|---|
| A | X80 → iQOO 7, Pad 3 bystander | Quick check, then Everything |
| A2 | X80 → Pad 3, iQOO 7 bystander | Timing |
| B. Audible gap | During run A's Timing part, record about 40 handoffs: one earbud against a laptop microphone in a quiet room, 48 kHz 16-bit WAV. Then `python3 scripts/measure_audio.py rec.wav`. | |
| C. Real-world faults | By hand: turn Bluetooth off on one device mid-handoff; try a handoff after 30 min with the screen off (Doze). The buttons under **Lab → Diagnostics** work for one-off faults. | |
| D. Battery | Unplugged, Segue on, test track playing for 4 h. Record `adb shell dumpsys battery` before and after on both devices. Repeat with Segue stopped. | |

## 6. Export the logs and send them

On **each** device, open **Lab** and tap **Export logs** and save the ZIP (for example to Drive or
Downloads). It is named `segue-logs-<device>-<time>.zip` and holds every log file, the device model and
Android version, and the app version. No USB cable is needed.

Then analyze two or three ZIPs together:

```
python3 scripts/analyze_handoffs.py segue-logs-x80-*.zip segue-logs-iqoo-7-*.zip segue-logs-pad-3-*.zip --out primary/
python3 scripts/analyze_handoffs.py segue-logs-x80-*.zip segue-logs-pad-3-*.zip --out secondary/
```

It prints handoff timing (normal cases only), a pass/fail table of every test case, and every interval in
which two devices played at once, classified as *unexpected*, *known rollback hazard* or *both users
pressed play*. With `--out` it also writes `handoffs.csv`, `cases.csv` and `dual_playback.csv`.

Send the ZIPs, the WAV files and your notes from steps 1 and 2 (codec, low-latency mode, Phase 0 timings,
whether audio switching exists). I'll fill the device table and update the paper. **Delete old** (Lab → Logs)
clears finished log files once you've exported them.

## Notes specific to this setup

- **Check the iQOO 7's Android version.** It got Android 13 in India in December 2022. If yours is still on Android 12, the stronger route check falls back to "headset connected"; the app logs which one applies.
- **Phone and tablet results are separate.** The phone pair carries the paper's main claim; the tablet pair is reported as a separate result.
- **No Continue On comparison.** Android 17's Continue On can't be compared here: none of the three devices runs Android 17.
- **Possible Bluetooth contention.** The buds' triple-device multipoint and Nearby's own Bluetooth use share each device's radio. Listen for stutter during handoffs and note it.
