package com.xiaobei.assistant.util;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 语音模型管理器：下载并解压 Vosk 离线中文模型 vosk-model-small-cn-0.22 (~42MB)
 * 最终位置: filesDir/vosk-model-small-cn-0.22/conf/mfcc.conf
 */
public class ModelManager {
    public static final String MODEL_DIR = "vosk-model-small-cn-0.22";
    private static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip";
    private static final String TMP_ROOT = "model_tmp";
    private static final String ZIP_NAME = "model_dl.zip";

    public interface ProgressListener {
        void onProgress(int percent, String msg);
    }

    public static boolean isModelReady(Context ctx) {
        return new File(new File(ctx.getFilesDir(), MODEL_DIR), "conf/mfcc.conf").exists();
    }

    public static String getModelPath(Context ctx) {
        return new File(ctx.getFilesDir(), MODEL_DIR).getAbsolutePath();
    }

    /** 阻塞方法，需要放到子线程 */
    public static void downloadAndExtract(Context ctx, ProgressListener p) throws IOException {
        File root = ctx.getFilesDir();
        File zip = new File(root, ZIP_NAME);

        if (!(zip.exists() && zip.length() > 5_000_000)) {
            download(zip, p);
        }
        File tmp = new File(root, TMP_ROOT);
        if (tmp.exists()) deleteAll(tmp);
        if (!tmp.mkdirs()) throw new IOException("无法创建临时目录");
        p.onProgress(100, "正在解压模型...");
        unzip(zip, tmp);

        File real = findConf(tmp);
        if (real == null) {
            deleteAll(tmp);
            throw new IOException("模型文件异常，请重新下载");
        }

        File finalDir = new File(root, MODEL_DIR);
        if (finalDir.exists()) deleteAll(finalDir);
        if (real.equals(tmp)) tmp.renameTo(finalDir);
        else real.renameTo(finalDir);
        deleteAll(tmp);
        if (zip.exists()) zip.delete();
        p.onProgress(100, "模型安装完成");
    }

    private static void download(File zip, ProgressListener p) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(30000);
        c.connect();
        if (c.getResponseCode() != 200) {
            throw new IOException("下载失败 HTTP " + c.getResponseCode());
        }
        long total = c.getContentLengthLong();
        long done = 0;
        byte[] b = new byte[16384];
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(zip))) {
            int n;
            while ((n = in.read(b)) != -1) {
                out.write(b, 0, n);
                done += n;
                if (total > 0 && p != null)
                    p.onProgress((int) (done * 100 / total), "下载中文模型(42MB)...");
            }
        }
    }

    private static void unzip(File zip, File dest) throws IOException {
        try (ZipInputStream zi = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            byte[] b = new byte[16384];
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.indexOf('/');
                String top = slash > 0 ? name.substring(0, slash) : "";
                String clean = top.equals(MODEL_DIR) ? name.substring(slash + 1) : name;
                if (clean.isEmpty()) continue;
                File out = new File(dest, clean);
                if (e.isDirectory()) out.mkdirs();
                else {
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    try (OutputStream o = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = zi.read(b)) != -1) o.write(b, 0, n);
                    }
                }
                zi.closeEntry();
            }
        }
    }

    private static File findConf(File dir) {
        if (new File(dir, "conf/mfcc.conf").exists()) return dir;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory()) {
                File f = findConf(k);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static void deleteAll(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteAll(k);
        }
        f.delete();
    }
}
