package kr.co.kitchenhelper.inspector;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local-only message store for the active meal window. */
public final class AlertStore {
    private static final String PREFS = "kitchen_alert_board";
    private static final String KEY_MEAL_ID = "meal_id";
    private static final String KEY_MESSAGES = "messages";
    private static final String KEY_WAITING_TEXT = "waiting_text";
    private static final int MAX_MESSAGES = 200;

    private AlertStore() {}

    public static synchronized boolean ensureCurrentMeal(Context context, long now) {
        SharedPreferences preferences = prefs(context);
        String currentId = MealWindow.at(context, now).id;
        if (currentId.equals(preferences.getString(KEY_MEAL_ID, ""))) return false;
        preferences.edit().putString(KEY_MEAL_ID, currentId)
                .putString(KEY_MESSAGES, "[]").apply();
        return true;
    }

    /** Adds the notification's original message strings without keyword extraction. */
    public static synchronized int addSnapshot(Context context, NotificationSnapshot snapshot) {
        ensureCurrentMeal(context, snapshot.receivedAt);
        MealWindow active = MealWindow.at(context, snapshot.receivedAt);
        List<AlertMessage> messages = loadMessages(context, snapshot.receivedAt);
        int added = 0;

        if (!snapshot.messages.isEmpty()) {
            for (NotificationSnapshot.MessageEntry entry : snapshot.messages) {
                long time = entry.timestamp > 0 ? entry.timestamp : snapshot.receivedAt;
                if (!MealWindow.at(context, time).id.equals(active.id)) continue;
                String sender = entry.sender.isEmpty() ? snapshot.title : entry.sender;
                if (entry.text.isEmpty()) continue;
                if (NotificationRouteStore.isExcluded(context, sender, entry.text)) continue;
                added += addUnique(messages, new AlertMessage(sender, entry.text, time,
                        fingerprint(snapshot.notificationKey, sender, entry.text, time)));
            }
        } else {
            String body = snapshot.bestBody();
            if (!body.isEmpty() && !"본문 없음".equals(body)) {
                if (NotificationRouteStore.isExcluded(context, snapshot.title, body)) return 0;
                long time = snapshot.postedAt > 0 ? snapshot.postedAt : snapshot.receivedAt;
                added += addUnique(messages, new AlertMessage(snapshot.title, body, time,
                        fingerprint(snapshot.notificationKey, snapshot.title, body, time)));
            }
        }
        if (added > 0) save(context, active.id, messages);
        return added;
    }

    /** True when the newest text represented by this notification matches an exclusion word. */
    public static boolean isLatestExcluded(Context context, NotificationSnapshot snapshot) {
        if (!snapshot.messages.isEmpty()) {
            NotificationSnapshot.MessageEntry latest =
                    snapshot.messages.get(snapshot.messages.size() - 1);
            String sender = latest.sender.isEmpty() ? snapshot.title : latest.sender;
            return NotificationRouteStore.isExcluded(context, sender, latest.text);
        }
        return NotificationRouteStore.isExcluded(context, snapshot.title, snapshot.bestBody());
    }

    public static synchronized void addTestMessage(Context context, String body) {
        long now = System.currentTimeMillis();
        ensureCurrentMeal(context, now);
        List<AlertMessage> messages = loadMessages(context, now);
        AlertMessage test = new AlertMessage("테스트 발신자", body, now,
                fingerprint("test", "테스트 발신자", body, now));
        addUnique(messages, test);
        save(context, MealWindow.at(context, now).id, messages);
    }

    public static synchronized List<AlertMessage> loadMessages(Context context, long now) {
        ensureCurrentMeal(context, now);
        List<AlertMessage> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_MESSAGES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                AlertMessage message = new AlertMessage(item.optString("sender"),
                        item.optString("body"), item.optLong("time"),
                        item.optString("fingerprint"));
                if (!NotificationRouteStore.isExcluded(context, message.sender, message.body)) {
                    result.add(message);
                }
            }
        } catch (Exception ignored) {
            // Damaged local state is treated as empty.
        }
        return result;
    }

    public static synchronized void clear(Context context, long now) {
        prefs(context).edit().putString(KEY_MEAL_ID, MealWindow.at(context, now).id)
                .putString(KEY_MESSAGES, "[]").apply();
    }

    public static String waitingText(Context context) {
        return prefs(context).getString(KEY_WAITING_TEXT, "대기중");
    }

    public static void setWaitingText(Context context, String value) {
        String text = value == null ? "" : value.trim();
        prefs(context).edit().putString(KEY_WAITING_TEXT,
                text.isEmpty() ? "대기중" : text).apply();
    }

    private static int addUnique(List<AlertMessage> messages, AlertMessage candidate) {
        for (AlertMessage message : messages) {
            if (message.fingerprint.equals(candidate.fingerprint)) return 0;
        }
        messages.add(candidate);
        while (messages.size() > MAX_MESSAGES) messages.remove(0);
        return 1;
    }

    private static void save(Context context, String mealId, List<AlertMessage> messages) {
        JSONArray array = new JSONArray();
        for (AlertMessage message : messages) {
            JSONObject item = new JSONObject();
            try {
                item.put("sender", message.sender);
                item.put("body", message.body);
                item.put("time", message.time);
                item.put("fingerprint", message.fingerprint);
                array.put(item);
            } catch (Exception ignored) {
                return;
            }
        }
        prefs(context).edit().putString(KEY_MEAL_ID, mealId)
                .putString(KEY_MESSAGES, array.toString()).apply();
    }

    private static String fingerprint(String key, String sender, String body, long time) {
        String source = key + "|" + sender + "|" + body + "|" + time;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format(Locale.US, "%02x", value & 0xff));
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(source.hashCode());
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static final class AlertMessage {
        public final String sender;
        public final String body;
        public final long time;
        public final String fingerprint;

        AlertMessage(String sender, String body, long time, String fingerprint) {
            this.sender = sender == null ? "" : sender;
            this.body = body == null ? "" : body;
            this.time = time;
            this.fingerprint = fingerprint == null ? "" : fingerprint;
        }
    }
}
