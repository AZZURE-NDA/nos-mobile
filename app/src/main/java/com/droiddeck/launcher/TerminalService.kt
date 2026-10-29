package com.droiddeck.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.OrphanReaper
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/** One shell the user opened: kept by [TerminalSessions] while the terminal screen comes and goes. */
class TerminalTab(val id: Int, val root: Boolean, val session: TerminalSession) {
    var title: String = if (root) "root" else "shell"
    val pid: Int get() = session.pid
}

/**
 * The open shells. They outlive the terminal screen: [TerminalService] keeps the app in the foreground
 * while any is running, so a long Claude Code run survives switching apps.
 */
object TerminalSessions {
    private const val TAG = "TerminalSessions"
    private const val TRANSCRIPT_ROWS = 5000
    private var nextId = 1
    val tabs = mutableListOf<TerminalTab>()
    var client: TerminalSessionClient? = null
        set(value) {
            field = value
            tabs.forEach { it.session.updateTerminalSessionClient(value ?: Detached) }
        }
    var onChanged: (() -> Unit)? = null

    /** Builds the proot command off the main thread, then calls [done] on the main thread. */
    fun open(context: Context, root: Boolean, done: (TerminalTab?) -> Unit) {
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val prepared = runCatching { GuestCommand.shell(app, root) }.onFailure { Log.e(TAG, "could not prepare a shell", it) }.getOrNull()
            main.post {
                if (prepared == null) { done(null); return@post }
                val (command, hostEnv) = prepared
                val env = LinkedHashMap(System.getenv()).apply { putAll(hostEnv) }.map { (k, v) -> "$k=$v" }.toTypedArray()
                val session = TerminalSession(command.first(), LinuxRuntime.rootDir(app).path, command.toTypedArray(), env, TRANSCRIPT_ROWS, client ?: Detached)
                session.mSessionName = "N-OS terminal"
                val tab = TerminalTab(nextId++, root, session)
                tabs += tab
                TerminalService.sync(app)
                onChanged?.invoke()
                done(tab)
            }
        }, "terminal-open").start()
    }

    /** Called once the session's process exists, so the session reaper leaves it alone. */
    fun track(tab: TerminalTab) {
        if (tab.pid > 1) OrphanReaper.keep(tab.pid)
    }

    fun trackAll() { runCatching { tabs.toList() }.getOrDefault(emptyList()).forEach { track(it) } }

    fun close(context: Context, tab: TerminalTab) {
        val pid = tab.pid
        tabs.remove(tab)
        if (pid > 1) {
            OrphanReaper.release(pid)
            OrphanReaper.killTree(pid)
        }
        tab.session.finishIfRunning()
        TerminalService.sync(context.applicationContext)
        onChanged?.invoke()
    }

    fun finished(context: Context, session: TerminalSession) {
        val tab = tabs.firstOrNull { it.session === session } ?: return
        if (tab.pid > 1) OrphanReaper.release(tab.pid)
        tabs.remove(tab)
        TerminalService.sync(context.applicationContext)
        onChanged?.invoke()
    }

    fun closeAll(context: Context) {
        tabs.toList().forEach { close(context, it) }
    }

    /** Receives a shell's events while no terminal screen is showing it. */
    private object Detached : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) {
            appContext?.let { finished(it, finishedSession) }
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(changedSession: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int = com.termux.terminal.TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) = Unit
        override fun logDebug(tag: String, message: String) = Unit
        override fun logVerbose(tag: String, message: String) = Unit
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }
    }

    @Volatile var appContext: Context? = null
}

class TerminalService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TerminalSessions.appContext = applicationContext
        if (intent?.action == ACTION_STOP) {
            TerminalSessions.closeAll(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val count = TerminalSessions.tabs.size
        if (count == 0) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(count))
        return START_NOT_STICKY
    }

    private fun notification(count: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.terminal_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(this, 0, Intent(this, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, TerminalService::class.java).setAction(ACTION_STOP), flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(resources.getQuantityString(R.plurals.terminal_running, count, count))
            .setContentText(getString(R.string.terminal_running_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.terminal_stop_all), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "terminal"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "com.droiddeck.launcher.terminal.STOP"

        fun sync(context: Context) {
            TerminalSessions.appContext = context.applicationContext
            val intent = Intent(context, TerminalService::class.java)
            try {
                if (TerminalSessions.tabs.isNotEmpty() && Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                Log.w("TerminalService", "could not update the terminal service", e)
            }
        }
    }
}
