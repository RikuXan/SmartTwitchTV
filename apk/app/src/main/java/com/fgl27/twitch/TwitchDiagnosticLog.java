package com.fgl27.twitch;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class TwitchDiagnosticLog {
    private static final String TAG = "TwitchLL";
    private static final String SESSION = UUID.randomUUID().toString();
    private static final ArrayBlockingQueue<Record> QUEUE = new ArrayBlockingQueue<>(1024);
    private static final AtomicLong LOST = new AtomicLong();
    private static final int MAX_MESSAGE_BYTES = 3000;
    private static long sequence;
    private static boolean started;

    private TwitchDiagnosticLog() {}

    public static synchronized void initialize(Context context) {
        if (started) return;
        Context application = context.getApplicationContext();
        Thread writer = new Thread(() -> writeFiles(application), "TwitchDiagnosticLog");
        writer.setDaemon(true);
        writer.start();
        started = true;
        i("DIAGNOSTICS-START version=" + BuildConfig.VERSION_NAME + " files=16 fileMiB=8 flushMs=1000");
    }

    public static synchronized void i(String message) {
        long wallMs = System.currentTimeMillis();
        long monoMs = SystemClock.elapsedRealtime();
        byte[] encoded = message.replace("\r", "\\r").replace("\n", "\\n").getBytes(StandardCharsets.UTF_8);
        String body = encoded.length > MAX_MESSAGE_BYTES
            ? new String(encoded, 0, MAX_MESSAGE_BYTES, StandardCharsets.UTF_8) + " [truncated]"
            : new String(encoded, StandardCharsets.UTF_8);
        body +=
            " diagSession=" + SESSION + " diagSeq=" + (++sequence) + " diagWallMs=" + wallMs +
            " diagMonoMs=" + monoMs + " diagLost=" + LOST.get();
        Log.i(TAG, body);
        if (!QUEUE.offer(new Record(wallMs, Process.myTid(), body))) LOST.incrementAndGet();
    }

    private static void writeFiles(Context context) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        TwitchDiagnosticFile file = null;
        long retryAtMs = 0;
        long lastFlushMs = SystemClock.elapsedRealtime();
        int unflushed = 0;
        while (true) {
            try {
                Record record = QUEUE.poll(1000, TimeUnit.MILLISECONDS);
                long nowMs = SystemClock.elapsedRealtime();
                if (record != null) {
                    unflushed++;
                    if (file == null) {
                        if (nowMs < retryAtMs) {
                            LOST.addAndGet(unflushed);
                            unflushed = 0;
                            continue;
                        }
                        File root = context.getExternalFilesDir(null);
                        if (root == null) throw new IOException("External files storage unavailable");
                        file = new TwitchDiagnosticFile(new File(root, "logs"), SESSION, 8 * 1024 * 1024, 16);
                    }
                    file.append(format.format(new Date(record.wallMs)) + " " + Process.myPid() + " " + record.tid +
                        " I " + TAG + ": " + record.body);
                }
                if (file != null && nowMs - lastFlushMs >= 1000) {
                    file.flush();
                    unflushed = 0;
                    lastFlushMs = nowMs;
                }
            } catch (IOException | RuntimeException error) {
                LOST.addAndGet(unflushed);
                unflushed = 0;
                if (file != null) {
                    try { file.close(); } catch (IOException ignored) {}
                    file = null;
                }
                retryAtMs = SystemClock.elapsedRealtime() + 30000;
                Log.w(TAG, "DIAGNOSTICS-FILE-ERROR retryMs=30000 lost=" + LOST.get(), error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                if (file != null) {
                    try { file.close(); } catch (IOException ignored) {}
                }
                return;
            }
        }
    }

    private static final class Record {
        final long wallMs;
        final int tid;
        final String body;

        Record(long wallMs, int tid, String body) {
            this.wallMs = wallMs;
            this.tid = tid;
            this.body = body;
        }
    }
}
