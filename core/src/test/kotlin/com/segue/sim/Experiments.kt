package com.segue.sim

import java.io.File
import java.util.Locale

class Agg {
    var trials = 0L; var attempts = 0L; var skipped = 0L; var refused = 0L
    var handed = 0L; var kept = 0L; var silent = 0L; var dualCp = 0L
    var attemptsWithDual = 0L; var trialsWithDual = 0L
    var dualMsTotal = 0.0; var dualMsMax = 0.0
    var ownerConflicts = 0L; var noOwner = 0L
    var events = 0L
    val silence = ArrayList<Double>()
    val handedSilence = ArrayList<Double>()

    fun add(sim: Simulator) {
        trials++; events += sim.events
        var anyDual = false
        for (r in sim.records) {
            if (!r.triggered) { skipped++; continue }
            attempts++
            if (r.refused) refused++
            when (r.outcome) {
                Outcome.HANDED -> { handed++; handedSilence.add(r.maxSilenceMs) }
                Outcome.SOURCE_KEPT -> kept++
                Outcome.SILENT -> silent++
                Outcome.DUAL -> dualCp++
            }
            if (r.dualMs >= 100.0) { attemptsWithDual++; anyDual = true }
            dualMsTotal += r.dualMs
            if (r.dualMs > dualMsMax) dualMsMax = r.dualMs
            if (r.ownerConflict) ownerConflicts++
            if (r.noOwner) noOwner++
            silence.add(r.maxSilenceMs)
        }
        if (anyDual) trialsWithDual++
    }

    fun merge(o: Agg) {
        trials += o.trials; attempts += o.attempts; skipped += o.skipped; refused += o.refused
        handed += o.handed; kept += o.kept; silent += o.silent; dualCp += o.dualCp
        attemptsWithDual += o.attemptsWithDual; trialsWithDual += o.trialsWithDual
        dualMsTotal += o.dualMsTotal; dualMsMax = maxOf(dualMsMax, o.dualMsMax)
        ownerConflicts += o.ownerConflicts; noOwner += o.noOwner; events += o.events
        silence.addAll(o.silence); handedSilence.addAll(o.handedSilence)
    }
}

fun pct(xs: ArrayList<Double>, q: Double): Double {
    if (xs.isEmpty()) return Double.NaN
    val s = xs.sorted()
    return s[minOf(s.size - 1, (q * (s.size - 1)).toInt())]
}

fun runCell(variant: Variant, p: SimParams, trials: Int, baseSeed: Long): Agg {
    val threads = 2
    val parts = Array(threads) { Agg() }
    val ts = (0 until threads).map { th ->
        Thread {
            var i = th
            while (i < trials) {
                val sim = Simulator(variant, p, baseSeed + i)   // same seed across variants => same fault schedule
                sim.run()
                parts[th].add(sim)
                i += threads
            }
        }.also { it.start() }
    }
    ts.forEach { it.join() }
    val a = Agg(); parts.forEach { a.merge(it) }
    return a
}

fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val trials = if (args.isNotEmpty()) args[0].toInt() else 20000
    val outDir = File(if (args.size > 1) args[1] else "results"); outDir.mkdirs()
    val out = File(outDir, "handoff_sim.csv").printWriter()
    out.println("experiment,variant,loss,skew_ms,p_partition,p_crash,crash_role,heavy_delay,dup,p_start_fail,trials,attempts,skipped,refused,handed,source_kept,silent,dual_at_checkpoint,attempts_with_dual,trials_with_dual,dual_ms_total,dual_ms_max,owner_conflicts,no_owner,silence_p50,silence_p95,silence_p99,silence_mean,handed_silence_p50,handed_silence_p95,events")

    val base = SimParams()
    val cells = ArrayList<Pair<String, SimParams>>()
    cells.add("fault_free" to SimParams(loss = 0.0, skewMs = 0, pPartition = 0.0, pCrash = 0.0, pStartFail = 0.0, heavyDelayProb = 0.0, dupProb = 0.0, ancientDupProb = 0.0))
    for (l in listOf(0.0, 0.02, 0.05, 0.10, 0.20, 0.30)) cells.add("loss_sweep" to base.copy(loss = l))
    for (s in listOf(0L, 500L, 1000L, 2000L, 5000L, 10000L)) cells.add("skew_sweep" to base.copy(skewMs = s, pPartition = 0.0, pCrash = 0.0))
    for (c in listOf(0.0, 0.02, 0.05, 0.10)) cells.add("crash_sweep" to base.copy(pCrash = c, pPartition = 0.0))
    for (q in listOf(0.0, 0.05, 0.10, 0.20)) cells.add("partition_sweep" to base.copy(pPartition = q, pCrash = 0.0))
    cells.add("crash_source_only" to base.copy(pCrash = 0.10, pPartition = 0.0, crashRole = 1))
    cells.add("crash_target_only" to base.copy(pCrash = 0.10, pPartition = 0.0, crashRole = 2))
    cells.add("delay_dup_stress" to base.copy(loss = 0.10, heavyDelayProb = 0.30, dupProb = 0.30, ancientDupProb = 0.10, pPartition = 0.10, pCrash = 0.0))

    val t0 = System.currentTimeMillis()
    var cellIdx = 0
    for ((name, p) in cells) {
        cellIdx++
        for (v in Variant.entries) {
            val a = runCell(v, p, trials, 1_000_000L * cellIdx)
            out.println(listOf(name, v.name, p.loss, p.skewMs, p.pPartition, p.pCrash, p.crashRole, p.heavyDelayProb, p.dupProb, p.pStartFail, a.trials, a.attempts, a.skipped, a.refused,
                a.handed, a.kept, a.silent, a.dualCp, a.attemptsWithDual, a.trialsWithDual,
                "%.1f".format(a.dualMsTotal), "%.1f".format(a.dualMsMax), a.ownerConflicts, a.noOwner,
                "%.1f".format(pct(a.silence, 0.50)), "%.1f".format(pct(a.silence, 0.95)), "%.1f".format(pct(a.silence, 0.99)),
                "%.1f".format(a.silence.average()), "%.1f".format(pct(a.handedSilence, 0.50)), "%.1f".format(pct(a.handedSilence, 0.95)), a.events).joinToString(","))
            out.flush()
            System.err.println("%-16s %-13s loss=%.2f skew=%5d part=%.2f crash=%.2f  attempts=%d handed=%.2f%% kept=%.2f%% silent=%.3f%% dualCp=%.3f%% dual>=100ms=%.3f%% conflicts=%d  silP95=%.0f".format(
                name, v.name, p.loss, p.skewMs, p.pPartition, p.pCrash, a.attempts, 100.0 * a.handed / a.attempts, 100.0 * a.kept / a.attempts,
                100.0 * a.silent / a.attempts, 100.0 * a.dualCp / a.attempts, 100.0 * a.attemptsWithDual / a.attempts, a.ownerConflicts, pct(a.silence, 0.95)))
        }
    }
    out.close()
    System.err.println("done in ${(System.currentTimeMillis() - t0) / 1000}s")
}
