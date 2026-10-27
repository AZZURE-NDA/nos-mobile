package com.droiddeck.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
internal fun LogoThrobber(modifier: Modifier = Modifier, running: Boolean = true) {
    val ink = MaterialTheme.colorScheme.onBackground
    val node = LocalPalette.current.signal
    val ring = MaterialTheme.colorScheme.background
    val loop = rememberInfiniteTransition(label = "throbber")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)), label = "loop")
    val still = !running || Motion.scale == 0f
    Canvas(modifier.aspectRatio(1f)) {
        val p = if (still) 0f else loop.value
        val s = size.width / NosMark.SIZE
        fun px(x: Float, y: Float) = Offset((x - NosMark.ORIGIN) * s, (y - NosMark.ORIGIN) * s)
        val stroke = NosMark.STROKE * s
        val a = px(NosMark.AX, NosMark.AY)
        val b = px(NosMark.BX, NosMark.BY)
        val path = listOf(px(NosMark.AX, NosMark.BY), a, b, px(NosMark.BX, NosMark.AY))
        drawLine(ink, path[0], a, stroke, StrokeCap.Round)
        drawLine(ink, path[3], b, stroke, StrokeCap.Round)
        drawLine(ink, a, b, stroke, StrokeCap.Round)
        var nearA = 0f
        var nearB = 0f
        if (!still) {
            // A signal runs up the left stem, across the link and up the right stem, trailing a comet tail.
            val head = p * (1f + TAIL)
            for (i in TRAIL_DOTS downTo 0) {
                val u = head - TAIL * i / TRAIL_DOTS
                if (u < 0f || u > 1f) continue
                val fade = smooth(minOf(u, 1f - u) / EDGE)
                val k = 1f - i.toFloat() / (TRAIL_DOTS + 1)
                drawCircle(node.copy(alpha = node.alpha * fade * k * k), radius = stroke * (0.2f + 0.24f * k), center = along(path, u))
            }
            val h = head.coerceIn(0f, 1f)
            nearA = 1f - smooth(kotlin.math.abs(h - NODE_A_AT) / NODE_REACH)
            nearB = 1f - smooth(kotlin.math.abs(h - NODE_B_AT) / NODE_REACH)
        }
        for ((c, k) in listOf(a to 1f + 0.16f * nearA, b to 1f + 0.16f * nearB)) {
            drawCircle(ring, radius = (NosMark.NODE + NosMark.RING) * s * k, center = c)
            drawCircle(node, radius = NosMark.NODE * s * k, center = c)
        }
    }
}

/** The three bouncing dots of a chat app's "thinking" indicator. */
@Composable
internal fun ThinkingDots(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = LocalPalette.current.signal, dot: androidx.compose.ui.unit.Dp = 7.dp) {
    val wave = rememberInfiniteTransition(label = "dots")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(DOTS_MS, easing = LinearEasing)), label = "wave")
    val still = Motion.scale == 0f
    Canvas(modifier.width(dot * 5).height(dot * 2)) {
        val r = dot.toPx() / 2f
        for (i in 0 until 3) {
            val phase = if (still) 0.5f else ((wave.value - i * 0.16f) % 1f + 1f) % 1f
            val lift = if (phase < 0.5f) sin(phase * 2f * Math.PI.toFloat()) else 0f
            drawCircle(
                color.copy(alpha = color.alpha * (0.4f + 0.6f * lift)),
                radius = r * (0.85f + 0.15f * lift),
                center = Offset(r + i * r * 4f, size.height - r - lift * r * 1.6f),
            )
        }
    }
}

