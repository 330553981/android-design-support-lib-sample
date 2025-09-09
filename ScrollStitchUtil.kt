// Replaced by Java implementation. Keeping file placeholder to avoid build issues if referenced.
package placeholder

class ScrollStitchUtilKtPlaceholder

/**
 * ScrollStitchUtil
 *
 * A single-file utility to estimate vertical overlap between consecutive screenshots
 * using a coarse-to-fine ZNCC (zero-mean normalized cross-correlation) and to stitch
 * them into a long image with a softly blended seam. No third-party libraries.
 *
 * Assumptions:
 * - All input bitmaps share the same width (will be enforced; next frames will be scaled to match width).
 * - Vertical-only motion between frames (typical scroll). Minor local differences are tolerated.
 */
object ScrollStitchUtil {

    data class StitchOptions(
        val pyramidLevels: Int = 3,                 // Coarse-to-fine levels (>=1)
        val maxSearchPercent: Float = 0.5f,         // Max vertical search range as a fraction of frame height
        val refineWindowPx: Int = 12,               // Search half-window at finer levels around coarse estimate
        val sampleXStep: Int = 2,                   // Horizontal sampling stride when computing ZNCC
        val sampleYStep: Int = 2,                   // Vertical sampling stride when computing ZNCC
        val cropTopPx: Int = 0,                     // Crop fixed headers before matching
        val cropBottomPx: Int = 0,                  // Crop fixed footers before matching
        val minConfidence: Double = 0.25,           // Accept offset only if NCC is above this value
        val blendBandPx: Int = 24,                  // Seam blending band width
        val clampOffsetToRange: Boolean = true      // Clamp final offset into [0, height-1]
    )

    data class OffsetResult(
        val offsetPx: Int,      // Positive means the content moved up by offset (user scrolled down)
        val confidence: Double  // NCC score of the best offset [-1, 1]
    )

    data class StitchResult(
        val bitmap: Bitmap,
        val offsets: List<OffsetResult>
    )

    /**
     * Stitch a list of frames captured during scrolling.
     * Uses vertical-only alignment and seam blending at each join.
     */
    fun stitch(frames: List<Bitmap>, options: StitchOptions = StitchOptions()): StitchResult {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        val normalized = normalizeWidths(frames)

        var result = normalized.first().copy(Bitmap.Config.ARGB_8888, true)
        val resultCanvas = Canvas(result)
        val offsets = mutableListOf<OffsetResult>()

        for (i in 1 until normalized.size) {
            val prev = normalized[i - 1]
            val next = normalized[i]

            val estimated = estimateVerticalOffset(prev, next, options)
            offsets += estimated

            val join = computeJoinAndGrow(result, next, estimated, options)
            result = join
        }

        return StitchResult(result, offsets)
    }

