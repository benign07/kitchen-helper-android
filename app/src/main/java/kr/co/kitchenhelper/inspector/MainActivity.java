package kr.co.kitchenhelper.inspector;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Phone-side settings controller. The notification listener owns the monitor window. */
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(20, 29, 43);
    private static final int MUTED = Color.rgb(95, 106, 121);
    private static final int GREEN = Color.rgb(0, 133, 82);
    private static final int RED = Color.rgb(211, 43, 43);
    private static final int BLUE = Color.rgb(35, 91, 170);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayManager displayManager;
    private TextView monitorStatus;
    private TextView listenerStatus;
    private TextView mealStatus;
    private TextView messageCount;
    private EditText waitingInput;
    private EditText excludedWordsInput;
    private Spinner roomSpinner;
    private CheckBox autoReplyCheck;
    private EditText successReplyInput;
    private EditText failureReplyInput;
    private EditText filteredReplyInput;
    private Button breakfastResetButton;
    private Button lunchResetButton;
    private Button dinnerResetButton;
    private int breakfastResetMinutes;
    private int lunchResetMinutes;
    private int dinnerResetMinutes;
    private boolean receiverRegistered;
    private String visibleMealId = "";

    private final Runnable mealWatcher = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            MealWindow current = MealWindow.at(MainActivity.this, now);
            if (AlertStore.ensureCurrentMeal(MainActivity.this, now)
                    || !current.id.equals(visibleMealId)) {
                KakaoNotificationListener.sendCommand(MainActivity.this,
                        KakaoNotificationListener.COMMAND_REFRESH);
            }
            updateController();
            long untilReset = current.nextResetAtMillis - now;
            handler.postDelayed(this, Math.min(15_000L, Math.max(250L, untilReset + 100L)));
        }
    };

    private final BroadcastReceiver alertReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateController();
            refreshRoomSpinner();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        breakfastResetMinutes = MealScheduleStore.breakfast(this);
        lunchResetMinutes = MealScheduleStore.lunch(this);
        dinnerResetMinutes = MealScheduleStore.dinner(this);
        setContentView(buildController());
        refreshRoomSpinner();
        updateController();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(KakaoNotificationListener.ACTION_ALERT_UPDATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alertReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerLegacyReceiver(filter);
        }
        receiverRegistered = true;
        KakaoNotificationListener.sendCommand(this, KakaoNotificationListener.COMMAND_REFRESH);
        handler.post(mealWatcher);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshRoomSpinner();
        updateController();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(mealWatcher);
        if (receiverRegistered) {
            unregisterReceiver(alertReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private View buildController() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(24), dp(24), dp(32));
        page.setBackgroundColor(Color.WHITE);

        page.addView(text("조리실 알림판", 30, INK, Typeface.BOLD));
        TextView subtitle = text("외부 모니터 화면은 이 설정 화면을 닫아도 자동으로 유지·복구됩니다.",
                15, MUTED, Typeface.NORMAL);
        subtitle.setPadding(0, dp(6), 0, dp(20));
        page.addView(subtitle);

        monitorStatus = statusCard(page);
        listenerStatus = statusCard(page);
        mealStatus = statusCard(page);
        messageCount = statusCard(page);

        Button update = button("업데이트 확인 (현재 v" + currentVersionName() + ")", BLUE);
        update.setOnClickListener(v -> AppUpdater.check(this));
        page.addView(update, buttonParams());

        Button blackout = button("휴대폰 화면 검게 유지 (모니터 계속 표시)", INK);
        blackout.setOnClickListener(v -> {
            KakaoNotificationListener.sendCommand(this,
                    KakaoNotificationListener.COMMAND_REFRESH);
            startActivity(new Intent(this, PhoneBlackoutActivity.class));
        });
        page.addView(blackout, buttonParams());
        TextView blackoutHelp = text("전원 버튼을 누르지 말고 이 모드를 사용하세요. 검은 화면을 누르면 돌아옵니다.",
                14, MUTED, Typeface.NORMAL);
        blackoutHelp.setPadding(0, dp(8), 0, 0);
        page.addView(blackoutHelp);

        addSectionLabel(page, "알림판 초기화 시간");
        breakfastResetButton = button("", Color.rgb(90, 99, 112));
        breakfastResetButton.setOnClickListener(v -> chooseResetTime("조식",
                breakfastResetMinutes, value -> {
                    breakfastResetMinutes = value;
                    updateResetButtonLabels();
                }));
        page.addView(breakfastResetButton, buttonParams());
        lunchResetButton = button("", Color.rgb(90, 99, 112));
        lunchResetButton.setOnClickListener(v -> chooseResetTime("중식",
                lunchResetMinutes, value -> {
                    lunchResetMinutes = value;
                    updateResetButtonLabels();
                }));
        page.addView(lunchResetButton, buttonParams());
        dinnerResetButton = button("", Color.rgb(90, 99, 112));
        dinnerResetButton.setOnClickListener(v -> chooseResetTime("석식",
                dinnerResetMinutes, value -> {
                    dinnerResetMinutes = value;
                    updateResetButtonLabels();
                }));
        page.addView(dinnerResetButton, buttonParams());
        updateResetButtonLabels();
        Button saveSchedule = button("초기화 시간 저장", BLUE);
        saveSchedule.setOnClickListener(v -> saveResetSchedule());
        page.addView(saveSchedule, buttonParams());
        TextView scheduleHelp = text("설정 시각에 이전 끼니 내용이 사라지고 다음 끼니 알림판으로 전환됩니다.",
                14, MUTED, Typeface.NORMAL);
        scheduleHelp.setPadding(0, dp(8), 0, 0);
        page.addView(scheduleHelp);

        addSectionLabel(page, "표시할 카카오톡 채팅방");
        roomSpinner = new Spinner(this);
        roomSpinner.setPadding(dp(12), dp(4), dp(12), dp(4));
        roomSpinner.setBackground(fieldBackground());
        page.addView(roomSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));
        Button saveRoom = button("선택한 채팅방만 표시", BLUE);
        saveRoom.setOnClickListener(v -> saveSelectedRoom());
        page.addView(saveRoom, buttonParams());
        TextView roomHelp = text("채팅방이 없다면 그 방에서 카톡을 한 번 받은 뒤 목록을 확인하세요. 선택 전에는 모든 방을 표시합니다.",
                14, MUTED, Typeface.NORMAL);
        roomHelp.setPadding(0, dp(8), 0, 0);
        page.addView(roomHelp);

        addSectionLabel(page, "대기 문구");
        waitingInput = edit(AlertStore.waitingText(this), 2);
        page.addView(waitingInput);
        Button saveWaiting = button("대기 문구 저장", BLUE);
        saveWaiting.setOnClickListener(v -> {
            AlertStore.setWaitingText(this, waitingInput.getText().toString());
            waitingInput.setText(AlertStore.waitingText(this));
            KakaoNotificationListener.sendCommand(this, KakaoNotificationListener.COMMAND_REFRESH);
            Toast.makeText(this, "모니터 대기 문구를 저장했습니다.", Toast.LENGTH_SHORT).show();
        });
        page.addView(saveWaiting, buttonParams());

        addSectionLabel(page, "알림 제외 단어");
        excludedWordsInput = edit(NotificationRouteStore.excludedWordsText(this), 2);
        excludedWordsInput.setHint("예: 영양사\n업무 외");
        page.addView(excludedWordsInput);
        TextView excludedHelp = text("한 줄에 하나씩 입력하세요. 발신자 또는 원문에 포함되면 화면·알람·자동답장에서 제외됩니다.",
                14, MUTED, Typeface.NORMAL);
        excludedHelp.setPadding(0, dp(8), 0, 0);
        page.addView(excludedHelp);
        Button saveExcluded = button("제외 단어 저장", BLUE);
        saveExcluded.setOnClickListener(v -> {
            NotificationRouteStore.setExcludedWordsText(this,
                    excludedWordsInput.getText().toString());
            excludedWordsInput.setText(NotificationRouteStore.excludedWordsText(this));
            KakaoNotificationListener.sendCommand(this,
                    KakaoNotificationListener.COMMAND_REFRESH);
            updateController();
            Toast.makeText(this, "알림 제외 단어를 저장했습니다.", Toast.LENGTH_SHORT).show();
        });
        page.addView(saveExcluded, buttonParams());

        addSectionLabel(page, "카카오톡 자동답장");
        autoReplyCheck = new CheckBox(this);
        autoReplyCheck.setText("알림이 오는 모든 대상 채팅방에 자동답장 사용");
        autoReplyCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        autoReplyCheck.setChecked(NotificationRouteStore.autoReplyEnabled(this));
        page.addView(autoReplyCheck);
        TextView successLabel = text("정상 표시 답장", 15, INK, Typeface.BOLD);
        successLabel.setPadding(0, dp(8), 0, dp(6));
        page.addView(successLabel);
        successReplyInput = edit(NotificationRouteStore.successReply(this), 1);
        page.addView(successReplyInput);
        TextView failureLabel = text("모니터 표시 실패 답장", 15, INK, Typeface.BOLD);
        failureLabel.setPadding(0, dp(10), 0, dp(6));
        page.addView(failureLabel);
        failureReplyInput = edit(NotificationRouteStore.failureReply(this), 1);
        page.addView(failureReplyInput);
        TextView filteredLabel = text("필터 제외 답장", 15, INK, Typeface.BOLD);
        filteredLabel.setPadding(0, dp(10), 0, dp(6));
        page.addView(filteredLabel);
        filteredReplyInput = edit(NotificationRouteStore.filteredReply(this), 1);
        page.addView(filteredReplyInput);
        Button saveReply = button("자동답장 설정 저장", BLUE);
        saveReply.setOnClickListener(v -> {
            NotificationRouteStore.setAutoReplyEnabled(this, autoReplyCheck.isChecked());
            NotificationRouteStore.setReplyTexts(this, successReplyInput.getText().toString(),
                    failureReplyInput.getText().toString(), filteredReplyInput.getText().toString());
            successReplyInput.setText(NotificationRouteStore.successReply(this));
            failureReplyInput.setText(NotificationRouteStore.failureReply(this));
            filteredReplyInput.setText(NotificationRouteStore.filteredReply(this));
            Toast.makeText(this, "자동답장 설정을 저장했습니다.", Toast.LENGTH_SHORT).show();
        });
        page.addView(saveReply, buttonParams());

        Button test = button("화면·경보 테스트", RED);
        test.setOnClickListener(v -> KakaoNotificationListener.sendCommand(
                this, KakaoNotificationListener.COMMAND_TEST));
        page.addView(test, buttonParams());

        Button stop = button("알람·점멸 즉시 중지", GREEN);
        stop.setOnClickListener(v -> KakaoNotificationListener.sendCommand(
                this, KakaoNotificationListener.COMMAND_STOP));
        page.addView(stop, buttonParams());

        Button clear = button("현재 끼니 내용 지우기", Color.rgb(90, 99, 112));
        clear.setOnClickListener(v -> {
            AlertStore.clear(this, System.currentTimeMillis());
            KakaoNotificationListener.sendCommand(this, KakaoNotificationListener.COMMAND_STOP);
            KakaoNotificationListener.sendCommand(this, KakaoNotificationListener.COMMAND_REFRESH);
            updateController();
        });
        page.addView(clear, buttonParams());

        Button permission = button("알림 접근 권한 설정", Color.rgb(90, 99, 112));
        permission.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        page.addView(permission, buttonParams());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        return scroll;
    }

    private void saveSelectedRoom() {
        Object item = roomSpinner.getSelectedItem();
        if (!(item instanceof RoomChoice)) return;
        RoomChoice choice = (RoomChoice) item;
        String previous = NotificationRouteStore.selectedRoomId(this);
        NotificationRouteStore.setSelectedRoomId(this, choice.id);
        if (!previous.equals(choice.id)) AlertStore.clear(this, System.currentTimeMillis());
        KakaoNotificationListener.sendCommand(this, KakaoNotificationListener.COMMAND_REFRESH);
        Toast.makeText(this, choice.id.isEmpty() ? "모든 채팅방을 표시합니다."
                : choice.label + " 채팅방만 표시합니다.", Toast.LENGTH_SHORT).show();
        updateController();
    }

    private void chooseResetTime(String meal, int current, TimeSetter setter) {
        new TimePickerDialog(this, (view, hour, minute) -> setter.set(hour * 60 + minute),
                current / 60, current % 60, true).show();
    }

    private void updateResetButtonLabels() {
        if (breakfastResetButton == null) return;
        breakfastResetButton.setText("조식 초기화  "
                + MealScheduleStore.format(breakfastResetMinutes));
        lunchResetButton.setText("중식 초기화  "
                + MealScheduleStore.format(lunchResetMinutes));
        dinnerResetButton.setText("석식 초기화  "
                + MealScheduleStore.format(dinnerResetMinutes));
    }

    private void saveResetSchedule() {
        if (!MealScheduleStore.save(this, breakfastResetMinutes,
                lunchResetMinutes, dinnerResetMinutes)) {
            Toast.makeText(this, "초기화 시간은 조식 < 중식 < 석식 순서로 설정하세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        AlertStore.ensureCurrentMeal(this, System.currentTimeMillis());
        KakaoNotificationListener.sendCommand(this, KakaoNotificationListener.COMMAND_STOP);
        KakaoNotificationListener.sendCommand(this, KakaoNotificationListener.COMMAND_REFRESH);
        updateController();
        Toast.makeText(this, "알림판 초기화 시간을 저장했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void refreshRoomSpinner() {
        if (roomSpinner == null) return;
        String selected = NotificationRouteStore.selectedRoomId(this);
        List<RoomChoice> choices = new ArrayList<>();
        choices.add(new RoomChoice("", "모든 카카오톡 채팅방"));
        for (NotificationRouteStore.Room room : NotificationRouteStore.rooms(this)) {
            choices.add(new RoomChoice(room.id, room.name));
        }
        ArrayAdapter<RoomChoice> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, choices);
        roomSpinner.setAdapter(adapter);
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).id.equals(selected)) {
                roomSpinner.setSelection(i);
                return;
            }
        }
        roomSpinner.setSelection(0);
    }

    private void updateController() {
        if (monitorStatus == null) return;
        long now = System.currentTimeMillis();
        AlertStore.ensureCurrentMeal(this, now);
        MealWindow window = MealWindow.at(this, now);
        visibleMealId = window.id;
        List<AlertStore.AlertMessage> messages = AlertStore.loadMessages(this, now);
        boolean listenerEnabled = isListenerEnabled();
        Display display = choosePresentationDisplay();
        if (display == null) {
            monitorStatus.setText("외부 모니터: 연결되지 않음 (연결 시 자동 복구)");
            monitorStatus.setTextColor(RED);
        } else {
            Point size = new Point();
            display.getRealSize(size);
            monitorStatus.setText(String.format(Locale.KOREA,
                    "외부 모니터: 연결됨·자동 복구 사용 (%d×%d)", size.x, size.y));
            monitorStatus.setTextColor(GREEN);
        }
        listenerStatus.setText(listenerEnabled ? "카카오톡 알림 접근: 정상"
                : "카카오톡 알림 접근: 허용 필요");
        listenerStatus.setTextColor(listenerEnabled ? GREEN : RED);
        mealStatus.setText("현재 구간: " + window.heading);
        messageCount.setText(String.format(Locale.KOREA, "현재 누적 메시지: %d개", messages.size()));
    }

    private Display choosePresentationDisplay() {
        for (Display display : displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY && display.isValid()) return display;
        }
        return null;
    }

    private boolean isListenerEnabled() {
        ComponentName component = new ComponentName(this, KakaoNotificationListener.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            return manager != null && manager.isNotificationListenerAccessGranted(component);
        }
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(component.flattenToString());
    }

    private EditText edit(String value, int minLines) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(minLines);
        input.setMaxLines(Math.max(minLines, 3));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setBackground(fieldBackground());
        input.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private void addSectionLabel(LinearLayout page, String label) {
        TextView view = text(label, 17, INK, Typeface.BOLD);
        view.setPadding(0, dp(18), 0, dp(8));
        page.addView(view);
    }

    private TextView statusCard(LinearLayout page) {
        TextView view = text("", 17, INK, Typeface.BOLD);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackground(fieldBackground());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(8));
        page.addView(view, params);
        return view;
    }

    private TextView text(String value, float sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        return view;
    }

    private Button button(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(12));
        button.setBackground(background);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        params.setMargins(0, dp(10), 0, 0);
        return params;
    }

    private GradientDrawable fieldBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(246, 247, 249));
        background.setStroke(dp(1), Color.rgb(214, 219, 226));
        background.setCornerRadius(dp(10));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String currentVersionName() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null ? "?" : version;
        } catch (Exception ignored) {
            return "?";
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(IntentFilter filter) {
        registerReceiver(alertReceiver, filter);
    }

    private static final class RoomChoice {
        final String id;
        final String label;
        RoomChoice(String id, String label) { this.id = id; this.label = label; }
        @Override public String toString() { return label; }
    }

    private interface TimeSetter {
        void set(int minutes);
    }
}
