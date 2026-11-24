package com.xyrcodex.heast.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import coil.load
import com.xyrcodex.heast.MainActivity
import com.xyrcodex.heast.R
import com.xyrcodex.heast.data.model.Track
import kotlin.math.abs
import kotlin.math.sin

internal object FloatingPlayerOverlay {

    private const val PREFS_NAME = "heast_floating_player"
    private const val KEY_ENABLED = "dynamic_island_enabled"
    private const val COMPACT_W_DP = 250
    private const val COMPACT_H_DP = 58
    private const val EXPANDED_W_DP = 344
    private const val EXPANDED_H_DP = 168
    private const val EXPANDED_CORNER_DP = 38
    private const val MORPH_MS = 250L
    private const val TICK_MS = 500L

    private val lime = Color.rgb(189, 244, 119)
    private val artistColor = Color.argb(255, 158, 168, 159)

    private var manager: WindowManager? = null
    private var container: FrameLayout? = null
    private var islandBg: GradientDrawable? = null
    private var layout: WindowManager.LayoutParams? = null
    private var player: Player? = null
    private var trackId: String? = null
    private var stage = 0
    private var morphAnimator: ValueAnimator? = null
    private var equalizerAnimator: ValueAnimator? = null
    private var headers: List<IslandHeader> = emptyList()
    private var equalizers: List<EqualizerView> = emptyList()
    private var compactContent: LinearLayout? = null
    private var expandedContent: LinearLayout? = null
    private var progress: IslandSeekBar? = null
    private var elapsed: TextView? = null
    private var remaining: TextView? = null
    private var playPause: ImageView? = null
    private var playDrawable: Drawable? = null
    private var pauseDrawable: Drawable? = null

