package com.multivpn.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.hiddify.core.libbox.CommandServer
import com.hiddify.core.libbox.CommandServerHandler
import com.hiddify.core.libbox.ConnectionOwner
import com.hiddify.core.libbox.InterfaceUpdateListener
import com.hiddify.core.libbox.Libbox
import com.hiddify.core.libbox.LocalDNSTransport
import com.hiddify.core.libbox.NetworkInterface
import com.hiddify.core.libbox.NetworkInterfaceIterator
import com.hiddify.core.libbox.Notification as LibboxNotification
import com.hiddify.core.libbox.OverrideOptions
import com.hiddify.core.libbox.PlatformInterface
import com.hiddify.core.libbox.SystemProxyStatus
import com.hiddify.core.libbox.TunOptions
import com.hiddify.core.libbox.WIFIState
import com.multivpn.android.LibboxSetup
import com.multivpn.android.MainActivity
import java.io.File
import java.net.NetworkInterface as JavaNetworkInterface

/**
 * The Android VPN transport — the counterpart of the desktop app's local
 * core processes. libbox (hiddify-core v4.1.0, embedding sing-box 1.13) runs
 * the tunnel; this service owns the TUN device and lifetime.
 *
 * Architecture (mirrors SFA, the reference libbox client):
 *  - [Libbox.setup] runs FIRST, in the Application (see MultiVpnApp) — every
 *    libbox call before it fails inside Go;
 *  - a [CommandServer] is the control point, and it must be `start()`ed before
 *    `startOrReloadService` will do anything;
 *  - the TUN fd flows back through [PlatformInterface.openTun] implemented HERE;
 *  - a foreground notification is mandatory for a VpnService, and on API 34+
 *    the specialUse type additionally needs FOREGROUND_SERVICE_SPECIAL_USE or
 *    startForeground throws and the service dies before libbox is asked to run;
 *  - status surfaces through [EngineBridge] so the Compose UI reacts.
 *
 * HONESTY CONTRACT (desktop PLAN §4): "وصل شد" is only ever reported by the
 * ENGINE after it verified a real 204 through the tunnel — libbox starting
 * up merely moves the status to CONNECTING, never CONNECTED. Every failure
 * here carries the core's own message, never a generic one.
 */
class TunnelVpnService : VpnService(), PlatformInterface {

