package com.watchwire.app.motion

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Simple frame-differencing motion detector: downsamples each incoming grayscale frame,
 * blurs it to suppress sensor noise, and compares it against the previous frame with
 * OpenCV's absdiff + threshold. The fraction of pixels that changed becomes the motion
 * score. Deliberately not a background-subtraction model or anything ML-based -- this is
 * meant to be cheap enough to run continuously on-device at a low frame rate. */
class MotionDetector(sensitivity: Float) {
    // Higher sensitivity -> lower pixel-difference threshold -> smaller movements register.
    private val diffThreshold = (70 - sensitivity.coerceIn(0f, 1f) * 50).coerceIn(20f, 70f)

    private var previousFrame: Mat? = null
    private val workingSize = Size(160.0, 120.0)

    /** Returns a motion score in [0, 1], or null if there's no previous frame to compare
     * against yet (the very first frame after start/reset). */
    fun processFrame(graySourceFrame: Mat): Float? {
        val resized = Mat()
        Imgproc.resize(graySourceFrame, resized, workingSize)
        Imgproc.GaussianBlur(resized, resized, Size(5.0, 5.0), 0.0)

        val prev = previousFrame
        if (prev == null) {
            previousFrame = resized
            return null
        }

        val diff = Mat()
        Core.absdiff(prev, resized, diff)
        Imgproc.threshold(diff, diff, diffThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)

        val changedPixels = Core.countNonZero(diff)
        val totalPixels = diff.rows() * diff.cols()
        val rawFraction = changedPixels.toFloat() / totalPixels.toFloat()

        prev.release()
        diff.release()
        previousFrame = resized

        // Real motion in a typical indoor scene changes a small percentage of pixels;
        // scale up so the score uses more of the 0..1 range for a readable UI percentage.
        return (rawFraction * 8f).coerceIn(0f, 1f)
    }

    fun reset() {
        previousFrame?.release()
        previousFrame = null
    }
}
