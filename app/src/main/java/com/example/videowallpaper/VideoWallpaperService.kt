package com.example.videowallpaper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    private inner class VideoEngine : Engine() {

        private var player: ExoPlayer? = null
        private var state = Slot.LOCKED
        private var visible = false
        private var loadedVersion = -1L

        // Some OEMs (Transsion/TECNO, some Xiaomi/Oppo builds) throttle or drop
        // dynamically-registered broadcast receivers in the background, so
        // ACTION_USER_PRESENT / ACTION_SCREEN_OFF can silently never arrive.
        // This poller is a resilient fallback: it just watches KeyguardManager
        // directly and reacts to real state changes, independent of broadcasts.
        private val handler = Handler(Looper.getMainLooper())
        private var lastKeyguardLocked: Boolean? = null
        private val keyguardPoller = object : Runnable {
            override fun run() {
                checkKeyguardState()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

        private lateinit var lockedItem: MediaItem
        private lateinit var transitionItem: MediaItem
        private lateinit var unlockedItem: MediaItem

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> playTransition()
                    Intent.ACTION_SCREEN_OFF -> showLocked()
                    VideoStore.ACTION_VIDEOS_CHANGED -> reloadIfChanged()
                }
            }
        }

        private val listener = object : Player.Listener {
            // Playlist is [transition, unlocked]. When transition ends, ExoPlayer
            // advances to unlocked; that's our cue to start looping it.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (state == Slot.TRANSITION && mediaItem?.mediaId == Slot.UNLOCKED.id) {
                    state = Slot.UNLOCKED
                    player?.repeatMode = Player.REPEAT_MODE_ONE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error", error)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            player = ExoPlayer.Builder(this@VideoWallpaperService).build().apply {
                volume = 0f
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                addListener(listener)
            }
            loadItems()

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(VideoStore.ACTION_VIDEOS_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }

            val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            lastKeyguardLocked = locked
            if (isPreview || locked) showLocked() else showUnlocked()

            handler.postDelayed(keyguardPoller, POLL_INTERVAL_MS)
        }

        private fun checkKeyguardState() {
            // Same poller also re-checks the clip version every tick, so a
            // changed video is picked up even if VIDEOS_CHANGED and the
            // visibility callback both get missed (seen on some OEMs).
            reloadIfChanged()

            val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            val previouslyLocked = lastKeyguardLocked
            lastKeyguardLocked = locked

            if (previouslyLocked == null) return // first read, nothing changed yet

            if (previouslyLocked && !locked && state == Slot.LOCKED) {
                // Device just unlocked and we're still showing the locked loop
                // (broadcast likely missed) -> catch up now.
                playTransition()
            } else if (!previouslyLocked && locked && state != Slot.LOCKED) {
                // Device just locked and we're not showing the locked video
                // (broadcast likely missed) -> catch up now.
                showLocked()
            }
        }

        /** Reloads clips and re-applies the current state if the on-disk version moved on. */
        private fun reloadIfChanged() {
            if (VideoStore.version(this@VideoWallpaperService) != loadedVersion) {
                loadItems()
                applyState()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            player?.setVideoSurfaceHolder(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            player?.clearVideoSurfaceHolder(holder)
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                // user may have changed clips in the app while we were hidden
                reloadIfChanged()
            }
            player?.playWhenReady = visible // pause when hidden, resume in place
        }

        override fun onDestroy() {
            handler.removeCallbacks(keyguardPoller)
            unregisterReceiver(receiver)
            player?.removeListener(listener)
            player?.release()
            player = null
            super.onDestroy()
        }

        private fun loadItems() {
            loadedVersion = VideoStore.version(this@VideoWallpaperService)
            lockedItem = item(Slot.LOCKED)
            transitionItem = item(Slot.TRANSITION)
            unlockedItem = item(Slot.UNLOCKED)
        }

        private fun item(slot: Slot): MediaItem =
            MediaItem.Builder()
                .setMediaId(slot.id)
                .setUri(VideoStore.uriFor(this@VideoWallpaperService, slot))
                .build()

        private fun applyState() {
            when (state) {
                Slot.LOCKED -> showLocked()
                Slot.TRANSITION -> playTransition()
                Slot.UNLOCKED -> showUnlocked()
            }
        }

        private fun showLocked() {
            state = Slot.LOCKED
            player?.apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(lockedItem)
                prepare()
                playWhenReady = visible
            }
        }

        private fun showUnlocked() {
            state = Slot.UNLOCKED
            player?.apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(unlockedItem)
                prepare()
                playWhenReady = visible
            }
        }

        private fun playTransition() {
            // No custom transition clip picked -> nothing bundled to fall back to, cut straight to Unlocked.
            if (Slot.TRANSITION.optional && !VideoStore.hasCustom(this@VideoWallpaperService, Slot.TRANSITION)) {
                showUnlocked()
                return
            }
            state = Slot.TRANSITION
            player?.apply {
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItems(listOf(transitionItem, unlockedItem))
                prepare()
                playWhenReady = visible
            }
        }
    }

    private companion object {
        const val TAG = "VideoWallpaper"
        const val POLL_INTERVAL_MS = 800L
    }
}
