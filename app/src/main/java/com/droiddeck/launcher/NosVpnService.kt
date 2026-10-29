package com.droiddeck.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.IpPrefix
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.droiddeck.launcher.runtime.BwrapSpawner
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress

/**
 * Android's side of the full VPN. openconnect in the Linux system hands its packets to nos-vpn-bridge,
 * which asks this service, over an abstract socket, for a tun interface with the tunnel's address,
 * routes and DNS, and receives its file descriptor. Every app on the tablet, the Linux side included,
 * then reaches the VPN's networks through it, ping and traceroute too.
 */
class NosVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(getString(R.string.vpn_waiting)))
        listen()
        return START_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        runCatching { server?.close() }
        server = null
        super.onDestroy()
    }

    @Synchronized
    private fun listen() {
        if (server != null) return
        val socket = try { LocalServerSocket(socketName(this)) } catch (e: IOException) {
            Log.e(TAG, "cannot listen for the VPN bridge", e); return
        }
        server = socket
        Thread({
            while (true) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                Thread({ serve(client) }, "vpn-bridge").apply { isDaemon = true; start() }
            }
        }, "vpn-server").apply { isDaemon = true; start() }
    }

    private fun serve(client: LocalSocket) {
        client.use {
            if (client.peerCredentials.uid != Process.myUid()) {
                Log.w(TAG, "refused a VPN request from uid ${client.peerCredentials.uid}")
                return
            }
            val line = client.inputStream.bufferedReader().readLine() ?: return
            val conf = runCatching { JSONObject(line) }.getOrNull() ?: return
            val tun = try { build(conf) } catch (e: Exception) {
                Log.e(TAG, "could not build the VPN interface", e)
                client.outputStream.write(("error: " + (e.message ?: e.toString()) + "\n").toByteArray())
                return
            }
            if (tun == null) {
                client.outputStream.write("error: Android refused the VPN (permission not given)\n".toByteArray())
                return
            }
            stopTunnel()
            synchronized(this) { this.tun = tun; this.bridge = client }
            state = State(true, conf.optString("address"), conf.optJSONArray("routes")?.length() ?: 0)
            notify(getString(R.string.vpn_connected, conf.optString("address")))
            client.setFileDescriptorsForSend(arrayOf(tun.fileDescriptor))
            client.outputStream.write("ok\n".toByteArray())
            client.outputStream.flush()
            client.setFileDescriptorsForSend(null)
            // The bridge keeps this connection open for the tunnel's lifetime; its end ends the VPN.
            runCatching { while (client.inputStream.read() >= 0) Unit }
            synchronized(this) { if (this.tun === tun) stopTunnel() }
            notify(getString(R.string.vpn_waiting))
        }
    }

    private fun build(conf: JSONObject): ParcelFileDescriptor? {
        val builder = Builder().setSession("N-OS VPN").setMtu(conf.optInt("mtu", 1400).coerceIn(576, 9000))
        builder.addAddress(conf.getString("address"), 32)
        val routes = conf.optJSONArray("routes")
        if (routes == null || routes.length() == 0) {
            builder.addRoute("0.0.0.0", 0)
            // The connection to the VPN server itself must stay outside the tunnel.
            val gateway = conf.optString("gateway")
            if (gateway.isNotBlank() && Build.VERSION.SDK_INT >= 33) {
                runCatching { builder.excludeRoute(IpPrefix(InetAddress.getByName(gateway), 32)) }
            }
        } else {
            for (i in 0 until routes.length()) route(routes.getString(i))?.let { (a, b) -> builder.addRoute(a, b) }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val excludes = conf.optJSONArray("excludes")
            if (excludes != null) for (i in 0 until excludes.length()) {
                route(excludes.getString(i))?.let { (a, b) -> runCatching { builder.excludeRoute(IpPrefix(InetAddress.getByName(a), b)) } }
            }
        }
        val dns = conf.optJSONArray("dns")
        if (dns != null) for (i in 0 until dns.length()) runCatching {
            builder.addDnsServer(dns.getString(i))
            // With split routes the VPN's DNS servers may lie outside them; queries to them go through the tunnel.
            if (routes != null && routes.length() > 0) builder.addRoute(dns.getString(i), 32)
        }
        val domains = conf.optJSONArray("domains")
        if (domains != null) for (i in 0 until domains.length()) runCatching { builder.addSearchDomain(domains.getString(i)) }
        builder.setBlocking(true)
        return builder.establish()
    }

    private fun route(spec: String): Pair<String, Int>? {
        val address = spec.substringBefore('/')
        val bits = spec.substringAfter('/', "32").toIntOrNull() ?: return null
        return if (address.isBlank()) null else address to bits.coerceIn(0, 32)
    }

    @Synchronized
    private fun stopTunnel() {
        runCatching { tun?.close() }
        tun = null
        // The bridge holds its own copy of the interface; closing its connection makes it let go.
        runCatching { bridge?.shutdownInput(); bridge?.shutdownOutput(); bridge?.close() }
        bridge = null
        state = State(false, "", 0)
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, NosVpnService::class.java).setAction(ACTION_STOP), flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(getString(R.string.vpn_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.vpn_stop), stop).build())
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    private var tun: ParcelFileDescriptor? = null
    private var bridge: LocalSocket? = null

    class State(val up: Boolean, val address: String, val routes: Int)

    companion object {
        private const val TAG = "NosVpnService"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 4712
        const val ACTION_STOP = "com.droiddeck.launcher.vpn.STOP"
        @Volatile private var server: LocalServerSocket? = null
        @Volatile var state = State(false, "", 0)
            private set

        fun socketName(context: Context) = BwrapSpawner.socket(context) + ".vpn"

        /** Starts listening for the bridge; Android's consent ([VpnService.prepare]) must already be given. */
        fun start(context: Context) {
            val intent = Intent(context, NosVpnService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NosVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
