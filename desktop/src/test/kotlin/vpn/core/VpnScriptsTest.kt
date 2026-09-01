package vpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards for every generated PowerShell script in [VpnScripts].
 *
 * VpnScripts had **0 % coverage** while being the most dangerous file in the
 * tree: it builds the scripts that self-elevate to admin, register a SYSTEM
 * scheduled task, import certificates into LocalMachine and rewrite ACLs. A
 * quoting slip here is not a cosmetic bug — it either breaks every connect or
 * hands an attacker SYSTEM.
 *
 * These tests are pure string assertions on the generated text, so they run
 * offline and on any OS.
 */
class VpnScriptsTest {

    // ------------------------------------------------------------------
    // Shared invariants
    // ------------------------------------------------------------------

    private fun allScripts(): Map<String, String> = mapOf(
        "ikev2Connect" to VpnScripts.buildIkev2ConnectScript(
            resultFile = "C:\\Temp\\r.txt",
            name = "VPN-Test",
            server = "1.2.3.4",
            caPath = "C:\\certs\\ca.crt",
            p12Path = "C:\\certs\\client.p12",
            p12Pass = "s3cret",
            caSubjects = listOf("CN=Freebuff IKEv2 CA", "CN=VPN Root CA"),
        ),
        "msiInstall" to VpnScripts.buildMsiInstallScript("C:\\Temp\\r.txt", "C:\\Temp\\x.msi"),
        "ovpnConnect" to VpnScripts.buildOvpnConnectScript(
            "C:\\Temp\\r.txt", "C:\\app\\openvpn.exe", "C:\\app\\current.ovpn",
            "C:\\app\\openvpn.log", "MultiVPN_OpenVPN", "C:\\ProgramData\\MultiVPN\\openvpn-secure",
        ),
        "ovpnStop" to VpnScripts.buildOvpnStopScript(
            "C:\\Temp\\r.txt", "MultiVPN_OpenVPN", "C:\\AppData\\openvpn-task.active",
            "C:\\ProgramData\\MultiVPN\\openvpn-secure",
        ),
        "killProcess" to VpnScripts.buildKillProcessScript("C:\\Temp\\r.txt", "HiddifyCli.exe"),
        "cleanup" to VpnScripts.buildCleanupScript(
            "C:\\Temp\\r.txt", listOf("VPN-A", "VPN-B"), false,
            listOf("CN=Freebuff IKEv2 CA"),
        ),
    )

    @Test
    fun `no script leaks the internal placeholder`() {
        // '§' stands in for '$' so Kotlin templates do not clash. If
        // dollarize() is ever skipped, the script becomes syntactic garbage
        // that fails at runtime with no useful message.
        allScripts().forEach { (name, s) ->
            assertFalse('§' in s, "$name: § placeholder leaked — dollarize() not applied")
        }
    }

    @Test
    fun `every script writes a result the app can read`() {
        // VpnScripts.readResultFile treats an empty result as "UAC declined".
        // A script with no Write-Result path would therefore be reported as a
        // declined prompt no matter what actually happened.
        allScripts().forEach { (name, s) ->
            assertTrue("Write-Result" in s || "Out-File" in s, "$name: writes no result")
            assertTrue(
                "ERROR" in s,
                "$name: has no failure path, so a real error looks like a declined UAC",
            )
        }
    }

    @Test
    fun `every script self-elevates before doing privileged work`() {
        allScripts().forEach { (name, s) ->
            assertTrue(
                "IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)" in s,
                "$name: no admin check",
            )
            assertTrue("Start-Process powershell -Verb RunAs" in s, "$name: no UAC re-launch")
            // -Wait matters: without it the outer script exits immediately and
            // the app reads an empty result file as "declined".
            assertTrue("-Wait" in s, "$name: elevation does not wait for the child")
        }
    }

    @Test
    fun `no string concatenation inside cmdlet arguments`() {
        // Regression: `-ArgumentList "a" + $b` parses as three argument-mode
        // tokens and fails with "positional parameter '+'".
        allScripts().forEach { (name, s) ->
            val offenders = s.lineSequence()
                .filter { it.contains("-ArgumentList") || it.contains("-DisplayName") }
                .filter { Regex("\"\\s*\\+").containsMatchIn(it) }
                .toList()
            assertTrue(offenders.isEmpty(), "$name: concatenation in cmdlet args: $offenders")
        }
    }

    // ------------------------------------------------------------------
    // psEscape
    // ------------------------------------------------------------------

