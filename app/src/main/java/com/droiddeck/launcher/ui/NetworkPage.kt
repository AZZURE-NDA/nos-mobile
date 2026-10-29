package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.TerminalActivity
import com.droiddeck.launcher.runtime.LinuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** A saved SSH target. [jump] is `user@host:port` (user and port optional). */
internal data class SshHost(val name: String, val host: String, val port: Int, val user: String, val jump: String, val viaVpn: Boolean)

/** [full]: an Android VPN interface (ping, traceroute, every app); otherwise a SOCKS proxy only. */
internal data class VpnProfile(val server: String, val user: String, val protocol: String, val full: Boolean = true)

/** Saved network settings, and the guest-side files and commands built from them. */
internal object NetworkStore {
    private const val PREFS = "nos_network"
    const val SOCKS_PORT = 11080
    val PROTOCOLS = listOf("anyconnect", "gp", "fortinet", "pulse", "nc")

    fun hosts(context: Context): List<SshHost> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ssh", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                SshHost(o.optString("name"), o.optString("host"), o.optInt("port", 22), o.optString("user"), o.optString("jump"), o.optBoolean("vpn"))
            }
        }.getOrDefault(emptyList())
    }

    fun saveHosts(context: Context, hosts: List<SshHost>) {
        val array = JSONArray()
        hosts.forEach { h ->
            array.put(JSONObject().put("name", h.name).put("host", h.host).put("port", h.port).put("user", h.user).put("jump", h.jump).put("vpn", h.viaVpn))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ssh", array.toString()).apply()
        runCatching { writeSshConfig(context, hosts) }
    }

    fun vpn(context: Context): VpnProfile {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return VpnProfile(p.getString("vpn_server", "") ?: "", p.getString("vpn_user", "") ?: "", p.getString("vpn_protocol", "anyconnect") ?: "anyconnect",
            p.getBoolean("vpn_full", true))
    }

    fun saveVpn(context: Context, vpn: VpnProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("vpn_server", vpn.server).putString("vpn_user", vpn.user).putString("vpn_protocol", vpn.protocol)
            .putBoolean("vpn_full", vpn.full).apply()
        runCatching { writeSshConfig(context, hosts(context)) }
    }

    fun alias(h: SshHost) = "nos-" + h.name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifEmpty { "host" }

    /**
     * openconnect in user space. Full: nos-vpn-bridge carries the packets to the app's Android VPN interface.
     * Proxy: ocproxy turns the tunnel into a SOCKS5 proxy. Neither needs a tun device or root in Linux.
     */
    fun vpnCommand(v: VpnProfile): String {
        val handler = if (v.full) "/usr/local/bin/nos-vpn-bridge" else "ocproxy -D $SOCKS_PORT -k 30"
        val parts = mutableListOf("openconnect", "--script-tun", "--script", sq(handler))
        if (v.protocol.isNotBlank() && v.protocol != "anyconnect") parts += "--protocol=" + sq(v.protocol)
        if (v.user.isNotBlank()) parts += "--user=" + sq(v.user)
        parts += sq(v.server)
        return parts.joinToString(" ")
    }

    fun vpnUp(): Boolean = com.droiddeck.launcher.NosVpnService.state.up || socksUp()

    private fun socksUp(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 300); true }
    }.getOrDefault(false)

    /**
     * ~/.ssh/config.d/n-os.conf holds a Host block per saved target, so `ssh nos-<name>` works from any
     * shell too. Through the VPN, ssh reaches the target over ocproxy's SOCKS port with netcat.
     */
    private fun writeSshConfig(context: Context, hosts: List<SshHost>) {
        val ssh = File(LinuxRuntime.rootDir(context), "root/.ssh")
        val dir = File(ssh, "config.d")
        dir.mkdirs()
        ssh.setReadable(false, false); ssh.setReadable(true, true)
        ssh.setExecutable(false, false); ssh.setExecutable(true, true)
        ssh.setWritable(false, false); ssh.setWritable(true, true)
        // A full VPN routes for every program, so only proxy mode needs ssh pointed at the SOCKS port.
        val viaProxy = !vpn(context).full
        val proxy = "ProxyCommand nc -X 5 -x 127.0.0.1:$SOCKS_PORT %h %p"
        val out = StringBuilder("# Written by N-OS from the Network tab; edits here are replaced.\n")
        for (h in hosts) {
            val alias = alias(h)
            out.append("\nHost $alias\n  HostName ${h.host}\n  Port ${h.port}\n")
            if (h.user.isNotBlank()) out.append("  User ${h.user}\n")
            out.append("  ServerAliveInterval 30\n")
            if (h.jump.isNotBlank()) out.append("  ProxyJump $alias-jump\n") else if (h.viaVpn && viaProxy) out.append("  $proxy\n")
            if (h.jump.isNotBlank()) {
                val user = h.jump.substringBefore('@', "")
                val rest = h.jump.substringAfter('@')
                val jumpHost = rest.substringBefore(':')
                val jumpPort = rest.substringAfter(':', "22").toIntOrNull() ?: 22
                out.append("\nHost $alias-jump\n  HostName $jumpHost\n  Port $jumpPort\n")
                if (user.isNotBlank()) out.append("  User $user\n")
                out.append("  ServerAliveInterval 30\n")
                if (h.viaVpn && viaProxy) out.append("  $proxy\n")
            }
        }
        val conf = File(dir, "n-os.conf")
        conf.writeText(out.toString())
        conf.setReadable(false, false); conf.setReadable(true, true)
        val config = File(ssh, "config")
        val include = "Include config.d/*.conf"
        val existing = if (config.isFile) config.readText() else ""
        if (!existing.lines().any { it.trim() == include }) {
            config.writeText("$include\n" + existing)
            config.setReadable(false, false); config.setReadable(true, true)
        }
    }
}

