package io.github.omjasoliya.bloom

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** The petal wheel, as on the desktop: a ring of petals around the Bloom flower.
 *  Tap a petal to run it, tap the flower to close. */
class WheelView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var onPick: ((Petal) -> Unit)? = null
    var onCenter: (() -> Unit)? = null
    var label = ""
        set(value) { field = value; invalidate() }
    var progress = -1
        set(value) { field = value; invalidate() }

    private var petals = emptyList<Petal>()
    private val shapes = mutableListOf<Path>()
    private var pressed = NONE
    private var working = NONE
    private var bloom = 1f
    private var spin = 0f
    private var spinner: ValueAnimator? = null

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private var cx = 0f
    private var cy = 0f
    private var outer = 0f
    private var inner = 0f
    private var core = 0f

    private val accent = context.getColor(R.color.accent)
    private val petalColor = context.getColor(R.color.petal)
    private val ink = context.getColor(R.color.ink)
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.glass) }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = 0x99FFFFFF.toInt()
    }
    private val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        pathEffect = CornerPathEffect(16 * dp)
        setShadowLayer(10 * dp, 0f, 3 * dp, 0x1F3B2A8C)
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(12 * dp, 0f, 4 * dp, 0x263B2A8C)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * dp; strokeCap = Paint.Cap.ROUND; color = accent
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.soft) }
    private val flower = context.getDrawable(R.drawable.flower)!!
    private val overshoot = OvershootInterpolator(1.6f)

    private val helper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = when (val hit = hitTest(x, y)) {
            CENTER -> petals.size
            NONE -> INVALID_ID
            else -> hit
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            ids += petals.indices
            ids += petals.size
        }

        @Suppress("DEPRECATION")
        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            if (id == petals.size) {
                node.contentDescription = "Close"
                node.setBoundsInParent(Rect((cx - core).toInt(), (cy - core).toInt(), (cx + core).toInt(), (cy + core).toInt()))
            } else {
                node.text = petals.getOrNull(id)?.label ?: ""
                node.setBoundsInParent(labelBox(id))
            }
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            activate(if (id == petals.size) CENTER else id)
            return true
        }
    }

    init {
        ViewCompat.setAccessibilityDelegate(this, helper)
    }

    fun setPetals(list: List<Petal>) {
        petals = list
        pressed = NONE
        rebuild()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L + 45L * list.size
            addUpdateListener { bloom = it.animatedValue as Float; invalidate() }
            start()
        }
        helper.invalidateRoot()
    }

    /** Shows [petal] as the one being worked on, with a ring around the flower. */
    fun setBusy(petal: Petal?) {
        working = if (petal == null) NONE else petals.indexOf(petal)
        progress = -1
        spinner?.cancel()
        spinner = if (petal == null) null else ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { spin = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        outer = min(min(w, h) / 2f - 18 * dp, 190 * dp)
        inner = outer * 0.47f
        core = outer * 0.37f
        rebuild()
    }

    /** Rounded ring sectors with an even gap between them. */
    private fun rebuild() {
        shapes.clear()
        val n = petals.size
        if (n == 0 || outer <= 0f) return
        if (n == 1) {
            shapes += Path().apply {
                addCircle(cx, cy, outer, Path.Direction.CW)
                addCircle(cx, cy, inner, Path.Direction.CCW)
                fillType = Path.FillType.EVEN_ODD
            }
            return
        }
        val step = 2 * PI / n
        val gap = 9 * dp
        val go = gap / 2 / outer
        val gi = gap / 2 / inner
        for (i in 0 until n) {
            val a0 = -PI / 2 + (i - 0.5) * step
            val a1 = a0 + step
            val path = Path()
            val segments = 32
            for (k in 0..segments) {
                val a = a0 + go + (step - 2 * go) * k / segments
                val x = (cx + outer * cos(a)).toFloat()
                val y = (cy + outer * sin(a)).toFloat()
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (k in 0..segments) {
                val a = a1 - gi - (step - 2 * gi) * k / segments
                path.lineTo((cx + inner * cos(a)).toFloat(), (cy + inner * sin(a)).toFloat())
            }
            path.close()
            shapes += path
        }
    }

    private fun labelCenter(i: Int): Pair<Float, Float> {
        val mid = (outer + inner) / 2
        val a = -PI / 2 + i * 2 * PI / petals.size.coerceAtLeast(1)
        return Pair((cx + mid * cos(a)).toFloat(), (cy + mid * sin(a)).toFloat())
    }

    private fun labelBox(i: Int): Rect {
        val (x, y) = labelCenter(i)
        val half = (outer - inner) / 2
        return Rect((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (outer <= 0f) return
        canvas.drawCircle(cx, cy, outer + 12 * dp, glass)
        canvas.drawCircle(cx, cy, outer + 12 * dp, rim)

        val n = petals.size
        val stagger = 0.12f
        val span = 1f + stagger * (n - 1)
        petals.forEachIndexed { i, petal ->
            val local = ((bloom * span - i * stagger) / 1f).coerceIn(0f, 1f)
            if (local <= 0f || i >= shapes.size) return@forEachIndexed
            val scale = 0.6f + 0.4f * overshoot.getInterpolation(local)
            val alpha = (255 * min(1f, local * 1.6f)).toInt()
            val hot = i == pressed || i == working
            val dim = working != NONE && !hot
            canvas.save()
            canvas.scale(scale, scale, cx, cy)
            petalPaint.color = if (hot) accent else petalColor
            petalPaint.alpha = if (dim) alpha / 2 else alpha
            canvas.drawPath(shapes[i], petalPaint)
            val (x, y) = labelCenter(i)
            val room = if (n == 1) outer else min(2 * ((outer + inner) / 2) * sin(PI / n).toFloat() - 22 * dp, (outer - inner) * 1.45f)
            drawLabel(canvas, petal.label, x, y, room, if (hot) 0xFFFFFFFF.toInt() else ink, if (dim) alpha / 2 else alpha)
            canvas.restore()
        }

        canvas.drawCircle(cx, cy, core, corePaint)
        if (working != NONE) {
            val r = core - 7 * dp
            val box = RectF(cx - r, cy - r, cx + r, cy + r)
            if (progress >= 0) canvas.drawArc(box, -90f, progress * 3.6f, false, ring)
            else canvas.drawArc(box, spin - 90f, 100f, false, ring)
        }
        val f = (core * 0.78f).toInt()
        val fy = (cy - core * 0.16f).toInt()
        flower.setBounds((cx - f / 2).toInt(), fy - f / 2, (cx + f / 2).toInt(), fy + f / 2)
        flower.draw(canvas)
        if (label.isNotEmpty()) {
            text.textSize = 11 * sp
            text.color = ink
            text.alpha = 255
            val w = text.measureText(label) + 16 * dp
            val py = cy + core * 0.52f
            val h = 20 * dp
            canvas.drawRoundRect(RectF(cx - w / 2, py - h / 2, cx + w / 2, py + h / 2), h / 2, h / 2, pill)
            canvas.drawText(label, cx, py - (text.ascent() + text.descent()) / 2, text)
        }
    }

    private fun drawLabel(canvas: Canvas, label: String, x: Float, y: Float, room: Float, color: Int, alpha: Int) {
        var size = 16 * sp
        text.textSize = size
        val lines = if (' ' in label && text.measureText(label) > room) label.split(' ', limit = 2) else listOf(label)
        while (size > 10 * sp && lines.maxOf { text.measureText(it) } > room) {
            size -= sp
            text.textSize = size
        }
        text.color = color
        text.alpha = alpha
        val lineHeight = size * 1.18f
        val top = y - lineHeight * (lines.size - 1) / 2
        val shift = (text.ascent() + text.descent()) / 2
        lines.forEachIndexed { k, line -> canvas.drawText(line, x, top + k * lineHeight - shift, text) }
    }

    private fun hitTest(x: Float, y: Float): Int {
        val d = hypot(x - cx, y - cy)
        if (d <= core) return CENTER
        if (d < inner - 6 * dp || d > outer + 12 * dp || petals.isEmpty()) return NONE
        val step = 360.0 / petals.size
        var a = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())) + 90 + step / 2
        a = ((a % 360) + 360) % 360
        return (a / step).toInt().coerceIn(0, petals.size - 1)
    }

    private fun activate(hit: Int) {
        when {
            hit == CENTER -> onCenter?.invoke()
            hit >= 0 && hit < petals.size && working == NONE -> onPick?.invoke(petals[hit])
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = hitTest(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressed = if (hit >= 0 && working == NONE) hit else NONE
            MotionEvent.ACTION_MOVE -> if (pressed != NONE && hit != pressed) pressed = NONE
            MotionEvent.ACTION_UP -> {
                val was = pressed
                pressed = NONE
                if (hit == CENTER || (was != NONE && was == hit)) {
                    performClick()
                    activate(hit)
                }
            }
            MotionEvent.ACTION_CANCEL -> pressed = NONE
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        helper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onDetachedFromWindow() {
        spinner?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val NONE = -1
        const val CENTER = -2
    }
}
