package com.segue.engine

/**
 * Test model of Spotify as the App Remote SDK shows it. One [SpotifyAccount] is one Spotify Connect
 * session: every device signed in to the account reports the same track, position and paused flag,
 * and a command sent from any device acts on the device that is active, not on the sender. Only
 * the active device produces audio. This is the behaviour SpotifyController is written against;
 * it is an assumption about Spotify until it has been checked on devices.
 */
class SpotifyAccount(val sched: VirtualScheduler) {
    val apps = ArrayList<FakeSpotify>()
    var active: FakeSpotify? = null
    var uri: String? = null
    var paused = true
    private var posBase = 0L
    private var posAt = 0L
    private var playingOn: FakeSpotify? = null
    /** Session position each time a device began to play (after a resume, a play or a transfer). */
    val beginPositions = ArrayList<Long>()

    fun position(): Long = if (paused) posBase else posBase + (sched.now - posAt)
    fun setPosition(ms: Long) { posBase = ms; posAt = sched.now }
    fun state() = SpotifyState(uri, uri?.let { "Title of $it" }, "Artist", paused, position(), 1f)

    fun mutate(block: SpotifyAccount.() -> Unit) {
        posBase = position(); posAt = sched.now
        block()
        posAt = sched.now
        val playing = if (!paused && uri != null) active else null
        if (playing != null && playing !== playingOn) beginPositions.add(position())
        playingOn = playing
        for (a in apps) a.setShouldPlay(a === playing)
        for (a in apps) if (a.connected) sched.schedule(a.eventLatMs) { a.deliver(state()) }
    }
}

class FakeSpotify(val account: SpotifyAccount, private val sched: VirtualScheduler, val name: String) : SpotifyApp {
    var cmdLatMs = 60L
    var pauseLatMs = 60L
    var eventLatMs = 40L
    var audioStartMs = 300L
    /** The app is reachable but ignores commands (models a start that never happens). */
    var dead = false
    lateinit var controller: SpotifyController
    override var connected = true
    var audible = false; private set
    private var want = false
    private var gen = 0
    /** Audible intervals in true time; end = MAX while playing. */
    val intervals = ArrayList<LongArray>()
    val startPositions = ArrayList<Long>()
    /** Commands this device's controller sent, with the true time. */
    val commands = ArrayList<Pair<Long, String>>()

    init { account.apps.add(this) }

    fun commandsSince(t: Long): List<String> = commands.filter { it.first >= t }.map { it.second }

    fun setShouldPlay(w: Boolean) {
        if (w == want) return
        want = w; gen++
        if (!w) { if (audible) { audible = false; intervals.last()[1] = sched.now } }
        else {
            val g = gen
            sched.schedule(audioStartMs) {
                if (g == gen && !audible) { audible = true; intervals.add(longArrayOf(sched.now, Long.MAX_VALUE)); startPositions.add(account.position()) }
            }
        }
    }

    fun deliver(s: SpotifyState) { if (connected) controller.onState(s) }

    private fun cmd(name: String, latMs: Long, block: SpotifyAccount.() -> Unit, done: () -> Unit = {}) {
        commands.add(sched.now to name)
        if (dead) return
        sched.schedule(latMs) { account.mutate(block); done() }
    }

    override fun reconnect() {}
    override fun pause() = cmd("pause", pauseLatMs, { paused = true })
    override fun resume() = cmd("resume", cmdLatMs, { if (active == null) active = this@FakeSpotify; if (uri != null) paused = false })
    override fun play(uri: String, done: () -> Unit) =
        cmd("play", cmdLatMs, { if (active == null) active = this@FakeSpotify; this.uri = uri; setPosition(0); paused = false }, done)
    override fun seekTo(positionMs: Long) = cmd("seek", cmdLatMs, { setPosition(positionMs) })
    override fun switchToLocalDevice(done: () -> Unit) {
        commands.add(sched.now to "switch")
        sched.schedule(cmdLatMs) { if (!dead) account.mutate { active = this@FakeSpotify }; done() }
    }
    override fun readState(result: (SpotifyState) -> Unit) { sched.schedule(10) { result(account.state()) } }
    override fun musicActive(): Boolean = audible
    override fun mediaKeyPause() { if (audible) account.mutate { paused = true } }

    // --- the user, in the Spotify app on this device
    fun userPlay(uri: String) = account.mutate { active = this@FakeSpotify; this.uri = uri; setPosition(0); paused = false }
    fun userPause() = account.mutate { paused = true }
    fun userResume() = account.mutate { paused = false }
    /** Picks this device in Spotify's device list while something is playing elsewhere. */
    fun userMoveHere() = account.mutate { active = this@FakeSpotify }
}
