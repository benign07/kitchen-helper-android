package kr.co.kitchenhelper.inspector;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Native-resolution board used on the external presentation display. */
public final class AlertBoardView extends FrameLayout {
    private static final int INK = Color.rgb(18, 25, 36);
    private static final int MUTED = Color.rgb(103, 113, 126);
    private static final int GREEN = Color.rgb(0, 137, 84);
    private static final int RED = Color.rgb(220, 38, 38);

    private final TextView heading;
    private final TextView waiting;
    private final LinearLayout content;
    private final TextView status;
    private final ScrollView scroll;
    private ValueAnimator flashAnimator;
    private ValueAnimator scrollAnimator;
    private String renderedText = "";
    private String currentWaitingText = "대기중";
    private final Runnable scrollCycle = this::startScrollCycle;
    private final Runnable resetScrollCycle = this::resetScrollAfterEnd;
    private final Runnable clockTicker = this::tickClock;

    public AlertBoardView(Context context) {
        super(context);
        setPadding(dp(18), dp(18), dp(18), dp(18));
        setBackground(background(Color.rgb(218, 222, 228), dp(4)));

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(54), dp(34), dp(54), dp(30));
        addView(page, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        View topRule = new View(context);
        topRule.setBackgroundColor(INK);
        page.addView(topRule, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(5)));

        heading = text(46, INK, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headingParams.setMargins(0, dp(20), 0, dp(20));
        page.addView(heading, headingParams);

        View divider = new View(context);
        divider.setBackgroundColor(INK);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(5));
        dividerParams.setMargins(0, 0, 0, dp(20));
        page.addView(divider, dividerParams);

        FrameLayout body = new FrameLayout(context);
        page.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        waiting = text(76, INK, Typeface.BOLD);
        waiting.setGravity(Gravity.CENTER);
        waiting.setAutoSizeTextTypeUniformWithConfiguration(42, 92, 2,
                TypedValue.COMPLEX_UNIT_SP);
        body.addView(waiting, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(10), dp(20), dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        status = text(18, MUTED, Typeface.NORMAL);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(12), 0, 0);
        page.addView(status, statusParams);
    }

    public void render(MealWindow window, List<AlertStore.AlertMessage> messages,
                       String waitingText, boolean listenerEnabled) {
        heading.setText(window.heading);
        status.setText(listenerEnabled ? "● 카카오톡 알림 수신 중" : "● 알림 접근 권한이 필요합니다");
        status.setTextColor(listenerEnabled ? GREEN : RED);
        boolean empty = messages.isEmpty();
        currentWaitingText = waitingText;
        waiting.setVisibility(empty ? VISIBLE : GONE);
        scroll.setVisibility(empty ? GONE : VISIBLE);

        if (empty) {
            tickClock();
            content.removeAllViews();
            renderedText = "";
            stopAutoScroll();
            stopFlashing();
            return;
        }

        removeCallbacks(clockTicker);

        StringBuilder originalMessages = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            AlertStore.AlertMessage message = messages.get(i);
            if (i > 0) originalMessages.append("\n");
            originalMessages.append(message.body);
        }
        String nextText = originalMessages.toString();
        if (!nextText.equals(renderedText)) {
            renderedText = nextText;
            content.removeAllViews();
            for (int i = 0; i < messages.size(); i++) {
                TextView row = text(76, INK, Typeface.BOLD);
                row.setText(messages.get(i).body);
                row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                row.setLineSpacing(dp(10), 1.12f);
                row.setTextIsSelectable(true);
                row.setPadding(dp(28), dp(20), dp(28), dp(22));
                row.setBackgroundColor(i % 2 == 0
                        ? Color.WHITE : Color.rgb(241, 244, 247));
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, 0, 0, dp(6));
                content.addView(row, rowParams);
            }
            restartAutoScroll();
        }
    }

    public void startFlashing() {
        stopFlashing();
        flashAnimator = ValueAnimator.ofFloat(0f, 1f);
        flashAnimator.setDuration(800L);
        flashAnimator.setRepeatCount(ValueAnimator.INFINITE);
        flashAnimator.setRepeatMode(ValueAnimator.REVERSE);
        flashAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            int faded = Math.round(38 + 178 * fraction);
            setBackground(background(Color.rgb(220, faded, faded), dp(20)));
        });
        flashAnimator.start();
    }

    public void stopFlashing() {
        if (flashAnimator != null) {
            flashAnimator.cancel();
            flashAnimator = null;
        }
        setBackground(background(Color.rgb(218, 222, 228), dp(4)));
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(clockTicker);
        stopAutoScroll();
        stopFlashing();
        super.onDetachedFromWindow();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (waiting.getVisibility() == VISIBLE) tickClock();
    }

    private void tickClock() {
        removeCallbacks(clockTicker);
        if (waiting.getVisibility() != VISIBLE || !isAttachedToWindow()) return;
        long now = System.currentTimeMillis();
        String time = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date(now));
        waiting.setText(currentWaitingText + "  ·  현재시간 " + time);
        postDelayed(clockTicker, 1_000L - now % 1_000L);
    }

    private void restartAutoScroll() {
        stopAutoScroll();
        scroll.scrollTo(0, 0);
        scroll.postDelayed(scrollCycle, 3_000L);
    }

    private void startScrollCycle() {
        int range = Math.max(0, content.getHeight() - scroll.getHeight());
        if (range <= 0 || scroll.getVisibility() != VISIBLE) {
            scroll.scrollTo(0, 0);
            return;
        }
        long duration = Math.max(5_000L, Math.round(range / (double) dp(70) * 1_000L));
        scrollAnimator = ValueAnimator.ofInt(0, range);
        scrollAnimator.setDuration(duration);
        scrollAnimator.setInterpolator(new LinearInterpolator());
        scrollAnimator.addUpdateListener(value -> scroll.scrollTo(0, (Integer) value.getAnimatedValue()));
        scrollAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                scrollAnimator = null;
                scroll.postDelayed(resetScrollCycle, 4_000L);
            }
        });
        scrollAnimator.start();
    }

    private void resetScrollAfterEnd() {
        scroll.scrollTo(0, 0);
        scroll.postDelayed(scrollCycle, 3_000L);
    }

    private void stopAutoScroll() {
        scroll.removeCallbacks(scrollCycle);
        scroll.removeCallbacks(resetScrollCycle);
        if (scrollAnimator != null) {
            scrollAnimator.removeAllListeners();
            scrollAnimator.cancel();
            scrollAnimator = null;
        }
    }

    private TextView text(float sp, int color, int style) {
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        return view;
    }

    private GradientDrawable background(int borderColor, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.WHITE);
        drawable.setStroke(width, borderColor);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
