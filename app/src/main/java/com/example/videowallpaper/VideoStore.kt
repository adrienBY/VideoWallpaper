package com.example.videowallpaper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.RawRes
import java.io.File

enum class Slot(
    val id: String,
    @RawRes val rawRes: Int,
    val title: String,
    val subtitle: String,
    /** True if the app should skip this slot entirely (no bundled fallback) until the user picks a clip. */
    val optional: Boolean = false,
) {
    LOCKED("idle_closed", R.raw.idle_closed, "Locked", "Loops while the screen is locked"),
    TRANSITION(
        "transition",
        R.raw.transition,
        "Unlock transition",
        "Optional — plays once when you unlock",
        optional = true,
    ),
    UNLOCKED("idle_open", R.raw.idle_open, "Unlocked", "Loops until the screen turns off"),
}

/** Custom clips live in filesDir/videos/. Missing slot -> bundled res/raw default. */
object VideoStore {
    private const val PREFS = "video_store"
    private const val KEY_VERSION = "version"

    /** Sent whenever a clip changes, so any running wallpaper engine can reload right away. */
    const val ACTION_VIDEOS_CHANGED = "com.example.videowallpaper.VIDEOS_CHANGED"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun notifyChanged(c: Context) {
        c.sendBroadcast(Intent(ACTION_VIDEOS_CHANGED).setPackage(c.packageName))
    }

    fun customFile(c: Context, slot: Slot) = File(File(c.filesDir, "videos"), "${slot.id}.mp4")

    fun hasCustom(c: Context, slot: Slot) = customFile(c, slot).exists()

    fun uriFor(c: Context, slot: Slot): Uri =
        if (hasCustom(c, slot)) Uri.fromFile(customFile(c, slot))
        else Uri.parse("android.resource://${c.packageName}/${slot.rawRes}")

    /** Bumped on every change so the wallpaper engine knows to reload. */
    fun version(c: Context): Long = prefs(c).getLong(KEY_VERSION, 0L)

    fun label(c: Context, slot: Slot): String {
        val name = prefs(c).getString("name_${slot.id}", null)
        return when {
            name != null -> "Custom · $name"
            slot.optional -> "Not set · unlock cuts straight to Unlocked"
            else -> "Default clip"
        }
    }

    fun importFrom(c: Context, slot: Slot, source: Uri) {
        val dest = customFile(c, slot)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${slot.id}.tmp")
        val input = c.contentResolver.openInputStream(source) ?: error("Cannot open file")
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        dest.delete()
        check(tmp.renameTo(dest)) { "Cannot save file" }
        val name = c.contentResolver.query(source, null, null, null, null)?.use {
            val col = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && it.moveToFirst()) it.getString(col) else null
        }
        prefs(c).edit()
            .putString("name_${slot.id}", name ?: "video")
            .putLong(KEY_VERSION, System.nanoTime())
            .apply()
        notifyChanged(c)
    }

    fun reset(c: Context, slot: Slot) {
        customFile(c, slot).delete()
        prefs(c).edit()
            .remove("name_${slot.id}")
            .putLong(KEY_VERSION, System.nanoTime())
            .apply()
        notifyChanged(c)
    }
}