    companion object {
        const val TAG = "MultiVPN.Tunnel"
        const val CHANNEL_ID = "multivpn_tunnel"
        const val NOTIFICATION_ID = 1

        // Go's net.Flags bits (net/interface.go) — sing-box reads these
        // verbatim, so the numeric layout has to match Go's, not Java's.
        private const val FLAG_UP = 1
        private const val FLAG_BROADCAST = 2
        private const val FLAG_LOOPBACK = 4
        private const val FLAG_POINT_TO_POINT = 8
        private const val FLAG_MULTICAST = 16
        private const val FLAG_RUNNING = 32

        /** The running service instance — the engine talks to libbox through it. */
        @Volatile
        var instance: TunnelVpnService? = null
            private set

        fun start(context: android.content.Context) {
            context.startService(Intent(context, TunnelVpnService::class.java))
        }

        fun stop(context: android.content.Context) {
            instance?.requestDisconnect()
            context.stopService(Intent(context, TunnelVpnService::class.java))
        }
    }

    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val netMonitor by lazy { DefaultNetworkMonitor(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("در حال آماده‌سازی…"))
        LibboxSetup.error?.let { e ->
            Log.e(TAG, "Libbox.setup failed: $e")
            EngineBridge.setFailed("راه‌اندازی هسته ناموفق بود: $e")
            stopSelf()
            return
        }
        try {
            val server = Libbox.newCommandServer(StatusHandler(), this)
            // start() opens the core's control socket. Without it
            // startOrReloadService is a no-op and the tunnel never comes up.
            server.start()
            commandServer = server
            Log.i(TAG, "command server started, libbox ${runCatching { Libbox.version() }.getOrNull()}")
        } catch (e: Exception) {
            Log.e(TAG, "newCommandServer/start failed", e)
            EngineBridge.setFailed("هسته تونل راه نیفتاد: ${e.message ?: e.toString()}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { netMonitor.stop() }
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        closeTun()
        instance = null
        EngineBridge.setServiceGone()
        super.onDestroy()
    }

    /**
     * Validates then loads [configJson] into libbox.
     * @return null on success, or the core's own rejection message. checkConfig
     *         runs first so a schema error is reported as a schema error
     *         instead of a silent "timeout" 20 s later.
     */
    fun loadAndStart(configJson: String): String? {
        val server = commandServer
            ?: return "سرویس تونل هنوز راه نیفتاده — یک بار دیگر تلاش کنید."
        EngineBridge.setStatus(EngineStatus.CONNECTING)
        updateNotification("در حال اتصال…")
        try {
            server.checkConfig(configJson)
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            Log.e(TAG, "checkConfig rejected the config: $msg")
            EngineBridge.setFailed("کانفیگ از نظر هسته نامعتبر است: $msg")
            return msg
        }
        return try {
            // OverrideOptions must NOT be null: sing-box 1.13's
            // CommandServer.StartOrReloadService dereferences it immediately
            // (command_server.go:173), so a null crashed the whole PROCESS
            // with a Go SIGSEGV that no Java catch block can see.
            server.startOrReloadService(configJson, OverrideOptions())
            Log.i(TAG, "startOrReloadService accepted the config")
            null
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            Log.e(TAG, "startOrReloadService failed: $msg")
            EngineBridge.setFailed("اجرای تونل ناموفق بود: $msg")
            msg
        }
    }

    fun requestDisconnect() {
        runCatching { commandServer?.closeService() }
    }

    /** The core's stderr (Go panics never surface as Java exceptions). */
    fun coreStderrTail(maxChars: Int = 600): String? = runCatching {
        val f = File(File(filesDir, "box"), "stderr.log")
        if (!f.exists() || f.length() == 0L) return null
        f.readText().takeLast(maxChars).trim().ifEmpty { null }
    }.getOrNull()

    // ------------------------------------------------------------------
    // PlatformInterface — libbox calls back into these
    // ------------------------------------------------------------------

    /** libbox parsed the config and wants its TUN device. We build it with
     *  Android's VpnService.Builder and hand back the fd. */
    override fun openTun(options: TunOptions): Int {
        Log.i(TAG, "openTun: mtu=${options.getMTU()} autoRoute=${options.getAutoRoute()}")
        val builder = Builder()
            .setSession("MultiVPN")
            .setMtu(options.getMTU())

        val it4 = options.getInet4Address()
        while (it4.hasNext()) {
            val p = it4.next()
            builder.addAddress(p.address(), p.prefix())
        }
        val it6 = options.getInet6Address()
        while (it6.hasNext()) {
            val p = it6.next()
            builder.addAddress(p.address(), p.prefix())
        }
        // Routes: with auto_route the config pushes 0.0.0.0/0 + ::/0 — but the
        // parser default may omit them, so fall back to full-tunnel routes
        // explicitly. Without ANY route the TUN captures nothing and libbox
        // would sit "started" on a dead device.
        var added4 = false
        val r4 = options.getInet4RouteAddress()
        while (r4.hasNext()) {
            val p = r4.next()
            builder.addRoute(p.address(), p.prefix())
            added4 = true
        }
        if (!added4) builder.addRoute("0.0.0.0", 0)
        var added6 = false
        val r6 = options.getInet6RouteAddress()
        while (r6.hasNext()) {
            val p = r6.next()
            builder.addRoute(p.address(), p.prefix())
            added6 = true
        }
        if (!added6) builder.addRoute("::", 0)

        val dns = runCatching { options.getDNSServerAddress() }.getOrNull()
        if (dns != null) {
            for (d in dns.getValue().split(",".toRegex())) {
                runCatching { builder.addDnsServer(d.trim()) }
            }
        }

        val ex = options.getExcludePackage()
        while (ex.hasNext()) runCatching { builder.addDisallowedApplication(ex.next()) }
        val inc = options.getIncludePackage()
        while (inc.hasNext()) runCatching { builder.addAllowedApplication(inc.next()) }

        closeTun()
        val fd = builder.establish()
            ?: throw Exception("establish() returned null — دسترسی VPN داده نشده است")
        tunFd = fd
        Log.i(TAG, "TUN established")
        // fd.fd, NOT detachFd(): libbox does not take ownership of the
        // descriptor, so detaching left nobody to close it — every reconnect
        // leaked an fd and stranded a tun device (observed: tun0/tun1/tun2 all
        // present after three connects). We keep the ParcelFileDescriptor and
        // close it in closeTun().
        return fd.fd
    }

    /** Closes the TUN we own, if any. Safe to call repeatedly. */
    private fun closeTun() {
        val fd = tunFd ?: return
        tunFd = null
        runCatching { fd.close() }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun sendNotification(notification: LibboxNotification) {
        updateNotification(notification.getTitle().ifEmpty { "تونل فعال" })
    }

    /**
     * Enumerates the device's live interfaces for libbox's interface picker.
     *
     * Two things the core is unforgiving about:
     *  - each address must be a bare CIDR. Java renders a link-local IPv6 as
     *    `fe80::…%dummy0`, and sing-box calls netip.MustParsePrefix on it,
     *    which PANICS on a zone suffix and takes the whole process down
     *    (observed: "IPv6 zones cannot be present in a prefix"). Strip `%…`;
     *  - `flags` must carry the real Go net.Flags bits — a zeroed flags field
     *    makes every interface look down, so the core finds no candidate to
     *    bind its outbound sockets to.
     */
    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = mutableListOf<NetworkInterface>()
        val jnifs = runCatching {
            JavaNetworkInterface.getNetworkInterfaces()?.toList()
        }.getOrNull() ?: emptyList()
        for (nif in jnifs) {
            val addrs = mutableListOf<String>()
            for (a in nif.interfaceAddresses) {
                val host = a.address?.hostAddress ?: continue
                // Drop the scope id: "fe80::1%dummy0" -> "fe80::1"
                addrs.add("${host.substringBefore('%')}/${a.networkPrefixLength}")
            }
            val ni = NetworkInterface()
            ni.index = nif.index
            ni.name = nif.name
            ni.mtu = runCatching { nif.mtu }.getOrDefault(1500)
            ni.addresses = StringIteratorBox(addrs)
            ni.flags = goFlags(nif)
            ni.type = interfaceType(nif.name)
            list.add(ni)
        }
        return NetworkIteratorBox(list)
    }

    /** Go's net.Flags bit layout, which is what sing-box reads. */
    private fun goFlags(nif: JavaNetworkInterface): Int {
        var f = 0
        runCatching { if (nif.isUp) f = f or FLAG_UP or FLAG_RUNNING }
        runCatching { if (nif.supportsMulticast()) f = f or FLAG_MULTICAST }
        runCatching { if (nif.isLoopback) f = f or FLAG_LOOPBACK }
        runCatching { if (nif.isPointToPoint) f = f or FLAG_POINT_TO_POINT }
        runCatching {
            if (!nif.isLoopback && !nif.isPointToPoint) f = f or FLAG_BROADCAST
        }
        return f
    }

    /** Best-effort interface class; only affects the core's preference order. */
    private fun interfaceType(name: String): Int = when {
        name.startsWith("wlan") -> Libbox.InterfaceTypeWIFI
        name.startsWith("rmnet") || name.startsWith("ccmni") -> Libbox.InterfaceTypeCellular
        name.startsWith("eth") -> Libbox.InterfaceTypeEthernet
        else -> Libbox.InterfaceTypeOther
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun useProcFS(): Boolean = true

    /** libbox asks for the owning app of each connection (per-app stats).
     *  On API 29+ the platform answer is the (rarely granted) USAGE_STATS
     *  access; returning our own uid only degrades stats, never the tunnel —
     *  SFA does the same without the permission. */
    override fun findConnectionOwner(
        pid: Int,
        protocol: String,
        sourcePort: Int,
        destinationIp: String,
        destinationPort: Int,
    ): ConnectionOwner = ConnectionOwner().apply {
        userId = android.os.Process.myUid()
        androidPackageName = packageName
    }

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {}

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        netMonitor.stop()
    }

    /**
     * libbox asks to be told which interface is the default one. With
     * `auto_detect_interface` the core binds its outbound sockets to whatever
     * this reports — so skipping it means the TUN comes up and nothing ever
     * leaves the device.
     */
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        netMonitor.start(listener)
    }

    override fun localDNSTransport(): LocalDNSTransport? = null

    /** The device trust store — libbox needs the CA list for TLS through the
     *  tunnel. This AAR exposes no native systemCertificates() helper
     *  (checked with javap), so we enumerate the system + user stores. */
    override fun systemCertificates(): com.hiddify.core.libbox.StringIterator {
        val pems = mutableListOf<String>()
        try {
            val factory = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm(),
            )
            factory.init(null as java.security.KeyStore?)
            for (tm in factory.trustManagers) {
                if (tm is javax.net.ssl.X509TrustManager) {
                    for (cert in tm.acceptedIssuers) {
                        pems.add(
                            "-----BEGIN CERTIFICATE-----\n" +
                                android.util.Base64.encodeToString(cert.encoded, android.util.Base64.DEFAULT) +
                                "-----END CERTIFICATE-----\n",
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // empty iterator: libbox falls back to its bundled Mozilla list
        }
        return StringIteratorBox(pems)
    }

    override fun onRevoke() {
        // VPN revoked from system settings — report honestly, no retry loop.
        EngineBridge.setFailed("دسترسی VPN توسط سیستم لغو شد.")
        stopSelf()
    }

    // ------------------------------------------------------------------
    // Status bridge (the REAL CommandServerHandler contract)
    // ------------------------------------------------------------------

    private inner class StatusHandler : CommandServerHandler {
        override fun getSystemProxyStatus(): SystemProxyStatus? = null

        override fun serviceReload() {
            // The UI never asks libbox for a reload in phase 2; nothing to do.
        }

        override fun serviceStop() {
            EngineBridge.setStatus(EngineStatus.DISCONNECTED)
            updateNotification("قطع")
            runCatching { tunFd?.close() }
            tunFd = null
        }

        override fun setSystemProxyEnabled(enabled: Boolean) {}

        override fun writeDebugMessage(message: String) {
            Log.d(TAG, "core: $message")
        }
    }

    // ------------------------------------------------------------------
    // Notification plumbing (mandatory for a foreground VpnService)
    // ------------------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MultiVPN Tunnel", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MultiVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }
}

/** Minimal StringIterator over a Kotlin list (libbox proxies lists as iterators). */
internal class StringIteratorBox(private val items: List<String>) :
    com.hiddify.core.libbox.StringIterator {
    private val it = items.iterator()
    override fun hasNext(): Boolean = it.hasNext()
    override fun next(): String = it.next()
    override fun len(): Int = items.size
}

/** Minimal NetworkInterfaceIterator. */
internal class NetworkIteratorBox(private val items: List<NetworkInterface>) :
    NetworkInterfaceIterator {
    private val it = items.iterator()
    override fun hasNext(): Boolean = it.hasNext()
    override fun next(): NetworkInterface = it.next()
}
