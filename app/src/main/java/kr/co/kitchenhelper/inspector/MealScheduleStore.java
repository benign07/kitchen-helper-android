package kr.co.kitchenhelper.inspector;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** User-configurable meal reset boundaries, stored as minutes after midnight. */
public final class MealScheduleStore {
    private static final String PREFS = "meal_schedule";
    private static final String KEY_BREAKFAST = "breakfast_reset_minutes";
    private static final String KEY_LUNCH = "lunch_reset_minutes";
    private static final String KEY_DINNER = "dinner_reset_minutes";

    private MealScheduleStore() {}

    public static int breakfast(Context context) {
        return prefs(context).getInt(KEY_BREAKFAST, 9 * 60);
    }

    public static int lunch(Context context) {
        return prefs(context).getInt(KEY_LUNCH, 13 * 60);
    }

    public static int dinner(Context context) {
        return prefs(context).getInt(KEY_DINNER, 21 * 60);
    }

    public static boolean isValid(int breakfast, int lunch, int dinner) {
        return breakfast > 0 && breakfast < lunch && lunch < dinner && dinner < 24 * 60;
    }

    public static boolean save(Context context, int breakfast, int lunch, int dinner) {
        if (!isValid(breakfast, lunch, dinner)) return false;
        prefs(context).edit()
                .putInt(KEY_BREAKFAST, breakfast)
                .putInt(KEY_LUNCH, lunch)
                .putInt(KEY_DINNER, dinner)
                .apply();
        return true;
    }

    public static String format(int minutes) {
        return String.format(Locale.KOREA, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
