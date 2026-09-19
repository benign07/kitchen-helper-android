package kr.co.kitchenhelper.inspector;

import android.app.Presentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.util.List;

/** Full-screen content hosted at the external monitor's native resolution. */
public final class KitchenPresentation extends Presentation {
    private AlertBoardView board;

    public KitchenPresentation(Context context, Display display) {
        super(context, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        board = new AlertBoardView(getContext());
        setContentView(board);

        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            WindowInsetsController controller = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? window.getInsetsController() : null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        }
    }

    public void render(MealWindow window, List<AlertStore.AlertMessage> messages,
                       String waitingText, boolean listenerEnabled) {
        if (board != null) board.render(window, messages, waitingText, listenerEnabled);
    }

    public void startFlashing() {
        if (board != null) board.startFlashing();
    }

    public void stopFlashing() {
        if (board != null) board.stopFlashing();
    }
}