    @Test
    fun `psEscape neutralises the three PowerShell metacharacters`() {
        assertEquals("plain", VpnScripts.psEscape("plain"))
        // Backtick is the escape char and must be doubled FIRST, or the later
        // substitutions would be escaped by a half-written escape.
        assertEquals("a``b", VpnScripts.psEscape("a`b"))
        assertEquals("a`\$b", VpnScripts.psEscape("a\$b"))
        assertEquals("a`\"b", VpnScripts.psEscape("a\"b"))
        // A path with a quote in it must not be able to close the string and
        // start a new command.
        val evil = "C:\\x\"; calc; \""
        val escaped = VpnScripts.psEscape(evil)
        assertFalse(Regex("(?<!`)\"").containsMatchIn(escaped), "an unescaped quote survived: $escaped")
    }

    @Test
    fun `a config name with metacharacters cannot break out of the script`() {
        val s = VpnScripts.buildIkev2ConnectScript(
            resultFile = "C:\\Temp\\r.txt",
            name = "VPN-\"; calc; \"",
            server = "1.2.3.4\$(calc)",
            caPath = null, p12Path = null, p12Pass = "p",
            caSubjects = listOf("CN=X"),
        )
        // The payload IS present as literal text — that is fine and expected.
        // What matters is that every quote inside the assignment is
        // backtick-escaped, so PowerShell parses it as one string instead of
        // closing the literal and running `calc`.
        //
        // NOTE the line shape: elevatedPrelude() ends with `}` and the body is
        // appended without a newline, so the first body line reads
        // `}$Name = "..."`. PowerShell accepts that (verified), which is why
        // the assertions match on `$Name = ` anywhere in the line rather than
        // at its start.
        val nameLine = s.lineSequence().first { it.contains("\$Name = \"") }
        val value = nameLine.substringAfter("\$Name = \"").removeSuffix("\"")
        assertFalse(
            Regex("(?<!`)\"").containsMatchIn(value),
            "an unescaped quote closes the literal and allows command injection: $nameLine",
        )
        // The same for a `$` in the server address: unescaped it would be a
        // subexpression and `$(calc)` would execute.
        val serverLine = s.lineSequence().first { it.contains("\$Server = \"") }
        val serverValue = serverLine.substringAfter("\$Server = \"").removeSuffix("\"")
        assertFalse(
            Regex("(?<!`)\\$").containsMatchIn(serverValue),
            "an unescaped \$ allows subexpression execution: $serverLine",
        )
        assertEquals("1.2.3.4`\$(calc)", serverValue, "the \$ was not escaped")
    }

    // ------------------------------------------------------------------
    // IKEv2
    // ------------------------------------------------------------------

    @Test
    fun `ikev2 pins the ipsec policy to match the server`() {
        val s = allScripts().getValue("ikev2Connect")
        // Windows proposes only 3des/sha1/modp1024 by default, and
        // setup-ikev2.sh no longer accepts any of those. Without this pin every
        // connect fails with "policy match error".
        assertTrue("Set-VpnConnectionIPsecConfiguration" in s, "IPsec policy not pinned")
        assertTrue("-DHGroup Group14" in s, "DH group 14 (modp2048) not requested")
        assertTrue("-PfsGroup PFS2048" in s, "PFS2048 not requested")
        assertTrue("-IntegrityCheckMethod SHA256" in s, "SHA256 integrity not requested")
        assertTrue("-EncryptionMethod AES256" in s, "AES256 not requested")
        // No weak algorithm may be REQUESTED. Comments are excluded — they
        // legitimately explain why the weak defaults are being overridden.
        val code = s.lineSequence()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n").lowercase()
        assertFalse("3des" in code, "3DES must not be requested")
        assertFalse("sha1" in code, "SHA-1 must not be requested")
        assertFalse("group2" in code, "DH group 2 (modp1024) must not be requested")
    }

    @Test
    fun `ikev2 policy failure does not abort the connect`() {
        val s = allScripts().getValue("ikev2Connect")
        // An older Windows build can reject a parameter; the server still
        // offers other strong proposals, so this must be caught, not fatal.
        val idx = s.indexOf("Set-VpnConnectionIPsecConfiguration")
        val after = s.substring(idx)
        assertTrue(after.contains("catch"), "the policy pin is not wrapped in try/catch")
        assertTrue("policyNote" in s, "the failure is not surfaced in the result message")
    }

