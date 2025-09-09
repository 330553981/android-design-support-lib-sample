package util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

/**
 * ScrollStitchUtil (Java)
 *
 * Vertical-only alignment and stitching of scrolling screenshots using
 * multi-scale ZNCC search and seam blending. No third-party dependencies.
 */
public final class ScrollStitchUtil {

    private ScrollStitchUtil() {}

    public static final class StitchOptions {
        public int pyramidLevels = 3;       // >= 1
        public float maxSearchPercent = 0.5f;
        public int refineWindowPx = 12;
        public int sampleXStep = 2;
        public int sampleYStep = 2;
        public int cropTopPx = 0;
        public int cropBottomPx = 0;
        public double minConfidence = 0.25;
        public int blendBandPx = 24;
        public boolean clampOffsetToRange = true;
    }

    public static final class OffsetResult {
        public final int offsetPx;
        public final double confidence; // NCC score
        public OffsetResult(int offsetPx, double confidence) {
            this.offsetPx = offsetPx;
            this.confidence = confidence;
        }
    }

    public static final class StitchResult {
        public final Bitmap bitmap;
        public final List<OffsetResult> offsets;
        public StitchResult(Bitmap bitmap, List<OffsetResult> offsets) {
            this.bitmap = bitmap;
            this.offsets = offsets;
        }
    }

    public static StitchResult stitch(List<Bitmap> frames, StitchOptions options) {
        if (frames == null || frames.isEmpty()) throw new IllegalArgumentException("frames empty");
        if (options == null) options = new StitchOptions();

        List<Bitmap> normalized = normalizeWidths(frames);
        Bitmap result = normalized.get(0).copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);
        List<OffsetResult> offsets = new ArrayList<>();

