package com.theerthkr.essentialmoments.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log


/**
 * Converts a device image URI into a ByteBuffer ready for SigLIP2 inference.
 *
 * SigLIP2 contract:
 *   Input shape  : [1, 224, 224, 3]  (NHWC)
 *   Dtype        : float16 (2 bytes per channel value)
 *   Pixel range  : [-1.0, 1.0]
 *   Normalisation: (pixel_uint8 / 127.5f) - 1.0f
 *   Array size   : 1 × 224 × 224 × 3 = 150,528 floats
 *
 * Debug checkpoints (enabled via debug=true):
 *   [D1] Bitmap W×H + null guard              → after load
 *   [D2] Cropped bitmap saved to external dir  → after centerCrop
 *   [D3] Pixel min/max                         → after packFloat16
 *   [D4] Array float count                     → after packFloat16
 */
class ImagePreprocessor(private val context: Context) {

    companion object {
        private const val TAG = "ImagePreprocessor"
        const val INPUT_SIZE   = 224
        const val CHANNELS     = 3
        // float16 = 2 bytes; total = 1 × 224 × 224 × 3 × 2
        const val ARRAY_SIZE = INPUT_SIZE * INPUT_SIZE * CHANNELS
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Full pipeline: URI → load → crop → normalise → FloatArray.
     * Returns null only if the bitmap cannot be decoded.
     */
    fun preprocess(uri: Uri, debug: Boolean = false): FloatArray? {
        val raw = loadBitmap(uri, debug) ?: return null
        return preprocessBitmap(raw, debug)
    }

    /**
     * Accepts a Bitmap directly (used by selfSimilarityTest and ModelActivity).
     * Does NOT recycle the input bitmap — caller owns it.
     */
    fun preprocessBitmap(bitmap: Bitmap, debug: Boolean = false): FloatArray {
        val cropped = centerCrop(bitmap, debug)
        val buf = packFloatArray(cropped, debug)
        // Only recycle if we made a new bitmap (centerCrop always creates one)
        if (cropped !== bitmap) cropped.recycle()
        return buf
    }

    // ─────────────────────────────────────────────────────────────
    // Stage 1 – Load  [Debug D1: log W×H, assert not null]
    // ─────────────────────────────────────────────────────────────

    private fun loadBitmap(uri: Uri, debug: Boolean): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                // First pass: just read dimensions (no pixel alloc)
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }

            if (debug) {
                Log.d(TAG, "[D1] raw image: ${opts.outWidth}×${opts.outHeight}  " +
                        "mimeType=${opts.outMimeType}")
            }

            // Second pass: decode pixels, enforce ARGB_8888
            val decodeOpts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }

            if (bmp == null) {
                Log.e(TAG, "[D1] ❌ loadBitmap returned null for uri=$uri")
                return null
            }

            // Ensure ARGB_8888 — some camera JPEGs decode as RGB_565
            val ensured = if (bmp.config == Bitmap.Config.ARGB_8888) {
                bmp
            } else {
                Log.w(TAG, "[D1] config=${bmp.config} — converting to ARGB_8888")
                bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
            }

            if (debug) {
                Log.d(TAG, "[D1] ✅ loaded: ${ensured.width}×${ensured.height}  " +
                        "config=${ensured.config}  byteCount=${ensured.byteCount}")
            }
            ensured

        } catch (e: Exception) {
            Log.e(TAG, "[D1] ❌ loadBitmap exception for uri=$uri : ${e.message}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Stage 2 – Center-crop to 224×224  [Debug D2: save PNG]
    // ─────────────────────────────────────────────────────────────

    fun centerCrop(src: Bitmap, debug: Boolean = false): Bitmap {
        val side = minOf(src.width, src.height)
        val left = (src.width  - side) / 2
        val top  = (src.height - side) / 2

        val square = Bitmap.createBitmap(src, left, top, side, side)
        val resized = Bitmap.createScaledBitmap(square, INPUT_SIZE, INPUT_SIZE, true)

        // Recycle intermediate square only if it's a new allocation
        if (square !== src) square.recycle()

        if (debug) {
            Log.d(TAG, "[D2] centerCrop: ${src.width}×${src.height} → " +
                    "square($side) left=$left top=$top → ${INPUT_SIZE}×${INPUT_SIZE}")
            // Save for visual inspection in Android Studio Device Explorer
            // Path: /sdcard/Android/data/<package>/files/debug_crop_<ts>.png
            savePng(resized, "debug_crop_${System.currentTimeMillis()}.png")
            Log.d(TAG, "[D2] Saved cropped bitmap — check Device Explorer → " +
                    "sdcard/Android/data/.../files/")
        }
        return resized
    }

    // ─────────────────────────────────────────────────────────────
    // Stage 3+4 – Normalise + pack as float16
    // [Debug D3: pixel min/max] [Debug D4: buffer byte count]
    // ─────────────────────────────────────────────────────────────

    fun packFloatArray(bitmap: Bitmap, debug: Boolean = false): FloatArray {
        require(bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            "Expected ${INPUT_SIZE}×${INPUT_SIZE}, got ${bitmap.width}×${bitmap.height}"
        }

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val buf = FloatArray(ARRAY_SIZE)

        // Stats only computed in debug mode — no overhead in production
        var minV =  Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        var nanCount = 0
        var idx = 0

        for (px in pixels) {
            // SigLIP2 normalisation: (uint8 / 127.5) - 1.0  →  range [-1, 1]
            val r = (px ushr 16 and 0xFF) / 127.5f - 1f
            val g = (px ushr  8 and 0xFF) / 127.5f - 1f
            val b = (px         and 0xFF) / 127.5f - 1f

            buf[idx++] = r
            buf[idx++] = g
            buf[idx++] = b

            if (debug) {
                minV = minOf(minV, r, g, b)
                maxV = maxOf(maxV, r, g, b)
                if (r.isNaN() || g.isNaN() || b.isNaN()) nanCount++
            }
        }



        if (debug) {
            // [D3] Pixel value range
            Log.d(TAG, "[D3] pixel range: min=${"%.4f".format(minV)}  " +
                    "max=${"%.4f".format(maxV)}  (expect ≈ [-1.0, 1.0])")
            if (nanCount > 0) Log.e(TAG, "[D3] ❌ NaN detected: $nanCount values!")
            else              Log.d(TAG, "[D3] ✅ no NaN values")

            // [D4] Buffer size verification
            val expectedFloats = ARRAY_SIZE
            Log.d(TAG, "[D4] array size: ${buf.size} floats  " +
                    "(expected $expectedFloats) " +
                    if (buf.size == expectedFloats) "✅" else "❌ MISMATCH")

            // Spot-check: decode first pixel to verify
            val r0 = buf[0]
            val g0 = buf[1]
            val b0 = buf[2]
            Log.d(TAG, "[D4] first pixel check: " +
                    "r=${"%.4f".format(r0)} g=${"%.4f".format(g0)} b=${"%.4f".format(b0)}")
        }

        return buf
    }

    // ─────────────────────────────────────────────────────────────
    // Debug helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Saves a bitmap to the app's external files dir.
     * Visible in Android Studio Device Explorer:
     *   /sdcard/Android/data/<package>/files/<filename>
     */
    fun savePng(bitmap: Bitmap, filename: String) {
        try {
            val f = java.io.File(context.getExternalFilesDir(null), filename)
            f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.d(TAG, "[D2] Saved → ${f.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "[D2] savePng failed: ${e.message}")
        }
    }
}
