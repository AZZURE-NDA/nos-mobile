package com.droiddeck.launcher.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.TerminalSessions
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.OrphanReaper
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** One program on the Linux side, summed over its processes. */
internal class LinuxProgram(val name: String, val processes: Int, val rssKb: Long, val cpu: Float)

internal class LinuxSnapshot(
    val programs: List<LinuxProgram>,
    val rssKb: Long,
    val cpu: Float,
    val deviceTotalKb: Long,
    val deviceAvailKb: Long,
    val storageTotal: Long,
    val storageFree: Long,
)

/**
 * Reads the app's own processes from /proc: everything the Linux system runs shares the app's uid,
 * and Android lets an app read /proc for its own uid only.
 */
internal object LinuxStats {
    private val ticks = HashMap<Int, Long>()
    private var sampledAt = 0L
    private val hz: Long by lazy { runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L) }
    private val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    @Volatile var rootfsBytes: Long = -1

    fun snapshot(context: Context): LinuxSnapshot {
        val me = Process.myPid()
        val uid = Process.myUid()
        val now = System.nanoTime()
        val elapsed = if (sampledAt == 0L) 0.0 else (now - sampledAt) / 1e9
        val byName = HashMap<String, LongArray>()
        val cpuByName = HashMap<String, Float>()
        val seen = HashMap<Int, Long>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            val status = runCatching { File(entry, "status").readLines() }.getOrNull() ?: return@forEach
            val owner = status.firstOrNull { it.startsWith("Uid:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
            if (owner != uid) return@forEach
            val rss = status.firstOrNull { it.startsWith("VmRSS:") }?.filter(Char::isDigit)?.toLongOrNull() ?: 0L
            if (rss == 0L) return@forEach
            val name = programName(entry) ?: return@forEach
            val stat = runCatching { File(entry, "stat").readText() }.getOrNull() ?: return@forEach
            val fields = stat.substringAfterLast(") ").split(' ')
            val used = (fields.getOrNull(11)?.toLongOrNull() ?: 0L) + (fields.getOrNull(12)?.toLongOrNull() ?: 0L)
            seen[pid] = used
            val before = ticks[pid]
            if (before != null && elapsed > 0) {
                val share = ((used - before).coerceAtLeast(0L).toDouble() / hz / elapsed * 100.0 / cores).toFloat()
                cpuByName[name] = (cpuByName[name] ?: 0f) + share
            }
            val acc = byName.getOrPut(name) { LongArray(2) }
            acc[0] += 1
            acc[1] += rss
        }
        ticks.clear(); ticks.putAll(seen); sampledAt = now
        val programs = byName.map { (n, v) -> LinuxProgram(n, v[0].toInt(), v[1], cpuByName[n] ?: 0f) }
            .sortedByDescending { it.rssKb }
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val fs = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        return LinuxSnapshot(
            programs, programs.sumOf { it.rssKb }, programs.sumOf { it.cpu.toDouble() }.toFloat(),
            memory.totalMem / 1024, memory.availMem / 1024,
            fs?.totalBytes ?: 0L, fs?.availableBytes ?: 0L,
        )
    }

    /** The guest program name: proot runs guest programs through its loader, so cmdline names them best. */
    private fun programName(entry: File): String? {
        val cmd = runCatching { File(entry, "cmdline").readText() }.getOrNull()?.split('\u0000')?.filter { it.isNotEmpty() } ?: return null
        if (cmd.isEmpty()) return null
        val first = cmd.first().substringAfterLast('/')
        val name = when {
            first.startsWith("libproot") || first == "proot" -> "proot"
            first.startsWith("python") && cmd.size > 1 -> cmd[1].substringAfterLast('/')
            first == "bash" || first == "sh" -> cmd.getOrNull(1)?.takeIf { !it.startsWith("-") }?.substringAfterLast('/') ?: first
            else -> first
        }
        return name.take(40)
    }

    fun measureRootfs(context: Context) {
        if (rootfsBytes >= 0) return
        var total = 0L
        val stack = ArrayDeque<File>().apply { add(LinuxRuntime.rootDir(context)) }
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                val st = runCatching { Os.lstat(f.path) }.getOrNull() ?: continue
                if (OsConstants.S_ISDIR(st.st_mode)) stack.add(f) else total += st.st_blocks * 512
            }
        }
        rootfsBytes = total
    }
}

