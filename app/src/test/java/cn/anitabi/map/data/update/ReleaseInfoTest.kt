package cn.anitabi.map.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoTest {

    @Test
    fun parsesTheFieldsTheAppUses() {
        val info = ReleaseInfo.parse(
            """{"tag_name":"v0.1.1","html_url":"https://github.com/AbuCuma/junrei-map/releases/tag/v0.1.1",
               "name":"Junrei Map v0.1.1","prerelease":false,"draft":false,
               "assets":[{"name":"junrei-map-v0.1.1.apk","browser_download_url":"https://example/x.apk"}]}""",
        )!!
        assertEquals("v0.1.1", info.tag)
        assertEquals(listOf(0, 1, 1), info.version.parts)
        assertEquals("https://github.com/AbuCuma/junrei-map/releases/tag/v0.1.1", info.htmlUrl)
        assertEquals("Junrei Map v0.1.1", info.name)
        assertFalse(info.prerelease)
    }

    @Test
    fun missingHtmlUrlFallsBackToTheReleasesPage() {
        val info = ReleaseInfo.parse("""{"tag_name":"v0.2.0"}""")!!
        assertEquals(ReleaseInfo.RELEASES_URL, info.htmlUrl)
        assertNull(info.name)
    }

    @Test
    fun badTagOrBadJsonIsNull() {
        assertNull(ReleaseInfo.parse("""{"tag_name":"latest"}"""))
        assertNull(ReleaseInfo.parse("""{"message":"Not Found"}"""))
        assertNull(ReleaseInfo.parse("not json"))
    }

    @Test
    fun keepsThePrereleaseFlag() {
        assertTrue(ReleaseInfo.parse("""{"tag_name":"v0.3.0-beta","prerelease":true}""")!!.prerelease)
    }
}
