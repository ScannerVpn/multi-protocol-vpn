package vpn.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Live probe against the server stored in %APPDATA%\MultiVPN\servers.json.
 * Runs ONLY when PROBE_SERVER env var is set — never in normal builds.
 * Read-only: inspects the WireGuard/AmneziaWG installation and prints the
 * obfuscation parameters (secrets redacted).
 */
class ServerProbeTest {

    private fun server(): ServerConfig? = runCatching { Storage.loadServers() }.getOrNull()?.firstOrNull()

    @Test
    fun probe() {
        assumeTrue(System.getenv("PROBE_SERVER") != null, "probe disabled")
        val s = server() ?: return

        val script = """
            echo "== host wg confs =="
            ls /etc/wireguard/ 2>/dev/null || echo "(none)"
            for f in /etc/wireguard/*.conf; do
              [ -f "${'$'}f" ] || continue
              echo "--- ${'$'}f"
              grep -E '^[[:space:]]*(Jc|Jmin|Jmax|S[1-4]|H[1-4]|I[1-5]|HeaderProtectionKey|ContentPaddingAddition|RekeyAfterTime|RekeyTimeout|RejectAfterTime|KeepaliveTimeout|MaxHandshakeAttempts|RandomTrailers|DisableCookies|ListenPort)[[:space:]]*=' "${'$'}f" | sed -E 's/(PrivateKey|HeaderProtectionKey|PresharedKey)[[:space:]]*=.*/\1 = <redacted>/'
            done
            echo "== docker containers =="
            docker ps --format '{{.Names}}' 2>/dev/null || echo "(no docker)"
            for name in ${'$'}(docker ps --format '{{.Names}}' 2>/dev/null | grep -iE 'amnezia|wireguard|^a?wg' || true); do
              echo "--- container: ${'$'}name"
              for p in /opt/amnezia/awg/awg0.conf /opt/amnezia/wireguard/wg0.conf /etc/amnezia/amnezia-wg/wg0.conf /etc/amnezia/wg0.conf /etc/wireguard/wg0.conf; do
                if docker exec "${'$'}name" test -f "${'$'}p" 2>/dev/null; then
                  echo "conf found: ${'$'}p"
                  docker exec "${'$'}name" grep -E '^[[:space:]]*(Jc|Jmin|Jmax|S[1-4]|H[1-4]|I[1-5]|HeaderProtectionKey|ContentPaddingAddition|RekeyAfterTime|RekeyTimeout|RejectAfterTime|KeepaliveTimeout|MaxHandshakeAttempts|RandomTrailers|DisableCookies|ListenPort)[[:space:]]*=' "${'$'}p" | sed -E 's/(PrivateKey|HeaderProtectionKey|PresharedKey)[[:space:]]*=.*/\1 = <redacted>/'
                fi
              done
              echo "awg tool: ${'$'}(docker exec ${'$'}name sh -c 'command -v awg >/dev/null && awg --version 2>&1 || echo no-awg')"
            done
            echo "== host tools =="
            command -v awg >/dev/null && awg --version 2>&1 || echo "(no host awg)"
            command -v wg >/dev/null && wg --version 2>&1 || echo "(no host wg)"
        """.trimIndent()

        val out = runBlocking {
            SshService.runCommandStreaming(s, "bash -s <<'__PROBE__'\n$script\n__PROBE__", timeoutSec = 60)
        }
        println(out)
        println("---- client-side interpretation ----")
    }
}