    private val handler = Handler(Looper.getMainLooper())

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) hide()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val active = player
            if (active == null || active.playbackState == Player.STATE_IDLE) {
                hide()
                return
            }
            refreshProgress(active)
            if (active.playWhenReady) {
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    fun update(context: Context, track: Track?, activePlayer: Player) {
        val overlayAllowed = Build.VERSION.SDK_INT >= 23 &&
            isEnabled(context) &&
            Settings.canDrawOverlays(context)
        if (!overlayAllowed || track == null || activePlayer.playbackState == Player.STATE_IDLE) {
            hide()
            return
        }
        player = activePlayer
        if (container == null) {
            attach(context.applicationContext, track)
        } else if (trackId != track.id) {
            trackId = track.id
            headers.forEach { header ->
                header.title.text = track.title
                header.artist.text = track.artist
                header.art.load(track.thumbnailUrl)
            }
        }
        syncPlaybackState()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun attach(context: Context, track: Track) {
        manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        trackId = track.id

        val background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(context, COMPACT_H_DP / 2).toFloat()
            setStroke(dp(context, 1), Color.argb(18, 255, 255, 255))
        }
        islandBg = background

        val params = WindowManager.LayoutParams(
            dp(context, COMPACT_W_DP),
            dp(context, COMPACT_H_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(context, 30)
        }
        layout = params

        playDrawable = ContextCompat.getDrawable(context, R.drawable.ic_overlay_play)
        pauseDrawable = ContextCompat.getDrawable(context, R.drawable.ic_overlay_pause)

        val root = FrameLayout(context)
        val card = FrameLayout(context).apply {
            background = background
            elevation = dp(context, 8).toFloat()
        }
        val compact = buildCompactContent(context, track)
        val expanded = buildExpandedContent(context, track)
        compactContent = compact
        expandedContent = expanded
        expanded.visibility = View.GONE
        card.addView(compact, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(expanded, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.setOnClickListener {
            if (stage == 0) morphTo(1) else openApp(context)
        }
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container = root

        runCatching { manager?.addView(root, params) }.onFailure {
            hide()
            return
        }
        syncPlaybackState()
    }

    private class IslandHeader(
        val row: LinearLayout,
        val art: ImageView,
        val title: TextView,
        val artist: TextView
    )

    private fun buildHeader(context: Context, track: Track, artSizeDp: Int, titleSizeSp: Int, artistSizeSp: Int): IslandHeader {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val artView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                setColor(Color.argb(255, 40, 44, 41))
                cornerRadius = dp(context, artSizeDp / 3).toFloat()
            }
            load(track.thumbnailUrl)
        }
        row.addView(artView, LinearLayout.LayoutParams(dp(context, artSizeDp), dp(context, artSizeDp)))
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), 0, dp(context, 4), 0)
        }
        val titleView = label(context, track.title, titleSizeSp, Color.WHITE, "sans-serif-medium")
        val artistView = label(context, track.artist, artistSizeSp, artistColor, "sans-serif")
        labels.addView(titleView)
        labels.addView(artistView)
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val header = IslandHeader(row, artView, titleView, artistView)
        headers = headers + header
        return header
    }

    private fun buildCompactContent(context: Context, track: Track): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 9), dp(context, 9), dp(context, 7), dp(context, 9))
        }
        val header = buildHeader(context, track, artSizeDp = 40, titleSizeSp = 12, artistSizeSp = 9)
        row.addView(header.row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 40)))
        val wave = EqualizerView(context)
        equalizers = equalizers + wave
        row.addView(
            wave,
            LinearLayout.LayoutParams(dp(context, 16), dp(context, 20)).apply {
                marginStart = dp(context, 10)
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        return row
    }

    private fun buildExpandedContent(context: Context, track: Track): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
        }
        val header = buildHeader(context, track, artSizeDp = 44, titleSizeSp = 13, artistSizeSp = 10)
        val wave = EqualizerView(context)
        equalizers = equalizers + wave
        header.row.addView(
            wave,
            LinearLayout.LayoutParams(dp(context, 16), dp(context, 20)).apply {
                marginStart = dp(context, 10)
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        column.addView(header.row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 44)))

        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val elapsedLabel = label(context, "0:00", 9, artistColor, "sans-serif")
        val remainingLabel = label(context, "-0:00", 9, artistColor, "sans-serif")
        elapsed = elapsedLabel
        remaining = remainingLabel
        val bar = IslandSeekBar(context)
        progress = bar
        bar.onSeek = { target ->
            val active = player
            active?.seekTo(target)
            elapsedLabel.text = format(target)
            remainingLabel.text = "-" + format(((active?.duration ?: 0L) - target).coerceAtLeast(0L))
        }
        progressRow.addView(elapsedLabel, LinearLayout.LayoutParams(dp(context, 34), ViewGroup.LayoutParams.WRAP_CONTENT))
        progressRow.addView(
            bar,
            LinearLayout.LayoutParams(0, dp(context, 26), 1f).apply {
                marginStart = dp(context, 4)
                marginEnd = dp(context, 4)
            }
        )
        progressRow.addView(remainingLabel, LinearLayout.LayoutParams(dp(context, 38), ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(
            progressRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 10)
            }
        )

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = iconButton(context, R.drawable.ic_overlay_prev, dp(context, 40), Color.WHITE)
        prev.setOnClickListener { player?.seekToPrevious() }
        val next = iconButton(context, R.drawable.ic_overlay_next, dp(context, 40), Color.WHITE)
        next.setOnClickListener { player?.seekToNext() }
        val playButton = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(lime)
            }
        }
        val playIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        playPause = playIcon
        playButton.addView(playIcon, FrameLayout.LayoutParams(dp(context, 26), dp(context, 26), Gravity.CENTER))
        playButton.setOnClickListener {
            player?.let { p -> if (p.playWhenReady) p.pause() else p.play() }
        }
        controls.addView(prev)
        controls.addView(
            playButton,
            LinearLayout.LayoutParams(dp(context, 46), dp(context, 46)).apply {
                marginStart = dp(context, 22)
                marginEnd = dp(context, 22)
            }
        )
        controls.addView(next)
        column.addView(
            controls,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 46)).apply {
                topMargin = dp(context, 12)
            }
        )

        return column
    }

    private fun syncPlaybackState() {
        val active = player
        val isPlaying = active != null && active.playWhenReady && active.playbackState != Player.STATE_IDLE
        playPause?.setImageDrawable(if (isPlaying) pauseDrawable else playDrawable)
        equalizers.forEach { it.playing = isPlaying }
        if (isPlaying) startEqualizer() else stopEqualizer()
    }

    private fun startEqualizer() {
        val existing = equalizerAnimator
        if (existing != null && existing.isRunning) return
        val animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1150L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val wave = anim.animatedValue as Float
                equalizers.forEach { it.setWave(wave) }
            }
        }
        equalizerAnimator = animation
        animation.start()
    }

    private fun stopEqualizer() {
        equalizerAnimator?.cancel()
        equalizerAnimator = null
        equalizers.forEach { it.setWave(0f) }
    }

    private fun morphTo(target: Int) {
        val root = container ?: return
        val params = layout ?: return
        val background = islandBg ?: return
        if (stage == target) return
        val previousStage = stage
        stage = target
        morphAnimator?.cancel()
        val context = root.context
        val fromWidth = params.width
        val fromHeight = params.height
        val toWidth = dp(context, if (target == 1) EXPANDED_W_DP else COMPACT_W_DP)
        val toHeight = dp(context, if (target == 1) EXPANDED_H_DP else COMPACT_H_DP)
        val fromRadius = dp(context, if (previousStage == 1) EXPANDED_CORNER_DP else COMPACT_H_DP / 2).toFloat()
        val toRadius = dp(context, if (target == 1) EXPANDED_CORNER_DP else COMPACT_H_DP / 2).toFloat()
        val outgoing = if (target == 1) compactContent else expandedContent
        val incoming = if (target == 1) expandedContent else compactContent
        outgoing?.visibility = View.VISIBLE
        incoming?.visibility = View.VISIBLE
        val animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MORPH_MS
            interpolator = if (target == 1) DecelerateInterpolator(1.8f) else AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                params.width = lerp(fromWidth, toWidth, t)
                params.height = lerp(fromHeight, toHeight, t)
                background.cornerRadius = fromRadius + (toRadius - fromRadius) * t
                outgoing?.alpha = 1f - t
                incoming?.alpha = t
                runCatching { manager?.updateViewLayout(root, params) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (stage == 1) {
                        compactContent?.visibility = View.GONE
                        expandedContent?.alpha = 1f
                    } else {
                        expandedContent?.visibility = View.GONE
                        compactContent?.alpha = 1f
                    }
                }
            })
        }
        morphAnimator = animation
        animation.start()
    }

    private fun refreshProgress(active: Player) {
        val duration = active.duration.coerceAtLeast(0L)
        val position = if (duration > 0L) {
            active.currentPosition.coerceIn(0L, duration)
        } else {
            active.currentPosition.coerceAtLeast(0L)
        }
        progress?.update(position, duration)
        elapsed?.let { item ->
            val text = format(position)
            if (item.text.toString() != text) item.text = text
        }
        remaining?.let { item ->
            val text = "-" + format((duration - position).coerceAtLeast(0L))
            if (item.text.toString() != text) item.text = text
        }
    }

    private fun openApp(context: Context) {
        morphTo(0)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_EXPAND_PLAYER, true)
        }
        runCatching { context.startActivity(intent) }
    }

    fun hide(context: Context) {
        hide()
    }

    private fun hide() {
        handler.removeCallbacks(ticker)
        morphAnimator?.cancel()
        morphAnimator = null
        equalizerAnimator?.cancel()
        equalizerAnimator = null
        container?.let { view -> runCatching { manager?.removeView(view) } }
        container = null
        islandBg = null
        layout = null
        player = null
        trackId = null
        headers = emptyList()
        equalizers = emptyList()
        compactContent = null
        expandedContent = null
        progress = null
        elapsed = null
        remaining = null
        playPause = null
        playDrawable = null
        pauseDrawable = null
        stage = 0
    }

    private fun iconButton(context: Context, res: Int, sizePx: Int, tint: Int): ImageView =
        ImageView(context).apply {
            val drawable = ContextCompat.getDrawable(context, res)
            drawable?.setTint(tint)
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(sizePx / 5, sizePx / 5, sizePx / 5, sizePx / 5)
        }

    private fun label(context: Context, value: String, sizeSp: Int, color: Int, family: String): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp.toFloat()
            setTextColor(color)
            typeface = Typeface.create(family, Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }

    private class IslandSeekBar(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(46, 255, 255, 255)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(189, 244, 119)
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        var position = 0L
            private set
        var duration = 0L
            private set
        var isDragging = false
            private set
        var onSeek: (Long) -> Unit = {}
        private var dragPosition = 0L

        fun update(newPosition: Long, newDuration: Long) {
            position = newPosition
            duration = newDuration
            if (!isDragging) invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = width.toFloat()
            val height = height.toFloat()
            if (width <= 0f || height <= 0f) return
            val barHeight = 3.5f * density
            val centerY = height / 2f
            val radius = barHeight / 2f
            val shown = if (isDragging) dragPosition else position
            val total = duration.coerceAtLeast(1L)
            val fraction = (shown.toFloat() / total).coerceIn(0f, 1f)
            canvas.drawRoundRect(0f, centerY - radius, width, centerY + radius, radius, radius, trackPaint)
            if (fraction > 0f) {
                canvas.drawRoundRect(0f, centerY - radius, width * fraction, centerY + radius, radius, radius, fillPaint)
            }
            val thumbRadius = 4.5f * density
            val thumbX = (width * fraction).coerceIn(thumbRadius, width - thumbRadius)
            canvas.drawCircle(thumbX, centerY, thumbRadius, thumbPaint)
        }

        private fun seekTo(x: Float) {
            val total = duration.coerceAtLeast(1L)
            if (width <= 0) return
            dragPosition = ((x / width.toFloat()).coerceIn(0f, 1f) * total).toLong()
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    seekTo(event.x)
                }
                MotionEvent.ACTION_MOVE -> seekTo(event.x)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    seekTo(event.x)
                    val target = dragPosition
                    isDragging = false
                    invalidate()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        onSeek(target)
                    }
                }
                else -> return false
            }
            return true
        }
    }

    private class EqualizerView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(140, 255, 255, 255)
        }
        private val phases = floatArrayOf(0f, 2.1f, 4.2f)
        private var wave = 0f
        private var active = false

        var playing: Boolean
            get() = active
            set(value) {
                if (active != value) {
                    active = value
                    barPaint.color = if (value) Color.rgb(189, 244, 119) else Color.argb(140, 255, 255, 255)
                    invalidate()
                }
            }

        fun setWave(progress: Float) {
            wave = progress
            if (active) invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = width.toFloat()
            val height = height.toFloat()
            if (width <= 0f || height <= 0f) return
            val barWidth = 2.6f * density
            val gap = (width - barWidth * 3f) / 2f
            val minHeight = height * 0.30f
            val maxHeight = height * 0.94f
            for (index in 0 until 3) {
                val energy = if (active) {
                    val s = sin(wave * 2.0 * Math.PI + phases[index]).toFloat()
                    0.42f + 0.58f * abs(s)
                } else {
                    0.5f
                }
                val barHeight = (minHeight + (maxHeight - minHeight) * energy).coerceAtMost(height)
                val left = index * (barWidth + gap)
                val top = (height - barHeight) / 2f
                canvas.drawRoundRect(left, top, left + barWidth, top + barHeight, barWidth / 2f, barWidth / 2f, barPaint)
            }
        }
    }

    private fun lerp(from: Int, to: Int, fraction: Float): Int =
        (from.toFloat() + (to - from).toFloat() * fraction).toInt()

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun format(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1000L
        return (seconds / 60L).toString() + ":" + (seconds % 60L).toString().padStart(2, '0')
    }
}