internal fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"

@Composable
internal fun NetworkPage(s: FrontEndState, modifier: Modifier) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var vpn by remember { mutableStateOf(NetworkStore.vpn(ctx)) }
    var vpnUp by remember { mutableStateOf(false) }
    val hosts = remember { mutableStateListOf<SshHost>().apply { addAll(NetworkStore.hosts(ctx)) } }
    var editing by remember { mutableStateOf<SshHost?>(null) }
    var editingIndex by remember { mutableStateOf(-1) }
    var target by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            vpnUp = withContext(Dispatchers.IO) { NetworkStore.vpnUp() }
            delay(3000)
        }
    }
    fun run(command: String) = TerminalActivity.run(ctx, command)
    val consent = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            com.droiddeck.launcher.NosVpnService.start(ctx)
            run(NetworkStore.vpnCommand(vpn))
        }
    }
    fun connect() {
        NetworkStore.saveVpn(ctx, vpn)
        if (!vpn.full) { run(NetworkStore.vpnCommand(vpn)); return }
        val ask = android.net.VpnService.prepare(ctx)
        if (ask != null) consent.launch(ask)
        else {
            com.droiddeck.launcher.NosVpnService.start(ctx)
            run(NetworkStore.vpnCommand(vpn))
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        Rise(0) { PageHeader(stringResource(R.string.net_title)) }
        Rise(1) {
            Text(stringResource(R.string.net_lead), fontSize = 15.sp, lineHeight = 22.sp, color = colors.onSurfaceVariant, modifier = Modifier.widthIn(max = 680.dp))
        }

        Rise(2) {
            NetCard(Modifier.padding(top = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.VpnLock, null, tint = colors.onBackground, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.net_vpn_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, modifier = Modifier.weight(1f))
                    StatusDot(vpnUp)
                    Text(
                        when {
                            !vpnUp -> stringResource(R.string.net_vpn_down)
                            com.droiddeck.launcher.NosVpnService.state.up -> stringResource(R.string.net_vpn_up_full, com.droiddeck.launcher.NosVpnService.state.address)
                            else -> stringResource(R.string.net_vpn_up, NetworkStore.SOCKS_PORT)
                        },
                        fontSize = 13.sp, color = colors.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AdbTextField(vpn.server, { vpn = vpn.copy(server = it.trim()) }, stringResource(R.string.net_vpn_server), KeyboardType.Uri, ImeAction.Next,
                        Modifier.weight(1.4f), placeholder = "vpn.example.com", compact = true)
                    AdbTextField(vpn.user, { vpn = vpn.copy(user = it.trim()) }, stringResource(R.string.net_vpn_user), KeyboardType.Text, ImeAction.Done,
                        Modifier.weight(1f), compact = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.net_vpn_protocol), fontSize = 12.sp, color = colors.onSurfaceVariant)
                    for (p in NetworkStore.PROTOCOLS) Pick(p, vpn.protocol == p) { vpn = vpn.copy(protocol = p) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.net_vpn_mode), fontSize = 12.sp, color = colors.onSurfaceVariant)
                    Pick(stringResource(R.string.net_vpn_mode_full), vpn.full) { vpn = vpn.copy(full = true) }
                    Pick(stringResource(R.string.net_vpn_mode_proxy), !vpn.full) { vpn = vpn.copy(full = false) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(stringResource(R.string.net_vpn_connect), enabled = s.ready && vpn.server.isNotBlank() && !vpnUp, compact = true) {
                        connect()
                    }
                    SecondaryButton(stringResource(R.string.net_vpn_disconnect), enabled = vpnUp, compact = true) {
                        run("pkill -INT openconnect; echo; echo 'VPN disconnected.'")
                        com.droiddeck.launcher.NosVpnService.stop(ctx)
                    }
                }
                Text(if (vpn.full) stringResource(R.string.net_vpn_hint_full) else stringResource(R.string.net_vpn_hint, NetworkStore.SOCKS_PORT), fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
            }
        }

        Rise(3) {
            NetCard(Modifier.padding(top = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Lan, null, tint = colors.onBackground, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.net_tools_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                }
                AdbTextField(target, { target = it.trim() }, stringResource(R.string.net_tools_target), KeyboardType.Uri, ImeAction.Done,
                    placeholder = "10.0.0.1 / router.example.net", compact = true)
                val t = sq(target)
                val ok = s.ready && target.isNotBlank()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    SecondaryButton("ping", enabled = ok, compact = true) { run("ping -c 10 $t") }
                    SecondaryButton("traceroute", enabled = ok, compact = true) { run("traceroute $t") }
                    SecondaryButton("mtr", enabled = ok, compact = true) { run("mtr -t $t") }
                    SecondaryButton("dig", enabled = ok, compact = true) { run("dig $t +noall +answer") }
                    SecondaryButton("nmap", enabled = ok, compact = true) { run("nmap -F -Pn $t") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.net_tools_interfaces), enabled = s.ready, compact = true) { run("ip -br addr; echo; ip route") }
                    SecondaryButton(stringResource(R.string.net_tools_public_ip), enabled = s.ready, compact = true) { run("curl -s https://ifconfig.me; echo") }
                }
            }
        }

        Rise(4) {
            NetCard(Modifier.padding(top = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.net_ssh_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.net_ssh_add), compact = true) {
                        editing = SshHost("", "", 22, "", "", vpnUp)
                        editingIndex = -1
                    }
                }
                if (hosts.isEmpty() && editing == null) Text(stringResource(R.string.net_ssh_empty), fontSize = 13.sp, color = colors.onSurfaceVariant)
                hosts.forEachIndexed { i, h ->
                    HostRow(h, s.ready,
                        onOpen = { run("ssh " + NetworkStore.alias(h)) },
                        onEdit = { editing = h; editingIndex = i },
                        onDelete = { hosts.removeAt(i); NetworkStore.saveHosts(ctx, hosts.toList()) })
                }
                editing?.let { draft ->
                    HostEditor(draft,
                        onSave = { saved ->
                            if (editingIndex in hosts.indices) hosts[editingIndex] = saved else hosts += saved
                            NetworkStore.saveHosts(ctx, hosts.toList())
                            editing = null
                        },
                        onCancel = { editing = null })
                }
                Text(stringResource(R.string.net_ssh_hint), fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NetCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.widthIn(max = 760.dp).fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, LocalPalette.current.line2, shape).padding(16.dp),
        content = content,
    )
}

