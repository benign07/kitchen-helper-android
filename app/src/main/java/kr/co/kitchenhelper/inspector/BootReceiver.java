package kr.co.kitchenhelper.inspector;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.notification.NotificationListenerService;

/** Requests the system-bound notification listener after boot or an app update. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        NotificationListenerService.requestRebind(
                new ComponentName(context, KakaoNotificationListener.class));
    }
}
