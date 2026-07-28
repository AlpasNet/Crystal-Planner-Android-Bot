package net.alpas.crystalplanner.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import net.alpas.crystalplanner.MainActivity;
import net.alpas.crystalplanner.R;
import net.alpas.crystalplanner.discord.DiscordToken;
import net.alpas.crystalplanner.storage.AppSettings;
import net.alpas.crystalplanner.storage.SecureTokenStore;
import net.alpas.crystalplanner.storage.StateStore;
import net.alpas.crystalplanner.util.SyncLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Keeps a Discord Gateway connection open so the bot can expose a presence.
 * The foreground notification is required because this is a user-enabled,
 * continuous network connection.
 */
public final class GatewayPresenceService extends Service {
    public static final String ACTION_START_OR_UPDATE =
            "net.alpas.crystalplanner.gateway.START_OR_UPDATE";
    public static final String ACTION_STOP =
            "net.alpas.crystalplanner.gateway.STOP";

    private static final String CHANNEL_ID = "discord_gateway_presence";
    private static final int NOTIFICATION_ID = 1101;
    private static final String GATEWAY_URL =
            "wss://gateway.discord.gg/?v=10&encoding=json";

    private final Object lock = new Object();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private OkHttpClient client;
    private WebSocket socket;
    private ScheduledFuture<?> heartbeatFuture;
    private ScheduledFuture<?> reconnectFuture;
    private boolean stopping;
    private boolean heartbeatAcknowledged = true;
    private long sequence = -1L;
    private String sessionId = "";
    private String resumeGatewayUrl = "";
    private int reconnectAttempt;
    private String connectedBotName = "";
    private boolean gatewayReady;

    private StateStore stateStore;
    private SyncLog log;