private fun smooth(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun along(points: List<Offset>, u: Float): Offset {
    val lengths = points.zipWithNext { p, q -> (q - p).getDistance() }
    var left = lengths.sum() * u.coerceIn(0f, 1f)
    for ((i, len) in lengths.withIndex()) {
        if (left <= len || i == lengths.lastIndex) {
            val f = if (len == 0f) 0f else (left / len).coerceIn(0f, 1f)
            return points[i] + (points[i + 1] - points[i]) * f
        }
        left -= len
    }
    return points.last()
}

internal fun throbberBall(bounds: androidx.compose.ui.geometry.Rect): Pair<Offset, Float> {
    val s = bounds.width / NosMark.SIZE
    return Offset(bounds.left + (NosMark.AX - NosMark.ORIGIN) * s, bounds.top + (NosMark.AY - NosMark.ORIGIN) * s) to NosMark.NODE * s
}

/** The N-OS mark in artwork/nos-mark.svg's units, cropped to a square around the drawing. */
private object NosMark {
    const val ORIGIN = 108f
    const val SIZE = 216f
    const val AX = 150f
    const val AY = 140f
    const val BX = 282f
    const val BY = 292f
    const val STROKE = 34f
    const val NODE = 27f
    const val RING = 10f
}

private const val LOOP_MS = 1700
private const val DOTS_MS = 1200
private const val TAIL = 0.22f
private const val TRAIL_DOTS = 24
private const val EDGE = 0.08f
// The nodes sit 30% and 70% of the way along the stem-link-stem path.
private const val NODE_A_AT = 0.30f
private const val NODE_B_AT = 0.70f
private const val NODE_REACH = 0.12f

internal object LogoShape {
    const val LEFT = -55.98f
    const val WIDTH = 239.92f
    const val HEIGHT = 223.72f
    const val CX = 63.98f
    const val CY = 111.86f
    const val BALL = 55.98f
    private const val OUTER = 111.86f
    private const val INNER = 71.81f
    private const val SQUARE_X = -31.98f
    private const val SPLIT_LEFT = 56.055f
    private const val SPLIT_RIGHT = 71.885f

    private class Pt(val x: Float, val y: Float)

    private fun at(a: Double, r: Float) = Pt((CX + r * cos(a)).toFloat(), (CY + r * sin(a)).toFloat())
    private fun angle(x: Float, y: Float) = atan2((y - CY).toDouble(), (x - CX).toDouble())
    private fun leftward(a: Double) = if (a < 0) a else a - 2 * Math.PI

    private val rays: List<Double> = run {
        val top = angle(SPLIT_LEFT, 0f)
        val topCorner = angle(SQUARE_X, 0f)
        val bottomCorner = leftward(angle(SQUARE_X, HEIGHT))
        val bottom = leftward(angle(SPLIT_LEFT, HEIGHT))
        fun span(a: Double, b: Double, n: Int) = List(n) { i -> a + (b - a) * i / (n - 1) }
        span(top, topCorner, 9).dropLast(1) + span(topCorner, bottomCorner, 25).dropLast(1) + span(bottomCorner, bottom, 9)
    }

    private val square: List<Pt> = rays.map { a ->
        val c = cos(a)
        val sn = sin(a)
        val hits = buildList {
            if (c < -1e-9) add((SQUARE_X - CX) / c)
            if (sn < -1e-9) add((0f - CY) / sn)
            if (sn > 1e-9) add((HEIGHT - CY) / sn)
        }
        val t = hits.minOrNull() ?: 0.0
        Pt((CX + t * c).toFloat(), (CY + t * sn).toFloat())
    }.toMutableList().also { it[0] = Pt(SPLIT_LEFT, 0f); it[it.lastIndex] = Pt(SPLIT_LEFT, HEIGHT) }

    private val arc: List<Pt> = rays.map { at(it, OUTER) }.toMutableList().also {
        val dy = sqrt(OUTER * OUTER - (SPLIT_LEFT - CX) * (SPLIT_LEFT - CX))
        it[0] = Pt(SPLIT_LEFT, CY - dy)
        it[it.lastIndex] = Pt(SPLIT_LEFT, CY + dy)
    }

    private val leftInner: List<Pt> = run {
        val dx = (SPLIT_LEFT - CX).toDouble()
        val dy = sqrt(INNER * INNER - dx * dx)
        val from = atan2(dy, dx)
        val to = atan2(-dy, dx) + 2 * Math.PI
        List(rays.size) { i -> at(from + (to - from) * i / (rays.size - 1), INNER) }
    }

    private val rightHalf: List<Pt> = run {
        val dx = (SPLIT_RIGHT - CX).toDouble()
        val oy = sqrt(OUTER * OUTER - dx * dx)
        val iy = sqrt(INNER * INNER - dx * dx)
        val outerFrom = atan2(-oy, dx)
        val outerTo = atan2(oy, dx)
        val innerFrom = atan2(iy, dx)
        val innerTo = atan2(-iy, dx)
        List(40) { i -> at(outerFrom + (outerTo - outerFrom) * i / 39, OUTER) } +
            List(40) { i -> at(innerFrom + (innerTo - innerFrom) * i / 39, INNER) }
    }

    fun left(path: Path, bend: Float, px: (Float, Float) -> Offset) {
        path.reset()
        square.forEachIndexed { i, q ->
            val r = arc[i]
            val o = px(q.x + (r.x - q.x) * bend, q.y + (r.y - q.y) * bend)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        for (q in leftInner) px(q.x, q.y).let { path.lineTo(it.x, it.y) }
        path.close()
    }

    fun right(path: Path, px: (Float, Float) -> Offset) {
        path.reset()
        rightHalf.forEachIndexed { i, q ->
            val o = px(q.x, q.y)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        path.close()
    }
}
