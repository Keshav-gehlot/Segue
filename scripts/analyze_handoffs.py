#!/usr/bin/env python3
"""
Analyse Segue device logs: handoff timing, per-case results of the automated campaign, and
dual playback measured from both devices' player events.

    python3 scripts/analyze_handoffs.py <device A> <device B> [<device C>] [--out DIR]

Each input is what one device produced: the ZIP saved by the app's "Export logs" button, a folder
of its CSV files, or a single CSV file.

Clocks. Every device logs in its own monotonic clock. Devices ping each other every few seconds
and log `clock peer=<id> offset=<peer minus self> rtt=<ms>`. Two devices' clocks drift apart by
tens of milliseconds per hour, so each timestamp is mapped with the lowest-RTT sample taken within
two minutes of it (error at most rtt/2), not with one offset for the whole run.

What is reported
  1. Handoff timing for every committed handoff between each pair (both directions):
       request_to_audible   PREPARE sent on the source      -> target player reports audio started
       gap                  source player reports paused     -> target player reports started
       pause / start        player command -> completion on the source / target
  2. Campaign cases (from the conductor's case_begin / case_end lines): the app's verdict and the
     overlap of the two players inside each case.
  3. Dual playback: every stretch of at least 100 ms in which two devices' players were both in
     the playing state, split into unexpected / known hazard / commanded by both users.

These are player-state measurements. What the listener hears also depends on the earbuds;
measure that with scripts/measure_audio.py.
"""
import csv
import io
import os
import re
import statistics
import sys
import zipfile

TOLERANCE_MS = 100          # the paper's tolerance for simultaneous playback


# ----------------------------------------------------------------------------- loading

def _rows(text):
    out = []
    for r in csv.DictReader(io.StringIO(text)):
        try:
            out.append((int(r["t_mono_ms"]), int(r["device"]), r["kind"], r["detail"] or ""))
        except (KeyError, TypeError, ValueError):
            continue                       # a line cut short by a kill
    return out


def load(path):
    """All events of one device, oldest first. Accepts a ZIP export, a directory or one CSV."""
    rows = []
    if os.path.isdir(path):
        for root, _, files in os.walk(path):
            for f in sorted(files):
                if f.endswith(".csv"):
                    rows += _rows(open(os.path.join(root, f), errors="replace").read())
    elif zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for name in sorted(z.namelist()):
                if name.endswith(".csv"):
                    rows += _rows(z.read(name).decode("utf-8", "replace"))
    else:
        rows = _rows(open(path, errors="replace").read())
    if not rows:
        sys.exit(f"{path}: no log lines found")
    rows.sort(key=lambda r: r[0])          # monotonic time keeps running across app restarts
    ids = {r[1] for r in rows}
    if len(ids) != 1:
        sys.exit(f"{path}: contains logs of {len(ids)} devices; give one input per device")
    return rows


class Device:
    def __init__(self, path):
        self.path = path
        self.rows = load(path)
        self.id = self.rows[0][1]
        self.info = next((d for _, _, k, d in self.rows if k == "device"), "")
        m = re.search(r"name=(.*?) android=", self.info)
        self.name = (m.group(1) if m and m.group(1) != "-" else None) or str(self.id)
        self.clock = {}                    # peer id -> [(t, offset, rtt)]
        for t, _, k, d in self.rows:
            if k == "clock":
                m = re.match(r"peer=(-?\d+) offset=(-?\d+) rtt=(\d+)", d)
                if m:
                    self.clock.setdefault(int(m.group(1)), []).append((t, int(m.group(2)), int(m.group(3))))
        self.intervals = playing_intervals(self.rows)


def playing_intervals(rows):
    """[start, end] stretches in which this device's player was playing, in its own clock."""
    out, start, last_t = [], None, rows[0][0]
    for t, _, k, d in rows:
        if k == "player_cb" and d.startswith("started ok=true"):
            if start is None:
                start = t
        elif k == "player_cb" and d.startswith("paused"):
            if start is not None:
                out.append([start, t]); start = None
        elif k == "start" and "playing=false" in d:
            # A new process: the old one died with its player. Close at the last line it wrote.
            if start is not None:
                out.append([start, last_t]); start = None
        last_t = t
    if start is not None:
        out.append([start, rows[-1][0]])
    return out


# ----------------------------------------------------------------------------- clocks

