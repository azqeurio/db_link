package dev.dblink.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class OiShareDirCandidatesTest {
    @Test
    fun `prefers original raw DCIM path before alternate no slash variant`() {
        assertEquals(
            listOf("/DCIM", "DCIM"),
            buildOiShareDirCandidates("/DCIM"),
        )
    }

    @Test
    fun `keeps nested image path raw for gateway query encoding`() {
        assertEquals(
            listOf(
                "/DCIM/100OMSYS/P6230001.JPG",
                "DCIM/100OMSYS/P6230001.JPG",
            ),
            buildOiShareDirCandidates("/DCIM/100OMSYS/P6230001.JPG"),
        )
    }
}
