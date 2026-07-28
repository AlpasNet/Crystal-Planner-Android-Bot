package net.alpas.crystalplanner;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
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
import net.alpas.crystalplanner.gateway.GatewayPresenceService;
import net.alpas.crystalplanner.discord.DiscordToken;
import net.alpas.crystalplanner.storage.AppSettings;
import net.alpas.crystalplanner.storage.SecureTokenStore;
import net.alpas.crystalplanner.storage.StateStore;
import net.alpas.crystalplanner.storage.SettingsBackup;
import net.alpas.crystalplanner.sync.CrystalPlannerWorker;
import net.alpas.crystalplanner.util.HttpClient;
import net.alpas.crystalplanner.util.SyncLog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends AppCompatActivity {
    private static final String PERIODIC_WORK = "crystal_planner_periodic_sync";
    private static final String MANUAL_WORK = "crystal_planner_manual_sync";
    private static final String CLEAR_LODESTONE_WORK = "crystal_planner_clear_lodestone";
    private static final String[] PRESENCE_STATUS_VALUES = {"online", "idle", "dnd", "invisible"};
    private static final int[] PRESENCE_ACTIVITY_TYPES = {4, 0, 3, 2, 5};

    private EditText editToken;
    private EditText editInterval;
    private CheckBox checkKeepScreenOn;
    private CheckBox checkGatewayPresence;
    private Spinner spinnerPresenceStatus;
    private Spinner spinnerActivityType;
    private EditText editPresenceMessage;
    private EditText editTopicsChannel;
    private EditText editNoticesChannel;
    private EditText editMaintenanceChannel;
    private EditText editUpdatesChannel;
    private CheckBox checkLinkshell;
    private EditText editWebFolderUrl;
    private EditText editLinkshellChannel;
    private EditText editDelay;
    private CheckBox checkRules;
    private EditText editRulesChannel;
    private CheckBox checkGuides;
    private EditText editGuidesChannel;
    private CheckBox checkMacros;
    private EditText editMacrosChannel;
    private TextView textStatus;
    private TextView textLastRun;
    private TextView textLog;
    private Button buttonSchedule;
    private Button buttonDisable;
    private ActivityResultLauncher<String> exportSettingsLauncher;
    private ActivityResultLauncher<String[]> importSettingsLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private CheckBox checkIncludeHistory;
    private String pendingBackupContent;

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
        registerBackupLaunchers();
        bindViews();
        configurePresenceSpinners();

        workManager = WorkManager.getInstance(this);
        stateStore = new StateStore(this);
        syncLog = new SyncLog(this);
        stateListener = (preferences, key) -> {
            if (StateStore.KEY_LAST_RUN.equals(key)
                    || StateStore.KEY_GATEWAY_STATE.equals(key)) {
                runOnUiThread(this::refreshStatus);
            }
        };
        stateStore.registerListener(stateListener);

        loadSettings();
        applyKeepScreenOnSetting();
        configureActions();
        observeWork();
        refreshStatus();
        if (AppSettings.load(this).gatewayPresenceEnabled) applyGatewaySetting();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void bindViews() {
        editToken = findViewById(R.id.editToken);
        editInterval = findViewById(R.id.editInterval);
        checkKeepScreenOn = findViewById(R.id.checkKeepScreenOn);
        checkGatewayPresence = findViewById(R.id.checkGatewayPresence);
        spinnerPresenceStatus = findViewById(R.id.spinnerPresenceStatus);
        spinnerActivityType = findViewById(R.id.spinnerActivityType);
        editPresenceMessage = findViewById(R.id.editPresenceMessage);
        editTopicsChannel = findViewById(R.id.editTopicsChannel);
        editNoticesChannel = findViewById(R.id.editNoticesChannel);
        editMaintenanceChannel = findViewById(R.id.editMaintenanceChannel);
        editUpdatesChannel = findViewById(R.id.editUpdatesChannel);
        editWebFolderUrl = findViewById(R.id.editWebFolderUrl);
        checkLinkshell = findViewById(R.id.checkLinkshell);
        editLinkshellChannel = findViewById(R.id.editLinkshellChannel);
        editDelay = findViewById(R.id.editDelay);
        checkRules = findViewById(R.id.checkRules);
        editRulesChannel = findViewById(R.id.editRulesChannel);
        checkGuides = findViewById(R.id.checkGuides);
        editGuidesChannel = findViewById(R.id.editGuidesChannel);
        checkMacros = findViewById(R.id.checkMacros);
        editMacrosChannel = findViewById(R.id.editMacrosChannel);
        textStatus = findViewById(R.id.textStatus);
        textLastRun = findViewById(R.id.textLastRun);
        textLog = findViewById(R.id.textLog);
        buttonSchedule = findViewById(R.id.buttonSchedule);
        buttonDisable = findViewById(R.id.buttonDisable);
        checkIncludeHistory = findViewById(R.id.checkIncludeHistory);
    }

    private void configureActions() {
        Button testToken = findViewById(R.id.buttonTestToken);
        Button clearToken = findViewById(R.id.buttonClearToken);
        Button clearLodestone = findViewById(R.id.buttonClearLodestoneChannels);
        Button save = findViewById(R.id.buttonSave);
        Button exportSettings = findViewById(R.id.buttonExportSettings);
        Button importSettings = findViewById(R.id.buttonImportSettings);
        Button runNow = findViewById(R.id.buttonRunNow);

        testToken.setOnClickListener(view -> testDiscordToken(testToken));
        clearToken.setOnClickListener(view -> clearDiscordToken());
        clearLodestone.setOnClickListener(view -> confirmClearLodestoneChannels());
        save.setOnClickListener(view -> {
            if (saveSettings(true)) applyGatewaySetting();
        });
        exportSettings.setOnClickListener(view -> beginSettingsExport());
        importSettings.setOnClickListener(view -> importSettingsLauncher.launch(
                new String[]{"application/json", "text/json", "text/plain"}
        ));
        buttonSchedule.setOnClickListener(view -> {
            if (!saveSettings(false)) return;
            applyGatewaySetting();
            schedulePeriodicSync();
        });
        buttonDisable.setOnClickListener(view -> disablePeriodicSync());
        runNow.setOnClickListener(view -> {
            if (!saveSettings(false)) return;
            applyGatewaySetting();
            enqueueManualSync();
        });
    }

    private void registerBackupLaunchers() {
        exportSettingsLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                uri -> {
                    if (uri != null) {
                        writeSettingsBackup(uri);
                    } else {
                        pendingBackupContent = null;
                    }
                }
        );
        importSettingsLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) readSettingsBackup(uri);
                }
        );
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (!granted) toast(R.string.toast_notification_permission_denied);
                    startGatewayServiceIfEnabled();
                }
        );
    }

    private void beginSettingsExport() {
        try {
            AppSettings settings = readSettingsFromForm();
            JSONObject history = checkIncludeHistory != null && checkIncludeHistory.isChecked()
                    ? stateStore.exportHistory()
                    : null;
            pendingBackupContent = SettingsBackup.create(settings, history);
            String timestamp = new SimpleDateFormat(
                    "yyyyMMdd-HHmmss",
                    Locale.ROOT
            ).format(new Date());
            exportSettingsLauncher.launch(
                    "Crystal-Planner-settings-" + timestamp + ".json"
            );
        } catch (Exception error) {
            toast(getString(R.string.error_backup_export_failed, errorMessage(error)));
        }
    }

    private void writeSettingsBackup(Uri uri) {
        final String content = pendingBackupContent;
        pendingBackupContent = null;
        if (content == null) return;

        networkExecutor.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("Output stream unavailable");
                output.write(content.getBytes(StandardCharsets.UTF_8));
                output.flush();
                runOnUiThread(() -> toast(R.string.toast_backup_exported));
            } catch (Exception error) {
                runOnUiThread(() -> toast(getString(
                        R.string.error_backup_export_failed,
                        errorMessage(error)
                )));
            }
        });
    }

    private void readSettingsBackup(Uri uri) {
        networkExecutor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Input stream unavailable");
                SettingsBackup.RestoreData imported = SettingsBackup.parse(readUtf8(input));
                runOnUiThread(() -> confirmSettingsImport(imported));
            } catch (Exception error) {
                runOnUiThread(() -> toast(getString(
                        R.string.error_backup_import_failed,
                        errorMessage(error)
                )));
            }
        });
    }

    private void confirmSettingsImport(SettingsBackup.RestoreData imported) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_import_backup_title)
                .setMessage(imported.hasHistory()
                        ? R.string.dialog_import_backup_with_history_message
                        : R.string.dialog_import_backup_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.import_settings, (dialog, which) -> {
                    imported.settings.save(this);
                    if (imported.hasHistory()) {
                        try {
                            stateStore.importHistory(imported.history);
                        } catch (Exception error) {
                            toast(getString(R.string.error_history_import_failed, errorMessage(error)));
                            return;
                        }
                    }
                    editToken.setText("");
                    loadSettings();
                    applyKeepScreenOnSetting();
                    if (periodicActive) {
                        schedulePeriodicSync(false);
                    }
                    applyGatewaySetting();
                    toast(imported.hasHistory()
                            ? R.string.toast_backup_with_history_imported
                            : R.string.toast_backup_imported);
                })
                .show();
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > 1024 * 1024) {
                throw new IOException("Backup file is too large");
            }
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
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
        AppSettings settings = AppSettings.load(this);
        settings.gatewayPresenceEnabled = false;
        settings.save(this);
        if (checkGatewayPresence != null) checkGatewayPresence.setChecked(false);
        GatewayPresenceService.stop(this);
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
        checkKeepScreenOn.setChecked(s.keepScreenOn);
        checkGatewayPresence.setChecked(s.gatewayPresenceEnabled);
        setSpinnerSelection(spinnerPresenceStatus, PRESENCE_STATUS_VALUES, s.presenceStatus);
        setSpinnerActivitySelection(s.presenceActivityType);
        editPresenceMessage.setText(s.presenceMessage);
        editTopicsChannel.setText(s.topicsChannel);
        editNoticesChannel.setText(s.noticesChannel);
        editMaintenanceChannel.setText(s.maintenanceChannel);
        editUpdatesChannel.setText(s.updatesChannel);

        editWebFolderUrl.setText(s.webFolderUrl);

        checkLinkshell.setChecked(s.linkshellEnabled);
        editLinkshellChannel.setText(s.linkshellChannel);
        editDelay.setText(String.valueOf(s.jsonReadDelaySeconds));

        checkRules.setChecked(s.rulesEnabled);
        editRulesChannel.setText(s.rulesChannel);

        checkGuides.setChecked(s.guidesEnabled);
        editGuidesChannel.setText(s.guidesChannel);

        checkMacros.setChecked(s.macrosEnabled);
        editMacrosChannel.setText(s.macrosChannel);

        SecureTokenStore tokenStore = new SecureTokenStore(this);
        editToken.setHint(tokenStore.hasToken()
                ? getString(R.string.hint_discord_token_stored)
                : getString(R.string.hint_discord_token));
    }

    private AppSettings readSettingsFromForm() {
        AppSettings s = new AppSettings();
        s.intervalMinutes = Math.max(15, parseInt(editInterval, 15));
        s.keepScreenOn = checkKeepScreenOn.isChecked();
        s.gatewayPresenceEnabled = checkGatewayPresence.isChecked();
        s.presenceStatus = PRESENCE_STATUS_VALUES[Math.max(0, spinnerPresenceStatus.getSelectedItemPosition())];
        int activityPosition = Math.max(0, spinnerActivityType.getSelectedItemPosition());
        s.presenceActivityType = PRESENCE_ACTIVITY_TYPES[Math.min(activityPosition, PRESENCE_ACTIVITY_TYPES.length - 1)];
        s.presenceMessage = value(editPresenceMessage);
        s.topicsChannel = value(editTopicsChannel);
        s.noticesChannel = value(editNoticesChannel);
        s.maintenanceChannel = value(editMaintenanceChannel);
        s.updatesChannel = value(editUpdatesChannel);

        s.webFolderUrl = value(editWebFolderUrl);

        s.linkshellEnabled = checkLinkshell.isChecked();
        s.linkshellChannel = value(editLinkshellChannel);
        s.jsonReadDelaySeconds = Math.max(0, parseInt(editDelay, 3));

        s.rulesEnabled = checkRules.isChecked();
        s.rulesChannel = value(editRulesChannel);

        s.guidesEnabled = checkGuides.isChecked();
        s.guidesChannel = value(editGuidesChannel);

        s.macrosEnabled = checkMacros.isChecked();
        s.macrosChannel = value(editMacrosChannel);
        return s;
    }

    private boolean saveSettings(boolean showConfirmation) {
        try {
            AppSettings s = readSettingsFromForm();
            s.save(this);
            editInterval.setText(String.valueOf(s.intervalMinutes));
            checkKeepScreenOn.setChecked(s.keepScreenOn);
            checkGatewayPresence.setChecked(s.gatewayPresenceEnabled);
            setSpinnerSelection(spinnerPresenceStatus, PRESENCE_STATUS_VALUES, s.presenceStatus);
            setSpinnerActivitySelection(s.presenceActivityType);
            editPresenceMessage.setText(s.presenceMessage);
            editWebFolderUrl.setText(s.webFolderUrl);

            String token = value(editToken);
            if (!token.trim().isEmpty()) {
                new SecureTokenStore(this).save(token);
                editToken.setText("");
                editToken.setHint(R.string.hint_discord_token_stored);
            }

            if (!new SecureTokenStore(this).hasToken()) {
                throw new IllegalStateException(getString(R.string.error_token_required));
            }

            applyKeepScreenOnSetting();
            if (showConfirmation) toast(R.string.toast_settings_saved);
            return true;
        } catch (Exception error) {
            toast(getString(R.string.error_with_message, error.getMessage()));
            return false;
        }
    }

    private void configurePresenceSpinners() {
        ArrayAdapter<String> statusAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item,
                new String[]{
                        getString(R.string.presence_status_online),
                        getString(R.string.presence_status_idle),
                        getString(R.string.presence_status_dnd),
                        getString(R.string.presence_status_invisible)
                }
        );
        statusAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerPresenceStatus.setAdapter(statusAdapter);

        ArrayAdapter<String> activityAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item,
                new String[]{
                        getString(R.string.presence_activity_custom),
                        getString(R.string.presence_activity_playing),
                        getString(R.string.presence_activity_watching),
                        getString(R.string.presence_activity_listening),
                        getString(R.string.presence_activity_competing)
                }
        );
        activityAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerActivityType.setAdapter(activityAdapter);
    }

    private void applyKeepScreenOnSetting() {
        AppSettings settings = AppSettings.load(this);
        if (settings.keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void applyGatewaySetting() {
        AppSettings settings = AppSettings.load(this);
        if (!settings.gatewayPresenceEnabled) {
            GatewayPresenceService.stop(this);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        startGatewayServiceIfEnabled();
    }

    private void startGatewayServiceIfEnabled() {
        AppSettings settings = AppSettings.load(this);
        if (settings.gatewayPresenceEnabled) {
            try {
                GatewayPresenceService.startOrUpdate(this);
            } catch (Exception error) {
                toast(getString(R.string.error_gateway_start_failed, errorMessage(error)));
            }
        }
    }

    private static void setSpinnerSelection(Spinner spinner, String[] values, String selected) {
        String expected = selected == null ? "" : selected;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(expected)) {
                spinner.setSelection(i);
                return;
            }
        }
        spinner.setSelection(0);
    }

    private void setSpinnerActivitySelection(int selectedType) {
        for (int i = 0; i < PRESENCE_ACTIVITY_TYPES.length; i++) {
            if (PRESENCE_ACTIVITY_TYPES[i] == selectedType) {
                spinnerActivityType.setSelection(i);
                return;
            }
        }
        spinnerActivityType.setSelection(0);
    }

    private void schedulePeriodicSync() {
        schedulePeriodicSync(true);
    }

    private void schedulePeriodicSync(boolean showToast) {
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
        if (showToast) {
            toast(getString(R.string.toast_sync_enabled, settings.intervalMinutes));
        }
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

        AppSettings currentSettings = AppSettings.load(this);
        JSONObject gateway = stateStore == null ? new JSONObject() : stateStore.getGatewayState();
        String gatewayCode = gateway.optString("state", "stopped");
        String gatewayText;
        if (!currentSettings.gatewayPresenceEnabled) {
            gatewayText = getString(R.string.gateway_state_disabled);
        } else if ("connected".equals(gatewayCode)) {
            String detail = gateway.optString("detail", "");
            gatewayText = detail.trim().isEmpty()
                    ? getString(R.string.gateway_state_connected)
                    : getString(R.string.gateway_state_connected_as, detail);
        } else if ("connecting".equals(gatewayCode)) {
            gatewayText = getString(R.string.gateway_state_connecting);
        } else if ("reconnecting".equals(gatewayCode)) {
            gatewayText = getString(R.string.gateway_state_reconnecting);
        } else if ("error".equals(gatewayCode)) {
            gatewayText = getString(R.string.gateway_state_error);
        } else {
            gatewayText = getString(R.string.gateway_state_stopped);
        }
        status.append("\n").append(getString(R.string.status_bot_presence, gatewayText));

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

    private static String errorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private void toast(int stringRes) {
        toast(getString(stringRes));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
