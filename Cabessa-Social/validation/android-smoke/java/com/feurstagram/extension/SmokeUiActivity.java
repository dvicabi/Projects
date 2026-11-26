package com.feurstagram.extension;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** Screenshots render the real production widgets; the surrounding shell is a test fixture. */
public final class SmokeUiActivity extends Activity {
    static Locale locale = Locale.ENGLISH;
    static boolean dark;
    static float fontScale = 1f;

    @Override protected void attachBaseContext(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        config.fontScale = fontScale;
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    @Override public void onCreate(Bundle state) {
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(getResources().getConfiguration().getLayoutDirection());
        root.setBackgroundColor(dark ? Color.BLACK : Color.WHITE);
        root.setPadding(0, 90, 0, 50);
        TextView label = new TextView(this);
        label.setTextColor(dark ? Color.WHITE : Color.BLACK);
        label.setTextSize(14);
        label.setPadding(24, 16, 24, 16);
        label.setGravity(Gravity.CENTER);
        label.setText("Cabessa Social · Android UI test fixture");
        root.addView(label);
        setContentView(root);
        String scene = getIntent().getStringExtra("scene");
        if ("profile".equals(scene)) {
            ProfileSettingsButton.ButtonView control = new ProfileSettingsButton.ButtonView(this,
                    dark ? Color.WHITE : Color.BLACK, Settings.dp(this,24));
            control.setOnClickListener(view -> Settings.show(this));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Settings.dp(this,48),Settings.dp(this,48));
            params.gravity = Gravity.END;
            params.setMargins(Settings.dp(this,16),Settings.dp(this,16),Settings.dp(this,16),Settings.dp(this,16));
            root.addView(control,params);
        } else if ("inbox".equals(scene)) {
            root.addView(new StoriesTrayRow.Host(this));
        } else if ("feed".equals(scene)) {
            getWindow().getDecorView().post(() -> {
                FeedMutes.hide(this, null, "example_profile");
                FeedMutes.hide(this, null, "second_profile");
                FeedMuteSettings.show(this);
            });
        } else {
            getWindow().getDecorView().post(() -> Settings.show(this));
        }
    }
}
