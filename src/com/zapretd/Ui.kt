package com.zapretd

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View

class Palette(context: Context) {
    private val dark = (context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    val isDark get() = dark

    private fun pick(light: Long, night: Long): Int = (if (dark) night else light).toInt()

    val background = pick(0xFFFBF8FF, 0xFF121316)
    val surface = pick(0xFFF0EDF4, 0xFF1F1F23)
    val surfaceHigh = pick(0xFFE9E7EE, 0xFF2A292E)
    val primary = pick(0xFF4355B9, 0xFFBAC3FF)
    val onPrimary = pick(0xFFFFFFFF, 0xFF08218A)
    val primaryContainer = pick(0xFFDEE0FF, 0xFF293CA0)
    val onPrimaryContainer = pick(0xFF00105C, 0xFFDEE0FF)
    val secondaryContainer = pick(0xFFE0E0F9, 0xFF424559)
    val onSecondaryContainer = pick(0xFF191A2C, 0xFFE0E0F9)
    val onSurface = pick(0xFF1B1B1F, 0xFFE4E1E6)
    val onSurfaceVariant = pick(0xFF46464F, 0xFFC7C5D0)
    val outline = pick(0xFF777680, 0xFF918F9A)
    val outlineVariant = pick(0xFFC7C5D0, 0xFF46464F)
    val errorContainer = pick(0xFFFFDAD6, 0xFF93000A)
    val onErrorContainer = pick(0xFF410002, 0xFFFFDAD6)
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun Context.dpf(value: Int): Float = value * resources.displayMetrics.density

fun Context.shape(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
    val drawable = GradientDrawable()
    drawable.setColor(color)
    drawable.setCornerRadius(dpf(radiusDp))
    if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor)
    return drawable
}

fun Context.rippleShape(color: Int, radiusDp: Int, rippleColor: Int, strokeColor: Int = 0, strokeDp: Int = 0): Drawable {
    val content = shape(color, radiusDp, strokeColor, strokeDp)
    val mask = shape(0xFF000000.toInt(), radiusDp)
    val tint = (rippleColor and 0x00FFFFFF) or 0x33000000
    return RippleDrawable(ColorStateList.valueOf(tint), content, mask)
}

class PowerButton(context: Context, private val palette: Palette) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val evaluator = ArgbEvaluator()
    private var progress = 0f
    private var pressedScale = 1f
    var busy = false
        set(value) {
            field = value
            alpha = if (value) 0.6f else 1f
        }

    init {
        stroke.style = Paint.Style.STROKE
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = context.dpf(5)
        isClickable = true
    }

    fun setActive(active: Boolean, animate: Boolean) {
        val target = if (active) 1f else 0f
        if (!animate || target == progress) {
            progress = target
            invalidate()
            return
        }
        val animator = ValueAnimator.ofFloat(progress, target)
        animator.duration = 320
        animator.addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
        animator.start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedScale = 0.94f
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressedScale = 1f
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size = context.dp(132)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = width / 2f * 0.72f * pressedScale

        halo.color = palette.primary
        halo.alpha = (progress * 46).toInt()
        canvas.drawCircle(cx, cy, width / 2f * pressedScale, halo)

        fill.color = evaluator.evaluate(progress, palette.surfaceHigh, palette.primary) as Int
        canvas.drawCircle(cx, cy, radius, fill)

        stroke.color = evaluator.evaluate(progress, palette.onSurfaceVariant, palette.onPrimary) as Int
        val iconRadius = radius * 0.46f
        val arc = RectF(cx - iconRadius, cy - iconRadius + radius * 0.05f, cx + iconRadius, cy + iconRadius + radius * 0.05f)
        canvas.drawArc(arc, -55f, 290f, false, stroke)
        canvas.drawLine(cx, cy - iconRadius * 1.25f, cx, cy - iconRadius * 0.05f, stroke)
    }
}

class M3Switch(context: Context, private val palette: Palette) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val evaluator = ArgbEvaluator()
    private var progress = 0f
    var onChange: ((Boolean) -> Unit)? = null

    var checked = false
        private set

    init {
        border.style = Paint.Style.STROKE
        border.strokeWidth = context.dpf(2)
        isClickable = true
        setOnClickListener { setChecked(!checked, true, true) }
    }

    fun setChecked(value: Boolean, animate: Boolean, notify: Boolean = false) {
        checked = value
        val target = if (value) 1f else 0f
        if (!animate) {
            progress = target
            invalidate()
        } else {
            val animator = ValueAnimator.ofFloat(progress, target)
            animator.duration = 200
            animator.addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            animator.start()
        }
        if (notify) onChange?.invoke(value)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(context.dp(52), context.dp(32))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = border.strokeWidth / 2f
        val box = RectF(inset, inset, w - inset, h - inset)

        track.color = evaluator.evaluate(progress, palette.surfaceHigh, palette.primary) as Int
        canvas.drawRoundRect(box, h / 2f, h / 2f, track)

        border.color = evaluator.evaluate(progress, palette.outline, palette.primary) as Int
        canvas.drawRoundRect(box, h / 2f, h / 2f, border)

        val small = context.dpf(8)
        val large = context.dpf(12)
        val thumbRadius = small + (large - small) * progress
        val left = context.dpf(16)
        val right = w - context.dpf(16)
        val x = left + (right - left) * progress
        thumb.color = evaluator.evaluate(progress, palette.outline, palette.onPrimary) as Int
        canvas.drawCircle(x, h / 2f, thumbRadius, thumb)
    }
}
