package com.fourkplus.tvplayer.iptv

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class NetworkSecurityTest {
    @Test fun onlyApprovedIptvHostsHaveCleartextPermission() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/network_security_config.xml"))
        val base = document.getElementsByTagName("base-config").item(0)
        assertEquals("false", base.attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
        val domains = document.getElementsByTagName("domain")
        val hosts = (0 until domains.length).map { i ->
            val domain = domains.item(i)
            assertEquals("false", domain.attributes.getNamedItem("includeSubdomains").nodeValue)
            assertEquals("true", domain.parentNode.attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
            domain.textContent.trim()
        }
        assertEquals(ApprovedServers.addresses.map { it.toHttpUrl().host }.toSet(), hosts.toSet())
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:networkSecurityConfig="@xml/network_security_config""""))
        assertFalse(manifest.contains("""android:usesCleartextTraffic="true""""))
    }
}
