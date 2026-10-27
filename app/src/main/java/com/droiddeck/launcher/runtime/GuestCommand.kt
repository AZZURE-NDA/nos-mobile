package com.droiddeck.launcher.runtime

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.session.OrphanReaper
import com.droiddeck.launcher.session.SessionFiles
import com.droiddeck.launcher.session.SessionPaths
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File

object GuestCommand {
    fun run(context: Context, argv: List<String>, fakeRoot: Boolean = false, logName: String? = null,
            linkDir: File? = null, extraBinds: List<String>? = null, fastPath: Boolean = false, onLine: (String) -> Unit): Int {
        val log = logName?.let {
            try {
                File(File(LinuxRuntime.logDir(context), SessionPaths.TOOLS_DIR).apply { mkdirs() }, "$it.log").printWriter()
            } catch (e: Exception) { null }
        }
        log?.println("== ${java.util.Date()} ${argv.joinToString(" ")}")
        try {
            return runLogged(context, argv, fakeRoot, linkDir, extraBinds, fastPath) { line -> log?.println(line); log?.flush(); onLine(line) }
        } finally {
            log?.close()
        }
    }

    /** The proot command and host environment for an interactive shell in the guest, for a terminal view's own PTY. */
    fun shell(context: Context, fakeRoot: Boolean): Pair<List<String>, Map<String, String>> {
        val (cmd, env) = prepare(context, fakeRoot, null, null, fastPath = !fakeRoot,
            guestEnv = listOf("TERM=xterm-256color", "COLORTERM=truecolor", "SHELL=/bin/bash", "N_OS_TERMINAL=1"))
        cmd += listOf("/bin/bash", "-l")
        return cmd to env
    }

    private fun prepare(context: Context, fakeRoot: Boolean, linkDir: File?, extraBinds: List<String>?, fastPath: Boolean,
                        guestEnv: List<String> = emptyList()): Pair<MutableList<String>, Map<String, String>> {
        val root = LinuxRuntime.rootDir(context)
        LinuxRuntime.writeAccounts(context)
        SessionFiles.stage(context, root)
        LinuxNetworkLinkComponent(context, root).publish()
        val runtimeDir = File(context.filesDir, ".flatpak-rt").apply { mkdirs() }
        val cmd = LinuxRuntime.prootPrefix(context, root, "/root", fakeRoot)
        if (linkDir != null) cmd.add(1, "--link2symlink")
        val binds = LinuxRuntime.binds(context, null, runtimeDir, Environment.getExternalStorageDirectory(), extraBinds)
        binds.forEach { cmd.add("-b"); cmd.add(it) }
        // Fake root and link2symlink are emulated by proot, so their syscalls must stay on the traced path.
        val fastPathKey = if (fastPath && !fakeRoot && linkDir == null && ProotFastPath.enabled(context)) ProotFastPath.key(root, binds) else null
        cmd += listOf(
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "XDG_RUNTIME_DIR=${LinuxRuntime.GUEST_RUNTIME_DIR}",
            "XDG_DATA_HOME=/root/.local/share", "FLATPAK_BWRAP=${FlatpakManager.BWRAP}",
            "DROIDDECK_BWRAP_SOCKET=${BwrapSpawner.socket(context)}",
            "XDG_DATA_DIRS=/root/.local/share/flatpak/exports/share:/usr/local/share:/usr/share",
        )
        cmd += guestEnv
        fastPathKey?.let { cmd += ProotFastPath.guestEnv(root, binds, it) }
        val env = LinkedHashMap<String, String>()
        env["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path
        env["PROOT_TMP_DIR"] = LinuxRuntime.prootTempDir(context).path
        if (linkDir != null) env["PROOT_L2S_DIR"] = linkDir.path
        if (SessionPrefs.prootNoSeccomp(context)) env["PROOT_NO_SECCOMP"] = "1"
        fastPathKey?.let { ProotFastPath.hostEnv(it).let { (k, v) -> env[k] = v } }
        LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { env["LD_LIBRARY_PATH"] = it }
        return cmd to env
    }

    private fun runLogged(context: Context, argv: List<String>, fakeRoot: Boolean, linkDir: File?, extraBinds: List<String>?,
                          fastPath: Boolean, onLine: (String) -> Unit): Int {
        val root = LinuxRuntime.rootDir(context)
        val (cmd, env) = prepare(context, fakeRoot, linkDir, extraBinds, fastPath)
        cmd += argv
        val builder = ProcessBuilder(cmd).directory(root).redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        val pid = HostProcess.pidOf(process)
        OrphanReaper.keep(pid)
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            return process.waitFor()
        } finally {
            OrphanReaper.release(pid)
        }
    }
}
