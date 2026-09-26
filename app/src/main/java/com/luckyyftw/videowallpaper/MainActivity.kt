package com.luckyyftw.videowallpaper

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private class Card(
        val thumb: ImageView,
        val playBadge: View,
        val emptyState: View,
        val status: TextView,
        val resetBtn: Button,
    )

    private val cards = mutableMapOf<Slot, Card>()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(32))
        }
        val root = ScrollView(this).apply {
            setBackgroundColor(color(R.color.bg_page))
            addView(col)
            isFillViewport = true
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int; val bottom: Int
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top; bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            col.setPadding(dp(20), top + dp(20), dp(20), bottom + dp(24))
            insets
        }
        setContentView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_header)
        }
        val badge = TextView(this).apply {
            text = "\u25B6"
            textSize = 20f
            setTextColor(color(R.color.on_accent))
            gravity = Gravity.CENTER
            background = gradientPill()
            elevation = dp(3).toFloat()
        }
        header.addView(badge, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(label("Video Wallpaper", 25f, R.color.text_primary, bold = true))
        titleCol.addView(label("Pick a clip for each lock state", 14f, R.color.text_secondary))
        header.addView(titleCol, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(14) })
        col.addView(header, lp().apply { bottomMargin = dp(28) })

        for (slot in Slot.entries) {
            col.addView(buildCard(slot), lp().apply { bottomMargin = dp(16) })
            refresh(slot)
        }

        col.addView(buildTipBanner(), lp().apply { topMargin = dp(8); bottomMargin = dp(20) })
        col.addView(primaryButton("Set as wallpaper") { confirmAndSetWallpaper() }, lp())
        col.addView(
            label("Open source \u00B7 MIT license", 12f, R.color.text_muted).apply { gravity = Gravity.CENTER },
            lp().apply { topMargin = dp(22) },
        )

        animateIn(col)
    }

    /** Staggered fade + rise for each top-level section, so the screen feels alive on open. */
    private fun animateIn(container: LinearLayout) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = dp(16).toFloat()
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(i * 45L)
                .setDuration(320)
                .start()
        }
    }

    /** Soft callout reminding the user which option to tap in the system picker. */
    private fun buildTipBanner(): LinearLayout {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_tip_banner)
        }
        val icon = label("\uD83D\uDCA1", 18f, R.color.text_primary)
        banner.addView(icon, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(10) })
        banner.addView(
            label(
                "Choose \u201cHome and lock screen\u201d in the next screen so both react to unlocking.",
                13f,
                R.color.text_secondary,
            ),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        return banner
    }

    /** Radial-ish gradient pill background for the header badge, drawn in code so no extra asset is needed. */
    private fun gradientPill(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(color(R.color.accent), color(R.color.accent_dark)),
    ).apply { cornerRadius = dp(24).toFloat() }

    /**
     * Shows a short explainer dialog before handing off to the system's live-wallpaper
     * picker, since Android's own screen offers "Wallpaper" vs. "Home and lock screen"
     * (wording varies by OEM) and picking the first one silently skips the lock screen.
     */
    private fun confirmAndSetWallpaper() {
        AlertDialog.Builder(this, R.style.AppAlertDialog)
            .setTitle("One thing before you continue")
            .setMessage(
                "On the next screen, Android will ask where to apply this wallpaper.\n\n" +
                    "Tap \u201cHome and lock screen\u201d (not just \u201cHome screen\u201d) so the " +
                    "unlock animation actually plays on your lock screen.",
            )
            .setPositiveButton("Got it, continue") { d, _ ->
                d.dismiss()
                setWallpaper()
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val slot = Slot.entries.getOrNull(requestCode - REQ_BASE) ?: return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return

        if (contentResolver.getType(uri)?.startsWith("video/") != true) {
            toast("That doesn't look like a video")
            return
        }
        cards[slot]?.status?.text = "Importing\u2026"
        thread {
            val err = runCatching { VideoStore.importFrom(this, slot, uri) }.exceptionOrNull()
            runOnUiThread {
                if (err != null) toast("Import failed: ${err.message}")
                refresh(slot)
            }
        }
    }

    private fun buildCard(slot: Slot): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            elevation = dp(1).toFloat()
        }

        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val step = TextView(this).apply {
            text = (slot.ordinal + 1).toString()
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.on_accent))
            gravity = Gravity.CENTER
            background = gradientPill()
        }
        titleRow.addView(step, LinearLayout.LayoutParams(dp(26), dp(26)))
        val titleTextCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleTextCol.addView(label(slot.title, 17f, R.color.text_primary, bold = true))
        titleTextCol.addView(label(slot.subtitle, 13f, R.color.text_secondary))
        titleRow.addView(titleTextCol, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
        card.addView(titleRow)

        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_thumb)
            clipToOutline = true
        }
        val playBadge = TextView(this).apply {
            text = "▶"
            textSize = 15f
            setTextColor(color(R.color.on_accent))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@MainActivity, R.color.scrim_on_media))
            }
        }
        val emptyState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_thumb_empty)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null) // dashed stroke needs software rendering
            addView(label("+ Add a clip", 14f, R.color.text_muted, bold = true))
            addView(
                label("Optional — skipped if you don't set one", 12f, R.color.text_muted).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(4), dp(24), 0)
                },
            )
        }
        val thumbFrame = FrameLayout(this)
        thumbFrame.addView(thumb, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        thumbFrame.addView(playBadge, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        thumbFrame.addView(emptyState, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        card.addView(thumbFrame, LinearLayout.LayoutParams(MATCH_PARENT, dp(180)).apply { topMargin = dp(16) })

        val status = label("", 13f, R.color.text_secondary)
        card.addView(status, lp().apply { topMargin = dp(10); bottomMargin = dp(10) })

        val row = LinearLayout(this)
        val choose = secondaryButton("Choose video") {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQ_BASE + slot.ordinal)
        }
        val reset = secondaryButton("Reset") {
            VideoStore.reset(this, slot)
            refresh(slot)
        }
        row.addView(choose, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(reset, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(row)

        cards[slot] = Card(thumb, playBadge, emptyState, status, reset)
        return card
    }

    private fun refresh(slot: Slot) {
        val c = cards[slot] ?: return
        val hasCustom = VideoStore.hasCustom(this, slot)
        c.status.text = VideoStore.label(this, slot)
        c.resetBtn.isEnabled = hasCustom
        c.resetBtn.alpha = if (hasCustom) 1f else 0.4f

        val showEmptyState = slot.optional && !hasCustom
        c.emptyState.visibility = if (showEmptyState) View.VISIBLE else View.GONE
        c.playBadge.visibility = if (showEmptyState) View.GONE else View.VISIBLE
        if (showEmptyState) {
            c.thumb.setImageDrawable(null)
            return
        }

        thread {
            val bmp = loadThumbnail(slot)
            runOnUiThread {
                if (!isDestroyed) {
                    c.thumb.alpha = 0f
                    c.thumb.setImageBitmap(bmp)
                    c.thumb.animate().alpha(1f).setDuration(220).start()
                }
            }
        }
    }

    private fun loadThumbnail(slot: Slot): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            val f = VideoStore.customFile(this, slot)
            if (f.exists()) {
                mmr.setDataSource(f.absolutePath)
            } else {
                resources.openRawResourceFd(slot.rawRes).use {
                    mmr.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            }
            mmr.getFrameAtTime(0)?.let { b ->
                Bitmap.createScaledBitmap(b, 480, (480f * b.height / b.width).toInt().coerceAtLeast(1), true)
            }
        } catch (e: Exception) {
            null
        } finally {
            mmr.release()
        }
    }

    private fun setWallpaper() {
        val component = ComponentName(this, VideoWallpaperService::class.java)
        try {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            )
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(color(R.color.on_accent))
        setTypeface(typeface, Typeface.BOLD)
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_primary)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        elevation = dp(6).toFloat()
        if (Build.VERSION.SDK_INT >= 28) {
            outlineAmbientShadowColor = color(R.color.accent)
            outlineSpotShadowColor = color(R.color.accent)
        }
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(color(R.color.text_primary))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_secondary)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun label(text: String, sp: Float, colorRes: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(color(colorRes))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQ_BASE = 100
    }
}
