package com.multivpn.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vpn.core.Subscription
import java.io.File
import java.nio.file.Files

/**
 * Persistence semantics ported from the desktop's StorageTest: atomic
 * rewrite, corrupt-file quarantine, and the lenient subscriptions rescue
 * (trailing commas + BOM, string-aware). Configs are NOT covered here —
 * their secret wrapping goes through the Android Keystore, which only
 * exists on a device.
 */
class StoreTest {

    private val dir: File = Files.createTempDirectory("mvpn-store").toFile()

    @Test
    fun `subscriptions round-trip`() {
        val store = Store(dir)
        val subs = listOf(
            Subscription(id = "s1", url = "https://example.com/a", name = "A"),
            Subscription(id = "s2", url = "https://example.com/b", name = "B", lastUpdate = 42L),
        )
        store.saveSubscriptions(subs)
        assertEquals(subs, store.loadSubscriptions())
    }

    @Test
    fun `a corrupt file is quarantined instead of emptied`() {
        val store = Store(dir)
        File(dir, "subscriptions.json").writeText("[ {\"id\": \"broken\", ")
        assertTrue(store.loadSubscriptions().isEmpty())
        assertTrue(
            "the broken file must be preserved, not destroyed by a later save",
            dir.listFiles()!!.any { it.name.startsWith("subscriptions.json.corrupt-") },
        )
    }

    @Test
    fun `a trailing comma is rescued and rewritten canonically`() {
        val store = Store(dir)
        File(dir, "subscriptions.json").writeText(
            """[{"id":"s1","url":"https://example.com/a","name":"A"},]""",
        )
        assertEquals(listOf("s1"), store.loadSubscriptions().map { it.id })
        // Rewritten strictly: the next load takes the normal path.
        assertFalse(File(dir, "subscriptions.json").readText().contains(",]"))
    }

    @Test
    fun `a BOM left by PowerShell tooling is rescued too`() {
        val store = Store(dir)
        File(dir, "subscriptions.json").writeText(
            "﻿[{\"id\":\"s9\",\"url\":\"https://example.com/s\",\"name\":\"S\"}]",
        )
        assertEquals(listOf("s9"), store.loadSubscriptions().map { it.id })
    }

    @Test
    fun `active config id round-trips and clears`() {
        val store = Store(dir)
        store.saveActiveConfigId("cfg-1")
        assertEquals("cfg-1", store.loadActiveConfigId())
        store.saveActiveConfigId(null)
        assertEquals(null, store.loadActiveConfigId())
    }

    @Test
    fun `stripTrailingCommas only drops separators outside strings`() {
        assertEquals("[1,2]", Store.stripTrailingCommas("[1,2,]"))
        assertEquals("[1,2 ]", Store.stripTrailingCommas("[1,2, ]"))
        assertEquals("""["a,]","b"]""", Store.stripTrailingCommas("""["a,]","b",]"""))
        assertEquals("""{"a":"x\",y","b":1}""", Store.stripTrailingCommas("""{"a":"x\",y","b":1,}"""))
    }
}
