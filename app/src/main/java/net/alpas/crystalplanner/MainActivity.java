package net.alpas.crystalplanner;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import net.alpas.crystalplanner.discord.DiscordApi;
import net.alpas.crystalplanner.discord.DiscordToken;
import net.alpas.crystalplanner.storage.AppSettings;
import net.alpas.crystalplanner.storage.SecureTokenStore;
import net.alpas.crystalplanner.storage.StateStore;
import net.alpas.crystalplanner.sync.CrystalPlannerWorker;
import net.alpas.crystalplanner.util.HttpClient;
import net.alpas.crystalplanner.util.SyncLog;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends AppCompatActivity {
    private static final String PERIODIC_WORK = "crystal_planner_periodic_sync";
    private static final String MANUAL_WORK = "crystal_planner_manual_sync";
    private static final String CLEAR_LODESTONE_WORK = "crystal_planner_clear_lodestone";

    private EditText editToken;
    private EditText editInterval;
    private EditText editTopicsChannel;
    private EditText editNoticesChannel;
    private EditText editMaintenanceChannel;
    private EditText editUpdatesChannel;
    private CheckBox checkLinkshell;
    private EditText editLinkshellChannel;
    private EditText editGeneratorUrl;
    private EditText editDataJsonUrl;
    private EditText editDelay;
    private CheckBox checkRules;
    private EditText editRulesChannel;
    private EditText editRulesUrl;
    private CheckBox checkGuides;
    private EditText editGuidesChannel;
    private EditText editGuidesUrl;
    private TextView textStatus;
    private TextView textLastRun;
    private TextView textLog;
    private Button buttonSchedule;
    private Button buttonDisable;

    private WorkManager workManager;
    private StateStore stateStore;
    private SyncLog syncLog;
    private SharedPreferences.OnSharedPreferenceChangeListener stateListener;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    private boolean periodicActive;
    private boolean periodicRunning;
    private boolean manualRunning;
    private boolean cleanupRunning;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();

        workManager = WorkManager.getInstance(this);
        stateStore = new StateStore(this);
        syncLog = new SyncLog(this);
        stateListener = (preferences, key) -> {
            if (StateStore.KEY_LAST_RUN.equals(key)) {
                runOnUiThread(this::refreshStatus);
            }
        };
        stateStore.registerListener(stateListener);

        loadSettings();
        configureActions();
        observeWork();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void bindViews() {
        editToken = findViewById(R.id.editToken);
        editInterval = findViewById(R.id.editInterval);
        editTopicsChannel = findViewById(R.id.editTopicsChannel);
        editNoticesChannel = findViewById(R.id.editNoticesChannel);
        editMaintenanceChannel = findViewById(R.id.editMaintenanceChannel);
        editUpdatesChannel = findViewById(R.id.editUpdatesChannel);
        checkLinkshell = findViewById(R.id.checkLinkshell);
        editLinkshellChannel = findViewById(R.id.editLinkshellChannel);
        editGeneratorUrl = findViewById(R.id.editGeneratorUrl);
        editDataJsonUrl = findViewById(R.id.editDataJsonUrl);
        editDelay = findViewById(R.id.editDelay);
        checkRules = findViewById(R.id.checkRules);
        editRulesChannel = findViewById(R.id.editRulesChannel);
        editRulesUrl = findViewById(R.id.editRulesUrl);
        checkGuides = findViewById(R.id.checkGuides);
        editGuidesChannel = findViewById(R.id.editGuidesChannel);
        editGuidesUrl = findViewById(R.id.editGuidesUrl);
        textStatus = findViewById(R.id.textStatus);
        textLastRun = findViewById(R.id.textLastRun);
        textLog = findViewById(R.id.textLog);
        buttonSchedule = findViewById(R.id.buttonSchedule);
        buttonDisable = findViewById(R.id.buttonDisable);
    }

    private void configureActions() {
        Button testToken = findViewById(R.id.buttonTestToken);
        Button clearToken = findViewById(R.id.buttonClearToken);
        Button clearLodestone = findViewById(R.id.buttonClearLodestoneChannels);
        Button save = findViewById(R.id.buttonSave);
        Button runNow = findViewById(R.id.buttonRunNow);

        testToken.setOnClickListener(view -> testDiscordToken(testToken));
        clearToken.setOnClickListener(view -> clearDiscordToken());
        clearLodestone.setOnClickListener(view -> confirmClearLodestoneChannels());
        save.setOnClickListener(view -> saveSettings(true));
        buttonSchedule.setOnClickListener(view -> {
            if (!saveSettings(false)) return;
            schedulePeriodicSync();
        });
        buttonDisable.setOnClickListener(view -> disablePeriodicSync());
        runNow.setOnClickListener(view -> {
            if (!saveSettings(false)) return;
            enqueueManualSync();
        });
    }

    private void testDiscordToken(Button button) {
        final String typedToken = value(editToken);
        button.setEnabled(false);
        textStatus.setText(R.string.status_verifying_token);

        networkExecutor.execute(() -> {
            try {
                SecureTokenStore tokenStore = new SecureTokenStore(this);
                String token = typedToken.trim().isEmpty() ? tokenStore.load() : typedToken;
                DiscordToken.requirePlausible(
                        token,
                        getString(R.string.discord_token_empty),
                        getString(R.string.discord_token_too_short),
                        getString(R.string.discord_token_whitespace)
                );

                String botName = new DiscordApi(
                        this,
                        new HttpClient(),
                        syncLog,
                        token
                ).verifyBot();

                if (!typedToken.trim().isEmpty()) {
                    tokenStore.save(token);
                }
                syncLog.info(getString(R.string.log_token_verified, botName));

                runOnUiThread(() -> {
                    button.setEnabled(true);
                    if (!typedToken.trim().isEmpty()) {
                        editToken.setText("");
                    }
                    editToken.setHint(R.string.hint_discord_token_verified);
                    toast(getString(R.string.toast_token_valid, botName));
                    refreshStatus();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null
                        ? error.getClass().getSimpleName()
                        : error.getMessage();
                syncLog.error(getString(R.string.log_token_test_failed, message));
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    toast(message);
                    refreshStatus();
                });
            }
        });
    }

    private void clearDiscordToken() {
        new SecureTokenStore(this).clear();
        editToken.setText("");
        editToken.setHint(R.string.hint_discord_token);
        toast(R.string.toast_token_cleared);
    }

    private void confirmClearLodestoneChannels() {
        if (!saveSettings(false)) return;

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_clear_lodestone_title)
                .setMessage(R.string.dialog_clear_lodestone_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear_all, (dialog, which) -> enqueueLodestoneCleanup())
                .show();
    }

    private void loadSettings() {
        AppSettings s = AppSettings.load(this);
        editInterval.setText(String.valueOf(s.intervalMinutes));
        editTopicsChannel.setText(s.topicsChannel);
        editNoticesChannel.setText(s.noticesChannel);
        editMaintenanceChannel.setText(s.maintenanceChannel);
        editUpdatesChannel.setText(s.updatesChannel);

        checkLinkshell.setChecked(s.linkshellEnabled);
        editLinkshellChannel.setText(s.linkshellChannel);
        editGeneratorUrl.setText(s.generatorUrl);
        editDataJsonUrl.setText(s.dataJsonUrl);
        editDelay.setText(String.valueOf(s.jsonReadDelaySeconds));

        checkRules.setChecked(s.rulesEnabled);
        editRulesChannel.setText(s.rulesChannel);
        editRulesUrl.setText(s.rulesJsonUrl);

        checkGuides.setChecked(s.guidesEnabled);
        editGuidesChannel.setText(s.guidesChannel);
        editGuidesUrl.setText(s.guidesJsonUrl);

        SecureTokenStore tokenStore = new SecureTokenStore(this);
        editToken.setHint(tokenStore.hasToken()
                ? getString(R.string.hint_discord_token_stored)
                : getString(R.string.hint_discord_token));
    }

    private boolean saveSettings(boolean showConfirmation) {
        try {
            AppSettings s = new AppSettings();
            s.intervalMinutes = Math.max(15, parseInt(editInterval, 15));
            s.topicsChannel = value(editTopicsChannel);
            s.noticesChannel = value(editNoticesChannel);
            s.maintenanceChannel = value(editMaintenanceChannel);
            s.updatesChannel = value(editUpdatesChannel);

            s.linkshellEnabled = checkLinkshell.isChecked();
            s.linkshellChannel = value(editLinkshellChannel);
            s.generatorUrl = value(editGeneratorUrl);
            s.dataJsonUrl = value(editDataJsonUrl);
            s.jsonReadDelaySeconds = Math.max(0, parseInt(editDelay, 3));

            s.rulesEnabled = checkRules.isChecked();
            s.rulesChannel = value(editRulesChannel);
            s.rulesJsonUrl = value(editRulesUrl);

            s.guidesEnabled = checkGuides.isChecked();
            s.guidesChannel = value(editGuidesChannel);
            s.guidesJsonUrl = value(editGuidesUrl);

            s.save(this);
            editInterval.setText(String.valueOf(s.intervalMinutes));

            String token = value(editToken);
            if (!token.trim().isEmpty()) {
                new SecureTokenStore(this).save(token);
                editToken.setText("");
                editToken.setHint(R.string.hint_discord_token_stored);
            }

            if (!new SecureTokenStore(this).hasToken()) {
                throw new IllegalStateException(getString(R.string.error_token_required));
            }

            if (showConfirmation) toast(R.string.toast_settings_saved);
            return true;
        } catch (Exception error) {
            toast(getString(R.string.error_with_message, error.getMessage()));
            return false;
        }
    }

    private void schedulePeriodicSync() {
        AppSettings settings = AppSettings.load(this);
        Constraints constraints = networkConstraints();
        Data input = new Data.Builder()
                .putString(CrystalPlannerWorker.INPUT_ACTION, CrystalPlannerWorker.ACTION_SYNC)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                CrystalPlannerWorker.class,
                Math.max(15, settings.intervalMinutes),
                TimeUnit.MINUTES
        )
                .setConstraints(constraints)
                .setInputData(input)
                .addTag(PERIODIC_WORK)
                .build();

        workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
        );
        periodicActive = true;
        stateStore.setScheduled(true);
        renderStatus();
        toast(getString(R.string.toast_sync_enabled, settings.intervalMinutes));
    }

    private void disablePeriodicSync() {
        workManager.cancelUniqueWork(PERIODIC_WORK);
        periodicActive = false;
        periodicRunning = false;
        stateStore.setScheduled(false);
        renderStatus();
        toast(R.string.toast_sync_disabled);
    }

    private void enqueueManualSync() {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CrystalPlannerWorker.class)
                .setConstraints(networkConstraints())
                .setInputData(new Data.Builder()
                        .putString(CrystalPlannerWorker.INPUT_ACTION, CrystalPlannerWorker.ACTION_SYNC)
                        .build())
                .addTag(MANUAL_WORK)
                .build();
        workManager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.REPLACE, request);
        manualRunning = true;
        renderStatus();
        toast(R.string.toast_sync_started);
    }

    private void enqueueLodestoneCleanup() {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CrystalPlannerWorker.class)
                .setConstraints(networkConstraints())
                .setInputData(new Data.Builder()
                        .putString(CrystalPlannerWorker.INPUT_ACTION,
                                CrystalPlannerWorker.ACTION_CLEAR_LODESTONE)
                        .build())
                .addTag(CLEAR_LODESTONE_WORK)
                .build();
        workManager.enqueueUniqueWork(
                CLEAR_LODESTONE_WORK,
                ExistingWorkPolicy.REPLACE,
                request
        );
        cleanupRunning = true;
        renderStatus();
        toast(R.string.toast_cleanup_started);
    }

    private Constraints networkConstraints() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }

    private void observeWork() {
        workManager.getWorkInfosForUniqueWorkLiveData(PERIODIC_WORK)
                .observe(this, this::handlePeriodicWorkInfos);
        workManager.getWorkInfosForUniqueWorkLiveData(MANUAL_WORK)
                .observe(this, infos -> handleOneTimeWorkInfos(infos, false));
        workManager.getWorkInfosForUniqueWorkLiveData(CLEAR_LODESTONE_WORK)
                .observe(this, infos -> handleOneTimeWorkInfos(infos, true));
    }

    private void handlePeriodicWorkInfos(List<WorkInfo> infos) {
        boolean wasRunning = periodicRunning;
        periodicActive = containsActiveWork(infos);
        periodicRunning = containsState(infos, WorkInfo.State.RUNNING);
        stateStore.setScheduled(periodicActive);
        renderStatus();

        if (wasRunning && !periodicRunning) {
            refreshStatus();
        }
    }

    private void handleOneTimeWorkInfos(List<WorkInfo> infos, boolean cleanup) {
        boolean running = containsState(infos, WorkInfo.State.RUNNING)
                || containsState(infos, WorkInfo.State.ENQUEUED)
                || containsState(infos, WorkInfo.State.BLOCKED);
        boolean finished = containsFinishedWork(infos);

        if (cleanup) {
            cleanupRunning = running;
        } else {
            manualRunning = running;
        }
        renderStatus();

        if (finished) {
            refreshStatus();
        }
    }

    private static boolean containsActiveWork(List<WorkInfo> infos) {
        if (infos == null) return false;
        for (WorkInfo info : infos) {
            WorkInfo.State state = info.getState();
            if (state == WorkInfo.State.ENQUEUED
                    || state == WorkInfo.State.RUNNING
                    || state == WorkInfo.State.BLOCKED) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsState(List<WorkInfo> infos, WorkInfo.State expected) {
        if (infos == null) return false;
        for (WorkInfo info : infos) {
            if (info.getState() == expected) return true;
        }
        return false;
    }

    private static boolean containsFinishedWork(List<WorkInfo> infos) {
        if (infos == null) return false;
        for (WorkInfo info : infos) {
            if (info.getState().isFinished()) return true;
        }
        return false;
    }

    private void refreshStatus() {
        if (textLastRun != null && stateStore != null) {
            JSONObject last = stateStore.getLastRun();
            long timestamp = last.optLong("timestamp", 0L);
            if (timestamp <= 0L) {
                textLastRun.setText(R.string.last_run_none);
            } else {
                boolean success = last.optBoolean("success", false);
                String date = DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.MEDIUM
                ).format(new Date(timestamp));
                textLastRun.setText((success
                        ? getString(R.string.last_run_success)
                        : getString(R.string.last_run_failure)) + " — " + date
                        + "\n" + last.optString("summary", ""));
            }
        }

        if (textLog != null && syncLog != null) {
            textLog.setText(syncLog.readTail());
        }
        renderStatus();
    }

    private void renderStatus() {
        if (textStatus == null) return;

        String automatic = getString(periodicActive
                ? R.string.status_enabled
                : R.string.status_disabled);
        StringBuilder status = new StringBuilder(
                getString(R.string.status_auto_sync, automatic)
        );

        if (periodicActive) {
            status.append("\n").append(getString(
                    R.string.status_interval,
                    AppSettings.load(this).intervalMinutes
            ));
        }
        if (cleanupRunning) {
            status.append("\n").append(getString(R.string.status_cleanup_running));
        } else if (manualRunning || periodicRunning) {
            status.append("\n").append(getString(R.string.status_sync_running));
        }

        textStatus.setText(status.toString());
        if (buttonSchedule != null) {
            buttonSchedule.setText(periodicActive
                    ? getString(R.string.update_schedule)
                    : getString(R.string.schedule));
        }
        if (buttonDisable != null) {
            buttonDisable.setEnabled(periodicActive);
        }
    }

    @Override
    protected void onDestroy() {
        if (stateStore != null && stateListener != null) {
            stateStore.unregisterListener(stateListener);
        }
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    private static String value(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private static int parseInt(EditText field, int fallback) {
        String value = value(field);
        if (value.trim().isEmpty()) return fallback;
        return Integer.parseInt(value);
    }

    private void toast(int stringRes) {
        toast(getString(stringRes));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
