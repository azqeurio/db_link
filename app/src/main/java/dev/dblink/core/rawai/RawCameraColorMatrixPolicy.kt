package dev.dblink.core.rawai

import kotlin.math.abs

data class RawCameraColorMatrixResolution(
    val matrix3x3: DoubleArray,
    val sourceModel: String,
    val effectiveModel: String,
    val source: String,
    val usedFallback: Boolean,
)

/** Explicit, auditable policy for decoder color-matrix gaps. */
object RawCameraColorMatrixPolicy {
    private val om5Matrix = doubleArrayOf(
        1.1896, -0.5110, -0.1076,
        -0.3181, 1.1378, 0.2048,
        -0.0519, 0.1224, 0.5166,
    )

    fun resolve(cameraModel: String, decoderMatrix: DoubleArray): RawCameraColorMatrixResolution {
        require(decoderMatrix.size == 9 || decoderMatrix.size == 12) {
            "Decoder RGB/XYZ matrix must contain 9 or 12 values"
        }
        // Project policy: the supplied OM-5 Mark II ISO series is evaluated as OM-5 on every
        // backend. This is deliberately applied even when newer LibRaw versions expose a valid
        // OM-5 Mark II matrix, otherwise Python 0.21 and Android 0.22 use different contracts.
        if (normalize(cameraModel) == "OM5MARKII") {
            return RawCameraColorMatrixResolution(
                matrix3x3 = om5Matrix.copyOf(),
                sourceModel = cameraModel.trim(),
                effectiveModel = "OM-5",
                source = "LibRaw_0.22.0_colordata_OM-5_project_alias",
                usedFallback = true,
            )
        }
        val candidate = decoderMatrix.copyOfRange(0, 9)
        if (!isSingular(candidate)) {
            return RawCameraColorMatrixResolution(
                matrix3x3 = candidate,
                sourceModel = cameraModel.trim(),
                effectiveModel = cameraModel.trim(),
                source = "decoder_metadata",
                usedFallback = false,
            )
        }
        error("No approved RGB/XYZ matrix fallback for camera model ${cameraModel.trim()}")
    }

    private fun normalize(value: String) = value.uppercase().filter(Char::isLetterOrDigit)

    private fun isSingular(matrix: DoubleArray): Boolean {
        val determinant =
            matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7]) -
                matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6]) +
                matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6])
        return !determinant.isFinite() || abs(determinant) <= 1e-12
    }
}