        for (int i = 1; i < normalized.size(); i++) {
            Bitmap prev = normalized.get(i - 1);
            Bitmap next = normalized.get(i);
            OffsetResult est = estimateVerticalOffset(prev, next, options);
            offsets.add(est);
            result = computeJoinAndGrow(result, next, est, options);
            canvas = new Canvas(result);
        }
        return new StitchResult(result, offsets);
    }

    public static OffsetResult estimateVerticalOffset(Bitmap prev, Bitmap next, StitchOptions options) {
        int width = prev.getWidth();
        int height = prev.getHeight();
        if (width != next.getWidth() || height != next.getHeight()) {
            throw new IllegalArgumentException("dimension mismatch");
        }

        int cropTop = clamp(options.cropTopPx, 0, height - 1);
        int cropBottom = clamp(options.cropBottomPx, 0, height - 1 - cropTop);
        int effectiveHeight = height - cropTop - cropBottom;
        if (effectiveHeight <= 8) throw new IllegalArgumentException("effective height too small");

        int levels = Math.max(1, options.pyramidLevels);
        Bitmap prevCrop = Bitmap.createBitmap(prev, 0, cropTop, width, effectiveHeight);
        Bitmap nextCrop = Bitmap.createBitmap(next, 0, cropTop, width, effectiveHeight);
        List<Bitmap> prevPyr = buildPyramid(prevCrop, levels);
        List<Bitmap> nextPyr = buildPyramid(nextCrop, levels);

        int bestOffsetAtLevel = 0;
        double bestScoreAtLevel = -2.0;

        for (int level = levels - 1; level >= 0; level--) {
            Bitmap a = prevPyr.get(level);
            Bitmap b = nextPyr.get(level);
            int h = a.getHeight();
            int w = a.getWidth();
            int searchRange = (level == levels - 1)
                    ? Math.max(1, Math.round(h * options.maxSearchPercent))
                    : Math.max(1, options.refineWindowPx);
            int coarseGuess = (level == levels - 1) ? 0 : bestOffsetAtLevel * 2;
            int from = Math.max(-(h - 1), coarseGuess - searchRange);
            int to = Math.min(h - 1, coarseGuess + searchRange);

            float[] grayA = toGrayscaleArray(a);
            float[] grayB = toGrayscaleArray(b);
            int stepX = Math.max(1, options.sampleXStep);
            int stepY = Math.max(1, options.sampleYStep);

            double bestScore = -2.0;
            double secondBest = -2.0;
            int bestOff = coarseGuess;
            for (int off = from; off <= to; off++) {
                double score = znccVerticalShift(grayA, grayB, w, h, off, stepX, stepY);
                if (score > bestScore) {
                    secondBest = bestScore;
                    bestScore = score;
                    bestOff = off;
                } else if (score > secondBest) {
                    secondBest = score;
                }
            }
            bestOffsetAtLevel = bestOff;
            bestScoreAtLevel = bestScore;
        }

        int finalOffset = bestOffsetAtLevel;
        if (options.clampOffsetToRange) {
            finalOffset = clamp(finalOffset, -(effectiveHeight - 1), (effectiveHeight - 1));
        }
        return new OffsetResult(finalOffset, bestScoreAtLevel);
    }

    private static List<Bitmap> normalizeWidths(List<Bitmap> frames) {
        if (frames.size() <= 1) return frames;
        int targetWidth = frames.get(0).getWidth();
        List<Bitmap> out = new ArrayList<>(frames.size());
        for (Bitmap bmp : frames) {
            if (bmp.getWidth() == targetWidth && bmp.getConfig() == Bitmap.Config.ARGB_8888 && bmp.isMutable()) {
                out.add(bmp);
            } else if (bmp.getWidth() == targetWidth) {
                out.add(bmp.copy(Bitmap.Config.ARGB_8888, true));
            } else {
                int scaledH = Math.round(bmp.getHeight() * (targetWidth / (float) bmp.getWidth()));
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, targetWidth, scaledH, true);
                out.add(scaled.copy(Bitmap.Config.ARGB_8888, true));
            }
        }
        return out;
    }

    private static List<Bitmap> buildPyramid(Bitmap src, int levels) {
        ArrayList<Bitmap> pyr = new ArrayList<>(levels);
        Bitmap cur = src;
        pyr.add(cur);
        for (int i = 1; i < levels; i++) {
            int nextW = Math.max(1, cur.getWidth() / 2);
            int nextH = Math.max(1, cur.getHeight() / 2);
            Bitmap down = Bitmap.createScaledBitmap(cur, nextW, nextH, true);
            pyr.add(down);
            cur = down;
        }
        return pyr;
    }

    private static float[] toGrayscaleArray(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] gray = new float[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b;
        }
        return gray;
    }

    private static double znccVerticalShift(float[] a, float[] b, int width, int height, int off, int stepX, int stepY) {
        if (off == 0) {
            return znccOverlap(a, b, width, height, 0, height, stepX, stepY);
        }
        if (off > 0) {
            return znccOverlap(a, b, width, height, off, height - off, stepX, stepY);
        } else {
            int p = -off;
            return znccOverlapShiftA(a, b, width, height, p, height - p, stepX, stepY);
        }
    }

    private static double znccOverlap(float[] a, float[] b, int width, int height, int offStartB, int overlapH, int stepX, int stepY) {
        if (overlapH <= 4) return -2.0;
        double sumA = 0, sumB = 0, sumAA = 0, sumBB = 0, sumAB = 0;
        int count = 0;
        int startRowA = 0;
        int startRowB = offStartB;
        int endRowA = overlapH;
        for (int yA = startRowA, yB = startRowB; yA < endRowA; yA += stepY, yB += stepY) {
            int idxA = yA * width;
            int idxB = yB * width;
            for (int x = 0; x < width; x += stepX) {
                double ia = a[idxA + x];
                double ib = b[idxB + x];
                sumA += ia;
                sumB += ib;
                sumAA += ia * ia;
                sumBB += ib * ib;
                sumAB += ia * ib;
                count++;
            }
        }
        if (count == 0) return -2.0;
        double meanA = sumA / count;
        double meanB = sumB / count;
        double varA = sumAA / count - meanA * meanA;
        double varB = sumBB / count - meanB * meanB;
        double denom = varA * varB;
        if (denom <= 1e-6) return -2.0;
        double cov = sumAB / count - meanA * meanA - meanB * meanB + meanA * meanB; // corrected below
        // Note: cov should be E[ab] - E[a]E[b]
        cov = (sumAB / count) - meanA * meanB;
        double ncc = cov / Math.sqrt(denom);
        if (ncc > 1.0) ncc = 1.0; else if (ncc < -1.0) ncc = -1.0;
        return ncc;
    }

    private static double znccOverlapShiftA(float[] a, float[] b, int width, int height, int offStartA, int overlapH, int stepX, int stepY) {
        if (overlapH <= 4) return -2.0;
        double sumA = 0, sumB = 0, sumAA = 0, sumBB = 0, sumAB = 0;
        int count = 0;
        int startRowA = offStartA;
        int startRowB = 0;
        int endRowA = offStartA + overlapH;
        for (int yA = startRowA, yB = startRowB; yA < endRowA; yA += stepY, yB += stepY) {
            int idxA = yA * width;
            int idxB = yB * width;
            for (int x = 0; x < width; x += stepX) {
                double ia = a[idxA + x];
                double ib = b[idxB + x];
                sumA += ia;
                sumB += ib;
                sumAA += ia * ia;
                sumBB += ib * ib;
                sumAB += ia * ib;
                count++;
            }
        }
        if (count == 0) return -2.0;
        double meanA = sumA / count;
        double meanB = sumB / count;
        double varA = sumAA / count - meanA * meanA;
        double varB = sumBB / count - meanB * meanB;
        double denom = varA * varB;
        if (denom <= 1e-6) return -2.0;
        double cov = (sumAB / count) - meanA * meanB;
        double ncc = cov / Math.sqrt(denom);
        if (ncc > 1.0) ncc = 1.0; else if (ncc < -1.0) ncc = -1.0;
        return ncc;
    }

    private static Bitmap computeJoinAndGrow(Bitmap currentResult, Bitmap next, OffsetResult off, StitchOptions options) {
        int width = currentResult.getWidth();
        int height = next.getHeight();
        int offset = clamp(off.offsetPx, -(height - 1), height - 1);

        int overlapH = (offset >= 0) ? height - offset : height + offset;
        overlapH = clamp(overlapH, 0, Math.min(height, currentResult.getHeight()));

        int alignTopYInResult = currentResult.getHeight() - overlapH;
        if (overlapH <= 0) {
            Bitmap grown = Bitmap.createBitmap(width, currentResult.getHeight() + height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(grown);
            canvas.drawBitmap(currentResult, 0, 0, null);
            canvas.drawBitmap(next, 0, currentResult.getHeight(), null);
            return grown;
        }

        int seamRowInOverlap = findSeamRow(currentResult, next, alignTopYInResult, overlapH);
        int blendBand = Math.max(0, options.blendBandPx);
        int seamStartInResult = clamp(alignTopYInResult + seamRowInOverlap - blendBand / 2, 0, currentResult.getHeight());
        int seamEndInResult = clamp(seamStartInResult + blendBand, 0, currentResult.getHeight());

        int newHeight = Math.max(currentResult.getHeight(), alignTopYInResult + seamRowInOverlap + (height - seamRowInOverlap));
        Bitmap grown = Bitmap.createBitmap(width, newHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(grown);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // draw current result
        canvas.drawBitmap(currentResult, 0, 0, null);

        // blend band
        if (blendBand > 0 && seamStartInResult < seamEndInResult) {
            int bandHeight = seamEndInResult - seamStartInResult;
            int[] rowPrev = new int[width];
            int[] rowNext = new int[width];
            int[] blended = new int[width];
            for (int y = 0; y < bandHeight; y++) {
                float alpha = bandHeight <= 1 ? 1f : (y / (float) (bandHeight - 1));
                int srcYPrev = seamStartInResult + y;
                int srcYNext = srcYPrev - alignTopYInResult;
                currentResult.getPixels(rowPrev, 0, width, 0, srcYPrev, width, 1);
                next.getPixels(rowNext, 0, width, 0, srcYNext, width, 1);
                blendRows(rowPrev, rowNext, alpha, blended);
                grown.setPixels(blended, 0, width, 0, srcYPrev, width, 1);
            }
        }

        // draw tail of next
        int tailStartInNext = Math.max(0, seamRowInOverlap + (blendBand + 1) / 2);
        int destYInResult = alignTopYInResult + tailStartInNext;
        if (tailStartInNext < height && destYInResult < grown.getHeight()) {
            int remainingH = Math.min(height - tailStartInNext, grown.getHeight() - destYInResult);
            if (remainingH > 0) {
                Bitmap tail = Bitmap.createBitmap(next, 0, tailStartInNext, width, remainingH);
                canvas.drawBitmap(tail, 0, destYInResult, paint);
            }
        }
        return grown;
    }

    private static int findSeamRow(Bitmap currentResult, Bitmap next, int alignTopYInResult, int overlapH) {
        int width = currentResult.getWidth();
        int x0 = Math.round(width * 0.1f);
        int x1 = Math.max(x0 + 1, Math.round(width * 0.9f));
        int[] rowPrev = new int[x1 - x0];
        int[] rowNext = new int[x1 - x0];
        long bestScore = Long.MAX_VALUE;
        int bestRow = 0;
        for (int y = 0; y < overlapH; y++) {
            int srcYPrev = alignTopYInResult + y;
            int srcYNext = y;
            currentResult.getPixels(rowPrev, 0, x1 - x0, x0, srcYPrev, x1 - x0, 1);
            next.getPixels(rowNext, 0, x1 - x0, x0, srcYNext, x1 - x0, 1);
            long sum = 0L;
            for (int i = 0; i < rowPrev.length; i++) {
                sum += colorDiff(rowPrev[i], rowNext[i]);
            }
            if (sum < bestScore) {
                bestScore = sum;
                bestRow = y;
            }
        }
        return bestRow;
    }

    private static int colorDiff(int a, int b) {
        int ra = (a >> 16) & 0xFF;
        int ga = (a >> 8) & 0xFF;
        int ba = a & 0xFF;
        int rb = (b >> 16) & 0xFF;
        int gb = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        return Math.abs(ra - rb) + Math.abs(ga - gb) + Math.abs(ba - bb);
    }

    private static void blendRows(int[] prev, int[] next, float alpha, int[] out) {
        float a = Math.max(0f, Math.min(1f, alpha));
        float ia = 1f - a;
        for (int i = 0; i < prev.length; i++) {
            int p = prev[i];
            int n = next[i];
            int pr = (p >> 16) & 0xFF;
            int pg = (p >> 8) & 0xFF;
            int pb = p & 0xFF;
            int nr = (n >> 16) & 0xFF;
            int ng = (n >> 8) & 0xFF;
            int nb = n & 0xFF;
            int r = clamp(Math.round(pr * ia + nr * a), 0, 255);
            int g = clamp(Math.round(pg * ia + ng * a), 0, 255);
            int b = clamp(Math.round(pb * ia + nb * a), 0, 255);
            out[i] = Color.rgb(r, g, b);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

