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

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private TextView status;
    private Button start;
    private Button share;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding * 2, padding, padding);
        scroll.addView(content);
        TextView title = new TextView(this);
        title.setText("ML Kit 主体抠图验证");
        title.setTextSize(24);
        content.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText("独立测试工具，不会替换 Yfuse。使用《鲸口脱险》和《野兽之心》的原始海报。首次运行需要 Google Play 服务联网下载模型，最长等待 10 分钟；海报在本机处理。");
        explanation.setTextSize(16);
        content.addView(explanation);
        start = new Button(this);
        start.setText("开始验证两张海报");
        content.addView(start);
        share = new Button(this);
        share.setText("分享验证报告和结果");
        share.setEnabled(false);
        content.addView(share);
        status = new TextView(this);
        status.setText("等待开始");
        status.setTextSize(15);
        content.addView(status);
        start.setOnClickListener(view -> {
            start.setEnabled(false);
            share.setEnabled(false);
            while (content.getChildCount() > 5) content.removeViewAt(5);
            status.setText("正在检查 Google Play 服务……");
            worker.execute(() -> {
                JSONObject report = new ProbeRunner(getApplicationContext(), message -> runOnUiThread(() -> {
                    if (!isDestroyed()) status.setText(message);
                })).run();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    status.append("\n\n状态：" + report.optString("status")
                            + "\nGoogle 服务：" + report.optString("google_play_services_version")
                            + "\n" + report.optString("error", "")
                            + "\n" + report.optString("case_errors", ""));
                    start.setEnabled(true);
                    share.setEnabled(true);
                    if (report.optInt("valid_output_count") > 0) showResults();
                });
            });
        });
        share.setOnClickListener(view -> shareReport());
        setContentView(scroll);
    }

    private void showResults() {
        File directory = new File(getFilesDir(), "mlkit-probe");
        for (String name : ProbeRunner.CASES) {
            TextView label = new TextView(this);
            label.setText(name.equals("whalefall") ? "鲸口脱险：原图 / 灰底前景" : "野兽之心：原图 / 灰底前景");
            label.setTextSize(18);
            content.addView(label);
            for (String suffix : new String[]{"-source.png", "-foreground.png"}) {
                Bitmap bitmap = BitmapFactory.decodeFile(new File(directory, name + suffix).getPath());
                if (bitmap == null) continue;
                if (suffix.equals("-foreground.png")) {
                    Bitmap gray = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(gray);
                    canvas.drawColor(Color.rgb(205, 205, 205));
                    canvas.drawBitmap(bitmap, 0, 0, null);
                    bitmap.recycle();
                    bitmap = gray;
                }
                ImageView image = new ImageView(this);
                image.setImageBitmap(bitmap);
                image.setAdjustViewBounds(true);
                image.setContentDescription(label.getText() + suffix);
                content.addView(image);
            }
        }
    }

    private void shareReport() {
        try {
            File directory = new File(getFilesDir(), "mlkit-probe");
            File zip = new File(getCacheDir(), "mlkit-probe-report.zip");
            try (ZipOutputStream archive = new ZipOutputStream(new FileOutputStream(zip))) {
                byte[] buffer = new byte[16384];
                for (File file : directory.listFiles()) {
                    if (!file.isFile()) continue;
                    archive.putNextEntry(new ZipEntry(file.getName()));
                    try (FileInputStream stream = new FileInputStream(file)) {
                        int count;
                        while ((count = stream.read(buffer)) != -1) archive.write(buffer, 0, count);
                    }
                    archive.closeEntry();
                }
            }
            Uri uri = Uri.parse("content://com.yfuse.cutoutprobe.report/report.zip");
            Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
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
