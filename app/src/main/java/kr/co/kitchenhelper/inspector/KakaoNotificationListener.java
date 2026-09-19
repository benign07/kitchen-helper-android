package kr.co.kitchenhelper.inspector;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.Display;
import android.view.WindowManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Receives KakaoTalk notifications and owns the persistent external-monitor board. */
public final class KakaoNotificationListener extends NotificationListenerService
        implements DisplayManager.DisplayListener {
    public static final String ACTION_ALERT_UPDATED = "kr.co.kitchenhelper.inspector.ALERT_UPDATED";
    public static final String EXTRA_NEW_MESSAGE_COUNT = "new_message_count";
    public static final String ACTION_BOARD_COMMAND = "kr.co.kitchenhelper.inspector.BOARD_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    public static final String COMMAND_REFRESH = "refresh";
    public static final String COMMAND_TEST = "test";
    public static final String COMMAND_STOP = "stop";

    private static final long WATCHDOG_INTERVAL_MS = 15_000L;
    private static final long SELF_REPLY_GUARD_MS = 6_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DingDongPlayer player = new DingDongPlayer();
    private final Map<String, Long> selfReplyGuards = new HashMap<>();
    private DisplayManager displayManager;
    private KitchenPresentation presentation;
    private int presentationDisplayId = Display.INVALID_DISPLAY;
    private boolean alerting;
    private boolean receiverRegistered;
    private PowerManager.WakeLock monitorWakeLock;

    private final Runnable attentionStopper = this::stopAttention;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            boolean changed = AlertStore.ensureCurrentMeal(
                    KakaoNotificationListener.this, System.currentTimeMillis());
            if (changed) stopAttention();
            ensurePresentation();
            renderBoard();
            long now = System.currentTimeMillis();
            long untilReset = MealWindow.at(KakaoNotificationListener.this, now)
                    .nextResetAtMillis - now;
            handler.postDelayed(this, Math.min(WATCHDOG_INTERVAL_MS,
                    Math.max(250L, untilReset + 100L)));
        }
    };

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String command = intent.getStringExtra(EXTRA_COMMAND);
            if (COMMAND_TEST.equals(command)) {
                ensurePresentation();
                renderBoard();
                startAttention();
            } else if (COMMAND_STOP.equals(command)) {
                stopAttention();
            } else {
                ensurePresentation();
                renderBoard();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        monitorWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "KitchenHelper:ExternalMonitor");
        monitorWakeLock.setReferenceCounted(false);
        displayManager.registerDisplayListener(this, handler);
        IntentFilter filter = new IntentFilter(ACTION_BOARD_COMMAND);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerLegacyReceiver(filter);
        }
        receiverRegistered = true;
        handler.post(watchdog);
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        rememberActiveRooms();
        ensurePresentation();
        renderBoard();
        broadcastUpdate(0);
    }

    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        requestRebind(new ComponentName(this, KakaoNotificationListener.class));
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (!NotificationSnapshot.KAKAO_PACKAGE.equals(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        NotificationSnapshot snapshot = NotificationSnapshot.from(sbn);
        String roomId = roomId(sbn);
        NotificationRouteStore.rememberRoom(this, roomId, roomName(notification, snapshot));
        broadcastUpdate(0);
        if (!NotificationRouteStore.accepts(this, roomId)) return;

        long now = System.currentTimeMillis();
        Long guardedUntil = selfReplyGuards.get(roomId);
        if (guardedUntil != null && guardedUntil > now) return;
        selfReplyGuards.remove(roomId);

        boolean filtered = AlertStore.isLatestExcluded(this, snapshot);
        if (filtered) {
            String latestBody = snapshot.bestBody();
            if (NotificationRouteStore.autoReplyEnabled(this)
                    && !NotificationRouteStore.isAutomaticReplyText(this, latestBody)
                    && sendDirectReply(notification, NotificationRouteStore.filteredReply(this))) {
                selfReplyGuards.put(roomId, now + SELF_REPLY_GUARD_MS);
            }
            return;
        }

        int added = AlertStore.addSnapshot(this, snapshot);
        boolean displayed = added > 0;
        if (displayed) {
            ensurePresentation();
            renderBoard();
            startAttention();
            broadcastUpdate(added);
        }

        if (displayed && NotificationRouteStore.autoReplyEnabled(this)) {
            String reply = displayed && presentation != null && presentation.isShowing()
                    ? NotificationRouteStore.successReply(this)
                    : NotificationRouteStore.failureReply(this);
            if (sendDirectReply(notification, reply)) {
                selfReplyGuards.put(roomId, now + SELF_REPLY_GUARD_MS);
            }
        }
    }

    @Override public void onDisplayAdded(int displayId) {
        handler.postDelayed(() -> { ensurePresentation(); renderBoard(); }, 500L);
    }

    @Override public void onDisplayRemoved(int displayId) {
        if (displayId == presentationDisplayId) dismissPresentation();
        handler.postDelayed(() -> { ensurePresentation(); renderBoard(); }, 1_000L);
        broadcastUpdate(0);
    }

    @Override public void onDisplayChanged(int displayId) {
        if (displayId == presentationDisplayId) renderBoard();
        else ensurePresentation();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopAttention();
        dismissPresentation();
        if (displayManager != null) displayManager.unregisterDisplayListener(this);
        if (receiverRegistered) unregisterReceiver(commandReceiver);
        releaseMonitorWakeLock();
        super.onDestroy();
    }

    public static void sendCommand(Context context, String command) {
        context.sendBroadcast(new Intent(ACTION_BOARD_COMMAND)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_COMMAND, command));
        requestRebind(new ComponentName(context, KakaoNotificationListener.class));
    }

    private void ensurePresentation() {
        Display target = choosePresentationDisplay();
        if (target == null) {
            dismissPresentation();
            releaseMonitorWakeLock();
            return;
        }
        acquireMonitorWakeLock();
        if (presentation != null && presentation.isShowing()
                && presentationDisplayId == target.getDisplayId()) return;
        dismissPresentation();
        try {
            KitchenPresentation next = new KitchenPresentation(this, target);
            next.setCanceledOnTouchOutside(false);
            next.setCancelable(false);
            next.setOnDismissListener(dialog -> {
                if (presentation == next) {
                    presentation = null;
                    presentationDisplayId = Display.INVALID_DISPLAY;
                    handler.postDelayed(this::ensurePresentation, 1_000L);
                }
            });
            next.show();
            presentation = next;
            presentationDisplayId = target.getDisplayId();
            if (alerting) next.startFlashing();
        } catch (WindowManager.InvalidDisplayException | WindowManager.BadTokenException ignored) {
            dismissPresentation();
        }
        broadcastUpdate(0);
    }

    private Display choosePresentationDisplay() {
        for (Display display : displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY && display.isValid()) return display;
        }
        return null;
    }

    private void dismissPresentation() {
        KitchenPresentation old = presentation;
        presentation = null;
        presentationDisplayId = Display.INVALID_DISPLAY;
        if (old != null) {
            old.setOnDismissListener(null);
            old.dismiss();
        }
    }

    private void acquireMonitorWakeLock() {
        if (monitorWakeLock != null && !monitorWakeLock.isHeld()) {
            monitorWakeLock.acquire();
        }
    }

    private void releaseMonitorWakeLock() {
        if (monitorWakeLock != null && monitorWakeLock.isHeld()) {
            monitorWakeLock.release();
        }
    }

    private void renderBoard() {
        if (presentation == null) return;
        long now = System.currentTimeMillis();
        AlertStore.ensureCurrentMeal(this, now);
        List<AlertStore.AlertMessage> messages = AlertStore.loadMessages(this, now);
        presentation.render(MealWindow.at(this, now), messages, AlertStore.waitingText(this), true);
        if (alerting) presentation.startFlashing();
    }

    private void startAttention() {
        handler.removeCallbacks(attentionStopper);
        alerting = true;
        if (presentation != null) presentation.startFlashing();
        player.play();
        handler.postDelayed(attentionStopper, 8_000L);
    }

    private void stopAttention() {
        handler.removeCallbacks(attentionStopper);
        alerting = false;
        if (presentation != null) presentation.stopFlashing();
        player.stop();
    }

    private boolean sendDirectReply(Notification notification, String message) {
        if (message == null || message.trim().isEmpty() || notification.actions == null) return false;
        for (Notification.Action action : notification.actions) {
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs == null || inputs.length == 0 || action.actionIntent == null) continue;
            Intent fillIn = new Intent();
            Bundle results = new Bundle();
            for (RemoteInput input : inputs) results.putCharSequence(input.getResultKey(), message);
            RemoteInput.addResultsToIntent(inputs, fillIn, results);
            try {
                action.actionIntent.send(this, 0, fillIn);
                return true;
            } catch (PendingIntent.CanceledException | RuntimeException ignored) {
                // Try another reply-capable action if KakaoTalk exposed one.
            }
        }
        return false;
    }

    private String roomId(StatusBarNotification sbn) {
        String shortcut = sbn.getNotification().getShortcutId();
        if (shortcut != null && !shortcut.isEmpty()) return "shortcut:" + shortcut;
        String tag = sbn.getTag();
        if (tag != null && !tag.isEmpty()) return "tag:" + tag;
        return "key:" + sbn.getKey();
    }

    private void rememberActiveRooms() {
        StatusBarNotification[] active = getActiveNotifications();
        if (active == null) return;
        for (StatusBarNotification sbn : active) {
            if (!NotificationSnapshot.KAKAO_PACKAGE.equals(sbn.getPackageName())) continue;
            if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            NotificationSnapshot snapshot = NotificationSnapshot.from(sbn);
            NotificationRouteStore.rememberRoom(this, roomId(sbn),
                    roomName(sbn.getNotification(), snapshot));
        }
    }

    private String roomName(Notification notification, NotificationSnapshot snapshot) {
        CharSequence conversation = notification.extras == null ? null
                : notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        return conversation == null || conversation.toString().trim().isEmpty()
                ? snapshot.title : conversation.toString();
    }

    private void broadcastUpdate(int added) {
        sendBroadcast(new Intent(ACTION_ALERT_UPDATED)
                .setPackage(getPackageName())
                .putExtra(EXTRA_NEW_MESSAGE_COUNT, added));
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(IntentFilter filter) {
        registerReceiver(commandReceiver, filter);
    }
}
