# Revision 2: response to the external review (16 Nov 2026)

Each review point below was checked against the paper, the simulation CSV and the code before anything changed.
"Changed" = the paper or code now differs. "Clarified" = the work was right but the paper didn't say so.
The pre-review version is kept as `autoplay.v1-before-review.tex` / `.pdf` (written before the rename).

## P0: protocol correctness

| Review point | Verdict | What changed |
|---|---|---|
| Asynchronous play/pause ordering underspecified | Clarified, plus a real bug fixed | The coordinator already defers a pause that comes due while a start is pending, and issues it when the start completes; it never has two player commands outstanding. That is now rule R2, together with an explicit player contract (P1: FIFO; P2: a start completes or fails within b_start and a failed start never plays later; P3: no audio after a pause completes). Fix: the reference player's start timeout was 1.5 s, above b_start = 1 s, which violated P2. It is now b_start − 50 ms, and a test checks that a slow target fails safely. |
| Simultaneous manual claims under partition | Changed | No protocol can stop a phone it cannot reach, so the safety property now excludes overlaps that both users started explicitly. New rule R7: owner/epoch is re-announced on every authenticated link; on an equal epoch, the playing phone (or the higher id) claims again. New engine tests: convergence 280 ms after reconnection, and 370 ms for simultaneous claims over a live link (modelled latencies). |
| Recovery-query semantics incomplete | Clarified | New "Recovery" paragraph gives every transition. A commit can only happen before the source's lease expires. A target that later receives the commit resumes from its own paused position; this is safe at any time because a committed source never resumes. A commit never follows an abort decision. Behaviour when the link never returns is stated for both policies. |

## P1: security and mathematics

| Review point | Verdict | What changed |
|---|---|---|
| SAS enrolment underspecified ("nonce-only") | Clarified | The code already hashed both identity keys and both nonces; the paper's text was incomplete. The exact commit, reveal and SAS derivation is now given, with Vaudenay (CRYPTO 2005) cited for the ≈10⁻⁶ argument. The paper also states that the SAS authenticates identity keys, not a session key, and that the following handshake binds the transport token. |
| AES-GCM nonce and HKDF unspecified | Changed | The paper now gives the frame layout, nonce (0³² ‖ seq), AAD, transcript hash, the HKDF salt, info strings and lengths, and the uniqueness argument (fresh keys per handshake, seq starts at 0). It also states that nothing is persisted, because a restart forces a new handshake. |
| Relay claim too broad | Changed | The abstract and Section IX now say "relay across two transport connections". Single-connection radio relays are stated as untested and not stopped. |
| Make the drift proof quantitative | Changed | Equation (1) is the full condition, including target pause latency and scheduling delay δ; the reviewer's simpler inequality omitted both. With our parameters it holds for δ < 199 ms, and drift contributes < 1 ms. New, stronger result: safe-pause's safety needs no timing assumption at all; timing matters only for rollback. |
| Protocol vs audible safety | Changed | The safety property is now defined on player states. Audible behaviour is measured acoustically (Section X), and the abstract, model and conclusion now agree. |

## API facts

| Review point | Verdict | What changed |
|---|---|---|
| Nearby service ID vs endpoint ID | Changed (reviewer right) | The service ID is app-supplied and constant, so it can reveal that an AutoPlay phone is nearby, though not which one. The endpoint name is app-supplied and rotating (now per device). The endpoint ID is assigned by Nearby. The parts we haven't measured are stated. |
| `getDevices()` isn't route evidence | Changed (reviewer right), plus code | There are now three levels: L1 connected, L2 media route (`getAudioDevicesForAttributes`), L3 player route (`AudioTrack.getRoutedDevice`). The reference player now fails a start unless its own track is routed to the headset. |

## Experiments and statistics