    public static void startOrUpdate(Context context) {
        Intent intent = new Intent(context, GatewayPresenceService.class)
                .setAction(ACTION_START_OR_UPDATE);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, GatewayPresenceService.class));
        new StateStore(context).setGatewayState("stopped", "");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        stateStore = new StateStore(this);
        log = new SyncLog(this);
        client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .build();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START_OR_UPDATE : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            AppSettings settings = AppSettings.load(this);
            settings.gatewayPresenceEnabled = false;
            settings.save(this);
            stopGateway(true);
            return START_NOT_STICKY;
        }

        AppSettings settings = AppSettings.load(this);
        if (!settings.gatewayPresenceEnabled) {
            stopGateway(true);
            return START_NOT_STICKY;
        }

        stopping = false;
        startForegroundNotification(getString(R.string.gateway_notification_connecting));
        synchronized (lock) {
            if (socket == null) {
                connect(false);
            } else if (gatewayReady) {
                sendPresence(settings);
                updateState("connected", connectedBotName);
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopGateway(false);
        scheduler.shutdownNow();
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
        super.onDestroy();
    }

    private void connect(boolean preferResume) {
        synchronized (lock) {
            if (stopping) return;
            cancelReconnect();
            cancelHeartbeat();
            gatewayReady = false;
            heartbeatAcknowledged = true;
            String url = preferResume && !resumeGatewayUrl.isEmpty()
                    ? gatewayUrl(resumeGatewayUrl)
                    : GATEWAY_URL;
            updateState("connecting", "");
            updateNotification(getString(R.string.gateway_notification_connecting));
            Request request = new Request.Builder().url(url).build();
            socket = client.newWebSocket(request, new GatewayListener());
        }
    }

    private void handleMessage(String text) {
        try {
            JSONObject payload = new JSONObject(text);
            if (!payload.isNull("s")) sequence = payload.optLong("s", sequence);
            int op = payload.optInt("op", -1);
            switch (op) {
                case 0:
                    handleDispatch(payload.optString("t", ""), payload.optJSONObject("d"));
                    break;
                case 1:
                    sendHeartbeat();
                    break;
                case 7:
                    reconnect(true, "Discord requested a reconnect");
                    break;
                case 9:
                    boolean resumable = payload.optBoolean("d", false);
                    if (!resumable) clearSession();
                    reconnect(resumable, "Discord invalidated the session");
                    break;
                case 10:
                    JSONObject hello = payload.optJSONObject("d");
                    long interval = hello == null ? 45000L
                            : Math.max(1000L, hello.optLong("heartbeat_interval", 45000L));
                    scheduleHeartbeat(interval);
                    try {
                        if (!sessionId.isEmpty()) {
                            sendResume();
                        } else {
                            sendIdentify();
                        }
                    } catch (Exception error) {
                        permanentError(safeMessage(error));
                    }
                    break;
                case 11:
                    heartbeatAcknowledged = true;
                    break;
                default:
                    break;
            }
        } catch (Exception error) {
            log.warn("Discord Gateway payload error: " + safeMessage(error));
        }
    }

    private void handleDispatch(String type, JSONObject data) {
        if ("READY".equals(type) && data != null) {
            sessionId = data.optString("session_id", "");
            resumeGatewayUrl = data.optString("resume_gateway_url", "");
            JSONObject user = data.optJSONObject("user");
            connectedBotName = user == null ? "" : user.optString("username", "");
            reconnectAttempt = 0;
            gatewayReady = true;
            updateState("connected", connectedBotName);
            updateNotification(getString(
                    R.string.gateway_notification_connected,
                    connectedBotName.isEmpty() ? getString(R.string.app_name) : connectedBotName
            ));
            log.info(getString(
                    R.string.log_gateway_connected,
                    connectedBotName.isEmpty() ? getString(R.string.app_name) : connectedBotName
            ));
        } else if ("RESUMED".equals(type)) {
            reconnectAttempt = 0;
            gatewayReady = true;
            updateState("connected", connectedBotName);
            updateNotification(getString(
                    R.string.gateway_notification_connected,
                    connectedBotName.isEmpty() ? getString(R.string.app_name) : connectedBotName
            ));
            log.info(getString(R.string.log_gateway_resumed));
        }
    }

    private void sendIdentify() throws Exception {
        String token = new SecureTokenStore(this).load();
        DiscordToken.requirePlausible(token);

        JSONObject properties = new JSONObject();
        properties.put("os", "android");
        properties.put("browser", "Crystal Planner Android");
        properties.put("device", "Crystal Planner Android");

        JSONObject data = new JSONObject();
        data.put("token", DiscordToken.normalize(token));
        data.put("intents", 0);
        data.put("properties", properties);
        data.put("presence", presenceObject(AppSettings.load(this)));

        sendPayload(2, data);
    }

    private void sendResume() throws Exception {
        String token = new SecureTokenStore(this).load();
        DiscordToken.requirePlausible(token);
        JSONObject data = new JSONObject();
        data.put("token", DiscordToken.normalize(token));
        data.put("session_id", sessionId);
        data.put("seq", sequence < 0 ? JSONObject.NULL : sequence);
        sendPayload(6, data);
    }

    private void sendPresence(AppSettings settings) {
        if (!gatewayReady) return;
        try {
            sendPayload(3, presenceObject(settings));
            log.info(getString(
                    R.string.log_gateway_presence_updated,
                    settings.presenceStatus,
                    settings.presenceMessage
            ));
            updateNotification(getString(
                    R.string.gateway_notification_connected,
                    connectedBotName.isEmpty() ? getString(R.string.app_name) : connectedBotName
            ));
        } catch (Exception error) {
            log.warn(getString(R.string.log_gateway_presence_failed, safeMessage(error)));
        }
    }

    private JSONObject presenceObject(AppSettings settings) throws Exception {
        JSONObject presence = new JSONObject();
        String status = AppSettings.normalizePresenceStatus(settings.presenceStatus);
        presence.put("since", "idle".equals(status)
                ? System.currentTimeMillis()
                : JSONObject.NULL);
        presence.put("status", status);
        presence.put("afk", "idle".equals(status));

        JSONArray activities = new JSONArray();
        String message = settings.presenceMessage == null
                ? ""
                : settings.presenceMessage.trim();
        if (!message.isEmpty()) {
            int type = AppSettings.normalizeActivityType(settings.presenceActivityType);
            JSONObject activity = new JSONObject();
            activity.put("type", type);
            if (type == 4) {
                activity.put("name", "Custom Status");
                activity.put("state", message);
            } else {
                activity.put("name", message);
            }
            activities.put(activity);
        }
        presence.put("activities", activities);
        return presence;
    }

    private void sendPayload(int op, Object data) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("op", op);
        payload.put("d", data == null ? JSONObject.NULL : data);
        WebSocket active;
        synchronized (lock) {
            active = socket;
        }
        if (active == null || !active.send(payload.toString())) {
            throw new IllegalStateException("Discord Gateway is not connected");
        }
    }

    private void scheduleHeartbeat(long intervalMs) {
        synchronized (lock) {
            cancelHeartbeat();
            long firstDelay = Math.max(250L, (long) (Math.random() * intervalMs));
            heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
                if (!heartbeatAcknowledged) {
                    reconnect(true, "Discord heartbeat acknowledgement missing");
                    return;
                }
                sendHeartbeat();
            }, firstDelay, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    private void sendHeartbeat() {
        try {
            heartbeatAcknowledged = false;
            sendPayload(1, sequence < 0 ? JSONObject.NULL : sequence);
        } catch (Exception error) {
            reconnect(true, safeMessage(error));
        }
    }

    private void reconnect(boolean resume, String reason) {
        synchronized (lock) {
            if (stopping) return;
            if (!resume) clearSession();
            gatewayReady = false;
            if (socket != null) {
                socket.cancel();
                socket = null;
            }
            cancelHeartbeat();
            if (reconnectFuture != null && !reconnectFuture.isDone()) return;
            long delay = Math.min(60L, Math.max(2L, 1L << Math.min(reconnectAttempt, 5)));
            reconnectAttempt++;
            updateState("reconnecting", reason);
            updateNotification(getString(R.string.gateway_notification_reconnecting, delay));
            log.warn(getString(R.string.log_gateway_reconnecting, delay, reason));
            reconnectFuture = scheduler.schedule(() -> {
                synchronized (lock) {
                    reconnectFuture = null;
                }
                connect(resume);
            }, delay, TimeUnit.SECONDS);
        }
    }

    private void permanentError(String reason) {
        synchronized (lock) {
            stopping = true;
            gatewayReady = false;
            cancelHeartbeat();
            cancelReconnect();
            if (socket != null) {
                socket.cancel();
                socket = null;
            }
            clearSession();
            updateState("error", reason);
            updateNotification(getString(R.string.gateway_notification_error));
            log.error(getString(R.string.log_gateway_permanent_error, reason));
        }
    }

    private void clearSession() {
        sessionId = "";
        resumeGatewayUrl = "";
        sequence = -1L;
    }

    private void stopGateway(boolean stopService) {
        synchronized (lock) {
            stopping = true;
            gatewayReady = false;
            cancelHeartbeat();
            cancelReconnect();
            if (socket != null) {
                socket.close(1000, "Crystal Planner presence disabled");
                socket = null;
            }
            clearSession();
            updateState("stopped", "");
        }
        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void cancelHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }
    }

    private void cancelReconnect() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(true);
            reconnectFuture = null;
        }
    }

    private void updateState(String state, String detail) {
        stateStore.setGatewayState(state, detail);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.gateway_notification_channel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.gateway_notification_channel_description));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void startForegroundNotification(String text) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                : 0;
        ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(text),
                type
        );
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent stopIntent = new Intent(this, GatewayPresenceService.class)
                .setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_crystal)
                .setContentTitle(getString(R.string.gateway_notification_title))
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, getString(R.string.gateway_notification_stop), stopPending)
                .build();
    }

    private static String gatewayUrl(String base) {
        if (base == null || base.trim().isEmpty()) return GATEWAY_URL;
        String value = base.trim();
        return value + (value.contains("?") ? "&" : "?") + "v=10&encoding=json";
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? (error == null ? "Unknown error" : error.getClass().getSimpleName())
                : message;
    }

    private final class GatewayListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            synchronized (lock) {
                if (!stopping) socket = webSocket;
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleMessage(text);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            synchronized (lock) {
                if (socket == webSocket) socket = null;
                gatewayReady = false;
            }
            if (stopping) return;
            if (code == 4004 || (code >= 4010 && code <= 4014)) {
                permanentError("Discord Gateway closed " + code + ": " + reason);
            } else {
                reconnect(true, "Closed " + code + ": " + reason);
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable error, Response response) {
            synchronized (lock) {
                if (socket == webSocket) socket = null;
                gatewayReady = false;
            }
            if (!stopping) reconnect(true, safeMessage(error));
        }
    }
}
