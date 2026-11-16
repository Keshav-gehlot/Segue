package com.segue.app.player

import android.content.Context
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * Lets the earbuds' play/pause tap and the lock-screen controls drive Segue. With multipoint
 * earbuds the tap goes to the phone that is currently the audible source, which is what we want.
 */
class MediaControls(context: Context, onPlay: () -> Unit, onPause: () -> Unit) {
    private val session = MediaSession(context, "Segue").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = onPlay()
            override fun onPause() = onPause()
        })
        isActive = true
    }

    val token: MediaSession.Token get() = session.sessionToken

    fun update(playing: Boolean, positionMs: Long) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, positionMs, if (playing) 1f else 0f)
                .build()
        )
    }

    fun release() = session.release()
}