    /**
     * Estimate vertical displacement between two frames using a multi-scale ZNCC search.
     */
    fun estimateVerticalOffset(prev: Bitmap, next: Bitmap, options: StitchOptions = StitchOptions()): OffsetResult {
        val width = prev.width
        val height = prev.height
        require(width == next.width) { "Frame widths must match" }
        require(height == next.height) { "Frame heights must match" }

        val cropTop = options.cropTopPx.coerceAtLeast(0).coerceAtMost(height - 1)
        val cropBottom = options.cropBottomPx.coerceAtLeast(0).coerceAtMost(height - 1 - cropTop)
        val effectiveHeight = height - cropTop - cropBottom
        require(effectiveHeight > 8) { "Effective height after cropping is too small" }

        // Build pyramids (coarsest last)
        val levels = options.pyramidLevels.coerceAtLeast(1)
        val prevPyr = buildPyramid(cropBitmap(prev, 0, cropTop, width, effectiveHeight), levels)
        val nextPyr = buildPyramid(cropBitmap(next, 0, cropTop, width, effectiveHeight), levels)

        // Coarse-to-fine search
        var bestOffsetAtLevel = 0
        var bestScoreAtLevel = -2.0
        var heightAtLevel = prevPyr.last().height

        for (level in levels - 1 downTo 0) {
            val a = prevPyr[level]
            val b = nextPyr[level]
            heightAtLevel = a.height

            val searchRange = if (level == levels - 1) {
                (heightAtLevel * options.maxSearchPercent).roundToInt()
            } else {
                options.refineWindowPx
            }.coerceAtLeast(1)

            val coarseGuess = if (level == levels - 1) 0 else bestOffsetAtLevel * 2
            val from = (coarseGuess - searchRange).coerceAtLeast(-(heightAtLevel - 1))
            val to = (coarseGuess + searchRange).coerceAtMost(heightAtLevel - 1)

            var bestOff = coarseGuess
            var bestScore = -2.0
            var secondBest = -2.0

            val grayA = toGrayscaleArray(a)
            val grayB = toGrayscaleArray(b)

            val stepY = options.sampleYStep.coerceAtLeast(1)
            val stepX = options.sampleXStep.coerceAtLeast(1)

            for (off in from..to) {
                val score = znccVerticalShift(grayA, grayB, a.width, a.height, off, stepX, stepY)
                if (score > bestScore) {
                    secondBest = bestScore
                    bestScore = score
                    bestOff = off
                } else if (score > secondBest) {
                    secondBest = score
                }
            }

            bestOffsetAtLevel = bestOff
            bestScoreAtLevel = bestScore
        }

        // Map offset back to full-res coordinates
        var finalOffset = bestOffsetAtLevel
        if (options.clampOffsetToRange) {
            finalOffset = finalOffset.coerceIn(-(effectiveHeight - 1), (effectiveHeight - 1))
        }

        // Confidence based on best score (NCC in [-1,1])
        val confidence = bestScoreAtLevel
        return OffsetResult(offsetPx = finalOffset, confidence = confidence)
    }

    // ------------------ internal helpers ------------------

    private fun normalizeWidths(frames: List<Bitmap>): List<Bitmap> {
        if (frames.size <= 1) return frames
        val targetWidth = frames.first().width
        val config = Bitmap.Config.ARGB_8888
        return frames.mapIndexed { idx, bmp ->
            if (bmp.width == targetWidth) {
                if (bmp.config == config && bmp.isMutable) bmp else bmp.copy(config, true)
            } else {
                val scaled = Bitmap.createBitmap(targetWidth, (bmp.height * (targetWidth / bmp.width.toFloat())).roundToInt(), config)
                val canvas = Canvas(scaled)
                canvas.drawBitmap(Bitmap.createScaledBitmap(bmp, scaled.width, scaled.height, true), 0f, 0f, null)
                scaled
            }
        }
    }

    private fun cropBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    private fun buildPyramid(src: Bitmap, levels: Int): List<Bitmap> {
        if (levels <= 1) return listOf(src)
        val pyr = ArrayList<Bitmap>(levels)
        var cur = src
        pyr.add(cur)
        for (i in 1 until levels) {
            val nextW = max(1, cur.width / 2)
            val nextH = max(1, cur.height / 2)
            val down = Bitmap.createScaledBitmap(cur, nextW, nextH, true)
            pyr.add(down)
            cur = down
        }
        return pyr
    }

