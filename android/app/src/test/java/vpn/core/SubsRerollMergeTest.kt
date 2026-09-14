package vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED repro for the Reality re-roll subscription: the same identity
 * (protocol+address+port+secret) with a DIFFERENT sni/sid must be recognized
 * as the same config, so a refresh can replace the link instead of
 * duplicating it. Mirrors AppModel.stableKeyOf (kept byte-parity simple).
 */
class SubsRerollMergeTest {

    private val fresh =
        "vless://65a568c5-5b50-4d63-bc0a-8c48088dea6e@103.102.229.222:2053?encryption=none&fp=chrome&pbk=PqBM40jJx4YfJ-xSS8Iegv208rQHRaqVa6UhyNys4hM&security=reality&sid=1a151fec1cd3bf&sni=api-stg.semiconductor.samsung.cn&spx=%2Fa071f86d584fa71&type=tcp#v-all"
    private val rerolled =
        "vless://65a568c5-5b50-4d63-bc0a-8c48088dea6e@103.102.229.222:2053?encryption=none&fp=chrome&pbk=PqBM40jJx4YfJ-xSS8Iegv208rQHRaqVa6UhyNys4hM&security=reality&sid=a8ebd7f8771a&sni=samsung.com&spx=%2Fa071f86d584fa71&type=tcp#v-all"

    @Test
    fun `reality reroll keeps the same stable identity`() {
        val a = Links.parse(fresh)!!
        val b = Links.parse(rerolled)!!
        assertEquals(a.protocol, b.protocol)
        assertEquals(a.address, b.address)
        assertEquals(a.port, b.port)
        assertEquals(a.secret, b.secret)
        // The volatile parts DID change — this is the panel's re-roll.
        assertTrue(a.params["sid"] != b.params["sid"])
    }

    @Test
    fun `different port is a different identity`() {
        val a = Links.parse(fresh)!!
        val b = Links.parse(rerolled.replace(":2053", ":8080"))!!
        assertTrue(a.port != b.port)
    }
}
