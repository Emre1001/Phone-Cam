package com.phonecam

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class Pal(val dark: Boolean) {
    private fun c(l: Long, d: Long) = (if (dark) d else l).toInt()
    val bg = c(0xFFF4F3F0, 0xFF0F0F11)
    val surface = c(0xFFFFFFFF, 0xFF18181B)
    val inset = c(0xFFEAE9E5, 0xFF222226)
    val border = c(0x17000000, 0x17FFFFFF)
    val text = c(0xFF1B1B1D, 0xFFECECEE)
    val muted = c(0xFF62626A, 0xFF9C9CA5)
    val accent = c(0xFFB4510F, 0xFFF08A3C)
    val onAccent = c(0xFFFFFFFF, 0xFF1A1007)
    val danger = c(0xFFB3261E, 0xFFFF8A80)
    val success = c(0xFF1F7A45, 0xFF6FD49A)
    val scrim = 0x8C08080C.toInt()
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radius
    if (stroke != 0) setStroke(strokeW, stroke)
}

fun ripple(pal: Pal, content: GradientDrawable, radius: Float): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(if (pal.dark) 0x22FFFFFF else 0x16000000), content, rounded(Color.WHITE, radius))

fun text(ctx: Context, pal: Pal, s: CharSequence, sp: Float = 14f, bold: Boolean = false, muted: Boolean = false) = TextView(ctx).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(if (muted) pal.muted else pal.text)
    typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.DEFAULT
    setLineSpacing(0f, 1.12f)
}

fun card(ctx: Context, pal: Pal) = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(pal.surface, ctx.dp(18).toFloat(), pal.border, ctx.dp(1))
    setPadding(ctx.dp(18), ctx.dp(16), ctx.dp(18), ctx.dp(16))
}

/** Press feedback: dips to 0.97 and springs back. */
fun View.pressable() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(2f)).start()
        }
        false
    }
}

enum class Kind { PRIMARY, TONAL, DANGER }

fun button(ctx: Context, pal: Pal, label: CharSequence, kind: Kind = Kind.TONAL, onClick: () -> Unit) = TextView(ctx).apply {
    text = label
    gravity = Gravity.CENTER
    minHeight = ctx.dp(48)
    minWidth = ctx.dp(48)
    setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    val r = ctx.dp(13).toFloat()
    when (kind) {
        Kind.PRIMARY -> { setTextColor(pal.onAccent); background = ripple(pal, rounded(pal.accent, r), r) }
        Kind.TONAL -> { setTextColor(pal.text); background = ripple(pal, rounded(pal.inset, r, pal.border, ctx.dp(1)), r) }
        Kind.DANGER -> { setTextColor(pal.danger); background = ripple(pal, rounded(pal.inset, r, pal.border, ctx.dp(1)), r) }
    }
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
    pressable()
}

/** Segmented control with a thumb that pulls back and springs to the chosen item. */
class Segmented(ctx: Context, private val pal: Pal, labels: List<String>, selected: Int, private val onSelect: (Int) -> Unit) : FrameLayout(ctx) {
    private val thumb = View(ctx)
    private val items = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val views = ArrayList<TextView>()
    private var index = selected
    private var thumbX = Float.NaN
    var originX = 0f
    var originY = 0f

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { originX = e.rawX; originY = e.rawY }
        return super.dispatchTouchEvent(e)
    }

    init {
        val r = ctx.dp(13).toFloat()
        background = rounded(pal.inset, r)
        setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        thumb.background = rounded(pal.surface, r - ctx.dp(3), pal.border, ctx.dp(1))
        addView(thumb, LayoutParams(0, ctx.dp(42)))
        addView(items, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        labels.forEachIndexed { i, l ->
            val tv = TextView(ctx).apply {
                text = l
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minHeight = ctx.dp(42)
                minWidth = ctx.dp(56)
                setPadding(ctx.dp(10), 0, ctx.dp(10), 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                isClickable = true
                setOnClickListener { select(i, true); onSelect(i) }
            }
            views.add(tv)
            items.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint()
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place(false) }
    }

    private fun paint() = views.forEachIndexed { i, v -> v.setTextColor(if (i == index) pal.text else pal.muted) }

    fun select(i: Int, animate: Boolean) {
        index = i
        paint()
        place(animate)
    }

    private fun place(animate: Boolean) {
        val v = views.getOrNull(index) ?: return
        if (v.width == 0) return
        val lp = thumb.layoutParams
        if (lp.width != v.width) { lp.width = v.width; thumb.layoutParams = lp }
        val x = (items.left + v.left).toFloat()
        val from = if (thumbX.isNaN()) x else thumbX
        thumbX = x
        thumb.animate().cancel()
        if (animate && from != x) {
            thumb.translationX = from
            thumb.animate().translationX(x).setDuration(520).setInterpolator(OvershootInterpolator(1.1f)).start()
        } else thumb.translationX = x
    }
}

/** Switch with a spring knob. */
class Toggle(ctx: Context, private val pal: Pal, on: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
    private var checked = on
    private var t = if (on) 1f else 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        minimumWidth = ctx.dp(52)
        minimumHeight = ctx.dp(48)
        isClickable = true
        setOnClickListener {
            checked = !checked
            ValueAnimator.ofFloat(t, if (checked) 1f else 0f).apply {
                duration = 480
                interpolator = OvershootInterpolator(1.2f)
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
            }.start()
            onChange(checked)
        }
        pressable()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(52), context.dp(48))

    override fun onDraw(c: Canvas) {
        val tw = context.dp(48).toFloat(); val th = context.dp(28).toFloat()
        val left = (width - tw) / 2; val top = (height - th) / 2
        p.color = if (checked) pal.accent else pal.inset
        c.drawRoundRect(left, top, left + tw, top + th, th / 2, th / 2, p)
        val k = th - context.dp(6)
        val x = left + context.dp(3) + (tw - k - context.dp(6)) * t.coerceIn(0f, 1f) + (if (t > 1f) (t - 1f) * context.dp(6) else 0f)
        p.color = Color.WHITE
        c.drawCircle(x + k / 2, top + th / 2, k / 2, p)
    }
}

