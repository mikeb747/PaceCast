package com.pacecast.app.ble

import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.DisplayCutout
import kotlin.math.roundToInt

enum class IslandLayoutMode(val label: String) {
    CUTOUT_PATH("cutout-path"),
    FLOATING_RECT("floating-rect"),
    TIGHT_CUTOUT("tight-cutout"),
    TALL_CUTOUT("tall-cutout"),
    FALLBACK("fallback")
}

data class DynamicIslandMetrics(
    val pillHeightPx: Int,
    val spacerWidthPx: Int,
    val targetY: Int,
    val mode: IslandLayoutMode,
    val estLensTopPx: Int?,
    val estLensCenterYPx: Int?
)

/**
 * Pure, density-independent Dynamic Island geometry calculator.
 *
 * Computes overlay vertical position (targetY), pill height (pillHeightPx), and
 * camera spacer width (spacerWidthPx) directly from the device's DisplayCutout
 * metrics and screen density, avoiding fixed raw-pixel offsets that misalign across
 * OEMs (e.g., Xiaomi/MIUI tight cutouts vs Pixel tall status-bar cutouts).
 */
object DynamicIslandGeometry {

    private const val MIN_VISUAL_HEIGHT_DP = 31.5f
    private const val MIN_PILL_HEIGHT_DP = 20f
    private const val MAX_PILL_HEIGHT_DP = 64f
    private const val MIN_TOP_MARGIN_DP = 3f
    private const val PATH_VERTICAL_PAD_DP = 2f
    private const val TIGHT_BOTTOM_PAD_DP = 2f
    private const val TALL_CUTOUT_THRESHOLD_DP = 42f

    private fun dpToPx(dp: Float, density: Float): Int {
        return (dp * density).roundToInt()
    }

