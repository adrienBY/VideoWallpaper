package com.luckyyftw.videowallpaper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
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
                // Poll fast only while the lock screen is showing, so an unlock
                // is caught within ~50 ms instead of waiting for USER_PRESENT
                // (which some OEMs send late, after the unlock animation).
                val fast = visible && state == Slot.LOCKED
                handler.postDelayed(this, if (fast) FAST_POLL_MS else POLL_INTERVAL_MS)
            }
        }

        // Where the Locked clip "settles" (its burst ends and it holds still), in ms.
        // 0 = a normal looping clip, so unlocking switches immediately.
        private var lockedSettleMs = 0L
        // Optional: start of a "pass-through" copy of the burst stored after the hold
        // in the same Locked file. It plays the burst without stopping and flows
        // straight into the unlock burst. -1 = not present.
        private var lockedPassStartMs = -1L
        private var pendingTransition = false
        // If you unlock while the Locked burst is still playing, let it finish
        // (sped up) and only then start the transition, so there's no jump cut.
        private val settleWatcher = object : Runnable {
            override fun run() {
                if (!pendingTransition) return
                val p = player ?: return
                if (state != Slot.LOCKED || !visible ||
                    p.currentPosition >= lockedSettleMs - SETTLE_TOLERANCE_MS) {
                    pendingTransition = false
                    p.setPlaybackSpeed(1f)
                    if (state == Slot.LOCKED) startTransition()
                } else {
                    handler.postDelayed(this, 8)
                }
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
            // Restart the poller so it switches speed right away.
            handler.removeCallbacks(keyguardPoller)
            handler.post(keyguardPoller)
            if (visible) {
                // user may have changed clips in the app while we were hidden
                reloadIfChanged()
            }
            player?.playWhenReady = visible // pause when hidden, resume in place
        }

        override fun onDestroy() {
            handler.removeCallbacks(keyguardPoller)
            handler.removeCallbacks(settleWatcher)
            unregisterReceiver(receiver)
            player?.removeListener(listener)
            player?.release()
            player = null
            super.onDestroy()
        }

        private fun loadItems() {
            loadedVersion = VideoStore.version(this@VideoWallpaperService)
            lockedItem = item(Slot.LOCKED)
            scanLocked(VideoStore.uriFor(this@VideoWallpaperService, Slot.LOCKED))
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
                Slot.TRANSITION -> startTransition()
                Slot.UNLOCKED -> showUnlocked()
            }
        }

        private fun showLocked() {
            state = Slot.LOCKED
            pendingTransition = false
            handler.removeCallbacks(settleWatcher)
            player?.apply {
                setPlaybackSpeed(1f)
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItems(listOf(lockedItem, unlockedItem))
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
            // Unlock is reported twice (fast poller + USER_PRESENT broadcast).
            // Only the first one counts; a second call used to restart the
            // transition mid-play, which looked like a stutter.
            if (pendingTransition || state != Slot.LOCKED) return
            val p = player
            if (state == Slot.LOCKED && visible && p != null && lockedSettleMs > 0 &&
                p.currentMediaItem?.mediaId == Slot.LOCKED.id &&
                p.currentPosition < lockedSettleMs - SETTLE_TOLERANCE_MS
            ) {
                val pos = p.currentPosition
                if (lockedPassStartMs > 0 && pos < lockedSettleMs - LOCK_EASE_MS) {
                    // Jump to the same moment in the pass-through copy: identical
                    // frames, but it keeps flying into the unlock burst instead of stopping.
                    state = Slot.TRANSITION
                    p.repeatMode = Player.REPEAT_MODE_OFF
                    p.seekTo(lockedPassStartMs + pos + SEEK_LEAD_MS)
                    return
                }
                // Already slowing into the freeze (or no pass-through): let it finish.
                pendingTransition = true
                p.setPlaybackSpeed(if (lockedPassStartMs > 0) 1f else CATCH_UP_SPEED)
                handler.post(settleWatcher)
                return
            }
            startTransition()
        }

        /**
         * Scans the Locked clip's frame timestamps. A clip made as
         * "burst, hold (sparse frames), [pass-through]" gives:
         *  - settle = where the hold starts (first big gap between frames)
         *  - pass-through start = where dense frames resume after the hold
         * A normal looping clip has no gap: settle = 0, no pass-through.
         */
        private fun scanLocked(uri: Uri) {
            lockedSettleMs = 0L
            lockedPassStartMs = -1L
            val ex = MediaExtractor()
            try {
                ex.setDataSource(this@VideoWallpaperService, uri, null)
                val track = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return
                ex.selectTrack(track)
                val times = ArrayList<Long>()
                while (times.size < 50000) {
                    val t = ex.sampleTime
                    if (t < 0) break
                    times.add(t)
                    if (!ex.advance()) break
                }
                times.sort()
                var i = 1
                while (i < times.size && times[i] - times[i - 1] <= 250_000L) i++
                if (i >= times.size) return
                lockedSettleMs = times[i - 1] / 1000
                // skip the sparse hold, find where frames get dense again
                while (i + 1 < times.size && times[i + 1] - times[i] > 100_000L) i++
                if (i + 1 < times.size) lockedPassStartMs = times[i] / 1000
            } catch (e: Exception) {
                Log.w(TAG, "Could not scan locked clip", e)
            } finally {
                ex.release()
            }
        }

        private fun startTransition() {
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
        const val POLL_INTERVAL_MS = 1500L
        const val FAST_POLL_MS = 50L
        const val CATCH_UP_SPEED = 2f
        const val LOCK_EASE_MS = 710L   // length of the slowdown at the end of my lock bursts
        const val SEEK_LEAD_MS = 16L
        const val SETTLE_TOLERANCE_MS = 20L
    }
}
