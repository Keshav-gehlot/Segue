#!/usr/bin/env python3
"""
Audible gap and position continuity from a microphone recording of the earbuds during handoffs.

    python3 scripts/measure_audio.py recording.wav            (needs numpy)

The reference player's test signal (app/.../player/Signal.kt) is a quiet 220 Hz bed plus a 60 ms
chirp at the start of every content second, pitch 1000 + 200*(second % 10) Hz. From a recording:
  - silent stretches of the 220 Hz bed are the audible gaps (this includes the earbuds' own
    source switch, which the phone logs cannot see);
  - chirp onsets give content time: across a gap the chirp spacing is 1000 ms + gap - error,
    so position_error_ms = gap_ms - (spacing_ms - 1000). Positive = content skipped, negative = repeated.
"""
import sys
import wave

import numpy as np

WIN_MS = 20      # analysis window for the 220 Hz bed
HOP_MS = 2       # hop between windows (sets the time resolution)


def load(path):
    with wave.open(path, "rb") as w:
        rate, ch, width, n = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()
        if width != 2:
            sys.exit("need 16-bit PCM WAV")
        x = np.frombuffer(w.readframes(n), dtype="<i2").astype(np.float64)
    if ch > 1:
        x = x.reshape(-1, ch).mean(axis=1)
    return x / 32768.0, rate


def windows(x, rate, win_ms, hop_ms):
    """Rectangular windows, so a window's energy scales with the square of its overlap with the signal."""
    win, hop = int(rate * win_ms / 1000), int(rate * hop_ms / 1000)
    return np.lib.stride_tricks.sliding_window_view(x, win)[::hop], win, hop


def band(rows, rate, win, lo, hi):
    f = np.fft.rfftfreq(win, 1 / rate)
    return (np.abs(np.fft.rfft(rows, axis=1))[:, (f > lo) & (f < hi)] ** 2).sum(axis=1)


def runs(mask):
    i = 0
    while i < len(mask):
        if mask[i]:
            j = i
            while j < len(mask) and mask[j]:
                j += 1
            yield i, j
            i = j
        else:
            i += 1


def half_overlap_ratio(rate):
    """Band energy of a window half filled with the 220 Hz bed, relative to a full window. Computed
    from the known signal (not 0.25: spectral leakage moves energy out of the narrow band)."""
    win = int(rate * WIN_MS / 1000)
    t = np.arange(win) / rate
    full = np.sin(2 * np.pi * 220.0 * t)
    ratios = []
    for phase in np.linspace(0, 2 * np.pi, 16, endpoint=False):
        tone = np.sin(2 * np.pi * 220.0 * t + phase)
        half = tone.copy(); half[win // 2:] = 0
        ratios.append(band(half[None, :], rate, win, 180, 260)[0] / band(tone[None, :], rate, win, 180, 260)[0])
    return float(np.mean(ratios))


def gaps(x, rate, min_ms=30):
    """Silent stretches of the 220 Hz bed: (start_ms, duration_ms).
    Coarse detection uses a low global threshold (tolerates the two phones playing at different
    volumes). Each edge is then placed where that side's own bed energy falls to the half-overlap
    level of a rectangular window, so the window centre sits on the edge."""
    w, win, hop = windows(x, rate, WIN_MS, HOP_MS)
    e = band(w, rate, win, 180, 260)
    ctx = int(0.3 * rate / hop)                       # 300 ms of context on each side
    r = half_overlap_ratio(rate)
    out = []
    for i, j in runs(e < np.median(e) * 0.02):
        left = e[max(0, i - ctx):i]
        right = e[j:j + ctx]
        if len(left) < ctx // 2 or len(right) < ctx // 2:
            continue                                  # recording starts or ends in silence
        lt, rt = np.median(left) * r, np.median(right) * r
        a = i
        while a > 0 and e[a - 1] < lt:
            a -= 1
        b = j
        while b < len(e) and e[b] < rt:
            b += 1
        dur = (b - a) * hop / rate * 1000
        if dur >= min_ms:
            out.append(((a * hop + win / 2) / rate * 1000, dur))
    return out


def chirps(x, rate):
    """(time_ms, pitch_hz) per chirp. Time = envelope peak - 30 ms (the Hann envelope peaks mid-chirp),
    which does not depend on playback volume, so a level change between phones adds no bias."""
    w, win, hop = windows(x, rate, 10, HOP_MS)
    e = band(w, rate, win, 900, 3000)
    res = []
    for i, j in runs(e > max(np.percentile(e, 99) * 0.1, 1e-12)):
        k = i + int(np.argmax(e[i:j]))
        t_peak = (k * hop + win / 2) / rate * 1000
        s = max(0, int((t_peak - 30) / 1000 * rate))
        t = min(len(x), s + int(0.06 * rate))
        sp = np.abs(np.fft.rfft(x[s:t] * np.hanning(t - s), n=1 << 15))
        ff = np.fft.rfftfreq(1 << 15, 1 / rate)
        sp[(ff < 900) | (ff > 3000)] = 0
        onset = t_peak - 30
        if not res or onset - res[-1][0] >= 400:        # merge the doubled chirp of every 10th second
            res.append((onset, float(ff[sp.argmax()])))
    return res


def main(argv):
    if len(argv) < 2:
        sys.exit(__doc__)
    x, rate = load(argv[1])
    g = gaps(x, rate)
    c = chirps(x, rate)
    print(f"{argv[1]}: {len(x) / rate:.1f} s, {len(c)} chirps, {len(g)} gaps >= 30 ms")
    for start, dur in g:
        before = [t for t, _ in c if t < start]
        after = [t for t, _ in c if t > start + dur]
        if before and after:
            spacing = after[0] - before[-1]
            err = dur - (spacing - 1000.0)
            print(f"  gap at {start / 1000:7.2f} s: {dur:5.0f} ms   chirp spacing {spacing:5.0f} ms   position error {err:+5.0f} ms")
        else:
            print(f"  gap at {start / 1000:7.2f} s: {dur:5.0f} ms   (no chirp on both sides)")
    steps = [(round((p2 - p1) / 200.0)) % 10 for (_, p1), (_, p2) in zip(c, c[1:])]
    bad = sum(1 for s in steps if s != 1)
    print(f"chirp pitch sequence: {len(steps) - bad} of {len(steps)} steps advance by one second" +
          ("" if bad == 0 else f"; {bad} skips/repeats of >= 1 s"))


if __name__ == "__main__":
    main(sys.argv)
