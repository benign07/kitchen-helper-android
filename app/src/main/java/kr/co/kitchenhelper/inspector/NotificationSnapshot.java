package kr.co.kitchenhelper.inspector;

import android.app.Notification;
import android.app.Person;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A local-only, text-focused copy of the data KakaoTalk posted to Android. */
public final class NotificationSnapshot {
    public static final String KAKAO_PACKAGE = "com.kakao.talk";

    public final long receivedAt;
    public final long postedAt;
    public final String notificationKey;
    public final String packageName;
    public final String category;
    public final String groupKey;
    public final String title;
    public final String text;
    public final String bigText;
    public final String subText;
    public final String summaryText;
    public final String infoText;
    public final List<String> textLines;
    public final List<MessageEntry> messages;
    public final List<MessageEntry> historicMessages;
    public final List<String> rawExtras;

    private NotificationSnapshot(
            long receivedAt,
            long postedAt,
            String notificationKey,
            String packageName,
            String category,
            String groupKey,
            String title,
            String text,
            String bigText,
            String subText,
            String summaryText,
            String infoText,
            List<String> textLines,
            List<MessageEntry> messages,
            List<MessageEntry> historicMessages,
            List<String> rawExtras
    ) {
        this.receivedAt = receivedAt;
        this.postedAt = postedAt;
        this.notificationKey = safe(notificationKey);
        this.packageName = safe(packageName);
        this.category = safe(category);
        this.groupKey = safe(groupKey);
        this.title = safe(title);
        this.text = safe(text);
        this.bigText = safe(bigText);
        this.subText = safe(subText);
        this.summaryText = safe(summaryText);
        this.infoText = safe(infoText);
        this.textLines = textLines;
        this.messages = messages;
        this.historicMessages = historicMessages;
        this.rawExtras = rawExtras;
    }

    public static NotificationSnapshot from(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras == null ? Bundle.EMPTY : notification.extras;

        List<String> lines = new ArrayList<>();
        CharSequence[] lineValues = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lineValues != null) {
            for (CharSequence line : lineValues) {
                lines.add(safe(line));
            }
        }

