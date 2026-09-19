package kr.co.kitchenhelper.inspector;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Small local ring buffer. No notification content leaves the phone. */
public final class SnapshotStore {
    private static final String PREFS = "kakao_notification_diagnostics";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 30;

    private SnapshotStore() {}

    public static synchronized void add(Context context, NotificationSnapshot snapshot) {
        JSONArray previous = readArray(context);
        JSONArray next = new JSONArray();
        try {
            next.put(snapshot.toJson());
            for (int i = 0; i < Math.min(previous.length(), MAX_ENTRIES - 1); i++) {
                next.put(previous.get(i));
            }
            prefs(context).edit().putString(KEY_ENTRIES, next.toString()).apply();
        } catch (JSONException ignored) {
            // A malformed older entry must never stop capture of future notifications.
        }
    }

    public static synchronized List<NotificationSnapshot> load(Context context) {
        JSONArray array = readArray(context);
        List<NotificationSnapshot> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) continue;
            try {
                result.add(NotificationSnapshot.fromJson(json));
            } catch (JSONException ignored) {
                // Skip only the damaged item.
            }
        }
        return result;
    }

    public static synchronized void clear(Context context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply();
    }

    private static JSONArray readArray(Context context) {
        String stored = prefs(context).getString(KEY_ENTRIES, "[]");
        try {
            return new JSONArray(stored);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
