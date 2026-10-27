package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.TerminalActivity

@Composable
internal fun TerminalPage(s: FrontEndState, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    Column(modifier.verticalScroll(rememberScrollState())) {
        Rise(0) { PageHeader(stringResource(R.string.terminal_title)) }
        Rise(1) {
            Text(
                stringResource(R.string.terminal_page_lead), fontSize = 15.sp, lineHeight = 22.sp, color = colors.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 620.dp),
            )
        }
        Rise(2) { ShellPreview(Modifier.padding(top = 18.dp).widthIn(max = 620.dp).fillMaxWidth()) }
        Rise(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 18.dp)) {
                PrimaryButton(stringResource(R.string.terminal_open), enabled = s.ready, main = true, icon = Icons.Outlined.Terminal) {
                    TerminalActivity.open(ctx, root = false)
                }
                SecondaryButton(stringResource(R.string.terminal_open_root), enabled = s.ready) { TerminalActivity.open(ctx, root = true) }
            }
        }
        Rise(4) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp).widthIn(max = 620.dp)) {
                Icon(Icons.Outlined.AdminPanelSettings, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.terminal_root_hint), fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
            }
        }
        if (!s.ready) Rise(5) { Box(Modifier.padding(top = 16.dp).widthIn(max = 620.dp)) { Note(stringResource(R.string.terminal_no_runtime)) } }
        Rise(6) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 24.dp).widthIn(max = 620.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)).background(colors.surface).border(1.dp, pal.line2, RoundedCornerShape(14.dp)).padding(16.dp),
            ) {
                Text(stringResource(R.string.terminal_tips_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                for (tip in listOf(R.string.terminal_tip_claude, R.string.terminal_tip_desktop, R.string.terminal_tip_keys)) {
                    Text("•  " + stringResource(tip), fontSize = 13.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ShellPreview(modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(Color(0xFF151921)).border(1.dp, Color(0xFF2A303C), shape)) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1B202A)).padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            for (c in listOf(0xFFFF5F57, 0xFFFEBC2E, 0xFF28C840)) Box(Modifier.size(11.dp).clip(RoundedCornerShape(50)).background(Color(c)))
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Line("root@n-os", ":~$ ", "claude")
            Line("root@n-os", ":~$ ", "ip -br addr")
            Text("wlan0   UP   192.168.1.24/24", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFFA0A8B6))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Line("root@n-os", ":~$ ", "")
                ThinkingDots(Modifier.padding(start = 2.dp), dot = 5.dp)
            }
        }
    }
}

@Composable
private fun Line(host: String, prompt: String, command: String) {
    Row {
        Text(host, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFF28C840), fontWeight = FontWeight.SemiBold)
        Text(prompt, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFF6EA0FF))
        if (command.isNotEmpty()) Text(command, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFFE0E5EE))
    }
}
