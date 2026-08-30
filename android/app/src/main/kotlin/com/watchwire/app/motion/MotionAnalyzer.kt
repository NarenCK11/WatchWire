package com.watchwire.app.motion

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat

/** CameraX analyzer that runs motion detection at a deliberately low rate to keep CPU and
 * battery usage down: frames arriving faster than [minFrameIntervalMs] are dropped before
 * any decoding happens, and a successful detection is followed by a debounce window so a
 * single ongoing motion doesn't flood the backend with events. No frame data is ever kept
 * beyond the single previous-frame buffer needed for differencing, and nothing is ever
 * written to disk or sent off-device. */
class MotionAnalyzer(
    sensitivity: Float,
    private val minFrameIntervalMs: Long = 700L,
    private val debounceMs: Long = 4000L,
    private val motionThreshold: Float = 0.12f,
    private val onMotionDetected: (score: Float) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = MotionDetector(sensitivity)
    private var lastProcessedAtMs = 0L
    private var lastFiredAtMs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastProcessedAtMs < minFrameIntervalMs) return
            lastProcessedAtMs = now

            if (image.format != ImageFormat.YUV_420_888) return

            val mat = yPlaneToGrayMat(image)
            val score = detector.processFrame(mat)
            mat.release()

            if (score != null && score >= motionThreshold && now - lastFiredAtMs >= debounceMs) {
                lastFiredAtMs = now
                onMotionDetected(score)
            }
        } finally {
            image.close()
        }
    }

    fun reset() {
        detector.reset()
        lastFiredAtMs = 0L
    }

    /** Converts the analysis frame's Y (luma) plane into an 8-bit single-channel Mat,
     * handling the row padding (rowStride > width) that many devices report. */
    private fun yPlaneToGrayMat(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val mat = Mat(height, width, CvType.CV_8UC1)
        buffer.rewind()

        if (rowStride == width) {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            mat.put(0, 0, bytes)
        } else {
            val rowBytes = ByteArray(rowStride)
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(rowBytes, 0, rowStride)
                mat.put(row, 0, rowBytes.copyOf(width))
            }
        }
        return mat
    }
}
