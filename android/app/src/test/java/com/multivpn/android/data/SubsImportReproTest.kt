package com.multivpn.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** RED repro: the user's REAL subscription body (base64) through Subs.parseLinks. */
class SubsImportReproTest {
    @Test
    fun `real body parses`() {
        val body = "dmxlc3M6Ly82NWE1NjhjNS01YjUwLTRkNjMtYmMwYS04YzQ4MDg4ZGVhNmVAMTAzLjEwMi4yMjkuMjIyOjIwNTM/ZW5jcnlwdGlvbj1ub25lJmZwPWNocm9tZSZwYms9UHFCTTQwakp4NFlmSi14U1M4SWVndjIwOHJRSFJhcVZhNlVoeU55czRoTSZzZWN1cml0eT1yZWFsaXR5JnNpZD00ODE4MDdlNyZzbmk9Y2RuLnNhbXN1bmcuY29tJnNweD0lMkZhMDcxZjg2ZDU4NGZhNzEmdHlwZT10Y3Ajdi1hbGwKc3M6Ly8yMDIyLWJsYWtlMy1hZXMtMjU2LWdjbTo3Y0hFRkpmRHBFUGNLZU5OR3dSblJienJmMTFhclhMYVEyUWhIWEdLSWVBJTNEOk8lMkZHanRWcjBoSk5EbWx0UXVDR2Voc1FMZzdMUkRzZDhjQUROemhiYjJwNCUzREAxMDMuMTAyLjIyOS4yMjI6NTQyNDM/dHlwZT10Y3Ajc2hhZG93CnZsZXNzOi8vNjVhNTY4YzUtNWI1MC00ZDYzLWJjMGEtOGM0ODA4OGRlYTZlQDEwMy4xMDIuMjI5LjIyMjo4MDgwP2VuY3J5cHRpb249bm9uZSZob3N0PSZwYXRoPSUyRiZzZWN1cml0eT1ub25lJnR5cGU9d3MjdncKdHJvamFuOi8vTyUyRkdqdFZyMGhKTkRtbHRRdUNHZWhzUUxnN0xSRHNkOGNBRE56aGJiMnA0JTNEQDEwMy4xMDIuMjI5LjIyMjo0NDg/ZnA9Y2hyb21lJnBiaz12TGFDcmNWVDJzUHdFMVhOM2lIdmpKVC03UGZBNUZOWVUtYXBJaVNtRENNJnNlY3VyaXR5PXJlYWxpdHkmc2lkPTY3MTExNDBmYWJjMCZzbmk9aW1hZ2Uuc2Ftc3VuZy5jb20mc3B4PSUyRmQyYmVhMWE1ZWQxMTFhOCZ0eXBlPXRjcApoeXN0ZXJpYTI6Ly81b3JqaWJieXlnbmFvdWI5QDEwMy4xMDIuMjI5LjIyMjoxODE2NT9hbHBuPWgzJmZwPWNocm9tZSZvYmZzPXNhbGFtYW5kZXImb2Jmcy1wYXNzd29yZD1haG16N29neWNldGI5d2hxJnNlY3VyaXR5PXRscyZzbmk9I2h5Cg=="
        val links = Subs.parseLinks(body)
        assertEquals(5, links.size)
    }
}

class SubsDecodeBodyTest {
    @Test
    fun `base64 body decodes to links`() {
        val enc = java.util.Base64.getMimeEncoder()
            .encodeToString("vless://u@h:1#a\nss://YWVzLTEyOC1nY206cGFzcw==@y:2#b".toByteArray())
        val out = Subs.decodeBody(enc)
        assertEquals(true, out.contains("://"))
        assertEquals(2, Subs.parseLinks(out).size)
    }

    @Test
    fun `plain body passes through`() {
        val plain = "vless://u@h:1#a"
        assertEquals(plain, Subs.decodeBody(plain))
    }

    @Test
    fun `non-base64 garbage falls back to raw`() {
        val junk = "سلام این یک صفحه html است"
        assertEquals(junk, Subs.decodeBody(junk))
    }
}
