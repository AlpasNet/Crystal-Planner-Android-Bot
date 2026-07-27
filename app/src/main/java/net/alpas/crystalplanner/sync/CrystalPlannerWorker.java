package net.alpas.crystalplanner.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import net.alpas.crystalplanner.R;
import net.alpas.crystalplanner.storage.AppSettings;
import net.alpas.crystalplanner.storage.SecureTokenStore;
import net.alpas.crystalplanner.storage.StateStore;
import net.alpas.crystalplanner.util.SyncLog;

public final class CrystalPlannerWorker extends Worker {
    public static final String INPUT_ACTION = "action";
    public static final String ACTION_SYNC = "sync";
    public static final String ACTION_CLEAR_LODESTONE = "clear_lodestone";

    public CrystalPlannerWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        StateStore state = new StateStore(context);
        SyncLog log = new SyncLog(context);

        // Each execution owns a fresh log. The activity refreshes it when last_run changes.
        log.clear();

        try {
            String token = new SecureTokenStore(context).load();
            if (token.trim().isEmpty()) {
                String message = context.getString(R.string.worker_token_missing);
                log.error(message);
                state.setLastRun(false, message);
                return Result.failure(output(message));
            }

            AppSettings settings = AppSettings.load(context);
            CrystalPlannerEngine engine = new CrystalPlannerEngine(context, settings, token);
            String action = getInputData().getString(INPUT_ACTION);

            if (ACTION_CLEAR_LODESTONE.equals(action)) {
                int deleted = engine.clearLodestoneChannels();
                String text = context.getString(R.string.cleanup_complete, deleted);
                state.setLastRun(true, text);
                return Result.success(output(text));
            }

            CrystalPlannerEngine.Summary summary = engine.run();
            boolean success = summary.errors == 0;
            String text = summary.describe(context);
            state.setLastRun(success, text);
            return success ? Result.success(output(text)) : Result.failure(output(text));
        } catch (Exception error) {
            String detail = error.getMessage() == null ? "" : error.getMessage();
            String message = error.getClass().getSimpleName() + (detail.isEmpty() ? "" : ": " + detail);
            log.error(context.getString(R.string.worker_failure, message));
            state.setLastRun(false, message);
            if (getRunAttemptCount() < 2 && !ACTION_CLEAR_LODESTONE.equals(
                    getInputData().getString(INPUT_ACTION))) {
                return Result.retry();
            }
            return Result.failure(output(message));
        }
    }

    private static Data output(String summary) {
        return new Data.Builder().putString("summary", summary).build();
    }
}
