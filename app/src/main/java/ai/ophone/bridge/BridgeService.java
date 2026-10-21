package ai.ophone.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class BridgeService extends Service {
    static final String ACTION_RUN_SCHEDULES = "ai.ophone.bridge.RUN_SCHEDULES";
    private volatile boolean running;
    private ServerSocket serverSocket;
    private final ExecutorService clientPool = Executors.newFixedThreadPool(8);
    private volatile int boundPort = LocalServerConfig.DEFAULT_PORT;
    private LocalToolKernel kernel;
    private LocalMcpProtocol mcp;
    private final android.content.BroadcastReceiver packageEvents=new android.content.BroadcastReceiver(){
        @Override public void onReceive(Context unused,Intent intent){if(intent.getData()!=null&&!intent.getBooleanExtra(Intent.EXTRA_REPLACING,false))AgentEvents.get(BridgeService.this).packageChanged(intent.getData().getSchemeSpecificPart(),Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction()));}
    };
    private final java.util.concurrent.ScheduledExecutorService taskSupervisor=Executors.newSingleThreadScheduledExecutor();

    private static final String CHANNEL_ID = "ophoneai_bridge";
    private static final int NOTIFICATION_ID = 8765;

    @Override
    public void onCreate() {
        super.onCreate();
        TaskProgress.track(getApplication());
        startForegroundMode();

        kernel = new LocalToolKernel(this);
        mcp = new LocalMcpProtocol(kernel);
        LocalMcpSecurity.token(this);

        startServer();
        android.content.IntentFilter packageFilter=new android.content.IntentFilter();packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);packageFilter.addDataScheme("package");
        if(Build.VERSION.SDK_INT>=33)registerReceiver(packageEvents,packageFilter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(packageEvents,packageFilter);
        taskSupervisor.scheduleWithFixedDelay(()->{
            AccessibilityHealth.supervise(this);
            TaskController.get(this).supervise();
            DirectModelAgent.supervise(this);
            LogcatEventMonitor.update(this);
            startForegroundMode();
        },2,5,java.util.concurrent.TimeUnit.SECONDS);
        refreshOverlay();
    }

    private void startForegroundMode() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_channel_desc));
            manager.createNotificationChannel(channel);
        }

        Intent openIntent = new Intent(this, PlatformActivity.class);
        openIntent.putExtra("url",TaskController.get(this).resumeUrl());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, openIntent, flags);

        Intent settingsIntent = new Intent(this, SettingsActivity.class);
        PendingIntent settingsPendingIntent =
                PendingIntent.getActivity(this, 1, settingsIntent, flags);

        Notification.Builder builder =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(this, CHANNEL_ID)
                        : new Notification.Builder(this);

        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(TaskController.get(this).notificationText())
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_view,
                        getString(R.string.notification_chat), pendingIntent)
                .addAction(android.R.drawable.ic_menu_preferences,
                        getString(R.string.notification_permissions), settingsPendingIntent)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    // The once-a-day limit lives in UpdateManager; this only gives a long-running service a chance to check.
    private final android.os.Handler updateTimer = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable updateCheck = new Runnable() { @Override public void run() { UpdateManager.maybeCheck(BridgeService.this); updateTimer.postDelayed(this, 3 * 60 * 60 * 1000L); } };
    private void scheduleUpdateCheck() { updateTimer.removeCallbacks(updateCheck); updateTimer.postDelayed(updateCheck, 15000); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundMode();
        if (intent != null && ACTION_RUN_SCHEDULES.equals(intent.getAction()))
            new Thread(() -> ScheduleRunner.runDue(this), "OPhoneAI-schedule").start();
        if (!running) startServer();
        refreshOverlay();
        scheduleUpdateCheck();
        return START_STICKY;
    }

    private void refreshOverlay() {
        if (RiskPolicy.overlayEnabled(this)) {
            try { startService(new Intent(this, OverlayService.class)); } catch (Exception ignored) {}
        } else {
            try { stopService(new Intent(this, OverlayService.class)); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        try{unregisterReceiver(packageEvents);}catch(Exception ignored){}LogcatEventMonitor.stop();
        taskSupervisor.shutdownNow();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        WorkbenchClient.get(this).close();
        clientPool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private synchronized void startServer() {
        if (running) return;
        running = true;

        new Thread(() -> {
            try {
                serverSocket = bindLocalServer();

                while (running) {
                    Socket socket = serverSocket.accept();
                    try {
                        clientPool.execute(() -> handle(socket));
                    } catch (RejectedExecutionException e) {
                        try { socket.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {
                running = false;
            }
        }, "OPhoneAI-HTTP").start();
    }

    private ServerSocket bindLocalServer() throws IOException {
        IOException last = null;
        int preferred = LocalServerConfig.port(this);

        try {
            return bindPort(preferred);
        } catch (IOException e) {
            last = e;
        }

        for (int port = LocalServerConfig.MIN_PORT;
             port <= LocalServerConfig.MAX_PORT;
             port++) {
            if (port == preferred) continue;
            try {
                return bindPort(port);
            } catch (IOException e) {
                last = e;
            }
        }

        throw last == null
                ? new IOException("no_local_port_available")
                : last;
    }

    private ServerSocket bindPort(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port), 32);
            boundPort = port;
            LocalServerConfig.setPort(this, port);
            return socket;
        } catch (IOException e) {
            try { socket.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(20000);

            BufferedInputStream in =
                    new BufferedInputStream(socket.getInputStream());
            String headerText = readHeaderBlock(in, 64 * 1024);
            if (headerText == null || headerText.isEmpty()) return;

            String[] lines = headerText.split("\r?\n");
            String[] requestParts = lines[0].split(" ");
            String method = requestParts.length > 0 ? requestParts[0] : "GET";
            String target = requestParts.length > 1 ? requestParts[1] : "/";

            Map<String, String> headers = new HashMap<>();
            int contentLength = 0;

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int p = line.indexOf(':');
                if (p <= 0) continue;

                String name = line.substring(0, p).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(p + 1).trim();
                headers.put(name, value);

                if ("content-length".equals(name)) {
                    contentLength = Integer.parseInt(value);
                }
            }

            if (contentLength < 0 || contentLength > 16 * 1024 * 1024) {
                writeJson(socket, 400, error("invalid_content_length"));
                return;
            }

            byte[] bodyBytes = readExactly(in, contentLength);
            String body = new String(bodyBytes, StandardCharsets.UTF_8);

            if ("/mcp".equals(target)) {
                if (!"POST".equals(method)) {
                    writeJson(socket, 405, error("method_not_allowed"));
                    return;
                }

                String token = headers.get("x-ophoneai-local");
                if (!LocalMcpSecurity.matches(this, token)) {
                    writeJson(socket, 403, error("forbidden"));
                    return;
                }

                JSONObject request =
                        body.isEmpty() ? new JSONObject() : new JSONObject(body);
                JSONObject response = mcp.handle(request);
                writeJson(socket, 200, response);
                return;
            }

            if (target.equals("/") || target.startsWith("/index")) {
                write(socket, 200, "text/html; charset=utf-8",
                        loadAsset("web/index.html"));
                return;
            }

            if (target.startsWith("/api/")) {
                String token = headers.get("x-ophoneai-local");
                if (!LocalMcpSecurity.matches(this, token)) {
                    writeJson(socket, 403, error("forbidden"));
                    return;
                }
            }

            if ("/api/status".equals(target)) {
                writeJson(socket, 200, kernel.deviceStatus());
                return;
            }

            if ("/api/ui".equals(target)) {
                writeJson(socket, 200,
                        kernel.call("get_ui", new JSONObject()));
                return;
            }

            if ("/api/action".equals(target) && "POST".equals(method)) {
                JSONObject input =
                        body.isEmpty() ? new JSONObject() : new JSONObject(body);
                JSONObject args = new JSONObject();
                String type = input.optString("type");
                String tool;

                if ("tap".equals(type)) {
                    tool = "tap";
                    args.put("x", input.optDouble("x"));
                    args.put("y", input.optDouble("y"));
                } else if ("swipe".equals(type)) {
                    tool = "swipe";
                    args.put("x1", input.optDouble("x1"));
                    args.put("y1", input.optDouble("y1"));
                    args.put("x2", input.optDouble("x2"));
                    args.put("y2", input.optDouble("y2"));
                    args.put("duration_ms", input.optLong("duration", 400));
                } else if ("global".equals(type)) {
                    tool = "press_global";
                    args.put("action", input.optString("action"));
                } else if ("text".equals(type)) {
                    tool = "input_text";
                    args.put("text", input.optString("text"));
                } else if ("click_text".equals(type)) {
                    tool = "click_text";
                    args.put("text", input.optString("text"));
                    args.put("contains", input.optBoolean("contains", true));
                } else if ("launch".equals(type)) {
                    tool = "launch_app";
                    args.put("package", input.optString("package"));
                } else {
                    writeJson(socket, 400, error("unknown_action"));
                    return;
                }

                writeJson(socket, 200, kernel.call(tool, args));
                return;
            }

            if ("/api/shell".equals(target)) {
                writeJson(socket, 404, error("shell_not_exposed"));
                return;
            }

            writeJson(socket, 404, error("not_found"));
        } catch (Exception e) {
            try {
                writeJson(socket, 500,
                        error(e.getClass().getSimpleName() + ": "
                                + String.valueOf(e.getMessage())));
            } catch (Exception ignored) {
            }
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String readHeaderBlock(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int state = 0;

        while (out.size() < max) {
            int b = in.read();
            if (b < 0) break;
            out.write(b);

            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = (b == '\r') ? 1 : 0;
        }

        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;

        while (offset < length) {
            int n = in.read(data, offset, length - offset);
            if (n < 0) break;
            offset += n;
        }

        if (offset == length) return data;

        byte[] shortData = new byte[offset];
        System.arraycopy(data, 0, shortData, 0, offset);
        return shortData;
    }

    private String loadAsset(String name) throws IOException {
        InputStream in = getAssets().open(name);
        return readText(in, 512 * 1024);
    }

    private String readText(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int n;

        while ((n = in.read(buf)) >= 0) {
            int take = Math.min(n, max - total);
            if (take > 0) out.write(buf, 0, take);
            total += take;
            if (total >= max) break;
        }

        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private JSONObject error(String message) throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }

    private void writeJson(Socket socket, int code, JSONObject object)
            throws IOException {
        write(socket, code, "application/json; charset=utf-8",
                object.toString());
    }

    private void write(Socket socket, int code, String type, String body)
            throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String status = statusText(code);

        OutputStream out = socket.getOutputStream();
        String head =
                "HTTP/1.1 " + code + " " + status + "\r\n"
                        + "Content-Type: " + type + "\r\n"
                        + "Content-Length: " + data.length + "\r\n"
                        + "Connection: close\r\n"
                        + "Cache-Control: no-store\r\n\r\n";

        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private String statusText(int code) {
        if (code == 200) return "OK";
        if (code == 400) return "Bad Request";
        if (code == 403) return "Forbidden";
        if (code == 404) return "Not Found";
        if (code == 405) return "Method Not Allowed";
        if (code == 503) return "Service Unavailable";
        return "Internal Server Error";
    }
}
