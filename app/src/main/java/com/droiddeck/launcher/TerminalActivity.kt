package com.droiddeck.launcher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.OrphanReaper
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.util.Properties
import kotlin.concurrent.thread

/** An Ubuntu shell on its own, without starting the desktop. */
class TerminalActivity : Activity() {
    private var session: TerminalSession? = null
    private var view: TerminalView? = null
    private var shellPid = -1
    private var ctrl = false
    private var alt = false
    private var ctrlKey: TextView? = null
    private var altKey: TextView? = null
    private var fontPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BAR
        window.navigationBarColor = BG
        val root = intent.getBooleanExtra(EXTRA_ROOT, false)
        fontPx = dp(15)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(BAR)
        }
        bar.addView(pill(getString(R.string.terminal_close)) { finish() })
        bar.addView(TextView(this).apply {
            text = getString(if (root) R.string.terminal_title_root else R.string.terminal_title)
            textSize = 15f
            setTextColor(FG)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(pill("A−") { zoom(-1) })
        bar.addView(pill("A+") { zoom(1) })
        bar.addView(pill(getString(R.string.terminal_keyboard)) { showKeyboard() })
        column.addView(bar, LinearLayout.LayoutParams(-1, -2))

        val client = Client()
        val terminal = TerminalView(this, null).apply {
            setTerminalViewClient(client)
            setTextSize(fontPx)
            setTypeface(monoFont())
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(BG)
            setPadding(dp(8), dp(6), dp(8), 0)
        }
        view = terminal
        column.addView(terminal, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(keyStrip(), LinearLayout.LayoutParams(-1, -2))
        setContentView(column)

        if (!LinuxRuntime.isInstalled(this)) {
            terminal.visibility = View.GONE
            column.addView(TextView(this).apply {
                text = getString(R.string.terminal_no_runtime)
                setTextColor(FG)
                gravity = Gravity.CENTER
            }, 1, LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }
        applyColors()
        thread(name = "terminal-start") {
            val prepared = runCatching { GuestCommand.shell(applicationContext, root) }
                .onFailure { Log.e(TAG, "could not prepare the shell", it) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed || prepared == null) return@runOnUiThread
                val (command, hostEnv) = prepared
                val env = LinkedHashMap(System.getenv()).apply { putAll(hostEnv) }.map { (k, v) -> "$k=$v" }.toTypedArray()
                val started = TerminalSession(command.first(), LinuxRuntime.rootDir(this).path, command.toTypedArray(), env, TRANSCRIPT_ROWS, client)
                started.mSessionName = "N-OS terminal"
                session = started
                terminal.attachSession(started)
                terminal.post { trackPid(); terminal.requestFocus(); showKeyboard() }
            }
        }
    }

    override fun onDestroy() {
        val pid = shellPid.takeIf { it > 1 } ?: (session?.pid ?: -1)
        if (pid > 1) {
            OrphanReaper.release(pid)
            OrphanReaper.killTree(pid)
        }
        session = null
        view = null
        super.onDestroy()
    }

    private fun trackPid() {
        val pid = session?.pid ?: -1
        if (pid > 1 && pid != shellPid) {
            shellPid = pid
            OrphanReaper.keep(pid)
        }
    }

    private fun zoom(step: Int) {
        fontPx = (fontPx + step * dp(1)).coerceIn(dp(9), dp(30))
        view?.setTextSize(fontPx)
    }

    private fun showKeyboard() {
        val terminal = view ?: return
        terminal.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun keyStrip(): View {
        val strip = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(BAR) }
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(5), dp(6), dp(5)) }
        fun send(label: String, input: String) = keys.addView(pill(label) { session?.write(input) })
        send("Esc", "\u001b")
        send("Tab", "\t")
        ctrlKey = pill("Ctrl") { ctrl = !ctrl; latch() }.also { keys.addView(it) }
        altKey = pill("Alt") { alt = !alt; latch() }.also { keys.addView(it) }
        send("↑", "\u001b[A")
        send("↓", "\u001b[B")
        send("←", "\u001b[D")
        send("→", "\u001b[C")
        send("Home", "\u001b[H")
        send("End", "\u001b[F")
        send("|", "|")
        send("~", "~")
        send("/", "/")
        send("-", "-")
        send("^C", "\u0003")
        send("^D", "\u0004")
        keys.addView(pill(getString(R.string.terminal_paste)) { paste() })
        strip.addView(keys)
        return strip
    }

    private fun latch() {
        (ctrlKey?.background as? GradientDrawable)?.setColor(if (ctrl) ACCENT else KEY)
        (altKey?.background as? GradientDrawable)?.setColor(if (alt) ACCENT else KEY)
    }

    private fun paste() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        if (clip != null && clip.itemCount > 0) session?.write(clip.getItemAt(0).coerceToText(this).toString())
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(FG)
        gravity = Gravity.CENTER
        minWidth = dp(44)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(KEY) }
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
    }

    private fun monoFont(): Typeface {
        val ubuntuMono = File(LinuxRuntime.rootDir(this), "usr/share/fonts/truetype/ubuntu/UbuntuMono-R.ttf")
        return runCatching { if (ubuntuMono.isFile) Typeface.createFromFile(ubuntuMono) else null }.getOrNull() ?: Typeface.MONOSPACE
    }

    private fun applyColors() {
        val props = Properties()
        props["background"] = "#151921"
        props["foreground"] = "#E0E5EE"
        props["cursor"] = "#3478F6"
        PALETTE.forEachIndexed { i, c -> props["color$i"] = c }
        TerminalColors.COLOR_SCHEME.updateWith(props)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class Client : TerminalSessionClient, TerminalViewClient {
        override fun onTextChanged(changedSession: TerminalSession) { view?.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) {
            if (shellPid > 1) OrphanReaper.release(shellPid)
            shellPid = -1
            if (!isFinishing) finish()
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession) = paste()
        override fun onBell(session: TerminalSession) { view?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
        override fun onColorsChanged(changedSession: TerminalSession) { view?.invalidate() }
        override fun onTerminalCursorStateChange(state: Boolean) { view?.setTerminalCursorBlinkerRate(if (state) 600 else 0) }
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }

        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) { zoom(if (scale > 1f) 1 else -1); return 1f }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent) = showKeyboard()
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = ctrl.also { if (it) { ctrl = false; latch() } }
        override fun readAltKey(): Boolean = alt.also { if (it) { alt = false; latch() } }
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onEmulatorSet() { view?.onScreenUpdated(); trackPid() }
    }

    companion object {
        private const val TAG = "TerminalActivity"
        const val EXTRA_ROOT = "root"
        private const val TRANSCRIPT_ROWS = 5000
        private val BG = Color.rgb(21, 25, 33)
        private val BAR = Color.rgb(27, 32, 42)
        private val KEY = Color.rgb(42, 48, 60)
        private val FG = Color.rgb(224, 229, 238)
        private val ACCENT = Color.rgb(52, 120, 246)
        private val PALETTE = listOf(
            "#2A303C", "#FF5F57", "#28C840", "#FEBC2E", "#3478F6", "#B07CF0", "#1E9BFF", "#C8CED8",
            "#5C6576", "#FF827A", "#6EE27C", "#FFD46E", "#6EA0FF", "#CCA4FF", "#6EC4FF", "#FFFFFF",
        )

        fun open(context: Context, root: Boolean) {
            context.startActivity(Intent(context, TerminalActivity::class.java).putExtra(EXTRA_ROOT, root))
        }
    }
}
