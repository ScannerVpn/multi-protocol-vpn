package vpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrafficProbeTest {

    @Test
    fun `parses one reply from localized Windows ping output`() {
        val output = """
            Pinging 1.2.3.4 with 32 bytes of data:
            Reply from 1.2.3.4: bytes=32 time=42ms TTL=54
        """.trimIndent()
        assertEquals(42, VpnPing.parseWindowsPingOutput(output))
    }

    @Test
    fun `parses less than one millisecond as one`() {
        assertEquals(
            1,
            VpnPing.parseWindowsPingOutput("Reply from 127.0.0.1: bytes=32 time<1ms TTL=128"),
        )
    }

    @Test
    fun `rejects timeout and unrelated numbers`() {
        assertNull(VpnPing.parseWindowsPingOutput("Request timed out."))
        assertNull(VpnPing.parseWindowsPingOutput("Pinging 192.168.1.20 with 32 bytes of data:"))
    }
}
