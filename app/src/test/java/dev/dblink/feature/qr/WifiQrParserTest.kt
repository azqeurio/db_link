package dev.dblink.feature.qr

import dev.dblink.core.logging.D
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class WifiQrParserTest {
    @Before
    fun disableDebugLogging() {
        D.enabled = false
    }

    @After
    fun restoreDebugLogging() {
        D.enabled = true
    }

    @Test
    fun `parses standard wifi payload`() {
        val credentials = WifiQrParser.parse("WIFI:S:MySSID;T:WPA;P:secret123;;")

        assertNotNull(credentials)
        assertEquals("MySSID", credentials?.ssid)
        assertEquals("secret123", credentials?.password)
        assertNull(credentials?.bleName)
        assertNull(credentials?.blePass)
    }

    @Test
    fun `parses olympus ois1 payload`() {
        val credentials = WifiQrParser.parse("OIS1,CRDC,GVDD")

        assertNotNull(credentials)
        assertEquals("TEST", credentials?.ssid)
        assertEquals("PASS", credentials?.password)
        assertNull(credentials?.bleName)
        assertNull(credentials?.blePass)
    }

    @Test
    fun `parses olympus ois2 payload with ble credentials`() {
        val credentials = WifiQrParser.parse("OIS2,3,TVJ-,KHTL,UKR,-+*%")

        assertNotNull(credentials)
        assertEquals("CAM1", credentials?.ssid)
        assertEquals("LOCK", credentials?.password)
        assertEquals("BLE", credentials?.bleName)
        assertEquals("1234", credentials?.blePass)
    }

    @Test
    fun `parses olympus ois3 payload with ble credentials`() {
        val credentials = WifiQrParser.parse("OIS3,3,meta,TVJ-,KHTL,UKR,-+*%")

        assertNotNull(credentials)
        assertEquals("CAM1", credentials?.ssid)
        assertEquals("LOCK", credentials?.password)
        assertEquals("BLE", credentials?.bleName)
        assertEquals("1234", credentials?.blePass)
    }

    @Test
    fun `returns null for malformed payload`() {
        assertNull(WifiQrParser.parse("NOT_A_WIFI_QR"))
        assertNull(WifiQrParser.parse("OIS2,3,ONLYSSID"))
    }
}