    @Test
    fun `ikev2 removes stale certs before importing fresh ones`() {
        val s = allScripts().getValue("ikev2Connect")
        // A stale client cert from a previous provisioning makes rasdial fail
        // with "Policy match error" — the CA subjects must be swept first.
        assertTrue("CN=Freebuff IKEv2 CA" in s && "CN=VPN Root CA" in s, "CA subjects missing")
        listOf("Cert:\\LocalMachine\\My", "Cert:\\LocalMachine\\Root", "Cert:\\LocalMachine\\CA")
            .forEach { assertTrue(it in s, "store $it not swept") }
        assertTrue("Remove-Item" in s, "certs are not removed")
        assertTrue("Import-PfxCertificate" in s, "client cert not imported")
        assertTrue("Import-Certificate" in s, "CA cert not imported")
        // Order matters: the sweep must come before the imports, or the fresh
        // certificate is deleted again.
        assertTrue(
            s.indexOf("Remove-Item") < s.indexOf("Import-PfxCertificate"),
            "the cert sweep runs AFTER the import and would delete it",
        )
    }

    @Test
    fun `ikev2 judges success by output text not exit code`() {
        val s = allScripts().getValue("ikev2Connect")
        // rasdial's exit code is unreliable in some PowerShell hosts.
        assertTrue("Successfully connected" in s, "success match missing")
        assertTrue("already connected" in s, "idempotent reconnect not accepted")
    }

    @Test
    fun `ikev2 omits import lines when no cert paths are given`() {
        val s = VpnScripts.buildIkev2ConnectScript(
            "C:\\Temp\\r.txt", "VPN-X", "1.2.3.4",
            caPath = null, p12Path = null, p12Pass = "x", caSubjects = listOf("CN=X"),
        )
        assertFalse("Import-Certificate" in s, "imported a CA that was not provided")
        assertFalse("Import-PfxCertificate" in s, "imported a p12 that was not provided")
        // …but the profile is still created and dialled.
        assertTrue("Add-VpnConnection" in s)
        assertTrue("rasdial" in s)
    }

    // ------------------------------------------------------------------
    // OpenVPN — the SYSTEM task
    // ------------------------------------------------------------------

    @Test
    fun `openvpn stages into an ACL-locked directory before running as SYSTEM`() {
        val s = allScripts().getValue("ovpnConnect")
        assertTrue("openvpn-secure" in s, "the secure staging dir is not used")
        assertTrue("icacls" in s, "the ACL is never tightened")
        assertTrue("/inheritance:r" in s, "inherited ACEs are not removed")
        // SYSTEM (S-1-5-18) and Administrators (S-1-5-32-544) keep full access.
        assertTrue("*S-1-5-18:(OI)(CI)F" in s, "SYSTEM has no access")
        assertTrue("*S-1-5-32-544:(OI)(CI)F" in s, "Administrators have no access")
        // Users (S-1-5-32-545) and Everyone (S-1-1-0) must be removed: a
        // standard user must not be able to replace what SYSTEM executes, nor
        // read the staged config (it embeds the client key).
        assertTrue("/remove:g `\"*S-1-5-32-545`\"" in s, "Users are not removed from the ACL")
        assertTrue("/remove:g `\"*S-1-1-0`\"" in s, "Everyone is not removed from the ACL")
        // And the task must run the STAGED copy, not the %APPDATA% original.
        assertTrue(
            Regex("\\\$exe\\s*=\\s*Join-Path \\\$secure").containsMatchIn(s),
            "the task still runs the user-writable copy",
        )
    }

    @Test
    fun `openvpn refuses a binary that fails its signature check`() {
        val s = allScripts().getValue("ovpnConnect")
        assertTrue("Get-AuthenticodeSignature" in s, "no tamper check on the SYSTEM binary")
        assertTrue("HashMismatch" in s, "a modified-after-signing binary is not rejected")
        // Unsigned/unverifiable must only warn — self-built cores are valid.
        assertTrue("sigNote" in s, "signature status is not surfaced")
    }

    @Test
    fun `openvpn forces script-security 0 on the command line`() {
        val s = allScripts().getValue("ovpnConnect")
        assertTrue(
            "--script-security 0" in s,
            "a script hook that survived the sanitizer could still run as SYSTEM",
        )
        assertTrue("--windows-driver wintun" in s, "wintun driver not selected")
    }

    @Test
    fun `openvpn stages every file the core needs`() {
        val s = allScripts().getValue("ovpnConnect")
        // Driven by CoreManifest, so this also pins the two together.
        CoreManifest.OPENVPN_FILES.forEach {
            assertTrue("\"$it\"" in s, "$it is not staged into the secure dir")
        }
        assertTrue("ovpn_auth.txt" in s, "the auth sidecar is not staged")
    }

    @Test
    fun `openvpn accepts either the pool address or its own log line`() {
        val s = allScripts().getValue("ovpnConnect")
        // A third-party .ovpn hands out a pool outside 10.8.0.x; OpenVPN's
        // English log line is the locale-independent fallback.
        assertTrue("10\\.8\\.0\\." in s, "pool probe missing")
        assertTrue("Initialization Sequence Completed" in s, "log-line probe missing")
    }