        List<MessageEntry> currentMessages = extractMessages(
                extras.getParcelableArray(Notification.EXTRA_MESSAGES));
        List<MessageEntry> oldMessages = extractMessages(
                extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES));

        return new NotificationSnapshot(
                System.currentTimeMillis(),
                sbn.getPostTime(),
                sbn.getKey(),
                sbn.getPackageName(),
                safe(notification.category),
                sbn.getGroupKey(),
                getText(extras, Notification.EXTRA_TITLE),
                getText(extras, Notification.EXTRA_TEXT),
                getText(extras, Notification.EXTRA_BIG_TEXT),
                getText(extras, Notification.EXTRA_SUB_TEXT),
                getText(extras, Notification.EXTRA_SUMMARY_TEXT),
                getText(extras, Notification.EXTRA_INFO_TEXT),
                lines,
                currentMessages,
                oldMessages,
                describeExtras(extras)
        );
    }

    public static NotificationSnapshot sample() {
        List<MessageEntry> messages = new ArrayList<>();
        messages.add(new MessageEntry("영양사", "샘플: 오늘 국은 미역국으로 변경합니다.", System.currentTimeMillis()));
        List<String> raw = new ArrayList<>();
        raw.add("android.title = 영양사");
        raw.add("android.text = 샘플: 오늘 국은 미역국으로 변경합니다.");
        return new NotificationSnapshot(
                System.currentTimeMillis(), System.currentTimeMillis(), "sample", KAKAO_PACKAGE,
                Notification.CATEGORY_MESSAGE, "sample-group", "영양사",
                "샘플: 오늘 국은 미역국으로 변경합니다.", "", "", "", "",
                new ArrayList<>(), messages, new ArrayList<>(), raw);
    }

    private static List<MessageEntry> extractMessages(Parcelable[] bundles) {
        List<MessageEntry> result = new ArrayList<>();
        if (bundles == null) {
            return result;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return extractModernMessages(bundles);
        }
        for (Parcelable parcelable : bundles) {
            if (!(parcelable instanceof Bundle)) continue;
            Bundle message = (Bundle) parcelable;
            String sender = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Parcelable senderValue = message.getParcelable("sender_person");
                Person person = senderValue instanceof Person ? (Person) senderValue : null;
                if (person != null) {
                    sender = safe(person.getName());
                }
            }
            if (sender.isEmpty()) sender = safe(message.getCharSequence("sender"));
            result.add(new MessageEntry(sender, safe(message.getCharSequence("text")),
                    message.getLong("time")));
        }
        return result;
    }

    @android.annotation.SuppressLint("NewApi")
    private static List<MessageEntry> extractModernMessages(Parcelable[] bundles) {
        List<MessageEntry> result = new ArrayList<>();
        for (Notification.MessagingStyle.Message message
                : Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)) {
            Person person = message.getSenderPerson();
            String sender = person == null ? "" : safe(person.getName());
            result.add(new MessageEntry(sender, safe(message.getText()), message.getTimestamp()));
        }
        return result;
    }

    private static List<String> describeExtras(Bundle extras) {
        List<String> result = new ArrayList<>();
        Set<String> keySet = extras.keySet();
        List<String> keys = new ArrayList<>(keySet);
        Collections.sort(keys);
        for (String key : keys) {
            Object value;
            try {
                value = extras.get(key);
            } catch (RuntimeException error) {
                result.add(key + " = <읽기 실패: " + error.getClass().getSimpleName() + ">");
                continue;
            }
            String description = printable(value);
            if (!description.isEmpty()) {
                result.add(key + " = " + description);
            }
        }
        return result;
    }

    private static String printable(Object value) {
        if (value == null) return "<null>";
        if (value instanceof CharSequence) return value.toString();
        if (value instanceof CharSequence[]) return TextUtils.join(" | ", (CharSequence[]) value);
        if (value instanceof String[]) return TextUtils.join(" | ", (String[]) value);
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Parcelable[]) return "<Parcelable[" + ((Parcelable[]) value).length + "]>";
        if (value instanceof Bundle) return "<Bundle keys=" + ((Bundle) value).keySet() + ">";
        String className = value.getClass().getSimpleName();
        if (className.contains("Bitmap") || className.contains("Icon")) {
            return "<" + className + ">";
        }
        String text = String.valueOf(value);
        return text.length() > 4000 ? text.substring(0, 4000) + "…<진단표시 제한>" : text;
    }

    public String bestBody() {
        if (!messages.isEmpty()) return messages.get(messages.size() - 1).text;
        if (!bigText.isEmpty()) return bigText;
        if (!text.isEmpty()) return text;
        if (!textLines.isEmpty()) return textLines.get(textLines.size() - 1);
        return "본문 없음";
    }

    public String fullReport() {
        StringBuilder out = new StringBuilder();
        out.append("수신: ").append(formatTime(receivedAt)).append('\n');
        out.append("게시: ").append(formatTime(postedAt)).append('\n');
        appendField(out, "TITLE", title);
        appendField(out, "TEXT", text);
        appendField(out, "BIG_TEXT", bigText);
        appendField(out, "SUB_TEXT", subText);
        appendField(out, "SUMMARY_TEXT", summaryText);
        appendField(out, "INFO_TEXT", infoText);
        out.append("TEXT_LINES [").append(textLines.size()).append("]\n");
        for (int i = 0; i < textLines.size(); i++) {
            appendField(out, "  #" + (i + 1), textLines.get(i));
        }
        appendMessages(out, "MESSAGES", messages);
        appendMessages(out, "HISTORIC_MESSAGES", historicMessages);
        out.append("분류: ").append(category).append('\n');
        out.append("그룹: ").append(groupKey).append('\n');
        out.append("키: ").append(notificationKey).append('\n');
        out.append("\n원시 extras(텍스트 중심)\n");
        for (String entry : rawExtras) out.append("- ").append(entry).append('\n');
        return out.toString().trim();
    }

    private static void appendMessages(StringBuilder out, String label, List<MessageEntry> entries) {
        out.append(label).append(" [").append(entries.size()).append("]\n");
        for (int i = 0; i < entries.size(); i++) {
            MessageEntry entry = entries.get(i);
            String value = entry.sender.isEmpty() ? entry.text : entry.sender + ": " + entry.text;
            appendField(out, "  #" + (i + 1), value);
        }
    }

    private static void appendField(StringBuilder out, String label, String value) {
        out.append(label).append(" (").append(value.length()).append("자): ")
                .append(value.isEmpty() ? "<없음>" : value).append('\n');
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("receivedAt", receivedAt);
        json.put("postedAt", postedAt);
        json.put("notificationKey", notificationKey);
        json.put("packageName", packageName);
        json.put("category", category);
        json.put("groupKey", groupKey);
        json.put("title", title);
        json.put("text", text);
        json.put("bigText", bigText);
        json.put("subText", subText);
        json.put("summaryText", summaryText);
        json.put("infoText", infoText);
        json.put("textLines", new JSONArray(textLines));
        json.put("messages", messagesToJson(messages));
        json.put("historicMessages", messagesToJson(historicMessages));
        json.put("rawExtras", new JSONArray(rawExtras));
        return json;
    }

    public static NotificationSnapshot fromJson(JSONObject json) throws JSONException {
        return new NotificationSnapshot(
                json.optLong("receivedAt"), json.optLong("postedAt"),
                json.optString("notificationKey"), json.optString("packageName"),
                json.optString("category"), json.optString("groupKey"),
                json.optString("title"), json.optString("text"), json.optString("bigText"),
                json.optString("subText"), json.optString("summaryText"), json.optString("infoText"),
                stringsFromJson(json.optJSONArray("textLines")),
                messagesFromJson(json.optJSONArray("messages")),
                messagesFromJson(json.optJSONArray("historicMessages")),
                stringsFromJson(json.optJSONArray("rawExtras")));
    }

    private static JSONArray messagesToJson(List<MessageEntry> messages) throws JSONException {
        JSONArray array = new JSONArray();
        for (MessageEntry entry : messages) {
            JSONObject json = new JSONObject();
            json.put("sender", entry.sender);
            json.put("text", entry.text);
            json.put("timestamp", entry.timestamp);
            array.put(json);
        }
        return array;
    }

    private static List<MessageEntry> messagesFromJson(JSONArray array) {
        List<MessageEntry> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json != null) {
                result.add(new MessageEntry(json.optString("sender"), json.optString("text"),
                        json.optLong("timestamp")));
            }
        }
        return result;
    }

    private static List<String> stringsFromJson(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) result.add(array.optString(i));
        return result;
    }

    private static String getText(Bundle extras, String key) {
        return safe(extras.getCharSequence(key));
    }

    private static String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static String formatTime(long time) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(new Date(time));
    }

    public static final class MessageEntry {
        public final String sender;
        public final String text;
        public final long timestamp;

        MessageEntry(String sender, String text, long timestamp) {
            this.sender = safe(sender);
            this.text = safe(text);
            this.timestamp = timestamp;
        }
    }
}
