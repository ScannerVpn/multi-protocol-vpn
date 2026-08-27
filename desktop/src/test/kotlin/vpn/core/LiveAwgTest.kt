package vpn.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * FULL live test against the stored server: provisions a new AmneziaWG peer
 * (script detects the existing AWG 3.1 docker install), verifies the version
 * is detected from the downloaded .conf, then connects the real wireproxy
 * core and checks actual traffic through the tunnel.
 * Runs ONLY when LIVE_AWG_TEST env var is set — never in normal builds.
 */
class LiveAwgTest {

    @Test
    fun `provision, detect 3_1 and connect`() {
        assumeTrue(System.getenv("LIVE_AWG_TEST") != null, "live test disabled")
        val server = runCatching { Storage.loadServers() }.getOrNull()?.firstOrNull() ?: return

        val wasRunning = WireProxy.isRunning()
        println("wireproxy already running: $wasRunning")

        val confFile = runBlocking {
            val dir = File(System.getProperty("java.io.tmpdir"), "multivpn-live-test").apply { mkdirs() }
            val result = SshService.provisionWireguard(server, dir, amnezia = true)
            println("provisioned: ${result.confPath}")
            println("detected awgVersion: ${result.awgVersion}")
            File(result.confPath)
        }

        // Show non-secret lines of the downloaded conf.
        val interesting = listOf(
            "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4",
            "I1", "I2", "I3", "I4", "I5", "HeaderProtectionKey", "ContentPaddingAddition",
            "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout",
            "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies", "Endpoint",
        )
        confFile.readLines().forEach { line ->
            val key = line.substringBefore("=").trim()
            if (key in interesting) {
                println(line.replace(Regex("(HeaderProtectionKey)\\s*=.*"), "$1 = <present>"))
            }
        }

        val detected = WireProxy.detectVersion(confFile)
        println("WireProxy.detectVersion -> $detected")
        check(detected == Awg.V31 || detected == Awg.V30) {
            "expected AWG 3.x detection, got $detected"
        }

        val built = WireProxy.buildConfig(confFile, amnezia = true)
            ?: error("buildConfig returned null")
        check(built.contains("HeaderProtectionKey")) { "built config lost HeaderProtectionKey" }
        check(built.contains("RandomTrailers = on")) { "built config lost RandomTrailers" }
        check(built.contains("ContentPaddingAddition = 10-100")) { "built config lost padding range" }

        // Live connect through the real core.
        runBlocking {
            if (wasRunning) WireProxy.kill()
            val ok = WireProxy.start(built)
            println("wireproxy started: $ok")
            if (ok) {
                val traffic = WireProxy.verifyTraffic()
                println("verifyTraffic: $traffic")
                println("core log tail:")
                println(WireProxy.lastLog(10))
                WireProxy.kill()
                check(traffic) { "tunnel came up but no real traffic passed" }
            } else {
                println(WireProxy.lastLog(15))
                error("wireproxy failed to start")
            }
        }
    }
}
