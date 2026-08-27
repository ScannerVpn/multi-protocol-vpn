package vpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * localeAwareDouble(): the ping parser used to call toDoubleOrNull directly,
 * which returns null on comma-decimal locales — PowerShell's Measure-Object
 * prints "12,5" on de-DE/fa-IR style systems and the app then showed NO
 * latency although the ICMP probe had actually succeeded.
 */
class VpnRobustnessTest {

    @Test
    fun `plain integers parse`() {
        assertEquals(23.0, VpnService.localeAwareDouble("23"))
        assertEquals(0.0, VpnService.localeAwareDouble("  0 "))
    }

    @Test
    fun `dot decimals parse`() {
        assertEquals(12.5, VpnService.localeAwareDouble("12.5"))
    }

    @Test
    fun `comma decimals parse`() {
        // The actual regression: de-DE / fa-IR print this.
        assertEquals(12.5, VpnService.localeAwareDouble("12,5"))
    }

    @Test
    fun `thousands separators survive`() {
        // "1,234.5" (en-US grouping) -> 1234.5 ; "1.234,5" (de grouping) too.
        assertEquals(1234.5, VpnService.localeAwareDouble("1,234.5"))
        assertEquals(1234.5, VpnService.localeAwareDouble("1.234,5"))
    }

    @Test
    fun `non numeric junk is rejected`() {
        assertNull(VpnService.localeAwareDouble(""))
        assertNull(VpnService.localeAwareDouble("no number here"))
        assertNull(VpnService.localeAwareDouble("--;--"))
    }

    @Test
    fun `openvpn initialization helper is false without a log file`() {
        // No connect ever ran in this JVM -> the managed log cannot contain
        // an Initialization Sequence Completed line for THIS session.
        val initialized = runCatching { VpnService.openvpnInitialized() }.getOrDefault(false)
        assertEquals(false, initialized)
    }

    // ---- P12 passphrase generator ---------------------------------------

    @Test
    fun `p12 passwords are long random and unambiguous`() {
        val pass = SshService.generateP12Password()
        assertEquals(24, pass.length)
        // Only look-alike-free alphanumerics: everything else would need
        // shell/PowerShell quoting the moment it crosses into the setup script.
        assertTrue(pass.all { it.isLetterOrDigit() })
        assertFalse(pass.any { it in "0O1lI" })
        // 24 draws from a 56-char alphabet — two consecutive generations
        // colliding is astronomically unlikely; this is really a sanity check
        // that SecureRandom is consulted at all.
        assertFalse(pass == SshService.generateP12Password())
    }
}
