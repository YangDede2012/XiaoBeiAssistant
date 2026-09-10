package com.xiaobei.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.xiaobei.assistant.service.WakeWordService;
import com.xiaobei.assistant.util.ModelManager;
import com.xiaobei.assistant.util.Prefs;

/** 小北助手主界面 */
public class MainActivity extends Activity implements WakeWordService.UiSink {
    private static final int RC_PERM = 1000;
    private TextView tvStatus, tvModel, tvConv;
    private Button btnDown, btnStart, btnStop, btnAuto;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        tvStatus = findViewById(R.id.tvStatus);
        tvModel = findViewById(R.id.tvModel);
        tvConv = findViewById(R.id.tvConv);
        btnDown = findViewById(R.id.btnDownload);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnAuto = findViewById(R.id.btnAuto);
        findViewById(R.id.btnApi).setOnClickListener(v -> keyDialog());
        btnDown.setOnClickListener(v -> download());
        btnStart.setOnClickListener(v -> startAs());
        btnStop.setOnClickListener(v -> stopAs());
        btnAuto.setOnClickListener(v -> toggleAuto());
        refreshAuto();
        refreshModel();
        // 若服务已在运行，直接显示停止按钮
        if (WakeWordService.running) {
            setRunningUI(true);
            tvStatus.setText("小北监听运行中…");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        WakeWordService.bindUi(this);
    }

    @Override
    public void state(String t, int c) {
        runOnUiThread(() -> {
            tvStatus.setText(t);
            // 只要引擎还在(非致命错误状态)就显示"停止"，包括待命 ST_WAKE 也是常驻运行
            boolean on = (c != WakeWordService.ST_ERR);
            setRunningUI(on);
        });
    }

    @Override
    public void line(String t) {
        runOnUiThread(() -> append(t));
    }

    @Override
    public void model(String t) {
        runOnUiThread(() -> tvModel.setText(t));
    }

    private void setRunningUI(boolean on) {
        btnStart.setVisibility(on ? View.GONE : View.VISIBLE);
        btnStop.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void download() {
        if (!ensurePerm()) return;
        btnDown.setEnabled(false);
        tvModel.setText("正在下载语音模型…(约42MB)");
        new Thread(() -> {
            try {
                ModelManager.downloadAndExtract(this, (p, m) ->
                        runOnUiThread(() -> tvModel.setText(m + " " + p + "%")));
                runOnUiThread(() -> {
                    tvModel.setText("模型就绪，可以开启随叫随到");
                    append("✔ 语音模型下载完成");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvModel.setText("下载失败：" + e.getMessage());
                    append("✘ 模型下载失败");
                });
            } finally {
                runOnUiThread(() -> btnDown.setEnabled(true));
            }
        }).start();
    }

    private void startAs() {
        if (!ensurePerm()) return;
        if (!ModelManager.isModelReady(this)) {
            append("请先下载离线语音模型（按钮①）");
            return;
        }
        startService(new Intent(this, WakeWordService.class));
        setRunningUI(true);
        tvStatus.setText("小北启动中…请稍候");
        append("★ 小北开始随叫随到…");
    }

    private void stopAs() {
        Intent i = new Intent(this, WakeWordService.class);
        i.putExtra("want_stop", true);
        startService(i);
        WakeWordService.running = false;
        setRunningUI(false);
        tvStatus.setText("已停止（省电）");
        append("■ 已停止监听");
    }

    private void keyDialog() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setHint("sk-...");
        e.setText(Prefs.getApiKey(this));
        new AlertDialog.Builder(this)
                .setTitle("千问API Key")
                .setMessage("前往阿里云百炼 bailian.aliyun.com 免费创建")
                .setView(e)
                .setPositiveButton("保存", (d, w) -> {
                    Prefs.setApiKey(this, e.getText().toString().trim());
                    append("✔ API Key 已保存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toggleAuto() {
        boolean on = prefs().getBoolean("auto_start", false);
        on = !on;
        prefs().edit().putBoolean("auto_start", on).apply();
        refreshAuto();
        append(on ? "✔ 已开启开机自启" : "✘ 已关闭开机自启");
    }

    private void refreshAuto() {
        boolean on = prefs().getBoolean("auto_start", false);
        btnAuto.setText("开机自启：" + (on ? "开 ✔" : "关"));
    }

    private void refreshModel() {
        tvModel.setText(ModelManager.isModelReady(this)
                ? "离线语音模型：已就绪 ✔"
                : "离线语音模型：未下载");
    }

    private void append(String s) {
        String old = tvConv.getText().toString();
        if (old.length() > 3000) old = "";
        tvConv.setText(old + s + "\n");
    }

    private boolean ensurePerm() {
        if (Build.VERSION.SDK_INT < 23) return true;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RC_PERM);
            return false;
        }
        return true;
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("xiaobei_prefs", MODE_PRIVATE);
    }
}