class Mapper:
    """Maps a timestamp of device `other` into the clock of device `base`."""

    def __init__(self, base, other):
        # samples as (t in base clock, other minus base, rtt)
        s = [(t, off, rtt) for t, off, rtt in base.clock.get(other.id, [])]
        s += [(t - (-off), -off, rtt) for t, off, rtt in other.clock.get(base.id, [])]
        self.samples = sorted(s)
        self.ok = bool(s)
        self.worst = 0

    def offset_at(self, t_base):
        near = [x for x in self.samples if abs(x[0] - t_base) <= 120_000]
        best = min(near, key=lambda x: x[2]) if near else min(self.samples, key=lambda x: abs(x[0] - t_base))
        self.worst = max(self.worst, best[2])
        return best[1]

    def to_base(self, t_other):
        # other = base + offset; iterate once because the offset is looked up by base time
        guess = t_other - min(self.samples, key=lambda x: abs(x[0] + x[1] - t_other))[1]
        return t_other - self.offset_at(guess)


# ----------------------------------------------------------------------------- handoffs

def tx_of(detail):
    m = re.search(r"tx=([0-9a-f]+)", detail)
    return m.group(1) if m else None


def first(rows, pred, lo=None, hi=None):
    for t, _, k, d in rows:
        if lo is not None and t < lo:
            continue
        if hi is not None and t > hi:
            break
        if pred(k, d):
            return t
    return None


def last_before(rows, pred, hi, lo):
    best = None
    for t, _, k, d in rows:
        if t < lo:
            continue
        if t > hi:
            break
        if pred(k, d):
            best = t
    return best


def handoffs(src, tgt, to_src):
    """Transactions with `src` as source and `tgt` as target; times in src's clock."""
    out, seen = [], set()
    for t, _, k, d in src.rows:
        if k != "msg_out" or not d.startswith("PREPARE") or f"to={tgt.id} " not in d + " ":
            continue
        tx = tx_of(d)
        if tx in seen:
            continue
        seen.add(tx)
        is_tx = lambda name: (lambda kk, dd: kk in ("msg_out", "msg_in") and dd.startswith(name) and tx_of(dd) == tx)
        t_st = first(src.rows, is_tx("STATE_TRANSFER"), lo=t)
        t_pause_cmd = first(src.rows, lambda kk, dd: kk == "player_cmd" and dd == "pause", lo=t, hi=t_st) if t_st else None
        t_paused = first(src.rows, lambda kk, dd: kk == "player_cb" and dd.startswith("paused"), lo=t, hi=t_st) if t_st else None
        committed = first(src.rows, lambda kk, dd: kk == "msg_out" and dd.startswith("OWNER_COMMITTED") and tx_of(dd) == tx, lo=t) is not None
        t_rx = first(tgt.rows, lambda kk, dd: kk == "msg_in" and dd.startswith("STATE_TRANSFER") and tx_of(dd) == tx)
        t_play_cmd = first(tgt.rows, lambda kk, dd: kk == "player_cmd" and dd.startswith("play"), lo=t_rx) if t_rx else None
        t_started = first(tgt.rows, lambda kk, dd: kk == "player_cb" and dd.startswith("started ok=true"), lo=t_rx, hi=t_rx + 6000) if t_rx else None
        rec = {"t_request_ms": t, "tx": tx, "source": src.id, "target": tgt.id,
               "outcome": "committed" if committed else "not committed",
               "request_to_audible_ms": None, "gap_ms": None, "pause_ms": None, "start_ms": None}
        if t_pause_cmd is not None and t_paused is not None:
            rec["pause_ms"] = t_paused - t_pause_cmd
        if t_play_cmd is not None and t_started is not None:
            rec["start_ms"] = t_started - t_play_cmd
        if t_started is not None:
            ts = to_src(t_started)
            rec["request_to_audible_ms"] = ts - t
            if t_paused is not None:
                rec["gap_ms"] = ts - t_paused
        out.append(rec)
    return out


def handshakes(dev):
    """Milliseconds from radio link up to authenticated link, per connection."""
    out, up = [], {}
    for t, _, k, d in dev.rows:
        m = re.match(r"ep=(\S+)", d)
        if k == "link_up" and m:
            up[m.group(1)] = t
        elif k == "secure":
            m = re.search(r"ep=(\S+)", d)
            if m and m.group(1) in up:
                out.append(t - up.pop(m.group(1)))
    return out


def pct(values, p):
    v = sorted(values)
    return v[max(0, min(len(v) - 1, int(round(p / 100.0 * len(v) + 0.5)) - 1))] if v else None