/** Bottom sheet with a list of choices. Back and outside tap close it. */
fun sheet(ctx: Context, pal: Pal, title: String, items: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): Dialog {
    val d = Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(pal.scrim))
        setGravity(Gravity.BOTTOM)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setWindowAnimations(0)
    }
    val col = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val r = ctx.dp(24).toFloat()
        background = GradientDrawable().apply { setColor(pal.surface); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        setPadding(ctx.dp(8), ctx.dp(18), ctx.dp(8), ctx.dp(16))
    }
    col.addView(text(ctx, pal, title, 17f, true).apply { setPadding(ctx.dp(14), 0, ctx.dp(14), ctx.dp(10)) })
    val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    items.forEach { (value, label) ->
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(52)
            setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            background = ripple(pal, rounded(Color.TRANSPARENT, ctx.dp(12).toFloat()), ctx.dp(12).toFloat())
            isClickable = true
            setOnClickListener { d.dismiss(); onPick(value) }
        }
        row.addView(text(ctx, pal, label, 16f, value == selected), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value == selected) row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_check)
            setColorFilter(pal.accent)
        }, LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)))
        list.addView(row)
    }
    col.addView(android.widget.ScrollView(ctx).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    d.setContentView(col)
    d.setOnShowListener {
        col.translationY = ctx.dp(48).toFloat(); col.alpha = 0f
        col.animate().translationY(0f).alpha(1f).setDuration(520).setInterpolator(OvershootInterpolator(0.8f)).start()
    }
    d.window?.decorView?.setPadding(0, 0, 0, 0)
    return d
}

/** Draws the old UI everywhere except a growing circle: the new theme grows out of the tapped control. */
class RevealOverlay(ctx: Context, private val old: Bitmap, private val cx: Float, private val cy: Float, private val onDone: () -> Unit) : View(ctx) {
    private var radius = 0f
    private val path = Path()

    fun start() {
        val max = Math.hypot(Math.max(cx, width - cx).toDouble(), Math.max(cy, height - cy).toDouble()).toFloat()
        ValueAnimator.ofFloat(0f, max).apply {
            duration = 700
            interpolator = DecelerateInterpolator(2.4f)
            addUpdateListener { radius = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { onDone() }
            })
        }.start()
    }

    override fun onDraw(c: Canvas) {
        path.rewind()
        path.addCircle(cx, cy, radius, Path.Direction.CW)
        c.save()
        c.clipOutPath(path)
        c.drawBitmap(old, 0f, 0f, null)
        c.restore()
    }
}

fun fade(v: View, show: Boolean) {
    if (show) {
        v.visibility = View.VISIBLE
        v.alpha = 0f
        v.translationY = v.context.dp(8).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(DecelerateInterpolator(2f)).start()
    } else v.visibility = View.GONE
}

@Suppress("unused") private fun anim(v: View) = ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f)
