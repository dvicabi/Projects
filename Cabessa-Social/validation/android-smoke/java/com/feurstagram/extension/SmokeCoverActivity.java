package com.feurstagram.extension;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.TextView;

/** Opaque cover exercises actual player onPause/onStop and subsequent onResume. */
public final class SmokeCoverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setBackgroundColor(Color.BLACK);
        view.setTextColor(Color.WHITE);
        view.setText("Framework lifecycle test: player is covered");
        setContentView(view);
    }
}
