package com.theerthkr.essentialmoments.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageEmbedderTest {

    @Test
    fun testL2Norm() {
        val vector = floatArrayOf(3f, 4f)
        val norm = ImageEmbedder.l2Norm(vector)
        // sqrt(3^2 + 4^2) = 5
        assertEquals(5f, norm, 1e-6f)
    }

    @Test
    fun testL2Norm_negativeValues() {
        val vector = floatArrayOf(-3f, -4f)
        val norm = ImageEmbedder.l2Norm(vector)
        // sqrt((-3)^2 + (-4)^2) = 5
        assertEquals(5f, norm, 1e-6f)
    }

    @Test
    fun testL2Normalize() {
        val vector = floatArrayOf(3f, 4f)
        val normalized = ImageEmbedder.l2Normalize(vector)

        // Expected: [3/5, 4/5] = [0.6, 0.8]
        val expected = floatArrayOf(0.6f, 0.8f)
        assertArrayEquals(expected, normalized, 1e-6f)
    }

    @Test
    fun testL2Normalize_zeroVector() {
        // Vector with norm < 1e-8f
        val vector = floatArrayOf(0f, 0f, 0f)
        val normalized = ImageEmbedder.l2Normalize(vector)

        // Expected: identical to original since norm is < 1e-8f
        assertArrayEquals(vector, normalized, 1e-6f)
    }
}
