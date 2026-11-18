package com.kyz.downloaderhub

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

// MainActivity puts the headers that yt-dlp gave for the current track here;
// the player sends them with every request.
object PlaybackBridge {
    @Volatile var headers: Map<String, String> = emptyMap()
    @Volatile var userAgent: String = "Mozilla/5.0"

    // Called when the notification's next (+1) or previous (-1) button is pressed
    @Volatile var onSkip: ((Int) -> Unit)? = null
}

// The page owns the song queue (each song's stream link is found only when it is about to play),
// so next/previous are forwarded to it instead of being handled by the player.
private class QueuePlayer(player: Player) : ForwardingPlayer(player) {
    private val skipCommands = intArrayOf(
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
    )

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*skipCommands).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command in skipCommands || super.isCommandAvailable(command)

    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true

    override fun seekToNext() { PlaybackBridge.onSkip?.invoke(1) }
    override fun seekToNextMediaItem() { PlaybackBridge.onSkip?.invoke(1) }
    override fun seekToPrevious() { PlaybackBridge.onSkip?.invoke(-1) }
    override fun seekToPreviousMediaItem() { PlaybackBridge.onSkip?.invoke(-1) }
}

// The music player. It lives in a service so music keeps playing when you leave the app,
// and Android shows the notification + lock-screen controls for it.
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val httpFactory = DataSource.Factory {
            DefaultHttpDataSource.Factory()
                .setUserAgent(PlaybackBridge.userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(PlaybackBridge.headers)
                .createDataSource()
        }

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)      // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)       // keep Wi-Fi awake while playing
            .build()

        mediaSession = MediaSession.Builder(this, QueuePlayer(player)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // The app was swiped away: stop the service unless music is playing
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
