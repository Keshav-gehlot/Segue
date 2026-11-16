package com.segue.engine

import java.util.PriorityQueue
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

fun interface Cancellable { fun cancel() }

/**
 * Single-threaded event loop. The engine touches its state only from tasks run here, so the
 * coordinator (which is not thread-safe) never sees concurrent calls.
 */
interface Scheduler {
    /** Monotonic milliseconds (SystemClock.elapsedRealtime on Android). */
    fun nowMs(): Long
    fun post(task: Runnable)
    fun schedule(delayMs: Long, task: Runnable): Cancellable
}

/** Production scheduler: one daemon thread. */
class ExecutorScheduler(
    threadName: String,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onError: (Throwable) -> Unit = { it.printStackTrace() }
) : Scheduler {
    private val exec = ScheduledThreadPoolExecutor(1) { r -> Thread(r, threadName).apply { isDaemon = true } }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    override fun nowMs(): Long = clock()

    override fun post(task: Runnable) {
        if (!exec.isShutdown) exec.execute(guard(task))
    }

    override fun schedule(delayMs: Long, task: Runnable): Cancellable {
        if (exec.isShutdown) return Cancellable { }
        val f = exec.schedule(guard(task), maxOf(0L, delayMs), TimeUnit.MILLISECONDS)
        return Cancellable { f.cancel(false) }
    }

    /** Run [block] on the loop thread and wait for its result. Never call from the loop thread. */
    fun <T> call(block: () -> T): T = exec.submit(Callable { block() }).get()

    fun shutdown() { exec.shutdownNow() }

    private fun guard(t: Runnable) = Runnable {
        try { t.run() } catch (e: Throwable) { onError(e) }
    }
}

/** Deterministic virtual-time scheduler for tests. Shared by every simulated phone. */
class VirtualScheduler(start: Long = 1_000_000L) : Scheduler {
    var now: Long = start
        private set
    private var seq = 0L

    private class Item(val at: Long, val seq: Long, val task: Runnable) { var cancelled = false }

    private val queue = PriorityQueue(compareBy<Item>({ it.at }, { it.seq }))

    override fun nowMs(): Long = now
    override fun post(task: Runnable) { queue.add(Item(now, seq++, task)) }
    override fun schedule(delayMs: Long, task: Runnable): Cancellable {
        val item = Item(now + maxOf(0L, delayMs), seq++, task)
        queue.add(item)
        return Cancellable { item.cancelled = true }
    }

    /** Advance virtual time by [ms], running everything due. */
    fun runFor(ms: Long) {
        val end = now + ms
        while (true) {
            val head = queue.peek() ?: break
            if (head.at > end) break
            queue.poll()
            if (head.at > now) now = head.at
            if (!head.cancelled) head.task.run()
        }
        now = end
    }
}

/** A view of a shared scheduler with its own monotonic origin, to model two phones' unrelated clocks. */
class OffsetScheduler(private val base: Scheduler, private val offsetMs: Long) : Scheduler {
    override fun nowMs(): Long = base.nowMs() + offsetMs
    override fun post(task: Runnable) = base.post(task)
    override fun schedule(delayMs: Long, task: Runnable): Cancellable = base.schedule(delayMs, task)
}