    @Test
    fun `openvpn stop wipes the staged secrets and the marker`() {
        val s = allScripts().getValue("ovpnStop")
        assertTrue("schtasks /end" in s, "the task is not stopped")
        assertTrue("schtasks /delete" in s, "the task is not removed")
        assertTrue("taskkill /IM openvpn.exe /F" in s, "the process is not killed")
        assertTrue("current.ovpn" in s, "the staged config (with the client key) is not wiped")
        assertTrue("ovpn_auth.txt" in s, "the staged credentials sidecar is not wiped")
        // The marker is deleted on the ELEVATED side, so a declined UAC leaves
        // it behind and the next launch retries the cleanup.
        assertTrue("openvpn-task.active" in s, "the retry marker is not removed")
    }

    @Test
    fun `openvpn stop without a secure dir omits the wipe block`() {
        val s = VpnScripts.buildOvpnStopScript("C:\\Temp\\r.txt", "T", "C:\\m.active")
        assertFalse("current.ovpn" in s, "wipe block emitted without a staging dir")
        assertTrue("schtasks /delete" in s, "the task teardown must still be there")
    }

    // ------------------------------------------------------------------
    // Cleanup + kill
    // ------------------------------------------------------------------

    @Test
    fun `cleanup by name removes exactly the named profiles`() {
        val s = allScripts().getValue("cleanup")
        assertTrue("-Name \"VPN-A\"" in s)
        assertTrue("-Name \"VPN-B\"" in s)
        assertFalse("-like \"VPN-*\"" in s, "named mode must not wildcard-delete every profile")
    }

    @Test
    fun `cleanup all mode wildcards our prefix only`() {
        val s = VpnScripts.buildCleanupScript(
            "C:\\Temp\\r.txt", emptyList(), true, listOf("CN=X"),
        )
        assertTrue("-like \"VPN-*\"" in s, "all-mode does not match our profiles")
        // It must stay scoped to OUR prefix — never every VPN on the machine.
        assertFalse(Regex("Get-VpnConnection[^|]*\\|\\s*Remove-VpnConnection").containsMatchIn(s),
            "all-mode deletes every VPN profile, including the user's own")
    }

    @Test
    fun `cleanup continues past individual failures`() {
        val s = VpnScripts.buildCleanupScript("C:\\Temp\\r.txt", listOf("A"), false, listOf("CN=X"))
        // ErrorActionPreference must be Continue here: one missing profile must
        // not abort the whole sweep and leave certificates behind.
        assertTrue("\$ErrorActionPreference = \"Continue\"" in s, "cleanup aborts on the first error")
    }

    @Test
    fun `kill script targets the given image only`() {
        val s = allScripts().getValue("killProcess")
        assertTrue("taskkill /IM HiddifyCli.exe /F" in s)
        assertFalse("xray" in s, "unrelated cores must not be swept by this script")
    }

    // ------------------------------------------------------------------
    // readResultFile
    // ------------------------------------------------------------------

    @Test
    fun `result parsing tolerates a BOM and reports each status`() {
        val f = java.io.File.createTempFile("vpnscripts_", ".txt")
        try {
            // Windows PowerShell's Out-File -Encoding utf8 writes a BOM; without
            // stripping it the status never equals "OK".
            f.writeText("\uFEFFOK\nConnected fine")
            VpnScripts.readResultFile(f).let {
                assertTrue(it.ok, "a BOM-prefixed OK was not recognised")
                assertEquals("Connected fine", it.message)
            }
            f.writeText("ERROR\nsomething broke")
            VpnScripts.readResultFile(f).let {
                assertFalse(it.ok)
                assertEquals("something broke", it.message)
            }
            f.writeText("FAIL\nno tunnel")
            assertFalse(VpnScripts.readResultFile(f).ok)
            // Empty / missing = the UAC prompt was declined.
            f.writeText("")
            VpnScripts.readResultFile(f).let {
                assertFalse(it.ok)
                assertTrue("UAC" in it.message, "an empty result must blame the UAC prompt")
            }
            f.delete()
            assertFalse(VpnScripts.readResultFile(f).ok, "a missing file must not read as success")
        } finally {
            f.delete()
        }
    }

    @Test
    fun `an ERROR with no message still yields a message`() {
        val f = java.io.File.createTempFile("vpnscripts_", ".txt")
        try {
            f.writeText("ERROR\n")
            val r = VpnScripts.readResultFile(f)
            assertFalse(r.ok)
            assertTrue(r.message.isNotEmpty(), "the UI would show an empty error card")
        } finally {
            f.delete()
        }
    }
}
