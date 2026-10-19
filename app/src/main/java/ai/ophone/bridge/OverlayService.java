package ai.ophone.bridge;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class OverlayService extends Service {
    private WindowManager windowManager;
    private View bubble;
    private WindowManager.LayoutParams params;

    @Override
    public void onCreate() {
        super.onCreate();
        showBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showBubble();
        return START_STICKY;
    }

    private void showBubble() {
        if (!RiskPolicy.overlayEnabled(this)) {
            stopSelf();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        if (bubble != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        TextView view = new TextView(this);
        view.setText("AI");
        view.setTextSize(16f);
        view.setGravity(Gravity.CENTER);
        view.setPadding(22, 14, 22, 14);
        view.setAlpha(0.82f);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 24;
        params.y = 360;

        final float[] down = new float[2];
        final int[] start = new int[2];
        final long[] downAt = new long[1];

        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                down[0] = event.getRawX();
                down[1] = event.getRawY();
                start[0] = params.x;
                start[1] = params.y;
                downAt[0] = System.currentTimeMillis();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - down[0];
                float dy = event.getRawY() - down[1];
                params.x = Math.max(0, start[0] - (int) dx);
                params.y = Math.max(0, start[1] + (int) dy);
                try { windowManager.updateViewLayout(bubble, params); } catch (Exception ignored) {}
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float dx = Math.abs(event.getRawX() - down[0]);
                float dy = Math.abs(event.getRawY() - down[1]);
                if (dx < 18 && dy < 18
                        && System.currentTimeMillis() - downAt[0] < 500) {
                    Intent open = new Intent(this, PlatformActivity.class);
                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(open);
                }
                return true;
            }
            return false;
        });

        bubble = view;
        try {
            windowManager.addView(bubble, params);
        } catch (Exception e) {
            bubble = null;
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (windowManager != null && bubble != null) {
            try { windowManager.removeView(bubble); } catch (Exception ignored) {}
        }
        bubble = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
