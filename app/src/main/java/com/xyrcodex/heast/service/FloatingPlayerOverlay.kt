package com.xyrcodex.heast.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.Player
import coil.load
import com.xyrcodex.heast.MainActivity
import com.xyrcodex.heast.data.model.Track
import kotlin.math.abs

internal object FloatingPlayerOverlay {
    private var manager: WindowManager? = null
    private var container: FrameLayout? = null
    private var layout: WindowManager.LayoutParams? = null
    private var player: Player? = null
    private var trackId: String? = null
    private var stage = 0
    private var art: ImageView? = null
    private var title: TextView? = null
    private var artist: TextView? = null
    private var seek: SeekBar? = null
    private var elapsed: TextView? = null
    private var remaining: TextView? = null
    private var controls: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    private const val PREFS_NAME = "heast_floating_player"
    private const val KEY_ENABLED = "dynamic_island_enabled"

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) hide()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val active = player
            if (active == null || !active.playWhenReady || active.playbackState == Player.STATE_IDLE) {
                hide()
                return
            }
            val duration = active.duration.coerceAtLeast(0L)
            val position = active.currentPosition.coerceAtLeast(0L)
            seek?.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            seek?.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            elapsed?.text = format(position)
            remaining?.text = "-" + format((duration - position).coerceAtLeast(0L))
            handler.postDelayed(this, 500L)
        }
    }

    fun update(context: Context, track: Track?, activePlayer: Player) {
        if (Build.VERSION.SDK_INT < 23 || !isEnabled(context) || !Settings.canDrawOverlays(context) || track == null || !activePlayer.playWhenReady || activePlayer.playbackState == Player.STATE_IDLE) {
            hide()
            return
        }
        player = activePlayer
        if (container == null) attach(context.applicationContext, track)
        else if (trackId != track.id) {
            trackId = track.id
            title?.text = track.title
            artist?.text = track.artist
            art?.load(track.thumbnailUrl)
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun attach(context: Context, track: Track) {
        manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        trackId = track.id
        val root = FrameLayout(context).apply {
            background = glassShape(32f)
            elevation = dp(context, 16).toFloat()
        }
        container = root
        val params = WindowManager.LayoutParams(
            dp(context, 252),
            dp(context, 66),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(context, 34)
        }
        layout = params
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(Color.DKGRAY, 12f)
            clipToOutline = true
            load(track.thumbnailUrl)
        }
        art = image
        head.addView(image, LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)))
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 9), 0, dp(context, 4), 0)
        }
        title = label(context, track.title, 13, Color.WHITE, true)
        artist = label(context, track.artist, 10, Color.LTGRAY, false)
        labels.addView(title)
        labels.addView(artist)
        head.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(label(context, "▮▮", 15, Color.rgb(190, 245, 112), true))
        column.addView(head, LinearLayout.LayoutParams(-1, dp(context, 48)))

        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        elapsed = label(context, "0:00", 9, Color.LTGRAY, false)
        remaining = label(context, "-0:00", 9, Color.LTGRAY, false)
        val bar = SeekBar(context).apply {
            max = 1
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    player?.seekTo(s?.progress?.toLong() ?: 0L)
                }
            })
        }
        seek = bar
        progressRow.addView(elapsed, LinearLayout.LayoutParams(dp(context, 34), -2))
        progressRow.addView(bar, LinearLayout.LayoutParams(0, dp(context, 28), 1f))
        progressRow.addView(remaining, LinearLayout.LayoutParams(dp(context, 38), -2))
        column.addView(progressRow)

        val buttons = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(action(context, "⏮") { player?.seekToPrevious() })
        buttons.addView(action(context, "Ⅱ") { player?.let { if (it.isPlaying) it.pause() else it.play() } })
        buttons.addView(action(context, "⏭") { player?.seekToNext() })
        controls = buttons
        column.addView(buttons, LinearLayout.LayoutParams(-1, dp(context, 34)))
        root.addView(column)

        root.setOnClickListener {
            if (stage == 0) {
                stage = 1
                render()
            } else {
                openApp(context)
            }
        }
        runCatching { manager?.addView(root, params) }.onFailure { hide() }
        render()
    }

    private fun render() {
        val root = container ?: return
        val params = layout ?: return
        val context = root.context
        root.animate().cancel()
        root.scaleX = 0.96f
        root.scaleY = 0.96f
        root.animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
        if (stage == 0) {
            params.width = dp(context, 252)
            params.height = dp(context, 66)
            seek?.visibility = View.GONE
            controls?.visibility = View.GONE
            art?.let { image ->
                (image.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.width = dp(context, 44)
                    it.height = dp(context, 44)
                    image.layoutParams = it
                }
            }
        } else {
            params.width = dp(context, 344)
            params.height = dp(context, 150)
            seek?.visibility = View.VISIBLE
            controls?.visibility = View.VISIBLE
            art?.let { image ->
                (image.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.width = dp(context, 48)
                    it.height = dp(context, 48)
                    image.layoutParams = it
                }
            }
        }
        runCatching { manager?.updateViewLayout(root, params) }
    }

    private fun openApp(context: Context) {
        stage = 0
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_EXPAND_PLAYER, true)
        }
        runCatching { context.startActivity(intent) }
    }

    fun hide(context: Context) { hide() }

    private fun hide() {
        handler.removeCallbacks(ticker)
        container?.let { runCatching { manager?.removeView(it) } }
        container = null
        layout = null
        player = null
        trackId = null
        art = null
        title = null
        artist = null
        seek = null
        elapsed = null
        remaining = null
        controls = null
        stage = 0
    }

    private fun label(context: Context, value: String, size: Int, color: Int, bold: Boolean) =
        TextView(context).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun action(context: Context, value: String, click: () -> Unit) =
        TextView(context).apply {
            text = value
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(dp(context, 68), dp(context, 34))
        }

    private fun glassShape(radius: Float) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(35, 47, 68), Color.rgb(13, 19, 31), Color.rgb(27, 36, 54))
    ).apply {
        cornerRadius = radius
        setStroke(dpSafe(1), Color.argb(92, 225, 238, 255))
    }

    private fun shape(color: Int, radius: Float, border: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            border?.let { setStroke(dpSafe(1), it) }
        }

    private fun dpSafe(value: Int) = value
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun format(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1000L
        return (seconds / 60L).toString() + ":" + (seconds % 60L).toString().padStart(2, '0')
    }
}
