package com.yfuse.cutoutprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class MainActivity extends Activity {
    private static final int PICK_IMAGES = 12, MAX_IMAGES = 8;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout results;
    private TextView status;
    private Button start, select, share;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding * 2, padding, padding);
        scroll.addView(content);
        TextView title = new TextView(this);
        title.setText("ML Kit 主体抠图验证 " + BuildConfig.VERSION_NAME);
        title.setTextSize(23);
        content.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText("独立测试工具。默认验证《鲸口脱险》和《野兽之心》，也可选择最多 8 张其他海报。首次运行需 Google Play 服务联网下载模型；图片在本机处理。每次显示原图、遮罩还原和 SDK 前景，结果仅用于检查实际效果。");
        explanation.setTextSize(15);
        content.addView(explanation);
        start = new Button(this);
        start.setText("开始验证两张海报");
        content.addView(start);
        select = new Button(this);
        select.setText("选择其他海报验证（最多 8 张）");
        content.addView(select);
        share = new Button(this);
        share.setText("分享验证报告和结果");
        share.setEnabled(false);
        content.addView(share);
        status = new TextView(this);
        status.setText("等待开始。每次验证会替换上次结果，请先分享需要保留的报告。");
        status.setTextSize(15);
        content.addView(status);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results);
        start.setOnClickListener(view -> runProbe(Collections.emptyList()));
        select.setOnClickListener(view -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(pick, PICK_IMAGES);
        });
        share.setOnClickListener(view -> shareReport());
        setContentView(scroll);
    }

    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (request != PICK_IMAGES || resultCode != RESULT_OK || data == null) return;
        LinkedHashSet<Uri> selected = new LinkedHashSet<>();
        if (data.getClipData() != null) {
            ClipData clips = data.getClipData();
            if (clips.getItemCount() > MAX_IMAGES) {
                status.setText("一次最多选择 8 张海报，请重新选择。");
                return;
            }
            for (int i = 0; i < clips.getItemCount(); i++) {
                Uri uri = clips.getItemAt(i).getUri();
                if (uri != null) selected.add(uri);
            }
        } else if (data.getData() != null) selected.add(data.getData());
        if (!selected.isEmpty()) runProbe(new ArrayList<>(selected));
    }

    private void runProbe(List<Uri> selected) {
        if (!start.isEnabled()) return;
        start.setEnabled(false);
        select.setEnabled(false);
        share.setEnabled(false);
        results.removeAllViews();
        status.setText("正在检查 Google Play 服务……");
        worker.execute(() -> {
            JSONObject report = new ProbeRunner(getApplicationContext(), message -> runOnUiThread(() -> {
                if (!isDestroyed()) status.setText(message);
            })).run(selected);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                status.append("\n\n已导出遮罩预览：" + report.optInt("diagnostic_output_count")
                        + " 张；SDK 前景：" + report.optInt("sdk_output_count") + " 张"
                        + "\n数值越界记录：" + report.optInt("range_warning_count") + " 张"
                        + "\n轮廓和文字残留需人工检查，导出不代表质量通过。"
                        + "\n" + report.optString("error", "")
                        + "\n" + report.optString("case_errors", ""));
                start.setEnabled(true);
                select.setEnabled(true);
                share.setEnabled(!report.optString("status").equals("already_running"));
                showResults(report);
            });
        });
    }

    private void showResults(JSONObject report) {
        File directory = new File(getFilesDir(), "mlkit-probe");
        JSONArray cases = report.optJSONArray("cases");
        if (cases == null) return;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject item = cases.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name");
            TextView label = new TextView(this);
            label.setText(name.equals("whalefall") ? "鲸口脱险" : name.equals("heart-of-the-beast")
                    ? "野兽之心" : "自选海报 " + (i + 1));
            label.setTextSize(19);
            results.addView(label);
            TextView metrics = new TextView(this);
            metrics.setText("耗时 " + item.optLong("inference_ms") + "ms"
                    + (item.has("alpha_mean_absolute_error_255") ? String.format(Locale.ROOT,
                    " · 透明度平均差 %.2f/255", item.optDouble("alpha_mean_absolute_error_255")) : "")
                    + (item.optInt("outside_unit_range_count") > 0 ? "\n存在数值越界，已保留原始记录。" : "")
                    + (item.optBoolean("sdk_degenerate_alpha") ? "\nSDK 前景全空或全满，请检查实际结果。" : "")
                    + (item.has("error") ? "\n" + item.optString("error") : ""));
            results.addView(metrics);
            LinearLayout row = new LinearLayout(this);
            String[] suffixes = {"-source.png", "-foreground.png", "-sdk-foreground.png"};
            String[] headings = {"原图", "遮罩还原", "SDK 前景"};
            for (int column = 0; column < suffixes.length; column++) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                TextView heading = new TextView(this);
                heading.setText(headings[column]);
                cell.addView(heading);
                Bitmap bitmap = thumbnail(new File(directory, name + suffixes[column]), column != 0);
                if (bitmap != null) {
                    ImageView image = new ImageView(this);
                    image.setImageBitmap(bitmap);
                    image.setAdjustViewBounds(true);
                    image.setContentDescription(label.getText() + " " + headings[column]);
                    cell.addView(image, new LinearLayout.LayoutParams(-1, -2));
                } else {
                    TextView missing = new TextView(this);
                    missing.setText("未生成");
                    cell.addView(missing);
                }
                row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
            }
            results.addView(row);
        }
    }

    private Bitmap thumbnail(File file, boolean grayBackground) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / decode.inSampleSize > 512) decode.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), decode);
        if (bitmap == null || !grayBackground) return bitmap;
        Bitmap gray = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(gray);
        canvas.drawColor(Color.rgb(205, 205, 205));
        canvas.drawBitmap(bitmap, 0, 0, null);
        bitmap.recycle();
        return gray;
    }

    private void shareReport() {
        try {
            File directory = new File(getFilesDir(), "mlkit-probe");
            File zip = new File(getCacheDir(), "mlkit-probe-report.zip");
            File[] files = directory.listFiles();
            if (files == null) throw new IllegalStateException("no report files");
            try (ZipOutputStream archive = new ZipOutputStream(new FileOutputStream(zip))) {
                byte[] buffer = new byte[16384];
                for (File file : files) {
                    if (!file.isFile()) continue;
                    archive.putNextEntry(new ZipEntry(file.getName()));
                    try (FileInputStream stream = new FileInputStream(file)) {
                        int count;
                        while ((count = stream.read(buffer)) != -1) archive.write(buffer, 0, count);
                    }
                    archive.closeEntry();
                }
            }
            Uri uri = Uri.parse("content://" + getPackageName() + ".report/report.zip");
            Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri("ML Kit 验证报告", uri));
            startActivity(Intent.createChooser(send, "分享验证结果"));
        } catch (Exception exception) {
            status.append("\n分享失败：" + exception.getClass().getSimpleName());
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }
}
