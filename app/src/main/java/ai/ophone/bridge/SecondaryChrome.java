package ai.ophone.bridge;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Screens opened from the chat drawer (and the screens they open): a bar with a back button at the top left, and a
 * swipe in from the left or right screen edge that slides the screen away with the finger and goes back.
 * Installed once per activity from the application's lifecycle callbacks; screens keep their own layout.
 */
final class SecondaryChrome {
    private static final Set<String> SCREENS = new HashSet<>(Arrays.asList(
            "SettingsActivity", "AboutActivity", "FeedbackActivity", "ScheduleActivity", "ScheduleEditActivity", "ToolsActivity",
            "ShareActivity", "ModelApiSettingsActivity", "AppPolicyActivity", "AuditActivity", "MemoryActivity", "ApiConversationActivity"));
    private SecondaryChrome() {}

    static boolean wants(Activity a) {
        String name = a.getClass().getSimpleName();
        if ("PlatformActivity".equals(name)) return a.getIntent() != null && a.getIntent().hasExtra(PlatformActivity.EXTRA_SWITCH_FROM);
        return SCREENS.contains(name);
    }

    /** Wraps the activity's content once (after onCreate set it). */
    static void install(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() != 1 || content.getChildAt(0) instanceof Frame) return;
        View page = content.getChildAt(0);
        content.removeView(page);
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(UiKit.pageColor(a));
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(UiKit.dp(a, 8), UiKit.dp(a, 6), UiKit.dp(a, 8), 0);
        View back = new BackButton(a);
        back.setContentDescription(a.getString(R.string.back));
        back.setOnClickListener(v -> a.onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(UiKit.dp(a, 44), UiKit.dp(a, 44)));
        column.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        column.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        Frame frame = new Frame(a, column);
        content.addView(frame, new FrameLayout.LayoutParams(-1, -1));
    }

    /** A round glass button with a chevron. */
    private static final class BackButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        BackButton(Context c) {
            super(c);
            setBackground(UiKit.glass(c, 22));
            setClickable(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(UiKit.dp(c, 2.4f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(UiKit.primaryTextColor(c));
            android.util.TypedValue tv = new android.util.TypedValue();
            if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) setForeground(c.getDrawable(tv.resourceId));
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h) * 0.18f;
            path.reset();
            path.moveTo(w / 2 + s * 0.45f, h / 2 - s);
            path.lineTo(w / 2 - s * 0.55f, h / 2);
            path.lineTo(w / 2 + s * 0.45f, h / 2 + s);
            canvas.drawPath(path, paint);
        }
    }

    /** Slides the screen with a finger pulled in from either edge; past a third (or a fling) it goes back. */
    private static final class Frame extends FrameLayout {
        private final Activity activity;
        private final View screen;
        private final int edge, slop;
        private float downX, downY;
        private int side; // -1 left edge, 1 right edge, 0 none
        private boolean dragging;
        private VelocityTracker velocity;

        Frame(Activity a, View screen) {
            super(a);
            activity = a;
            this.screen = screen;
            edge = UiKit.dp(a, 24);
            slop = ViewConfiguration.get(a).getScaledTouchSlop();
            addView(screen, new LayoutParams(-1, -1));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (Build.VERSION.SDK_INT >= 29 && getWidth() > 0) {
                int band = Math.min(getHeight(), UiKit.dp(getContext(), 200)), top = Math.max(0, (int) (getHeight() * 0.6f) - band / 2);
                List<Rect> rects = new ArrayList<>();
                rects.add(new Rect(0, top, edge, top + band));
                rects.add(new Rect(getWidth() - edge, top, getWidth(), top + band));
                setSystemGestureExclusionRects(rects);
            }
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); dragging = false;
                    side = downX <= edge ? -1 : downX >= getWidth() - edge ? 1 : 0;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (side == 0) return false;
                    float inward = side < 0 ? e.getX() - downX : downX - e.getX(), dy = Math.abs(e.getY() - downY);
                    if (inward > slop && inward > dy * 1.5f) {
                        dragging = true;
                        velocity = VelocityTracker.obtain();
                        velocity.addMovement(e);
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    if (dy > slop) side = 0;
                    return false;
                default:
                    return false;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!dragging) return side != 0 && e.getActionMasked() == MotionEvent.ACTION_DOWN;
            if (velocity != null) velocity.addMovement(e);
            float inward = Math.max(0, side < 0 ? e.getX() - downX : downX - e.getX());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    screen.setTranslationX(-side * inward);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float speed = 0;
                    if (velocity != null) { velocity.computeCurrentVelocity(1000); speed = -side * velocity.getXVelocity(); velocity.recycle(); velocity = null; }
                    boolean leave = e.getActionMasked() == MotionEvent.ACTION_UP && (inward > getWidth() / 3f || speed > UiKit.dp(getContext(), 900));
                    settle(leave);
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }

        private void settle(boolean leave) {
            float from = screen.getTranslationX(), to = leave ? -side * getWidth() : 0;
            ValueAnimator anim = ValueAnimator.ofFloat(from, to);
            anim.setDuration(leave ? 180 : 220);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> screen.setTranslationX((Float) a.getAnimatedValue()));
            if (leave) anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    activity.onBackPressed();
                    // Finished screens leave without the window animation (the finger already moved it away).
                    if (activity.isFinishing()) activity.overridePendingTransition(0, 0);
                    else screen.setTranslationX(0);
                }
            });
            anim.start();
        }
    }
}