| Review point | Verdict | What changed |
|---|---|---|
| "Nearly 18 million" not reproducible | Clarified (figure was correct) | A2⁻ was run in all 24 settings but the paper never said so. The total is 5 × 24 × 150,000 = 18,000,000 scheduled and 17,999,696 started (304 A0 attempts skipped). New Table IV lists the settings. |
| Independence / rule of three | Changed | The bound is now per trial: 0 of 1,150,000 trials gives 2.6 × 10⁻⁶ per trial pooled, and 6.0 × 10⁻⁵ for any single setting. The text notes that trials use distinct seeds and that variants share only the fault schedule. |
| Table IV doesn't sum | Clarified | The missing 0.057% is the "neither" state (0.051%) plus 7 skipped attempts and rounding. The Dual column was an any-time metric, not a checkpoint state. The table now shows the four checkpoint states (which sum to 100%) separately from Dual and Both-owner. |
| Abstract vs Table IV numbers | Clarified | The abstract's numbers are pooled over the 23 fault settings. New Table VI gives the pooled numerators and denominators. |

## Literature and framing

| Review point | Verdict | What changed |
|---|---|---|
| Six themes vs seven | Changed | The survey now has eight themes, with two additions (below). |
| IEEE/ACM/Scopus/patents not searched | Partly done | Site-restricted searches of IEEE Xplore, ACM DL and Google Patents were run, and the query strings are in the paper. These went through a web search engine, not the databases' own interfaces, and Scopus is still not searched, so the absence claim stays provisional. |
| Novelty claim | Changed | Found a major omission that the review also missed: Apple, Samsung and Google ship automatic headset switching between devices on one vendor account. Also found patents on position transfer and headset connection switching. Both are added to Table I. The novelty claim is narrowed to exclusivity under faults without synchronised clocks, the orphaned-player analysis, and the security layer. |
| Spotify citation | Changed | TechCrunch is replaced by Spotify's own blog post (6 Feb 2026). |
| Title "Clock-Free" | Changed | The title is now "…Without Synchronized Clocks…". This is more precise than "Wall-Clock-Independent", because the protocol also never compares the phones' monotonic clocks. |

## Still open (submission blockers)

1. **Device measurements.** Table X is all TBM. This needs two phones and multipoint earbuds; the app and both analysis scripts are ready.
2. **Repository URL.** The artifact statement is still a placeholder.
3. **First real build.** The Compose UI and Gradle build have not been compiled (no access to Google Maven in the build sandbox).
4. **Length.** The paper is now 10 pages, up from 8. Check the target venue's limit; Table I and the security details are the easiest places to trim.
5. **Formal verification.** Model checking the protocol (e.g. TLA+) would strengthen the safety argument; listed as future work.

## Rename (16 Nov 2026)

The system is now called **Segue** (was AutoPlay); the paper title starts "Segue: …".
"Baton" was considered and rejected: Nextbit shipped an Android app-handoff service under that name in 2014. It is related work and is now in Table I.

| Area | Change |
|---|---|
| Code | Packages `com.autoplay.*` became `com.segue.*`, and the app ID is `com.segue.app`. |
| Protocol | Domain-separation labels `AP2-*` became `SG2-*`. The paper's equations and the code agree. |
| Results | Two label strings in `security.csv` were renamed. No numbers changed: the full handoff simulation was re-run after the rename and its CSV is byte-identical. |

## Automated test campaign and a core fix (16 Nov 2026)

| Area | Change |
|---|---|
| Engine and app | In-app automated test campaign (test mode, presets Quick check / Timing / Faults / Everything) driven over the authenticated link, plus one-tap log export as a ZIP per device. The analyzer reads the ZIPs and classifies every overlap. |
| Core fix | Found by the campaign: a target that crashed after stopping, before its player started, resumed the player's stale position when it later committed. It now resumes from the transferred snapshot (`tgtStarted`, state codec version 2). |
| Results | Simulation re-run with the fix. 24 of 120 rows changed by one or two attempts. Pooled dual playback (A2 2,191; A3 0) and every value in Tables V–VIII and Fig. 2 are unchanged. Neither-playing attempts across the 23 fault settings: A2 4, A3 5 (was 0 and 1). |
| Paper | Implementation section: new core description (the "byte for byte" claim about the earlier additions was removed, since this change does alter the CSV), new line counts (core 1,052; engine 1,797; app 1,597), 61 engine checks, a *Test campaign* paragraph. Evaluation: the neither-playing sentence was updated. |
| Repository | MIT license; CI publishes the APK as a GitHub release (`apk-latest`, and `v<version>`). |
