package dev.dblink.core.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtpObjectInfoMetadataTest {
    @Test
    fun `parses rating metadata variants`() {
        assertEquals(5, PtpObjectInfo.parseRatingMetadata("Rating: 5"))
        assertEquals(5, PtpObjectInfo.parseRatingMetadata("5 star"))
        assertEquals(4, PtpObjectInfo.parseRatingMetadata("rank=4"))
    }

    @Test
    fun `parses camera share order selection metadata`() {
        assertTrue(PtpObjectInfo.parseCameraSelectionMetadata("Share Order"))
        assertTrue(PtpObjectInfo.parseCameraSelectionMetadata("share_mark=1"))
        assertTrue(PtpObjectInfo.parseCameraSelectionMetadata("selected"))
        assertFalse(PtpObjectInfo.parseCameraSelectionMetadata("rating=5"))
    }
}
