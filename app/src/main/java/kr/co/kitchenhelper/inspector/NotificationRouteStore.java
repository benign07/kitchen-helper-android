package kr.co.kitchenhelper.inspector;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Stores local routing and automatic-reply preferences. */
public final class NotificationRouteStore {
    private static final String PREFS = "notification_routes";
    private static final String KEY_ROOMS = "known_rooms";
    private static final String KEY_SELECTED = "selected_room";
    private static final String KEY_AUTO_REPLY = "auto_reply";
    private static final String KEY_SUCCESS_REPLY = "success_reply";
    private static final String KEY_FAILURE_REPLY = "failure_reply";
    private static final String KEY_FILTERED_REPLY = "filtered_reply";
    private static final String KEY_EXCLUDED_WORDS = "excluded_words";

    private NotificationRouteStore() {}

    public static synchronized void rememberRoom(Context context, String id, String name) {
        if (id == null || id.isEmpty()) return;
        List<Room> rooms = rooms(context);
        String cleanName = name == null || name.trim().isEmpty() ? "이름 없는 채팅방" : name.trim();
        boolean found = false;
        for (int i = 0; i < rooms.size(); i++) {
            if (rooms.get(i).id.equals(id)) {
                rooms.set(i, new Room(id, cleanName));
                found = true;
                break;
            }
        }
        if (!found) rooms.add(new Room(id, cleanName));
        JSONArray array = new JSONArray();
        for (Room room : rooms) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", room.id);
                item.put("name", room.name);
                array.put(item);
            } catch (Exception ignored) {
                return;
            }
        }
        prefs(context).edit().putString(KEY_ROOMS, array.toString()).apply();
    }

    public static synchronized List<Room> rooms(Context context) {
        List<Room> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_ROOMS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id");
                if (!id.isEmpty()) result.add(new Room(id, item.optString("name", "이름 없는 채팅방")));
            }
        } catch (Exception ignored) {
            // Damaged preferences are treated as empty.
        }
        return result;
    }

    public static String selectedRoomId(Context context) {
        return prefs(context).getString(KEY_SELECTED, "");
    }

    public static void setSelectedRoomId(Context context, String id) {
        prefs(context).edit().putString(KEY_SELECTED, id == null ? "" : id).apply();
    }

    public static boolean accepts(Context context, String roomId) {
        String selected = selectedRoomId(context);
        return selected.isEmpty() || selected.equals(roomId);
    }

    public static boolean autoReplyEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_REPLY, false);
    }

    public static void setAutoReplyEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_REPLY, enabled).apply();
    }

    public static String successReply(Context context) {
        return prefs(context).getString(KEY_SUCCESS_REPLY, "알림화면에 띄웠습니다.");
    }

    public static String failureReply(Context context) {
        return prefs(context).getString(KEY_FAILURE_REPLY, "문제가 발생했습니다. 직접 전달해 주세요.");
    }

    public static String filteredReply(Context context) {
        return prefs(context).getString(KEY_FILTERED_REPLY,
                "필터링되어 알림판에 뜨지 않았습니다.");
    }

    public static void setReplyTexts(Context context, String success, String failure,
                                     String filtered) {
        String ok = success == null ? "" : success.trim();
        String bad = failure == null ? "" : failure.trim();
        String excluded = filtered == null ? "" : filtered.trim();
        prefs(context).edit()
                .putString(KEY_SUCCESS_REPLY, ok.isEmpty() ? "알림화면에 띄웠습니다." : ok)
                .putString(KEY_FAILURE_REPLY, bad.isEmpty() ? "문제가 발생했습니다. 직접 전달해 주세요." : bad)
                .putString(KEY_FILTERED_REPLY, excluded.isEmpty()
                        ? "필터링되어 알림판에 뜨지 않았습니다." : excluded)
                .apply();
    }

    public static boolean isAutomaticReplyText(Context context, String body) {
        String text = body == null ? "" : body.trim();
        return text.equals(successReply(context)) || text.equals(failureReply(context))
                || text.equals(filteredReply(context));
    }

    public static String excludedWordsText(Context context) {
        return prefs(context).getString(KEY_EXCLUDED_WORDS, "영양사");
    }

    public static void setExcludedWordsText(Context context, String value) {
        String source = value == null ? "" : value;
        StringBuilder normalized = new StringBuilder();
        for (String part : source.split("[\\r\\n,]+")) {
            String word = part.trim();
            if (word.isEmpty()) continue;
            if (normalized.length() > 0) normalized.append('\n');
            normalized.append(word);
        }
        prefs(context).edit().putString(KEY_EXCLUDED_WORDS, normalized.toString()).apply();
    }

    public static boolean isExcluded(Context context, String sender, String body) {
        String haystack = ((sender == null ? "" : sender) + "\n"
                + (body == null ? "" : body)).toLowerCase(Locale.KOREA);
        for (String word : excludedWordsText(context).split("\\n")) {
            String needle = word.trim().toLowerCase(Locale.KOREA);
            if (!needle.isEmpty() && haystack.contains(needle)) return true;
        }
        return false;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static final class Room {
        public final String id;
        public final String name;

        Room(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override public String toString() { return name; }
    }
}
