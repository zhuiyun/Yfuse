package com.yfuse.offlineprobe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Debug;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.ByteBufferExtractor;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.*;

public final class ProbeRunner {
    public interface Progress { void update(String value); }
    public record Sample(String id, String title, Bitmap bitmap) { }
    private record Mask(float[] raw, int width, int height) { }
    private interface Engine extends AutoCloseable { Mask infer(Bitmap bitmap) throws Exception; void close() throws Exception; }

    public static JSONObject manifest(Context context) throws Exception {
        try (InputStream input = context.getAssets().open("manifest.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); copy(input, bytes);
            return new JSONObject(bytes.toString("UTF-8"));
        }
    }
    public static List<Sample> builtIns(Context context) throws Exception {
        JSONArray specs = manifest(context).getJSONArray("samples");
        List<Sample> result = new ArrayList<>();
        for (int i = 0; i < specs.length(); i++) {
            JSONObject spec = specs.getJSONObject(i);
            try (InputStream input = context.getAssets().open(spec.getString("file"))) {
                result.add(new Sample(spec.getString("id"), spec.getString("title"), limit(BitmapFactory.decodeStream(input))));
            }
        }
        return result;
    }
    public static Bitmap limit(Bitmap source) {
        if (source == null) throw new IllegalArgumentException("无法解码图片");
        int edge = Math.max(source.getWidth(), source.getHeight());
        if (edge <= 1600) return source;
        float ratio = 1600f / edge;
        Bitmap resized = Bitmap.createScaledBitmap(source, Math.round(source.getWidth() * ratio), Math.round(source.getHeight() * ratio), true);
        source.recycle(); return resized;
    }
    public static File out(Context context) { File root = new File(context.getFilesDir(), "offline-probe"); root.mkdirs(); return root; }
    private static File model(Context context, String name) throws Exception {
        File file = new File(context.getNoBackupFilesDir(), name);
        if (!file.isFile()) {
            try (InputStream input = context.getAssets().open(name); OutputStream output = new FileOutputStream(file)) { copy(input, output); }
        }
        return file;
    }
    private static Engine engine(Context context, JSONObject spec, JSONObject report) throws Exception {
        File file = model(context, spec.getString("file"));
        if (spec.getString("runtime").equals("tflite")) {
            ImageSegmenter.ImageSegmenterOptions options = ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(spec.getString("file")).setDelegate(Delegate.CPU).build())
                    .setRunningMode(RunningMode.IMAGE).setOutputCategoryMask(false).setOutputConfidenceMasks(true).build();
            final ImageSegmenter segmenter = ImageSegmenter.createFromOptions(context, options);
            report.put("runtime", "MediaPipe Tasks CPU 0.10.21 (official custom-op support)").put("threads", "MediaPipe default");
            return new Engine() {
                public Mask infer(Bitmap bitmap) throws Exception {
                    // MPImage.close() owns/recycles the bitmap; retain the test source.
                    MPImage input = new BitmapImageBuilder(bitmap.copy(Bitmap.Config.ARGB_8888, false)).build();
                    try {
                        ImageSegmenterResult result = segmenter.segment(input);
                        List<MPImage> masks = result.confidenceMasks().orElseThrow(() -> new IllegalStateException("Missing confidence masks"));
                        if (masks.size() != 1) throw new IllegalStateException("Expected one foreground channel: " + masks.size());
                        MPImage output = masks.get(0);
                        try {
                            ByteBuffer buffer = ByteBufferExtractor.extract(output).order(ByteOrder.nativeOrder());
                            float[] raw = new float[output.getWidth() * output.getHeight()]; buffer.asFloatBuffer().get(raw);
                            return new Mask(raw, output.getWidth(), output.getHeight());
                        } finally { for (MPImage mask : masks) mask.close(); }
                    } finally { input.close(); }
                }
                public void close() { segmenter.close(); }
            };
        }
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
        final OrtSession session;
        try { session = environment.createSession(file.getAbsolutePath(), options); } finally { options.close(); }
        JSONArray shapeSpec = spec.getJSONArray("input_shape");
        long[] shape = new long[4]; for (int i = 0; i < 4; i++) shape[i] = shapeSpec.getLong(i);
        report.put("actual_input_shape", new JSONArray(shape));
        return new Engine() {
            public Mask infer(Bitmap bitmap) throws Exception {
                int height = (int) shape[2], width = (int) shape[3];
                float[] rgb = pixels(bitmap, width, height, spec.getString("norm"), true);
                try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(rgb), shape);
                     OrtSession.Result result = session.run(Collections.singletonMap(spec.getString("input_name"), input))) {
                    OnnxTensor output = (OnnxTensor) result.get(0); long[] dims = output.getInfo().getShape();
                    report.put("actual_output_shape", new JSONArray(dims));
                    int outHeight = (int) dims[dims.length - 2], outWidth = (int) dims[dims.length - 1];
                    FloatBuffer values = output.getFloatBuffer();
                    if (spec.getString("alpha").equals("channel1")) values.position(outHeight * outWidth);
                    float[] raw = new float[outHeight * outWidth]; values.get(raw);
                    return new Mask(raw, outWidth, outHeight);
                }
            }
            public void close() throws Exception { session.close(); }
        };
    }
    private static float[] pixels(Bitmap original, int width, int height, String norm, boolean nchw) {
        Bitmap scaled = Bitmap.createScaledBitmap(original, width, height, true);
        int count = width * height; int[] argb = new int[count]; scaled.getPixels(argb, 0, width, 0, 0, width, height);
        if (scaled != original) scaled.recycle();
        float[] result = new float[count * 3]; float divisor = 255;
        if (norm.equals("u2net")) { divisor = 0; for (int value : argb) divisor = Math.max(divisor, Math.max((value >> 16) & 255, Math.max((value >> 8) & 255, value & 255))); divisor = Math.max(divisor, 1); }
        float[] means = {.485f, .456f, .406f}, stds = {.229f, .224f, .225f};
        for (int i = 0; i < count; i++) for (int c = 0; c < 3; c++) {
            float value = ((argb[i] >> (16 - 8 * c)) & 255) / divisor;
            if (norm.equals("half")) value = (value - .5f) / .5f;
            else if (norm.equals("imagenet") || norm.equals("u2net")) value = (value - means[c]) / stds[c];
            result[nchw ? c * count + i : i * 3 + c] = value;
        }
        return result;
    }
    public static JSONObject run(Context context, String id, List<Sample> samples, Progress progress) throws Exception {
        JSONObject all = manifest(context), spec = null;
        JSONArray models = all.getJSONArray("models");
        for (int i = 0; i < models.length(); i++) if (models.getJSONObject(i).getString("id").equals(id)) spec = models.getJSONObject(i);
        if (spec == null) throw new IllegalArgumentException("Unknown model: " + id);
        File directory = new File(out(context), id); directory.mkdirs();
        File[] previous = directory.listFiles();
        if (previous != null) for (File generated : previous) if (generated.isFile() && !generated.delete()) throw new IOException("Cannot clear old model output");
        JSONObject report = new JSONObject().put("model", spec).put("version", BuildConfig.VERSION_NAME)
                .put("device", Build.MANUFACTURER + " " + Build.MODEL).put("fingerprint", Build.FINGERPRINT)
                .put("abis", new JSONArray(Build.SUPPORTED_ABIS)).put("sdk", Build.VERSION.SDK_INT)
                .put("internet_permission", context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED)
                .put("timing_includes", "resize+normalization+tensor allocation+CPU inference+output copy; excludes file saving and model initialization")
                .put("threads", 2).put("pid", android.os.Process.myPid()).put("quality_status", "requires_visual_review");
        JSONArray results = new JSONArray(); report.put("samples", results); writeJson(new File(directory, "report.json"), report);
        try {
            progress.update(spec.getString("title") + "：加载模型");
            long started = SystemClock.elapsedRealtimeNanos();
            try (Engine engine = engine(context, spec, report)) {
                report.put("initialization_ms", elapsed(started)); report.put("memory_after_load", memory());
                for (Sample sample : samples) {
                    progress.update(spec.getString("title") + "：" + sample.title());
                    JSONObject item = new JSONObject().put("id", sample.id()).put("title", sample.title())
                            .put("width", sample.bitmap().getWidth()).put("height", sample.bitmap().getHeight());
                    results.put(item); writeJson(new File(directory, "report.json"), report);
                    engine.infer(sample.bitmap()); // one unmeasured warmup per sample
                    double[] times = new double[3]; Mask mask = null;
                    for (int i = 0; i < times.length; i++) { long t = SystemClock.elapsedRealtimeNanos(); mask = engine.infer(sample.bitmap()); times[i] = elapsed(t); }
                    double[] sorted = times.clone(); Arrays.sort(sorted);
                    item.put("pipeline_ms", new JSONArray(times)).put("median_pipeline_ms", sorted[1]);
                    saveMask(directory, sample, mask, spec.getString("alpha"), item);
                    item.put("memory", memory()).put("status", "output_created");
                    writeJson(new File(directory, "report.json"), report);
                }
                report.put("status", "outputs_created");
            }
        } catch (Throwable failure) {
            report.put("status", "failed").put("error", failure.toString());
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace)); report.put("trace", trace.toString());
        }
        report.put("memory_final", memory()); writeJson(new File(directory, "report.json"), report);
        return report;
    }
    private static double elapsed(long nanos) { return (SystemClock.elapsedRealtimeNanos() - nanos) / 1_000_000.0; }
    public static JSONObject memory() {
        JSONObject result = new JSONObject();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("VmRSS:") || line.startsWith("VmHWM:")) result.put(line.split(":")[0] + "_kb", Long.parseLong(line.split(":")[1].trim().split("\\s+")[0]));
            }
            result.put("pss_kb", Debug.getPss()).put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize());
        } catch (Exception ignored) { }
        return result;
    }
    private static void saveMask(File directory, Sample sample, Mask mask, String transform, JSONObject item) throws Exception {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY; int nonfinite = 0;
        ByteBuffer rawBytes = ByteBuffer.allocate(mask.raw().length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : mask.raw()) { rawBytes.putFloat(v); if (Float.isFinite(v)) { min = Math.min(min, v); max = Math.max(max, v); } else nonfinite++; }
        try (FileOutputStream stream = new FileOutputStream(new File(directory, sample.id() + "-raw.f32"))) { stream.write(rawBytes.array()); }
        item.put("mask_width", mask.width()).put("mask_height", mask.height()).put("nonfinite", nonfinite);
        if (Float.isFinite(min)) item.put("raw_min", min); if (Float.isFinite(max)) item.put("raw_max", max);
        if (nonfinite > 0) throw new IllegalStateException("Nonfinite model output; raw values preserved");
        int[] gray = new int[mask.raw().length]; int count = 0, clipped = 0;
        for (int i = 0; i < gray.length; i++) {
            float value = mask.raw()[i];
            if (transform.equals("sigmoid")) value = (float) (1 / (1 + Math.exp(-value)));
            else if (transform.equals("minmax")) value = (value - min) / Math.max(max - min, 1e-6f);
            if (value < 0 || value > 1) clipped++;
            value = Math.max(0, Math.min(1, value)); if (value >= .5f) count++;
            int g = Math.round(value * 255); gray[i] = 0xff000000 | g * 0x010101;
        }
        item.put("display_clipped_values", clipped).put("foreground_fraction_at_0_5", count / (double) gray.length);
        Bitmap low = Bitmap.createBitmap(gray, mask.width(), mask.height(), Bitmap.Config.ARGB_8888);
        Bitmap alpha = Bitmap.createScaledBitmap(low, sample.bitmap().getWidth(), sample.bitmap().getHeight(), true);
        png(new File(directory, sample.id() + "-mask.png"), alpha);
        int width = alpha.getWidth(), height = alpha.getHeight(); int[] pixels = new int[width * height], alphas = new int[pixels.length];
        sample.bitmap().getPixels(pixels, 0, width, 0, 0, width, height); alpha.getPixels(alphas, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) pixels[i] = (pixels[i] & 0x00ffffff) | ((alphas[i] & 255) << 24);
        Bitmap foreground = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        png(new File(directory, sample.id() + "-foreground.png"), foreground);
        File source = new File(outFileRoot(directory), sample.id() + "-source.png"); png(source, sample.bitmap());
        foreground.recycle(); if (alpha != low) alpha.recycle(); low.recycle();
    }
    private static File outFileRoot(File directory) { return directory.getParentFile(); }
    private static void copy(InputStream input, OutputStream output) throws IOException { byte[] bytes = new byte[65536]; for (int count; (count = input.read(bytes)) != -1;) output.write(bytes, 0, count); }
    private static void png(File file, Bitmap bitmap) throws Exception { try (OutputStream stream = new FileOutputStream(file)) { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("PNG failed"); } }
    private static void writeJson(File file, JSONObject value) throws Exception { try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) { writer.write(value.toString(2)); } }
}