def stat(values):
    v = [x for x in values if x is not None]
    if not v:
        return "no data"
    return f"median {statistics.median(v):.0f} ms, P95 {pct(v, 95)} ms, min {min(v)}, max {max(v)} (n={len(v)})"


# ----------------------------------------------------------------------------- overlap and cases

def overlaps(a_int, b_int):
    """Stretches where both interval lists overlap (same clock)."""
    out = []
    for s1, e1 in a_int:
        for s2, e2 in b_int:
            s, e = max(s1, s2), min(e1, e2)
            if e > s:
                out.append((s, e))
    return sorted(out)


def cases(conductor):
    out = []
    for t, _, k, d in conductor.rows:
        if k == "case_begin":
            f = dict(re.findall(r"(\w+)=(\S+)", d))
            if out and out[-1]["end"] is None:
                out[-1]["end"] = t
            out.append({"begin": t, "end": None, "name": f.get("name", "?"), "dir": f.get("dir", "?"),
                        "policy": f.get("policy", "?"), "expect": f.get("expect", "?"),
                        "expect_dual": f.get("expect_dual", "no"), "src": int(f.get("src", 0)),
                        "tgt": int(f.get("tgt", 0)), "verdict": "no verdict", "conductor": conductor.id})
        elif k == "case_end" and out:
            m = re.search(r"verdict=(\S+)", d)
            out[-1]["verdict"] = m.group(1) if m else "?"
        elif k == "case_skip" and out is not None:
            pass
        elif k == "campaign" and d.startswith("end") and out and out[-1]["end"] is None:
            out[-1]["end"] = t
    if out and out[-1]["end"] is None:
        out[-1]["end"] = conductor.rows[-1][0]
    return out


# ----------------------------------------------------------------------------- main

