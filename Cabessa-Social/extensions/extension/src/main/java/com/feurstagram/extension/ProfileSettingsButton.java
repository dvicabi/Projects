package com.feurstagram.extension;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;

/**
 * A preferences icon in the header of the signed-in user's own profile, just
 * before the menu button: a tap opens display preferences and local credits.
 *
 * <h2>Why there</h2>
 * The profile's menu is where people look for settings, and the header is the
 * same on every visit to the Profile tab, whatever is blocked or the landing page.
 * It is discreet: nowhere else in the app changes. (An icon at the end of the
 * bottom bar was tried and found too intrusive; a button in the feed header
 * disappeared whenever the feed was blocked.)
 *
 * <h2>Robustness</h2>
 * A window layout listener re-checks on every pass that the button is there and
 * puts it back when Instagram rebuilds the header. The own profile is told from
 * anyone else's by the "Create" button on the left of its header. The button is
 * recognised by its class, never by a tag (Instagram owns its views' tags), and
 * is sized like the header's own buttons. When the header changes shape in a later
 * Instagram release, the button simply does not appear; the long-press on the
 * first tab still opens the settings.
 */
public final class ProfileSettingsButton {

    private ProfileSettingsButton() {}

    /** Keep the button in the profile header of {@code root}'s window for as long as it lives. */
    static void install(ViewGroup root) {
        if (root == null) return;
        root.getViewTreeObserver().addOnGlobalLayoutListener(new Keeper(root));
    }

    private static final class Keeper implements ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<ViewGroup> root;

        Keeper(ViewGroup root) {
            this.root = new WeakReference<>(root);
        }

        @Override
        public void onGlobalLayout() {
            try {
                ViewGroup scope = root.get();
                if (scope == null) return;
                Context context = scope.getContext();
                View window = scope.getRootView();
                int createId = Hiders.resolveId(context, "profile_header_create_button");
                int rightId = Hiders.resolveId(context, "right_action_bar_buttons");
                int barId = Hiders.resolveId(context, "action_bar_layout");
                if (window == null || createId == 0 || rightId == 0 || barId == 0) return;
                View create = window.findViewById(createId);
                if (create == null) return; // own profile not built yet
                View bar = create;
                while (bar != null && bar.getId() != barId) {
                    bar = bar.getParent() instanceof View ? (View) bar.getParent() : null;
                }
                if (bar == null) return;
                View right = bar.findViewById(rightId);
                if (!(right instanceof LinearLayout)) return;
                LinearLayout buttons = (LinearLayout) right;
                View menu = null;
                for (int i = 0; i < buttons.getChildCount(); i++) {
                    View child = buttons.getChildAt(i);
                    if (child instanceof ButtonView) return; // already there
                    if (child instanceof ImageView && child.getVisibility() == View.VISIBLE) menu = child;
                }
                if (menu == null || menu.getWidth() == 0 || menu.getHeight() == 0
                        || !(menu.getLayoutParams() instanceof LinearLayout.LayoutParams)) {
                    return; // not laid out yet
                }
                attach(buttons, menu);
            } catch (Throwable ignored) {
                // A missing shortcut is fine; taking Instagram down is not.
            }
        }
    }

    /** Add the button before the menu, preserving a usable 48dp touch target. */
    private static void attach(LinearLayout buttons, View menu) {
        ButtonView button = new ButtonView(buttons.getContext(), iconColor(menu), iconSize(menu));
        button.setOnClickListener(ProfileSettingsButton::open);
        button.setOnLongClickListener(v -> {
            open(v);
            return true;
        });
        // A plain View asked to wrap its content takes all the room: give it the
        // menu button's own measured size.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                (LinearLayout.LayoutParams) menu.getLayoutParams());
        lp.width = Math.max(menu.getWidth(), Settings.dp(buttons.getContext(),48));
        lp.height = Math.max(menu.getHeight(), Settings.dp(buttons.getContext(),48));
        buttons.addView(button, buttons.indexOfChild(menu), lp);
    }

    private static void open(View from) {
        Context activity = Settings.getActivityContext(from);
        if (activity != null) Settings.show(activity);
    }

    /** The colour of the header's own icons, read off the menu button. */
    private static int iconColor(View menu) {
        if (menu instanceof ImageView) {
            ColorStateList tint = ((ImageView) menu).getImageTintList();
            if (tint != null) return tint.getDefaultColor();
        }
        int night = menu.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_NO ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** The size the header draws its icons at: the menu glyph's, not its padded button. */
    private static int iconSize(View menu) {
        int fallback = Settings.dp(menu.getContext(), 24);
        Drawable glyph = menu instanceof ImageView ? ((ImageView) menu).getDrawable() : null;
        if (glyph == null || glyph.getIntrinsicHeight() <= 0) return fallback;
        int inner = menu.getHeight() - menu.getPaddingTop() - menu.getPaddingBottom();
        return inner > 0 ? Math.min(glyph.getIntrinsicHeight(), inner) : glyph.getIntrinsicHeight();
    }

    /** The button itself: a familiar preferences glyph centred in its slot. */
    static final class ButtonView extends View {
        private final CabessaUi.LineIcon icon;
        private final int iconSize;

        ButtonView(Context context, int color, int iconSize) {
            super(context);
            this.iconSize = Math.max(Settings.dp(context,20),Math.min(iconSize,Settings.dp(context,24)));
            icon = new CabessaUi.LineIcon(CabessaUi.Icon.SLIDERS,color);
            setClickable(true);
            setFocusable(true);
            setContentDescription(Settings.label(context, "Display and about", "תצוגה ואודות"));
            setTooltipText(getContentDescription());
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x33808080), null,
                    CabessaUi.rounded(context,0xFFFFFFFF,24)));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int left = (getWidth() - iconSize) / 2;
            int top = (getHeight() - iconSize) / 2;
            icon.setBounds(left, top, left + iconSize, top + iconSize);
            icon.draw(canvas);
        }

        @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
    }
}