@Composable
private fun StatusDot(on: Boolean) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(if (on) Color(0xFF28C840) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
}

@Composable
private fun Pick(label: String, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Text(
        label, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(shape).background(if (selected) pal.signal else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun HostRow(h: SshHost, ready: Boolean, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().clip(shape).background(colors.surfaceVariant).clickable(enabled = ready, onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(h.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            val via = buildList { if (h.jump.isNotBlank()) add("via ${h.jump}"); if (h.viaVpn) add("VPN") }.joinToString(" · ")
            Text(
                (if (h.user.isNotBlank()) "${h.user}@" else "") + h.host + (if (h.port != 22) ":${h.port}" else "") + (if (via.isNotEmpty()) "  ·  $via" else ""),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant,
            )
        }
        Icon(Icons.Outlined.Edit, stringResource(R.string.net_ssh_edit), tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable(onClick = onEdit))
        Icon(Icons.Outlined.Delete, stringResource(R.string.net_ssh_delete), tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable(onClick = onDelete))
    }
}

@Composable
private fun HostEditor(draft: SshHost, onSave: (SshHost) -> Unit, onCancel: () -> Unit) {
    var name by remember(draft) { mutableStateOf(draft.name) }
    var host by remember(draft) { mutableStateOf(draft.host) }
    var port by remember(draft) { mutableStateOf(draft.port.toString()) }
    var user by remember(draft) { mutableStateOf(draft.user) }
    var jump by remember(draft) { mutableStateOf(draft.jump) }
    var viaVpn by remember(draft) { mutableStateOf(draft.viaVpn) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AdbTextField(name, { name = it }, stringResource(R.string.net_ssh_name), KeyboardType.Text, ImeAction.Next, Modifier.weight(1f), placeholder = "core-router-1", compact = true)
            AdbTextField(host, { host = it.trim() }, stringResource(R.string.net_ssh_host), KeyboardType.Uri, ImeAction.Next, Modifier.weight(1.3f), placeholder = "10.0.0.1", compact = true)
            AdbTextField(port, { port = it.filter(Char::isDigit).take(5) }, stringResource(R.string.net_ssh_port), KeyboardType.Number, ImeAction.Next, Modifier.weight(0.5f), compact = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AdbTextField(user, { user = it.trim() }, stringResource(R.string.net_ssh_user), KeyboardType.Text, ImeAction.Next, Modifier.weight(1f), compact = true)
            AdbTextField(jump, { jump = it.trim() }, stringResource(R.string.net_ssh_jump), KeyboardType.Uri, ImeAction.Done, Modifier.weight(1.3f), placeholder = "user@bastion:22", compact = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pick(stringResource(R.string.net_ssh_via_vpn), viaVpn) { viaVpn = !viaVpn }
            Box(Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.common_cancel), compact = true, onClick = onCancel)
            PrimaryButton(stringResource(R.string.net_ssh_save), enabled = name.isNotBlank() && host.isNotBlank(), compact = true) {
                onSave(SshHost(name.trim(), host, port.toIntOrNull() ?: 22, user, jump, viaVpn))
            }
        }
    }
}
