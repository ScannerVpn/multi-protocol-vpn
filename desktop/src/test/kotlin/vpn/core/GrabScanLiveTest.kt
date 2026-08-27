package vpn.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test

/**
 * Live read-only inventory against the stored server.
 * Runs ONLY when GRAB_SCAN_TEST env var is set.
 */
class GrabScanLiveTest {

    @Test
    fun `scan tunnels and xray links`() {
        assumeTrue(System.getenv("GRAB_SCAN_TEST") != null, "live scan disabled")
        val server = runCatching { Storage.loadServers() }.getOrNull()?.firstOrNull() ?: return

        val tunnels = runBlocking { SshService.scanTunnels(server) }
        println("tunnels: $tunnels")

        val linkOutput = runBlocking { SshService.scanXrayLinks(server) }
        val links = ScanTunnels.extractLinks(linkOutput)
        println("xray absent marker: ${linkOutput.contains("MV-XRAY-ABSENT")}")
        println("links (${links.size}): ${links.take(5)}")
    }
}
