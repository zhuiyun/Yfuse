package com.yfuse.cutoutprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Runs on JDK 17 without Android, a Google model or replacement inference. */
public final class MaskAnalysisTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid input accepted");
    }

    public static void main(String[] paths) throws Exception {
        // These ranges reproduce the real OPPO report; do not reinterpret them as roundoff.
        float[] raw = {-0.17613558f, -0.000003f, 0f, 0.5f, 1f, 1.0000001f, 1.1854275f};
        float[] original = raw.clone();
        MaskAnalysis.Stats stats = MaskAnalysis.analyze(raw);
        check(stats.nonFinite == 0 && stats.outsideUnitRange == 4 && stats.beyondRoundoff == 2,
                "range anomaly must remain visible");
        float[] clipped = MaskAnalysis.clipForPreview(raw);
        check(Arrays.equals(raw, original), "diagnostic preview changed raw mask");
        check(Arrays.equals(clipped, new float[]{0, 0, 0, .5f, 1, 1, 1}), "preview clipping");
        ByteBuffer bytes = ByteBuffer.wrap(MaskAnalysis.encode(raw)).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : raw) check(Float.floatToRawIntBits(value) == Float.floatToRawIntBits(bytes.getFloat()),
                "raw Float32 bytes changed");
        rejects(() -> MaskAnalysis.clipForPreview(new float[]{Float.NaN}));
        rejects(() -> MaskAnalysis.clipForPreview(new float[]{Float.POSITIVE_INFINITY}));
        rejects(() -> MaskAnalysis.clipForPreview(new float[]{Float.NEGATIVE_INFINITY}));
        rejects(() -> MaskAnalysis.clipForPreview(new float[]{}));
        int[] same = {0x00123456, 0x80123456, 0xff123456};
        MaskAnalysis.AlphaComparison exact = MaskAnalysis.compareAlpha(new float[]{0, .5f, 1}, same);
        check(exact.meanAbsoluteError == 0 && exact.maximumError == 0 && exact.intersectionOverUnion == 1,
                "identical alpha comparison");
        MaskAnalysis.AlphaComparison different = MaskAnalysis.compareAlpha(new float[]{0, 1}, new int[]{-1, 0});
        check(different.meanAbsoluteError == 255 && different.intersectionOverUnion == 0
                && different.disagreementFraction == 1, "different alpha comparison");
        rejects(() -> MaskAnalysis.compareAlpha(new float[]{0}, new int[2]));
        rejects(() -> MaskAnalysis.compareAlpha(new float[]{Float.NaN}, new int[1]));
        for (String path : paths) {
            byte[] data = Files.readAllBytes(Path.of(path));
            check(data.length > 0 && data.length % 4 == 0, "Float32 size");
            float[] values = new float[data.length / 4];
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
            MaskAnalysis.Stats real = MaskAnalysis.analyze(values);
            check(real.nonFinite == 0 && real.beyondRoundoff > 0, "device fixture regression");
            check(Arrays.equals(data, MaskAnalysis.encode(values)), "device fixture raw preservation");
            MaskAnalysis.Stats recovered = MaskAnalysis.analyze(MaskAnalysis.clipForPreview(values));
            check(recovered.nonFinite == 0 && recovered.outsideUnitRange == 0, "device preview recovery");
            System.out.println(Path.of(path).getFileName() + ": raw preserved; preview recovered; outside="
                    + real.outsideUnitRange + "; beyondRoundoff=" + real.beyondRoundoff);
        }
        System.out.println("MaskAnalysis regression checks passed");
    }
}
