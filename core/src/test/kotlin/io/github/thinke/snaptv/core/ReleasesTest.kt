package io.github.thinke.snaptv.core

import io.github.thinke.snaptv.core.update.Releases
import io.github.thinke.snaptv.core.update.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private fun v(s: String) = Version.parse(s)!!

    @Test
    fun versionOrdering() {
        assertTrue(v("0.1.2") > v("0.1.1"))
        assertTrue(v("0.1.10") > v("0.1.9"))
        assertTrue(v("0.2") > v("0.1.99"))
        assertTrue(v("0.1.2") > v("0.1.2-rc1"))
        assertTrue(v("0.1.2-rc2") > v("0.1.2-rc1"))
        assertTrue(v("0.1.2-rc10") > v("0.1.2-rc2"))
        assertTrue(v("0.1.2-rc1") > v("0.1.2-beta3"))
        assertTrue(v("0.1.2-rc1") > v("0.1.1"))
        assertEquals(v("v0.1.0"), v("0.1"))
        assertNull(Version.parse("latest"))
    }

    private val json = """
        [
          {"tag_name":"v0.1.2-rc1","name":"SnapTV 0.1.2-rc1","prerelease":true,"draft":false,"body":"rc","html_url":"https://x/rc1",
           "assets":[{"name":"snaptv-0.1.2-rc1.apk","size":1300000,"browser_download_url":"https://x/rc1.apk"},
                     {"name":"snaptv-0.1.2-rc1.apk.sha256","size":83,"browser_download_url":"https://x/rc1.sha"}]},
          {"tag_name":"v0.1.1","name":"SnapTV 0.1.1","prerelease":false,"draft":false,"body":"fixes","html_url":"https://x/011",
           "assets":[{"name":"snaptv-0.1.1.apk","size":1319410,"browser_download_url":"https://x/011.apk"},
                     {"name":"snaptv-0.1.1.apk.sha256","size":83,"browser_download_url":"https://x/011.sha"}]},
          {"tag_name":"v0.3.0","name":"draft","prerelease":false,"draft":true,"assets":[]},
          {"tag_name":"v0.2.0","name":"no apk","prerelease":false,"draft":false,"assets":[]}
        ]
    """.trimIndent()

    @Test
    fun parsesAndSkipsUnusable() {
        val r = Releases.parse(json)
        assertEquals(listOf("v0.1.2-rc1", "v0.1.1"), r.map { it.tag })
        assertEquals("https://x/011.apk", r[1].apkUrl)
        assertEquals("https://x/011.sha", r[1].sha256Url)
        assertTrue(r[0].prerelease)
    }

    @Test
    fun offersOnlyNewer() {
        val r = Releases.parse(json)
        assertNull(Releases.newest(r, v("0.1.1"), includePrereleases = false))
        assertEquals("v0.1.2-rc1", Releases.newest(r, v("0.1.1"), includePrereleases = true)?.tag)
        assertNull(Releases.newest(r, v("0.1.2"), includePrereleases = true))
    }

    @Test
    fun sha256Line() {
        val h = "4fbd3c4f54e3f1c71f2c47b31a2cdd23f8b0f98e1e8f4d0d6b1f3b2b7a9e0c11"
        assertEquals(h, Releases.parseSha256("$h  snaptv-0.1.1.apk\n"))
        assertNull(Releases.parseSha256("not a hash"))
    }

    @Test
    fun picksEachAppsOwnFile() {
        val json = """[{"tag_name":"v0.2.0","prerelease":false,"draft":false,"assets":[
            {"name":"snaptv-0.2.0.apk","size":1,"browser_download_url":"https://x/a.apk"},
            {"name":"snaptv-0.2.0.apk.sha256","size":1,"browser_download_url":"https://x/a.sha"},
            {"name":"SnapTV-Desktop-0.2.0-x86_64.AppImage","size":1,"browser_download_url":"https://x/d.AppImage"},
            {"name":"SnapTV-Desktop-0.2.0-x86_64.AppImage.sha256","size":1,"browser_download_url":"https://x/d.sha"}]},
          {"tag_name":"v0.3.0","prerelease":false,"draft":false,"assets":[
            {"name":"snaptv-0.3.0.apk","size":1,"browser_download_url":"https://x/b.apk"},
            {"name":"snaptv-0.3.0.apk.sha256","size":1,"browser_download_url":"https://x/b.sha"}]}]"""
        val r = Releases.parse(json)
        val appImage = Releases.newest(r, v("0.1.0"), includePrereleases = false) { it.download("x86_64.AppImage") != null }
        assertEquals("v0.2.0", appImage?.tag) // 0.3.0 has no AppImage
        assertEquals("https://x/d.sha", appImage?.download("x86_64.AppImage")?.second?.url)
        assertEquals("v0.3.0", Releases.newest(r, v("0.1.0"), includePrereleases = false)?.tag)
    }
}

