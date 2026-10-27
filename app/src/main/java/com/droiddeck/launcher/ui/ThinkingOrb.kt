// Ported from thinking-orbs by Jakub Antalik (MIT, see assets/licenses/thinking-orbs/LICENSE):
// the "working" (orbits) and "connecting" (web) geometry, with the 64-size presets.
package com.droiddeck.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class OrbState { Working, Connecting }

/** A dotted 3D "thinking" orb. Signals in the connecting state use [accent]. */
@Composable
internal fun ThinkingOrb(
    modifier: Modifier = Modifier,
    state: OrbState = OrbState.Connecting,
    running: Boolean = true,
    ink: Color = MaterialTheme.colorScheme.onBackground,
    accent: Color = LocalPalette.current.signal,
) {
    val clock = remember { mutableFloatStateOf(OrbClock.seconds()) }
    val still = !running || Motion.scale == 0f
    if (!still) LaunchedEffect(Unit) {
        while (true) withFrameNanos { clock.floatValue = OrbClock.seconds() }
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Canvas(modifier.aspectRatio(1f)) {
        // The presets are tuned for a 64-unit orb; larger orbs scale that drawing up so dots keep their weight.
        val t = (if (still) 1.3f else clock.floatValue) * if (state == OrbState.Working) 1.885f else 3.315f
        val frame = if (state == OrbState.Working) Orbs.orbits(TUNED_SIZE, t) else Orbs.web(TUNED_SIZE, t)
        drawFrame(frame, size.minDimension / TUNED_SIZE, dark, ink, accent)
    }
}

private const val TUNED_SIZE = 64f

/** Where a launch flood gathers into the orb: its centre. */
internal fun orbBall(bounds: androidx.compose.ui.geometry.Rect): Pair<Offset, Float> = bounds.center to bounds.width * 0.06f

private object OrbClock {
    private val start = System.nanoTime()
    // One clock for every orb, so several on screen stay in phase.
    fun seconds(): Float = ((System.nanoTime() - start) / 1_000_000L % 3_600_000L) / 1000f
}

private class OrbDot(val x: Float, val y: Float, val z: Float, var r: Float, val white: Float, val a: Float = 1f, val signal: Boolean = false)
private class OrbEdge(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val white: Float, val a: Float, val w: Float)
private class OrbFrame(val dots: List<OrbDot>, val edges: List<OrbEdge>)

private fun DrawScope.drawFrame(frame: OrbFrame, scale: Float, dark: Boolean, ink: Color, accent: Color) {
    fun shade(white: Float, alpha: Float): Color {
        val w = white.coerceIn(0f, 1f)
        // Ink 0 is the darkest mark; on a dark background the scale is mirrored so near dots read bright.
        val strength = 1f - w
        val bg = if (dark) Color.Black else Color.White
        return Color(
            bg.red + (ink.red - bg.red) * strength,
            bg.green + (ink.green - bg.green) * strength,
            bg.blue + (ink.blue - bg.blue) * strength,
            alpha.coerceIn(0f, 1f),
        )
    }
    for (e in frame.edges) {
        drawLine(shade(e.white, e.a), Offset(e.x1 * scale, e.y1 * scale), Offset(e.x2 * scale, e.y2 * scale), e.w * scale)
    }
    for (d in frame.dots) {
        val c = if (d.signal) accent.copy(alpha = d.a) else shade(d.white, d.a)
        drawCircle(c, d.r * scale, Offset(d.x * scale, d.y * scale))
    }
}

private object Orbs {
    private const val R_MIN = 0.3f

    private fun hash(a: Float, b: Float): Float {
        val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
        return (h - floor(h)).toFloat()
    }

    private fun noise(x: Float, y: Float): Float {
        val xi = floor(x)
        val yi = floor(y)
        var fx = x - xi
        var fy = y - yi
        fx = fx * fx * (3 - 2 * fx)
        fy = fy * fy * (3 - 2 * fy)
        val a = hash(xi, yi)
        val b = hash(xi + 1, yi)
        val c = hash(xi, yi + 1)
        val d = hash(xi + 1, yi + 1)
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
    }

    private fun fib(i: Int, n: Int): FloatArray {
        val golden = PI * (3 - sqrt(5.0))
        val y = 1 - 2 * (i + 0.5) / n
        val rad = sqrt(1 - y * y)
        val a = i * golden
        return floatArrayOf((rad * cos(a)).toFloat(), y.toFloat(), (rad * sin(a)).toFloat())
    }

    private class Proj(yaw: Float, tilt: Float, val cx: Float, val cy: Float, val scale: Float) {
        private val st = sin(tilt)
        private val ct = cos(tilt)
        private val sy = sin(yaw)
        private val cyw = cos(yaw)
        fun at(x: Float, y: Float, z: Float): FloatArray {
            val x1 = x * cyw + z * sy
            val z1 = -x * sy + z * cyw
            val y1 = y * ct - z1 * st
            val z2 = y * st + z1 * ct
            return floatArrayOf(cx + x1 * scale, cy - y1 * scale, z2)
        }
    }

    private fun radiusScale(size: Float) = (size / 300f).pow(0.6f)

    private fun finish(dots: MutableList<OrbDot>, edges: List<OrbEdge>): OrbFrame {
        val visible = dots.filter { it.a >= 0.02f }.onEach { it.r = max(R_MIN, it.r) }.sortedBy { it.z }
        return OrbFrame(visible, edges.filter { it.a >= 0.02f })
    }

    fun orbits(size: Float, t: Float): OrbFrame {
        val c = size / 2
        val big = c * 0.82f
        val pt = Proj(t * 0.12f, 0.3f, c, c, 1f)
        val rs = radiusScale(size)
        val dots = ArrayList<OrbDot>(12 * 43)
        for (orb in 0 until 12) {
            val h1 = hash(orb.toFloat(), 1.7f)
            val h2 = hash(orb.toFloat(), 5.2f)
            val h3 = hash(orb.toFloat(), 8.9f)
            val ro = big * (0.45f + 0.52f * h1)
            val th = h1 * 2 * PI.toFloat()
            val phi = acos(2 * h2 - 1)
            val nx = sin(phi) * cos(th)
            val ny = cos(phi)
            val nz = sin(phi) * sin(th)
            var ux = -ny
            var uy = nx
            val uz = 0f
            val ul = max(1e-6f, sqrt(ux * ux + uy * uy))
            ux /= ul
            uy /= ul
            val vx = ny * uz - nz * uy
            val vy = nz * ux - nx * uz
            val vz = nx * uy - ny * ux
            val speed = (0.25f + 0.55f * h3) * if (h3 > 0.5f) 1 else -1
            fun on(a: Float) = pt.at((ux * cos(a) + vx * sin(a)) * ro, (uy * cos(a) + vy * sin(a)) * ro, (uz * cos(a) + vz * sin(a)) * ro)
            for (k in 0 until 40) {
                val p = on(k / 40f * 2 * PI.toFloat())
                val depth = (p[2] / ro + 1) / 2
                dots += OrbDot(p[0], p[1], p[2], 0.9f * rs, 0.72f, 0.5f * (0.4f + 0.6f * depth))
            }
            for (m in 0 until 3) {
                val p = on(t * speed + m / 3f * 2 * PI.toFloat() + h2 * 6)
                val depth = (p[2] / ro + 1) / 2
                dots += OrbDot(p[0], p[1], p[2], (1.2f + 1.6f * depth) * rs, 0.3f - 0.22f * depth)
            }
        }
        return finish(dots, emptyList())
    }

    fun web(size: Float, t: Float): OrbFrame {
        val nodeN = 41
        val thr = 0.72f
        val nodeR = 1.4f * 0.95f
        val nodeRDepth = 1.8f * 0.95f
        val c = size / 2
        val pt = Proj(t * 0.12f, 0.32f, c, c, c * 0.8f)
        val rs = radiusScale(size)
        val nodes = Array(nodeN) { i ->
            val d = fib(i, nodeN)
            val x = d[0] + 0.3f * (noise(i * 0.31f + 9, t * 0.24f) - 0.5f) * 2
            val y = d[1] + 0.3f * (noise(i * 0.53f + 27, t * 0.21f) - 0.5f) * 2
            val z = d[2] + 0.3f * (noise(i * 0.77f + 55, t * 0.27f) - 0.5f) * 2
            val l = sqrt(x * x + y * y + z * z)
            floatArrayOf(x / l, y / l, z / l)
        }
        val projected = Array(nodeN) { pt.at(nodes[it][0], nodes[it][1], nodes[it][2]) }
        val edges = ArrayList<OrbEdge>()
        for (i in 0 until nodeN) for (j in i + 1 until nodeN) {
            val dx = nodes[i][0] - nodes[j][0]
            val dy = nodes[i][1] - nodes[j][1]
            val dz = nodes[i][2] - nodes[j][2]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist >= thr) continue
            val p = projected[i]
            val q = projected[j]
            val depth = ((p[2] + q[2]) / 2 + 1) / 2
            edges += OrbEdge(p[0], p[1], q[0], q[1], 0.42f, (1 - dist / thr) * (0.3f + 0.55f * depth), max(0.6f, 0.8f * rs))
        }
        val dots = ArrayList<OrbDot>(nodeN + 7)
        for (i in 0 until nodeN) {
            val p = projected[i]
            val depth = (p[2] + 1) / 2
            val pulse = 1 + 0.25f * sin(t * 1.4f + i * 2.7f)
            dots += OrbDot(p[0], p[1], p[2], (nodeR + nodeRDepth * depth) * pulse * rs, 0.55f - 0.45f * depth)
        }
        for (s in 0 until 7) {
            val seg = floor(t * 0.55f + s * 7.31f)
            val a = (hash(seg, s * 3.1f + 1.7f) * nodeN).toInt()
            val b = (hash(seg, s * 5.7f + 4.2f) * nodeN).toInt()
            if (a == b) continue
            val phase = t * 0.55f + s * 7.31f
            val f = phase - floor(phase)
            val x = nodes[a][0] + (nodes[b][0] - nodes[a][0]) * f
            val y = nodes[a][1] + (nodes[b][1] - nodes[a][1]) * f
            val z = nodes[a][2] + (nodes[b][2] - nodes[a][2]) * f
            val l = max(1e-6f, sqrt(x * x + y * y + z * z))
            val p = pt.at(x / l, y / l, z / l)
            val depth = (p[2] + 1) / 2
            // Packets fade in and out at the ends of their run instead of popping.
            val edge = min(f, 1 - f).coerceAtMost(0.12f) / 0.12f
            dots += OrbDot(p[0], p[1], p[2], (nodeR * 1.5f + nodeRDepth * depth) * rs, 0.05f, (0.5f + 0.5f * depth) * edge, signal = true)
        }
        return finish(dots, edges)
    }
}