def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    out_dir = None
    if "--out" in argv:
        out_dir = argv[argv.index("--out") + 1]
        args = [a for a in args if a != out_dir]
    if len(args) < 2:
        sys.exit(__doc__)
    devs = [Device(p) for p in args]
    if len({d.id for d in devs}) != len(devs):
        sys.exit("two inputs belong to the same device")
    base = devs[0]
    maps = {base.id: None}
    print("Devices")
    for d in devs:
        print(f"  {d.id}  {d.name}  {d.info}")
        if d is not base:
            m = Mapper(base, d)
            if not m.ok:
                print(f"  ! no clock samples between {base.name} and {d.name}: it is left out of the cross-device results")
                continue
            maps[d.id] = m
    usable = [d for d in devs if d.id in maps]

    def to_base(dev, t):
        return t if maps[dev.id] is None else maps[dev.id].to_base(t)

    def base_to(dev, t):
        return t if maps[dev.id] is None else t + maps[dev.id].offset_at(t)

    # 1. handoffs (printed after the cases are known, so that fault cases can be left out of the timing)
    all_recs, by_pair = [], []
    for s in usable:
        for g in usable:
            if s is g:
                continue
            recs = handoffs(s, g, lambda t, s=s, g=g: base_to(s, to_base(g, t)))
            for r in recs:
                r["t_request_ms"] = to_base(s, r["t_request_ms"])
                r["case"] = ""
            if recs:
                by_pair.append((s, g, recs))
                all_recs += recs

    # 2. dual playback, in the base clock
    ivs = {d.id: [(to_base(d, s), to_base(d, e)) for s, e in d.intervals] for d in usable}
    duals = []
    for i, x in enumerate(usable):
        for y in usable[i + 1:]:
            for s, e in overlaps(ivs[x.id], ivs[y.id]):
                if e - s >= TOLERANCE_MS:
                    duals.append({"start": s, "ms": e - s, "a": x.name, "b": y.name, "case": "", "class": "unexpected"})

    # 3. campaign cases
    all_cases = []
    for d in usable:
        for c in cases(d):
            c["begin_b"], c["end_b"] = to_base(d, c["begin"]), to_base(d, c["end"])
            c["overlap_ms"] = sum(min(e, c["end_b"]) - max(s, c["begin_b"])
                                  for x in duals for s, e in [(x["start"], x["start"] + x["ms"])]
                                  if min(e, c["end_b"]) > max(s, c["begin_b"]))
            all_cases.append(c)
    all_cases.sort(key=lambda c: c["begin_b"])
    for x in duals:
        for c in all_cases:
            if c["begin_b"] <= x["start"] < c["end_b"]:
                x["case"] = f"{c['name']} ({c['dir']}, {c['policy']})"
                x["class"] = {"hazard": "known hazard", "user": "both users pressed play"}.get(c["expect_dual"], "unexpected")
                break

    for r in all_recs:
        for c in all_cases:
            if c["begin_b"] <= r["t_request_ms"] < c["end_b"]:
                r["case"] = c["name"]
                break
    in_campaign = any(r["case"] for r in all_recs)
    print("\nHandoff timing" + (" (fault-free handoffs: \"normal\" cases and handoffs outside the campaign)" if in_campaign else ""))
    for s, g, recs in by_pair:
        clean = [r for r in recs if r["case"] in ("", "normal")]
        done = [r for r in clean if r["outcome"] == "committed" and r["gap_ms"] is not None]
        print(f"  {s.name} -> {g.name}: {len(done)} committed of {len(clean)} started" +
              (f" ({len(recs) - len(clean)} more inside fault cases, not timed)" if len(recs) != len(clean) else ""))
        if done:
            print(f"    request to target audio  {stat([r['request_to_audible_ms'] for r in done])}")
            print(f"    source paused to target started (gap)  {stat([r['gap_ms'] for r in done])}")
            print(f"    source pause latency  {stat([r['pause_ms'] for r in done])}")
            print(f"    target start latency  {stat([r['start_ms'] for r in done])}")
            print(f"    handoffs with a negative gap (overlap): {sum(1 for r in done if r['gap_ms'] < 0)}")
    for d in devs:
        h = handshakes(d)
        if h:
            print(f"  handshake on {d.name}: {stat(h)}")
    worst = max([m.worst for m in maps.values() if m is not None] or [0])
    print(f"  clock alignment error: at most {worst / 2:.0f} ms (half the largest round trip used)")

    if all_cases:
        print("\nCampaign cases")
        print(f"  {'case':32s} {'policy':19s} {'runs':>4s} {'pass':>4s} {'fail':>4s} {'other':>5s} {'with overlap':>12s} {'max ms':>7s}  note")
        groups = {}
        for c in all_cases:
            groups.setdefault((c["name"], c["policy"], c["expect_dual"]), []).append(c)
        for (name, policy, exp), cs in groups.items():
            ov = [c["overlap_ms"] for c in cs]
            note = {"hazard": "overlap expected: orphaned-player hazard", "user": "overlap allowed: both users pressed play"}.get(exp, "")
            print(f"  {name:32s} {policy:19s} {len(cs):4d} {sum(c['verdict'] == 'pass' for c in cs):4d} "
                  f"{sum(c['verdict'] == 'fail' for c in cs):4d} {sum(c['verdict'] not in ('pass', 'fail') for c in cs):5d} "
                  f"{sum(o > 0 for o in ov):12d} {max(ov):7d}  {note}")
        print(f"  total: {len(all_cases)} cases, {sum(c['verdict'] == 'pass' for c in all_cases)} passed, "
              f"{sum(c['verdict'] == 'fail' for c in all_cases)} failed, "
              f"{sum(c['verdict'] not in ('pass', 'fail') for c in all_cases)} without a verdict")

    print(f"\nDual playback (both players playing for at least {TOLERANCE_MS} ms)")
    for cls in ("unexpected", "known hazard", "both users pressed play"):
        sel = [x for x in duals if x["class"] == cls]
        line = f"  {cls}: {len(sel)}"
        if sel:
            line += f", longest {max(x['ms'] for x in sel)} ms"
        print(line)
    for x in [x for x in duals if x["class"] == "unexpected"][:20]:
        print(f"    at {x['start']} ms ({base.name} clock): {x['ms']} ms, {x['a']} and {x['b']}" + (f", during {x['case']}" if x["case"] else ", outside any case"))

    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
        def write(name, rows, fields):
            with open(os.path.join(out_dir, name), "w", newline="") as f:
                w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
                w.writeheader(); w.writerows(rows)
        all_recs.sort(key=lambda r: r["t_request_ms"])
        write("handoffs.csv", all_recs, ["t_request_ms", "tx", "source", "target", "outcome", "case", "request_to_audible_ms", "gap_ms", "pause_ms", "start_ms"])
        write("cases.csv", all_cases, ["begin_b", "name", "dir", "policy", "expect", "expect_dual", "verdict", "overlap_ms", "src", "tgt", "conductor"])
        write("dual_playback.csv", duals, ["start", "ms", "a", "b", "class", "case"])
        print(f"\nwrote handoffs.csv, cases.csv and dual_playback.csv to {out_dir}")


if __name__ == "__main__":
    main(sys.argv)
