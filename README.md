# Segue

[![Build](https://github.com/Keshav-gehlot/Segue/actions/workflows/build.yml/badge.svg)](https://github.com/Keshav-gehlot/Segue/actions/workflows/build.yml)

Segue moves music playback between Android devices that share multipoint Bluetooth earbuds.
It uses break-before-make: the source pauses before the target starts, and the target resumes
at the source's exact position. At most one device's player plays at a time, even when messages
are lost, clocks disagree or the app's process dies.

This repository holds the protocol core, the engine that runs it on real links, the Android app,
the fault-injection simulator and its results, and the paper.

**Paper (draft):** [`paper/segue.pdf`](paper/segue.pdf), titled *Segue: Authenticated Playback Handoff Without
Synchronized Clocks Between Android Phones Sharing Multipoint Earbuds*. It does not yet include
device measurements; [`TESTING.md`](TESTING.md) is the plan for collecting them.

```
core/     protocol: HandoffCoordinator, handshake, SecureChannel, codecs (pure Kotlin)
          core/src/test: fault-injection simulator, security experiments, self-tests
engine/   links, SAS pairing, write-ahead persistence, ownership sync, test campaign, log export (pure Kotlin)
app/      Android: foreground service, Nearby Connections, audio-route check,
          AudioTrack reference player, Keystore identity, Compose UI, fault injection
results/  outputs of the simulation and security runs reported in the paper, and figures
scripts/  analyze_handoffs.py (device logs), measure_audio.py (microphone recordings),
          make_figures.py (paper figures from results/)
paper/    LaTeX source (IEEEtran), PDF, revision notes; history/ has the pre-review version
tools/compile-check/  offline type-check of the app code (used before CI existed); tools/ci/ helps CI report errors
TESTING.md  device test plan (vivo X80, iQOO 7, OnePlus Pad 3, realme Buds Air 8)
```

## What has been verified, and how

| Part | Status |
|---|---|
| `:core` | Self-tests pass (20 checks). Since the simulation was first reported: state export/import, `allowHandoffHere()`, and one fix found by the automated campaign (a target that crashed after stopping but before starting resumed a stale position instead of the transferred snapshot). The simulation was re-run with the fix: 24 of 120 rows moved by one or two attempts; pooled dual playback and the paper's tables are unchanged. `results/` holds the re-run. |
| `:engine` | 61 end-to-end checks pass, with simulated phones on a virtual clock running the real handshake, AES-GCM channel, coordinator and file persistence (`./gradlew :engine:selfTest`). They include a full *Quick check* campaign run against ground truth and the log export. |
| Android app (`:app`, including the Compose UI and the Nearby Connections binding) | **Builds.** GitHub Actions compiles it against the real libraries and produces a debug APK on every push (first green build: 16 Nov 2026). |
| Automated device tests | The in-app campaign (Testing card) and log export are built and pass in simulation; see `TESTING.md`. |
| Running on devices | **Not done yet.** Nothing has run on a phone or tablet; `TESTING.md` is the plan. |
| `scripts/analyze_handoffs.py` | Checked on the engine-test logs: it recovers handoff timing exactly and classifies every campaign case and overlap with 0 mismatches against ground truth. Accepts exported ZIPs. |
| `scripts/measure_audio.py` | Checked on synthetic recordings with known gaps (250–410 ms), position errors (−40 to +100 ms) and a 6 dB level change between phones. All edges were within ±2 ms. |

## Build and install

Requirements: a current Android Studio with SDK Platform 37 installed (AGP 9.1.1 needs Gradle ≥ 9.3.1; the wrapper downloads 9.3.1). You also need two phones on Android 12+ with Google Play services, and multipoint Bluetooth earbuds.

```
./gradlew :core:selfTest :engine:selfTest      # JVM tests, no device needed
./gradlew :app:assembleDebug
adb -s <phoneA> install app/build/outputs/apk/debug/app-debug.apk
adb -s <phoneB> install app/build/outputs/apk/debug/app-debug.apk
```

**Ready-made APK.** Every push is built by GitHub Actions. Open the latest green run under
[Actions › Build](https://github.com/Keshav-gehlot/Segue/actions/workflows/build.yml), download the
`segue-debug-apk` artifact (you must be signed in to GitHub), unzip it and install `app-debug.apk`.
It is a debug build signed with a throwaway key, so install the same file on every device.

The same workflow runs the protocol and engine tests and re-runs the paper's simulation, failing
if the output differs from `results/handoff_sim.csv` by a single byte.

## Reproducing the paper's results

The simulation and security results in the paper come from `core/src/test`. They need only a JDK,
not the Android SDK.

```
./gradlew :core:selfTest                      # 20 protocol checks
./gradlew :core:experiments                   # 5 variants x 24 settings x 50,000 trials (about 1 min on 2 cores)
cmp core/build/results/handoff_sim.csv results/handoff_sim.csv   # should be identical (fixed seeds)
python3 scripts/make_figures.py               # rebuilds results/figures/ from results/handoff_sim.csv (matplotlib)
```

Without Gradle, with `kotlinc` (checked with Kotlin 2.1.21 and OpenJDK 21):

```
kotlinc core/src/main/kotlin core/src/test/kotlin -include-runtime -d segue-core.jar
java -cp segue-core.jar com.segue.sim.SelfTestKt
java -cp segue-core.jar com.segue.sim.SecurityExperimentsKt results
java -Xmx3g -cp segue-core.jar com.segue.sim.ExperimentsKt 50000 results
```

The security run's timing columns (microseconds per seal, milliseconds per handshake) depend on
the machine. Its counts, and every number in `handoff_sim.csv`, are deterministic.

To rebuild the paper, run `cd paper && latexmk -pdf segue.tex`. If you add citations, run
`python3 sort_refs.py segue.tex` first: it orders references by first citation, as IEEE requires.

## First run

1. **Start it.** Open Segue on both phones and grant *Nearby devices*. Notifications are optional, and location is asked for only on Android 12.
2. **Pair.** Tap **Pair a phone** on both phones. Each shows a 6-digit code; tap **They match** on both. The pairing is commit-then-reveal, so an attacker can't choose the code. Nothing is saved until both phones have confirmed and the authenticated handshake has run.
3. **Play.** Connect the earbuds to both phones (multipoint) and tap **Play test track** on phone A.
4. **Move playback.** You have three options:
   - **Send to phone B** on phone A (push).
   - **Move playback here** on phone B (pull). Both keep the position.
   - **Play here now** on phone B. This is an instant claim: phone A pauses, and B resumes its own position.
5. **Earbud button.** The earbud play/pause tap goes to the phone that is currently the audio source. Segue receives it through a MediaSession.

Before measuring anything, do the plan's **Phase 0** by hand. Pause on A, then play on B, and confirm that your earbuds really switch source and how long that takes. Segue coordinates the phones but cannot force the earbuds to switch.

## Download the APK

The newest build of `main` is always on the [Releases page](https://github.com/Keshav-gehlot/Segue/releases/tag/apk-latest)
as `segue-<version>-debug.apk`, with its SHA-256. Each `versionName` also gets its own release (`v0.2.0`, …).
CI publishes them after the tests and the simulation check pass. All builds share one debug signing key
(`app/debug.keystore`, public on purpose), so updates install over each other.

## Measuring on devices (for the paper's device table)

**Automated runs.** Turn on **Test mode** in the Testing card on both devices, then start a preset
(*Quick check*, *Timing*, *Faults*, *Everything*) from one of them. That device conducts: it arms faults on
both sides, triggers each handoff, and logs the expected and observed outcome. Then tap **Export logs** on
each device and analyze the ZIPs:

```
python3 scripts/analyze_handoffs.py segue-logs-a-*.zip segue-logs-b-*.zip [segue-logs-c-*.zip] --out results-dev/
```

The engine pings its peer every 5 s and logs a clock-offset sample. The analyzer maps each event using the lowest-RTT sample within 120 s, so its error is at most RTT/2. It reports request-to-audible time and source-pause-to-target-start gap for normal handoffs, a per-case pass/fail table, and each overlap classified as unexpected, known hazard, or both users pressing play. Full steps are in `TESTING.md`.

**Audible gap.** This includes the earbuds' own source switch. Record one earbud with a microphone during handoffs, for example with the earbud held against a laptop mic in a quiet room. Save a 16-bit WAV and run:

```
python3 scripts/measure_audio.py recording.wav          # needs numpy
```

The test track has a chirp each second whose pitch encodes the second. The script reports each silent gap and the position error across it: positive means content was skipped, negative means it was repeated.

**Fault runs by hand.** **Diagnostics** still offers one-off faults on the next handoff (drop READY or STATE_TRANSFER, kill the app in PROVISIONAL or TRANSFERRING).

## Engine design decisions beyond the paper's protocol description

These are implementation choices the paper's implementation section should mention.

1. **Write-ahead effects.** Messages and player commands emitted by the coordinator are held until its state has been stored with fsync. A crash therefore never leaves an effect that the stored state doesn't account for, which is what `onRestart()` assumes.
2. **Reboot detection.** The persisted state carries the boot count. After a reboot, `elapsedRealtime` restarts, so the lease anchor is rebased to "expired". Tested: without this, a source rebooted in TRANSFERRING would wait about 500,000 s.
3. **Sender bound to the link.** The coordinator trusts `Msg.sender`, so the engine drops any message whose sender is not the authenticated identity of the link it arrived on. Tested: phone C cannot speak for phone A.
4. **Ownership re-announcement.** A claim sent while a peer was disconnected would be lost. So each phone sends its (owner, epoch) in an authenticated status message on every link. A peer that owns at a newer epoch is treated exactly as an OWNER_CLAIM. If two phones both claimed the same epoch while partitioned, the playing phone (or the higher id) claims again.
5. **Pull.** "Move playback here" asks the owner to start a normal handoff. `allowHandoffHere()` lifts the suspension that a manual pause on the target had set.
6. **Per-device rotating names.** Advertised names are HMAC(group key, device id, 15-min epoch), not one name per group. A member can tell which phone is advertising; an outsider can link nothing across epochs. The lower device id initiates each connection.
7. **Pairing roster.** During pairing the handshake accepts only the key that was SAS-compared, and both phones apply the same rule to agree on one group key. Merging two existing groups is refused.

## Known limitations

- **Players.** Only the in-app reference player exists. The Spotify (App Remote) and MediaSession adapters from the plan come next.
- **No automatic trigger yet.** Handoffs are push, pull or claim, as the handover recommends until reliable proximity signals exist.
- **Battery.** Nearby advertising and discovery run whenever Segue is on.
- **Restart after process death.** Android's background-start limits may stop the foreground service from coming back on its own. Reopening the app restarts it.
- **Radios.** From late 2026, Nearby Connections no longer turns Bluetooth or Wi-Fi on by itself (Google, July 2026). The app warns when Bluetooth is off.
- **Bluetooth contention.** Nearby may carry the phones' link over Bluetooth/BLE, which shares the radio with the earbuds' audio. Check for audio glitches during handoffs.

## License

MIT. See [`LICENSE`](LICENSE).
