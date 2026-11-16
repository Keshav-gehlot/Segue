"""Regenerates the paper figures from results/handoff_sim.csv. Usage: python3 scripts/make_figures.py"""
import csv, os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
rows = list(csv.DictReader(open(os.path.join(ROOT, "results", "handoff_sim.csv"))))
SERIES = [("NAIVE", "A0 naive", "#2a78d6", "o"), ("EXPIRY", "A1 wall-clock expiry", "#eb6834", "s"),
          ("LEASE_RESUME", "A2 lease, rollback", "#1baf7a", "^"), ("LEASE_SAFE", "A3 lease, safe-pause", "#eda100", "D")]
INK, MUTED, GRID, SURF = "#0b0b0b", "#898781", "#e1e0d9", "#fcfcfb"

def series(exp, variant, xkey, yfun):
    rs = [r for r in rows if r["experiment"] == exp and r["variant"] == variant]
    rs.sort(key=lambda r: float(r[xkey]))
    return [float(r[xkey]) for r in rs], [yfun(r) for r in rs]

def style(ax, title, xl, yl):
    ax.set_facecolor(SURF)
    ax.set_title(title, loc="left", fontsize=10.5, color=INK, pad=10)
    ax.set_xlabel(xl, fontsize=9, color=INK); ax.set_ylabel(yl, fontsize=9, color=INK)
    ax.grid(axis="y", color=GRID, linewidth=0.8); ax.set_axisbelow(True)
    for s in ("top", "right"): ax.spines[s].set_visible(False)
    for s in ("left", "bottom"): ax.spines[s].set_color("#c3c2b7")
    ax.tick_params(colors=MUTED, labelsize=8.5)

def panel(ax, exp, xkey, yfun, xscale=1.0):
    for v, label, c, m in SERIES:
        x, y = series(exp, v, xkey, yfun)
        ax.plot([a * xscale for a in x], y, color=c, marker=m, markersize=6, linewidth=2, label=label,
                markeredgecolor=SURF, markeredgewidth=1.2)

pct_dual = lambda r: 100 * float(r["attempts_with_dual"]) / float(r["attempts"])
pct_hand = lambda r: 100 * float(r["handed"]) / float(r["attempts"])
p99 = lambda r: float(r["silence_p99"]) / 1000

fig, axes = plt.subplots(1, 3, figsize=(13.5, 4.1), facecolor=SURF)
panel(axes[0], "partition_sweep", "p_partition", pct_dual, 100)
style(axes[0], "Dual playback under link outages", "Attempts hit by a 1.5-12 s outage (%)", "Attempts with dual playback >= 100 ms (%)")
axes[0].annotate("A2 and A3 coincide at 0", xy=(10, 0), xytext=(10, 0.28), fontsize=8.5, color="#52514e", ha="center",
                 arrowprops=dict(arrowstyle="-", color="#898781", linewidth=0.8))
panel(axes[1], "skew_sweep", "skew_ms", pct_hand, 0.001)
axes[1].annotate("A0, A2 and A3 coincide (no wall clock used)", xy=(6, 95), xytext=(6, 78), fontsize=8.5, color="#52514e", ha="center",
                 arrowprops=dict(arrowstyle="-", color="#898781", linewidth=0.8))
style(axes[1], "Completed handoffs vs clock skew", "Wall-clock skew bound (s)", "Handoffs completed (%)")
axes[1].set_ylim(0, 100)
panel(axes[2], "partition_sweep", "p_partition", p99, 100)
style(axes[2], "Worst-case silence under link outages", "Attempts hit by a 1.5-12 s outage (%)", "99th-percentile longest silence (s)")
axes[2].set_ylim(0, None)
h, l = axes[0].get_legend_handles_labels()
fig.legend(h, l, loc="lower center", ncol=4, frameon=False, fontsize=9)
fig.tight_layout(rect=(0, 0.07, 1, 1))
out = os.path.join(ROOT, "results", "figures", "fig_ablation.png")
fig.savefig(out, dpi=200, facecolor=SURF)
print("wrote", out)

fig, ax = plt.subplots(figsize=(6.2, 4.0), facecolor=SURF)
for v, label, c, m in SERIES:
    x, y = series("crash_sweep", v, "p_crash", pct_dual)
    ax.plot([a * 100 for a in x], y, color=c, marker=m, markersize=6, linewidth=2, label=label, markeredgecolor=SURF, markeredgewidth=1.2)
style(ax, "Dual playback when the coordinator process dies", "Per-phone crash probability per attempt (%)", "Attempts with dual playback >= 100 ms (%)")
ax.legend(frameon=False, fontsize=8.5, loc="upper left")
fig.tight_layout()
out = os.path.join(ROOT, "results", "figures", "fig_crash.png")
fig.savefig(out, dpi=200, facecolor=SURF)
print("wrote", out)
