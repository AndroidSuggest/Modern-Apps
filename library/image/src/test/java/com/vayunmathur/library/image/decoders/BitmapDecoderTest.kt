package com.vayunmathur.library.image.decoders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BitmapDecoderTest {

    @Test
    fun sizedThumbnailRequestSamplesDown() {
        // 4000x3000 photo into a 256px thumbnail: halves 2000/1500 -> 1000/750 -> 500/375,
        // next step would drop below the target.
        assertEquals(8, BitmapDecoder.sampleSizeFor(4000, 3000, 256, 256))
    }

    @Test
    fun unconstrainedHugePhotoIsCappedAtMaxDimension() {
        // Regression test for files #768: an "original size" decode of a huge photo
        // must not come back at full resolution (~500 MB bitmap -> Canvas crash).
        val sample = BitmapDecoder.sampleSizeFor(12000, 8000, -1, -1)
        assertTrue(sample > 1, "expected downsampling, got $sample")
        assertTrue(12000 / sample <= BitmapDecoder.MAX_ORIGINAL_DIMENSION)
        assertTrue(8000 / sample <= BitmapDecoder.MAX_ORIGINAL_DIMENSION)
    }

    @Test
    fun unconstrainedSmallPhotoIsUntouched() {
        assertEquals(1, BitmapDecoder.sampleSizeFor(1000, 800, -1, -1))
    }

    @Test
    fun sizedRequestSmallerThanSourceIsUntouched() {
        assertEquals(1, BitmapDecoder.sampleSizeFor(100, 100, 256, 256))
    }
}
