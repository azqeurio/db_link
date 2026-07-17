package dev.dblink.core.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraHttpProbeValidatorTest {
    @Test
    fun `accepts real camera connect mode response`() {
        assertTrue(
            CameraHttpProbeValidator.isCameraConnectModeResponse(
                """<?xml version="1.0"?><connectmode>OPC</connectmode>""",
            ),
        )
    }

    @Test
    fun `rejects non camera connect mode body`() {
        assertFalse(CameraHttpProbeValidator.isCameraConnectModeResponse("<html>OK</html>"))
        assertFalse(CameraHttpProbeValidator.isCameraConnectModeResponse("OPC"))
    }

    @Test
    fun `accepts OM camera info response`() {
        assertTrue(
            CameraHttpProbeValidator.isCameraInfoResponse(
                """
                <?xml version="1.0"?>
                <caminfo>
                    <model>OM-1</model>
                    <firmwareversion>1.7</firmwareversion>
                </caminfo>
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `rejects generic lighttpd or router camera info response`() {
        assertFalse(CameraHttpProbeValidator.isCameraInfoResponse("<html><title>lighttpd</title></html>"))
        assertFalse(
            CameraHttpProbeValidator.isCameraInfoResponse(
                """
                <caminfo>
                    <model>Router 1000</model>
                    <firmwareversion>2.0</firmwareversion>
                </caminfo>
                """.trimIndent(),
            ),
        )
    }
}
