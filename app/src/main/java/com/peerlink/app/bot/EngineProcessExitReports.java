package com.peerlink.app.bot;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Read Android's own exit records, including records published after Binder disconnects. */
public final class EngineProcessExitReports {
    private EngineProcessExitReports() {}

    private static final long[] DELAYS_MS = {0, 500, 2000, 5000, 15000, 30000};
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(
            task -> new Thread(task, "bot-exit-report"));

    public static List<ApplicationExitInfo> read(Context context) {
        List<ApplicationExitInfo> result = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 30) return result;
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) throw new IllegalStateException("ActivityManager unavailable");
        String process = context.getPackageName() + ":botengine";
        for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(
                context.getPackageName(), 0, 64)) {
            if (process.equals(exit.getProcessName())) result.add(exit);
        }
        return result;
    }

    public static String reason(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native_crash";
            case ApplicationExitInfo.REASON_CRASH: return "java_crash";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low_memory";
            case ApplicationExitInfo.REASON_ANR: return "not_responding";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "user_or_system_stop";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "process_exited_itself";
            case ApplicationExitInfo.REASON_SIGNALED: return "signal";
            default: return "android_reason_" + reason;
        }
    }

    public static String format(ApplicationExitInfo exit) {
        return "ANDROID_ENGINE_EXIT pid=" + exit.getPid() + " reason=" + reason(exit.getReason()) +
                " reasonCode=" + exit.getReason() + " status=" + exit.getStatus() +
                " timeMs=" + exit.getTimestamp() + " importance=" + exit.getImportance() +
                " pssKiB=" + exit.getPss() + " rssKiB=" + exit.getRss();
    }

    /** Capture the identity now: a later room may already own EngineHost when this query runs. */
    public static void observe(Context context, int pid, long startedAt, Consumer<String> log) {
        Context owner = context.getApplicationContext();
        long lostAt = System.currentTimeMillis();
        if (Build.VERSION.SDK_INT < 30 || pid <= 0) {
            log.accept("ANDROID_ENGINE_EXIT pid=" + pid + " reason=not_available lookup=unsupported_or_missing_pid");
            return;
        }
        WORKER.execute(new Runnable() {
            int attempt = 0;
            @Override public void run() {
                String failure = null;
                try {
                    for (ApplicationExitInfo exit : read(owner)) {
                        // A record from an older process or a reused PID must not explain this exit.
                        if (exit.getPid() == pid && exit.getTimestamp() >= startedAt &&
                                exit.getTimestamp() <= lostAt + 5000) {
                            log.accept(format(exit) + " lookup=after_disconnect attempt=" + (attempt + 1));
                            return;
                        }
                    }
                } catch (RuntimeException error) {
                    failure = error.getClass().getSimpleName();
                }
                if (attempt == 0 || attempt == DELAYS_MS.length - 1) {
                    log.accept("ANDROID_ENGINE_EXIT pid=" + pid + " reason=not_available lookup=" +
                            (attempt == 0 ? "pending" : "retry_window_finished") +
                            " attempt=" + (attempt + 1) + (failure == null ? "" : " queryError=" + failure) +
                            "; an absent record does not establish the cause; export queries Android again");
                }
                if (++attempt < DELAYS_MS.length) {
                    WORKER.schedule(this, DELAYS_MS[attempt] - DELAYS_MS[attempt - 1], TimeUnit.MILLISECONDS);
                }
            }
        });
    }

    /** Called at export time; survives APK updates and does not require another match. */
    public static String export(Context context) {
        StringBuilder text = new StringBuilder("PeerLink bot-engine Android exit history\n");
        text.append("queriedAtMs=").append(System.currentTimeMillis()).append('\n');
        text.append("These are retained Android records and may belong to earlier builds or rooms.\n");
        text.append("Match pid and timeMs to match_log.txt; absence does not prove no crash or kill.\n");
        if (Build.VERSION.SDK_INT < 30) return text.append("API unavailable before Android 11.\n").toString();
        try {
            List<ApplicationExitInfo> exits = read(context);
            text.append("records=").append(exits.size()).append('\n');
            for (ApplicationExitInfo exit : exits) text.append(format(exit)).append(" lookup=export\n");
        } catch (RuntimeException error) {
            text.append("queryError=").append(error.getClass().getSimpleName()).append('\n');
        }
        return text.toString();
    }
}
