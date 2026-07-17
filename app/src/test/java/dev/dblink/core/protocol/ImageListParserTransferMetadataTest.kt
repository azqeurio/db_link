package dev.dblink.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageListParserTransferMetadataTest {
    @Test
    fun `parses rating share order jpeg raw and movie entries`() {
        val response = """
            VER_100
            /DCIM/100OMSYS,_6230001.JPG,15360000,0,5184,3888,rating=5,share_order=1
            /DCIM/100OMSYS,_6230001.ORF,22600000,0,5184,3888,rank 5,selected
            /DCIM/100OMSYS,_6230002.MOV,102400000,0,3840,2160
            /DCIM/100OMSYS,_6230003.JPG,12000000,0,5184,3888,rating=3
        """.trimIndent()

        val images = ImageListParser.parse(response)

        assertEquals(4, images.size)
        assertTrue(images[0].isJpeg)
        assertTrue(images[0].isFiveStar)
        assertTrue(images[0].isCameraSelected)
        assertTrue(images[1].isRaw)
        assertTrue(images[1].isFiveStar)
        assertTrue(images[1].isCameraSelected)
        assertTrue(images[2].isMovie)
        assertFalse(images[3].isFiveStar)
    }
}
