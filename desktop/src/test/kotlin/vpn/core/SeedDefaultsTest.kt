package vpn.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v3.6.12 bundled-defaults seeding helpers.
 *
 * The contract this guards:
 *  - a fresh install may receive configs shipped inside the build
 *    (classpath /seed/links.txt), read through [Storage.classpathText];
 *  - an install that EVER held user data must never be touched by the
 *    bundle ([Storage.isFreshDataDir] is false the moment servers.json or
 *    configs.json exists) — deletions stay deletions.
 */
class SeedDefaultsTest {

    // ---- Storage.classpathText ------------------------------------------

    @Test
    fun `missing classpath resource yields null`() {
        assertNull(Storage.classpathText("/seed/definitely-not-there-$$$.txt"))
    }

    @Test
    fun `present classpath resource reads verbatim`() {
        assertEquals(
            "alpha\nbeta gamma\n",
            Storage.classpathText("/seedtest/sample.txt"),
        )
    }

    @Test
    fun `nonexistent local file also yields null`() {
        assertNull(Storage.classpathText("relative/path/not-a-resource"))
    }

    // ---- Storage.isFreshDataDir -----------------------------------------

    @Test
    fun `empty directory counts as fresh`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "seedtest-fresh-${System.nanoTime()}")
        dir.mkdirs()
        try {
            assertTrue(Storage.isFreshDataDir(dir))
        } finally {
            dir.delete()
        }
    }

    @Test
    fun `servers json means existing user data`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "seedtest-srv-${System.nanoTime()}")
        dir.mkdirs()
        File(dir, "servers.json").writeText("[]")
        try {
            assertFalse(Storage.isFreshDataDir(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `configs json means existing user data`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "seedtest-cfg-${System.nanoTime()}")
        dir.mkdirs()
        File(dir, "configs.json").writeText("[]")
        try {
            assertFalse(Storage.isFreshDataDir(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
