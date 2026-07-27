package net.alpas.crystalplanner.util;

import android.content.Context;

import net.alpas.crystalplanner.R;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class SyncLog {
    private static final long MAX_BYTES = 120_000;
    private final Context context;
    private final File file;
    private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    public SyncLog(Context context) {
        this.context = context.getApplicationContext();
        file = new File(this.context.getFilesDir(), "crystal-planner-sync.log");
    }

    public synchronized void info(String message) {
        append("INFO", message);
    }

    public synchronized void warn(String message) {
        append("WARN", message);
    }

    public synchronized void error(String message) {
        append("ERROR", message);
    }

    private void append(String level, String message) {
        try {
            trimIfNeeded();
            String line = format.format(new Date()) + " [" + level + "] " + message + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private void trimIfNeeded() throws Exception {
        if (!file.exists() || file.length() <= MAX_BYTES) return;
        byte[] all = java.nio.file.Files.readAllBytes(file.toPath());
        int keep = Math.min(all.length, 60_000);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(all, all.length - keep, keep);
        }
    }


    public synchronized void clear() {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            // Truncate the previous cycle so the UI only shows the latest run.
        } catch (Exception ignored) {
        }
    }

    public synchronized String readTail() {
        try {
            if (!file.exists()) return context.getString(R.string.log_empty);
            byte[] all = java.nio.file.Files.readAllBytes(file.toPath());
            int keep = Math.min(all.length, 32_000);
            return new String(all, all.length - keep, keep, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return context.getString(R.string.log_read_failed, e.getMessage());
        }
    }
}