@Composable
internal fun StatusPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var snap by remember { mutableStateOf<LinuxSnapshot?>(null) }
    var rootfs by remember { mutableStateOf(LinuxStats.rootfsBytes) }
    var shells by remember { mutableStateOf(TerminalSessions.tabs.size) }
    var vpnUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { LinuxStats.snapshot(ctx) }
        while (true) {
            delay(2000)
            snap = withContext(Dispatchers.IO) { LinuxStats.snapshot(ctx) }
            vpnUp = withContext(Dispatchers.IO) { NetworkStore.vpnUp() }
            shells = TerminalSessions.tabs.size
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { LinuxStats.measureRootfs(ctx) }
        rootfs = LinuxStats.rootfsBytes
    }
    val desktop = SessionState.running

    Column(modifier.verticalScroll(rememberScrollState())) {
        Rise(0) { PageHeader(stringResource(R.string.status_title)) }
        Rise(1) {
            Text(stringResource(R.string.status_lead), fontSize = 15.sp, lineHeight = 22.sp, color = colors.onSurfaceVariant, modifier = Modifier.widthIn(max = 680.dp))
        }
        val sn = snap
        Rise(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 18.dp).widthIn(max = 760.dp).fillMaxWidth()) {
                Gauge(stringResource(R.string.status_cpu), sn?.let { "%.0f%%".format(it.cpu) } ?: "…", (sn?.cpu ?: 0f) / 100f, Modifier.weight(1f))
                Gauge(
                    stringResource(R.string.status_memory),
                    sn?.let { gb(it.rssKb * 1024) } ?: "…",
                    sn?.let { it.rssKb.toFloat() / it.deviceTotalKb.coerceAtLeast(1) } ?: 0f,
                    Modifier.weight(1f),
                    sn?.let { stringResource(R.string.status_memory_device, gb(it.deviceAvailKb * 1024), gb(it.deviceTotalKb * 1024)) },
                )
                Gauge(
                    stringResource(R.string.status_storage),
                    if (rootfs >= 0) gb(rootfs) else "…",
                    sn?.let { 1f - it.storageFree.toFloat() / it.storageTotal.coerceAtLeast(1) } ?: 0f,
                    Modifier.weight(1f),
                    sn?.let { stringResource(R.string.status_storage_free, gb(it.storageFree)) },
                )
            }
        }
        Rise(3) {
            StatusCard(Modifier.padding(top = 14.dp)) {
                Text(stringResource(R.string.status_running), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                Line(stringResource(R.string.status_desktop), if (desktop) stringResource(R.string.status_on) else stringResource(R.string.status_off))
                Line(stringResource(R.string.status_shells), shells.toString())
                Line(stringResource(R.string.status_vpn), if (vpnUp) stringResource(R.string.status_on) else stringResource(R.string.status_off))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(stringResource(R.string.status_stop_desktop), enabled = desktop, compact = true) { SessionService.stop(ctx) }
                    SecondaryButton(stringResource(R.string.status_close_shells), enabled = shells > 0, compact = true) {
                        TerminalSessions.closeAll(ctx); shells = 0
                    }
                    PrimaryButton(stringResource(R.string.status_stop_all), compact = true) {
                        if (desktop) SessionService.stop(ctx)
                        TerminalSessions.closeAll(ctx)
                        shells = 0
                        Thread({ Thread.sleep(3000); OrphanReaper.reap("Stop everything on the Status page") }, "stop-everything").start()
                    }
                }
            }
        }
        Rise(5) {
            StatusCard(Modifier.padding(top = 14.dp)) {
                Text(stringResource(R.string.status_programs), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                val programs = sn?.programs.orEmpty()
                if (programs.isEmpty()) Text(stringResource(R.string.status_programs_none), fontSize = 13.sp, color = colors.onSurfaceVariant)
                programs.take(12).forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name + if (p.processes > 1) " ×${p.processes}" else "", fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                            color = colors.onBackground, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("%.0f%%".format(p.cpu), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 56.dp))
                        Text(gb(p.rssKb * 1024), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 76.dp))
                    }
                }
                Text(stringResource(R.string.status_programs_hint), fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** GPU drawing for the desktop: KWin and Qt on Zink over the Vulkan driver, instead of the CPU. */
@Composable
private fun GpuCard() {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val config = remember { File(LinuxRuntime.rootDir(ctx), "root/.config") }
    var gpu by remember { mutableStateOf(runCatching { File(config, "nos-desktop-gpu").readText().trim() == "on" }.getOrDefault(false)) }
    val failed = remember { File(config, "nos-desktop-gpu-failed").isFile }
    fun set(on: Boolean) {
        runCatching {
            config.mkdirs()
            File(config, "nos-desktop-gpu").writeText(if (on) "on\n" else "off\n")
            if (on) File(config, "nos-desktop-gpu-failed").delete()
        }
        gpu = on
    }
    StatusCard(Modifier.padding(top = 14.dp)) {
        Text(stringResource(R.string.status_gpu_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (gpu) SecondaryButton(stringResource(R.string.status_gpu_software), compact = true) { set(false) }
            else PrimaryButton(stringResource(R.string.status_gpu_software), compact = true) { }
            if (gpu) PrimaryButton(stringResource(R.string.status_gpu_gpu), compact = true) { }
            else SecondaryButton(stringResource(R.string.status_gpu_gpu), compact = true) { set(true) }
        }
        Text(stringResource(R.string.status_gpu_hint), fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
        if (failed && gpu) Text(stringResource(R.string.status_gpu_failed), fontSize = 12.sp, lineHeight = 17.sp, color = colors.error)
    }
}

private fun gb(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
}

@Composable
private fun Gauge(label: String, value: String, fraction: Float, modifier: Modifier, detail: String? = null) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "gauge")
    val shape = RoundedCornerShape(14.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.clip(shape).background(colors.surface).border(1.dp, pal.line2, shape).padding(14.dp),
    ) {
        Text(label, fontSize = 12.sp, color = colors.onSurfaceVariant)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.surfaceVariant)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(RoundedCornerShape(3.dp)).background(pal.signal))
        }
        if (detail != null) Text(detail, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun StatusCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.widthIn(max = 760.dp).fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, LocalPalette.current.line2, shape).padding(16.dp),
        content = content,
    )
}

@Composable
private fun Line(label: String, value: String) {
    Row {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
    }
}
