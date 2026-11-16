#!/usr/bin/env python3
"""
Per-handoff timing from two Segue event logs (the CSV files the app writes, one per phone).

    python3 scripts/analyze_handoffs.py phoneA.csv phoneB.csv [--out handoffs.csv]

Each phone logs in its own monotonic clock (elapsedRealtime). The engine pings its peer every few
seconds and logs `clock peer=<id> offset=<peer minus self> rtt=<ms>`; the minimum-RTT sample is
used to map the target's timestamps into the source's timebase (error <= rtt/2).

For every transaction the source started (msg_out PREPARE) it reports:
  request_to_audible_ms  PREPARE sent on the source  -> target player reports audio started
  gap_ms                 source player reports paused -> target player reports started
                         (> 0 means break-before-make; < 0 would be overlap)
  outcome                committed / aborted
These are phone-side numbers. The earbuds' own source switch adds to the audible gap; measure that
acoustically (see README, "Measuring on devices").
"""
import csv
import re
import statistics
import sys


def load(path):
    rows = []
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            rows.append((int(r["t_mono_ms"]), int(r["device"]), r["kind"], r["detail"]))
    if not rows:
        sys.exit(f"{path}: empty log")
    return rows


def device_of(rows):
    return rows[0][1]


def best_offset(rows, peer):
    best = None
    for t, _, kind, detail in rows:
        if kind != "clock":
            continue
        m = re.match(r"peer=(-?\d+) offset=(-?\d+) rtt=(\d+)", detail)
        if m and int(m.group(1)) == peer:
            off, rtt = int(m.group(2)), int(m.group(3))
            if best is None or rtt < best[1]:
                best = (off, rtt)
    return best


def tx_of(detail):
    m = re.search(r"tx=([0-9a-f]+)", detail)
    return m.group(1) if m else None


def handoffs(src, tgt, offset_tgt_minus_src):
    """Transactions where `src` was the source and `tgt` the target."""
    out = []
    tgt_id = device_of(tgt)
    for i, (t, _, kind, detail) in enumerate(src):
        if kind != "msg_out" or not detail.startswith("PREPARE") or f"to={tgt_id}" not in detail:
            continue
        tx = tx_of(detail)
        if any(k == "msg_out" and d.startswith("PREPARE") and tx_of(d) == tx for _, _, k, d in src[:i]):
            continue                                  # retransmission
        t_req = t
        # source pause completion: first player_cb paused after the request, before STATE_TRANSFER for this tx
        t_st = next((tt for tt, _, k, d in src if tt >= t_req and k == "msg_out" and d.startswith("STATE_TRANSFER") and tx_of(d) == tx), None)
        t_pause = None
        if t_st is not None:
            t_pause = next((tt for tt, _, k, d in src if t_req <= tt <= t_st and k == "player_cb" and d.startswith("paused")), None)
        committed = any(k == "msg_out" and d.startswith("OWNER_COMMITTED") and tx_of(d) == tx for _, _, k, d in src)
        # target start completion after it received STATE_TRANSFER for this tx
        t_rx = next((tt for tt, _, k, d in tgt if k == "msg_in" and d.startswith("STATE_TRANSFER") and tx_of(d) == tx), None)
        t_start = None
        if t_rx is not None:
            t_start = next((tt for tt, _, k, d in tgt if tt >= t_rx and k == "player_cb" and d.startswith("started ok=true")), None)
        rec = {"t_request_ms": t_req, "tx": tx, "source": device_of(src), "target": tgt_id,
               "outcome": "committed" if committed else "aborted",
               "request_to_audible_ms": None, "gap_ms": None}
        if t_start is not None:
            t_start_src = t_start - offset_tgt_minus_src
            rec["request_to_audible_ms"] = t_start_src - t_req
            if t_pause is not None:
                rec["gap_ms"] = t_start_src - t_pause
        out.append(rec)
    return out


def pct(values, p):
    v = sorted(values)
    if not v:
        return None
    k = max(0, min(len(v) - 1, int(round(p / 100.0 * len(v) + 0.5)) - 1))   # nearest-rank
    return v[k]


def main(argv):
    if len(argv) < 3:
        sys.exit(__doc__)
    a, b = load(argv[1]), load(argv[2])
    out_path = argv[argv.index("--out") + 1] if "--out" in argv else None
    ida, idb = device_of(a), device_of(b)
    off_ab = best_offset(a, idb)
    off_ba = best_offset(b, ida)
    if off_ab is None and off_ba is None:
        sys.exit("no clock samples between the two phones; were they connected?")
    # offset of B relative to A (B minus A), from whichever side has the tighter sample
    if off_ab is not None and (off_ba is None or off_ab[1] <= off_ba[1]):
        b_minus_a, rtt = off_ab
    else:
        b_minus_a, rtt = -off_ba[0], off_ba[1]
    print(f"phones {ida} and {idb}; clock offset B-A = {b_minus_a} ms (+/- {rtt / 2:.0f} ms, min rtt {rtt} ms)")
    for name, rows in ((argv[1], a), (argv[2], b)):
        dev = next((d for _, _, k, d in rows if k == "device"), None)
        if dev:
            print(f"  {name}: {dev}")

    recs = handoffs(a, b, b_minus_a) + handoffs(b, a, -b_minus_a)
    for r in recs:                                   # common timebase (phone A) for ordering
        if r["source"] != ida:
            r["t_request_ms"] -= b_minus_a
    recs.sort(key=lambda r: r["t_request_ms"])
    for r in recs:
        print(f"  {r['source']} -> {r['target']}  tx={r['tx'][:8]}  {r['outcome']:9s}  "
              f"request_to_audible={r['request_to_audible_ms']} ms  gap={r['gap_ms']} ms")
    done = [r for r in recs if r["outcome"] == "committed" and r["gap_ms"] is not None]
    if done:
        g = [r["gap_ms"] for r in done]
        l = [r["request_to_audible_ms"] for r in done]
        print(f"committed handoffs: {len(done)} of {len(recs)}")
        print(f"  request_to_audible  median {statistics.median(l):.0f} ms  P95 {pct(l, 95)} ms  max {max(l)} ms")
        print(f"  gap                 median {statistics.median(g):.0f} ms  P95 {pct(g, 95)} ms  min {min(g)} ms")
        print(f"  overlaps (gap < 0): {sum(1 for x in g if x < 0)}")
    if out_path:
        with open(out_path, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(recs[0].keys()) if recs else ["tx"])
            w.writeheader()
            w.writerows(recs)
        print(f"wrote {out_path}")


if __name__ == "__main__":
    main(sys.argv)