    private fun toGrayscaleArray(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // luminance (Rec. 601)
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
            i++
        }
        return gray
    }

    /**
     * Compute ZNCC score for a vertical shift (off) between two grayscale images of same size.
     * Only overlapping region contributes. Sampling strides reduce cost.
     */
    private fun znccVerticalShift(
        a: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        off: Int,
        stepX: Int,
        stepY: Int
    ): Double {
        if (off == 0) {
            // full overlap
            return znccOverlap(a, b, width, height, 0, height, stepX, stepY)
        }
        return if (off > 0) {
            // content moved up by off: compare a[0..h-off) with b[off..h)
            znccOverlap(a, b, width, height, offStartB = off, overlapH = height - off, stepX = stepX, stepY = stepY)
        } else {
            // content moved down by |off|: compare a[|off|..h) with b[0..h-|off|)
            val p = -off
            znccOverlapShiftA(a, b, width, height, offStartA = p, overlapH = height - p, stepX = stepX, stepY = stepY)
        }
    }

    private fun znccOverlap(
        a: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        offStartB: Int,
        overlapH: Int,
        stepX: Int,
        stepY: Int
    ): Double {
        if (overlapH <= 4) return -2.0
        var sumA = 0.0
        var sumB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        var sumAB = 0.0
        var count = 0
        val startRowA = 0
        val startRowB = offStartB
        val endRowA = overlapH
        var yA = startRowA
        var yB = startRowB
        while (yA < endRowA) {
            var x = 0
            val idxA = yA * width
            val idxB = yB * width
            while (x < width) {
                val ia = a[idxA + x].toDouble()
                val ib = b[idxB + x].toDouble()
                sumA += ia
                sumB += ib
                sumAA += ia * ia
                sumBB += ib * ib
                sumAB += ia * ib
                count++
                x += stepX
            }
            yA += stepY
            yB += stepY
        }
        if (count == 0) return -2.0
        val meanA = sumA / count
        val meanB = sumB / count
        val varA = sumAA / count - meanA.pow(2.0)
        val varB = sumBB / count - meanB.pow(2.0)
        val denom = (varA * varB)
        if (denom <= 1e-6) return -2.0
        val cov = sumAB / count - meanA * meanB
        return (cov / kotlin.math.sqrt(denom)).coerceIn(-1.0, 1.0)
    }

    private fun znccOverlapShiftA(
        a: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        offStartA: Int,
        overlapH: Int,
        stepX: Int,
        stepY: Int
    ): Double {
        if (overlapH <= 4) return -2.0
        var sumA = 0.0
        var sumB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        var sumAB = 0.0
        var count = 0
        val startRowA = offStartA
        val startRowB = 0
        val endRowA = offStartA + overlapH
        var yA = startRowA
        var yB = startRowB
        while (yA < endRowA) {
            var x = 0
            val idxA = yA * width
            val idxB = yB * width
            while (x < width) {
                val ia = a[idxA + x].toDouble()
                val ib = b[idxB + x].toDouble()
                sumA += ia
                sumB += ib
                sumAA += ia * ia
                sumBB += ib * ib
                sumAB += ia * ib
                count++
                x += stepX
            }
            yA += stepY
            yB += stepY
        }
        if (count == 0) return -2.0
        val meanA = sumA / count
        val meanB = sumB / count
        val varA = sumAA / count - meanA.pow(2.0)
        val varB = sumBB / count - meanB.pow(2.0)
        val denom = (varA * varB)
        if (denom <= 1e-6) return -2.0
        val cov = sumAB / count - meanA * meanB
        return (cov / kotlin.math.sqrt(denom)).coerceIn(-1.0, 1.0)
    }

    /**
     * Compute seam and grow the result by appending the non-overlapping tail of next.
     * Blends across a small band around the seam.
     */
    private fun computeJoinAndGrow(currentResult: Bitmap, next: Bitmap, off: OffsetResult, options: StitchOptions): Bitmap {
        val width = currentResult.width
        val height = next.height

        val offset = off.offsetPx.coerceIn(-(height - 1), height - 1)

        // overlap height between last frame (at bottom of currentResult) and next
        val overlapH = if (offset >= 0) height - offset else height + offset // offset < 0 reduces overlap from top of current
        val overlapHClamped = overlapH.coerceIn(0, min(height, currentResult.height))

        // y in currentResult where the top of next should align
        val alignTopYInResult = currentResult.height - overlapHClamped

        // If no overlap (e.g., offset >= height), append directly
        if (overlapHClamped <= 0) {
            val grown = Bitmap.createBitmap(width, currentResult.height + height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(grown)
            canvas.drawBitmap(currentResult, 0f, 0f, null)
            canvas.drawBitmap(next, 0f, currentResult.height.toFloat(), null)
            return grown
        }

        // Determine optimal seam row within the overlap region by minimal per-row difference
        val seamRowInOverlap = findSeamRow(currentResult, next, alignTopYInResult, overlapHClamped)
        val blendBand = options.blendBandPx.coerceAtLeast(0)
        val seamStartInResult = (alignTopYInResult + seamRowInOverlap - blendBand / 2).coerceIn(0, currentResult.height)
        val seamEndInResult = (seamStartInResult + blendBand).coerceAtMost(currentResult.height)

        val newHeight = alignTopYInResult + seamRowInOverlap + (height - seamRowInOverlap)
        val grown = Bitmap.createBitmap(width, max(newHeight, currentResult.height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grown)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1) draw the existing result entirely
        canvas.drawBitmap(currentResult, 0f, 0f, null)

        // 2) Blend band
        if (blendBand > 0 && seamStartInResult < seamEndInResult) {
            val bandHeight = seamEndInResult - seamStartInResult
            val tmpRowPrev = IntArray(width)
            val tmpRowNext = IntArray(width)
            val blendedRow = IntArray(width)
            var y = 0
            while (y < bandHeight) {
                val alpha = (y.toFloat() / max(1, bandHeight - 1)).coerceIn(0f, 1f)
                val srcYPrev = seamStartInResult + y
                val srcYNext = srcYPrev - alignTopYInResult
                currentResult.getPixels(tmpRowPrev, 0, width, 0, srcYPrev, width, 1)
                next.getPixels(tmpRowNext, 0, width, 0, srcYNext, width, 1)
                blendRows(tmpRowPrev, tmpRowNext, alpha, blendedRow)
                grown.setPixels(blendedRow, 0, width, 0, srcYPrev, width, 1)
                y++
            }
        }

        // 3) Draw the tail of next below the seam band
        val tailStartInNext = max(0, seamRowInOverlap + (options.blendBandPx + 1) / 2)
        val destYInResult = alignTopYInResult + tailStartInNext
        if (tailStartInNext < height && destYInResult < grown.height) {
            val remainingH = min(height - tailStartInNext, grown.height - destYInResult)
            if (remainingH > 0) {
                val tail = Bitmap.createBitmap(next, 0, tailStartInNext, width, remainingH)
                canvas.drawBitmap(tail, 0f, destYInResult.toFloat(), paint)
            }
        }

        return grown
    }

    private fun findSeamRow(currentResult: Bitmap, next: Bitmap, alignTopYInResult: Int, overlapH: Int): Int {
        // Evaluate per-row absolute difference on a central vertical strip (to avoid dynamic sidebars)
        val width = currentResult.width
        val x0 = (width * 0.1f).roundToInt()
        val x1 = (width * 0.9f).roundToInt().coerceAtLeast(x0 + 1)
        val rowPrev = IntArray(x1 - x0)
        val rowNext = IntArray(x1 - x0)

        var bestRow = 0
        var bestScore = Double.MAX_VALUE
        var y = 0
        while (y < overlapH) {
            val srcYPrev = alignTopYInResult + y
            val srcYNext = y
            currentResult.getPixels(rowPrev, 0, x1 - x0, x0, srcYPrev, x1 - x0, 1)
            next.getPixels(rowNext, 0, x1 - x0, x0, srcYNext, x1 - x0, 1)
            var sum = 0L
            var i = 0
            while (i < rowPrev.size) {
                sum += colorDiff(rowPrev[i], rowNext[i])
                i++
            }
            val score = sum.toDouble() / rowPrev.size
            if (score < bestScore) {
                bestScore = score
                bestRow = y
            }
            y++
        }
        return bestRow
    }

    private fun colorDiff(a: Int, b: Int): Int {
        val ra = (a shr 16) and 0xFF
        val ga = (a shr 8) and 0xFF
        val ba = a and 0xFF
        val rb = (b shr 16) and 0xFF
        val gb = (b shr 8) and 0xFF
        val bb = b and 0xFF
        val dr = ra - rb
        val dg = ga - gb
        val db = ba - bb
        return abs(dr) + abs(dg) + abs(db)
    }

    private fun blendRows(prev: IntArray, next: IntArray, alpha: Float, out: IntArray) {
        val a = alpha.coerceIn(0f, 1f)
        val ia = 1f - a
        var i = 0
        while (i < prev.size) {
            val p = prev[i]
            val n = next[i]
            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            val nr = (n shr 16) and 0xFF
            val ng = (n shr 8) and 0xFF
            val nb = n and 0xFF
            val r = (pr * ia + nr * a).roundToInt().coerceIn(0, 255)
            val g = (pg * ia + ng * a).roundToInt().coerceIn(0, 255)
            val b = (pb * ia + nb * a).roundToInt().coerceIn(0, 255)
            out[i] = Color.rgb(r, g, b)
            i++
        }
    }
}