    /**
     * Extracts exact bounds of DisplayCutout.cutoutPath on API 31+ when available.
     */
    fun extractCutoutPathBounds(cutout: DisplayCutout?): Rect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || cutout == null) return null
        val path = cutout.cutoutPath ?: return null
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.isEmpty) return null
        return Rect(
            bounds.left.roundToInt(),
            bounds.top.roundToInt(),
            bounds.right.roundToInt(),
            bounds.bottom.roundToInt()
        )
    }

    fun compute(
        holeRect: Rect?,
        cutoutPathBounds: Rect?,
        density: Float,
        userYDeltaPx: Int = 0,
        userHeightDeltaPx: Int = 0
    ): DynamicIslandMetrics {
        val safeDensity = density.coerceAtLeast(1f)
        val minVisualHeightPx = dpToPx(MIN_VISUAL_HEIGHT_DP, safeDensity)
        val minPillHeightPx = dpToPx(MIN_PILL_HEIGHT_DP, safeDensity)
        val maxPillHeightPx = dpToPx(MAX_PILL_HEIGHT_DP, safeDensity)
        val minTopMarginPx = dpToPx(MIN_TOP_MARGIN_DP, safeDensity)

        val holeWidth = holeRect?.width() ?: cutoutPathBounds?.width() ?: 0
        val spacerWidthPx = if (holeWidth > 0) {
            maxOf(holeWidth - dpToPx(2f, safeDensity), dpToPx(26f, safeDensity))
        } else {
            dpToPx(28f, safeDensity)
        }

        // Case 1: API 31+ DisplayCutout.cutoutPath describes a floating punch-hole circle (top >= 2dp)
        val hasFloatingPathHole = cutoutPathBounds != null &&
            cutoutPathBounds.top >= dpToPx(2f, safeDensity) &&
            cutoutPathBounds.height() in dpToPx(10f, safeDensity)..dpToPx(44f, safeDensity) &&
            cutoutPathBounds.width() in dpToPx(10f, safeDensity)..dpToPx(80f, safeDensity) &&
            (holeRect == null || cutoutPathBounds.bottom <= holeRect.bottom + dpToPx(4f, safeDensity))

        val basePillHeightPx: Int
        val baseTopY: Int
        val mode: IslandLayoutMode
        val estLensTopPx: Int?
        val estLensCenterYPx: Int?

        if (hasFloatingPathHole && cutoutPathBounds != null) {
            val padPx = dpToPx(PATH_VERTICAL_PAD_DP, safeDensity)
            val lensCenterY = (cutoutPathBounds.top + cutoutPathBounds.bottom) / 2f
            basePillHeightPx = maxOf(minVisualHeightPx, cutoutPathBounds.height() + padPx * 2)
            baseTopY = (lensCenterY - basePillHeightPx / 2f).roundToInt().coerceAtLeast(minTopMarginPx)
            mode = IslandLayoutMode.CUTOUT_PATH
            estLensTopPx = cutoutPathBounds.top
            estLensCenterYPx = lensCenterY.roundToInt()
        } else if (holeRect != null && holeRect.top > 0 && holeRect.bottom > holeRect.top) {
            // Case 2: Bounding rect itself is floating below y=0
            val padPx = dpToPx(PATH_VERTICAL_PAD_DP, safeDensity)
            val lensCenterY = (holeRect.top + holeRect.bottom) / 2f
            basePillHeightPx = maxOf(minVisualHeightPx, holeRect.height() + padPx * 2)
            baseTopY = (lensCenterY - basePillHeightPx / 2f).roundToInt().coerceAtLeast(minTopMarginPx)
            mode = IslandLayoutMode.FLOATING_RECT
            estLensTopPx = holeRect.top
            estLensCenterYPx = lensCenterY.roundToInt()
        } else if (holeRect != null && holeRect.bottom > 0 && holeRect.width() > 0) {
            // Case 3: Standard AOSP top-anchored boundingRect (top == 0, bottom == safeInsetTop)
            val bottomDp = holeRect.bottom / safeDensity
            val computedEstLensTop = (holeRect.bottom - holeRect.width()).coerceAtLeast(0)
            val computedEstLensCenterY = holeRect.bottom - (holeRect.width() / 2)
            estLensTopPx = computedEstLensTop
            estLensCenterYPx = computedEstLensCenterY

            if (bottomDp > TALL_CUTOUT_THRESHOLD_DP) {
                // Tall status-bar cutout (e.g., Pixel 6/7/8/9 where B is ~48-52dp and lens sits centered in B)
                basePillHeightPx = minVisualHeightPx
                baseTopY = (holeRect.centerY() - (basePillHeightPx / 2)).coerceAtLeast(minTopMarginPx)
                mode = IslandLayoutMode.TALL_CUTOUT
            } else {
                // Tight punch-hole cutout (e.g., Xiaomi/MIUI/HyperOS, Samsung, OnePlus where B tightly hugs lens bottom)
                val bottomPadPx = dpToPx(TIGHT_BOTTOM_PAD_DP, safeDensity)
                val topPadPx = dpToPx(PATH_VERTICAL_PAD_DP, safeDensity)
                val desiredBottom = holeRect.bottom + bottomPadPx
                val desiredTopCap = (computedEstLensTop - topPadPx).coerceAtLeast(minTopMarginPx)
                val neededHeight = desiredBottom - desiredTopCap
                basePillHeightPx = maxOf(minVisualHeightPx, neededHeight)
                baseTopY = (desiredBottom - basePillHeightPx).coerceAtLeast(minTopMarginPx)
                mode = IslandLayoutMode.TIGHT_CUTOUT
            }
        } else {
            // Case 4: No display cutout reported (fallback)
            basePillHeightPx = minVisualHeightPx
            baseTopY = dpToPx(6f, safeDensity)
            mode = IslandLayoutMode.FALLBACK
            estLensTopPx = null
            estLensCenterYPx = null
        }

        val pillHeightPx = (basePillHeightPx + userHeightDeltaPx).coerceIn(minPillHeightPx, maxPillHeightPx)
        val targetY = (baseTopY + userYDeltaPx).coerceAtLeast(0)

        return DynamicIslandMetrics(
            pillHeightPx = pillHeightPx,
            spacerWidthPx = spacerWidthPx,
            targetY = targetY,
            mode = mode,
            estLensTopPx = estLensTopPx,
            estLensCenterYPx = estLensCenterYPx
        )
    }
}
