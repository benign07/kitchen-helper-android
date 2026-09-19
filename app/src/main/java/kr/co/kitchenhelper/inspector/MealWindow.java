package kr.co.kitchenhelper.inspector;

import android.content.Context;

import java.util.Calendar;
import java.util.Locale;

/** Maps time to a meal using the reset boundaries configured on the phone. */
public final class MealWindow {
    public final String id;
    public final String heading;
    public final int nextResetMinutes;
    public final long nextResetAtMillis;

    private MealWindow(String id, String heading, int nextResetMinutes,
                       long nextResetAtMillis) {
        this.id = id;
        this.heading = heading;
        this.nextResetMinutes = nextResetMinutes;
        this.nextResetAtMillis = nextResetAtMillis;
    }

    public static MealWindow at(Context context, long timeMillis) {
        Calendar received = Calendar.getInstance();
        received.setTimeInMillis(timeMillis);
        int minuteOfDay = received.get(Calendar.HOUR_OF_DAY) * 60
                + received.get(Calendar.MINUTE);
        int breakfastReset = MealScheduleStore.breakfast(context);
        int lunchReset = MealScheduleStore.lunch(context);
        int dinnerReset = MealScheduleStore.dinner(context);

        String meal;
        String suffix;
        int nextReset;
        Calendar target = (Calendar) received.clone();
        Calendar reset = (Calendar) received.clone();
        if (minuteOfDay < breakfastReset) {
            meal = "조식";
            suffix = "B";
            nextReset = breakfastReset;
        } else if (minuteOfDay < lunchReset) {
            meal = "중식";
            suffix = "L";
            nextReset = lunchReset;
        } else if (minuteOfDay < dinnerReset) {
            meal = "석식";
            suffix = "D";
            nextReset = dinnerReset;
        } else {
            meal = "조식";
            suffix = "B";
            nextReset = breakfastReset;
            target.add(Calendar.DAY_OF_MONTH, 1);
            reset.add(Calendar.DAY_OF_MONTH, 1);
        }
        reset.set(Calendar.HOUR_OF_DAY, nextReset / 60);
        reset.set(Calendar.MINUTE, nextReset % 60);
        reset.set(Calendar.SECOND, 0);
        reset.set(Calendar.MILLISECOND, 0);

        String dateId = String.format(Locale.US, "%04d%02d%02d",
                target.get(Calendar.YEAR), target.get(Calendar.MONTH) + 1,
                target.get(Calendar.DAY_OF_MONTH));
        String dateLabel = String.format(Locale.KOREA, "%d.%d.%d",
                target.get(Calendar.YEAR), target.get(Calendar.MONTH) + 1,
                target.get(Calendar.DAY_OF_MONTH));
        return new MealWindow(dateId + "-" + suffix,
                String.format(Locale.KOREA, "%s %s 식이 변경사항 (다음 초기화 %s)",
                        dateLabel, meal, MealScheduleStore.format(nextReset)),
                nextReset, reset.getTimeInMillis());
    }
}
