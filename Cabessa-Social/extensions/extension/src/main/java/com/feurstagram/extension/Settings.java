package com.feurstagram.extension;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Display preferences and local attribution. Content policy is fixed in Config. */
public final class Settings {

    // Dark defaults retained for callers that pass an explicit colour. Pages
    // resolve CabessaUi's palette from the host context when they are opened.

    /** Page background. */
    public static final int SURFACE = 0xFF000000;
    /** Cards, list rows. */
    public static final int SURFACE_CONTAINER = 0xFF121212;
    /** One step up: tonal buttons, the raised confirm card. */
    public static final int SURFACE_CONTAINER_HIGH = 0xFF262626;
    public static final int ON_SURFACE = 0xFFF4F4F4;
    public static final int ON_SURFACE_VARIANT = 0xFFABABAB;
    public static final int OUTLINE = 0xFF757575;
    public static final int OUTLINE_VARIANT = 0xFF3A3A3A;
    public static final int PRIMARY = 0xFF4A5DF9;
    public static final int ON_PRIMARY = 0xFFFFFFFF;
    public static final int ERROR = 0xFFED4956;
    public static final int ON_ERROR = 0xFFFFFFFF;
    public static final int DIVIDER = 0xFF2A2A2A;
    public static final int RIPPLE = 0x26FFFFFF;

    private Settings() {}

    private static final WeakHashMap<ViewGroup, Boolean> INSTALLED = new WeakHashMap<>();
    private static WeakReference<Dialog> openDialog = new WeakReference<>(null);
    private static boolean opening;

    /** Install each set of layout observers once per native tab bar. */
    public static void installHomeTabWatcher(ViewGroup tabBar) {
        if (tabBar == null || INSTALLED.containsKey(tabBar)) return;
        INSTALLED.put(tabBar, Boolean.TRUE);
        Activities.track(tabBar.getContext());
        Grayscale.install(tabBar.getContext());
        tabBar.getViewTreeObserver().addOnGlobalLayoutListener(new HomeTabWatcher(tabBar));
        ProfileSettingsButton.install(tabBar);
        Hiders.installAll(tabBar);
    }

    public static Context getActivityContext(View view) {
        if (view == null) return null;
        Activity activity = Activities.forView(view);
        return activity != null ? activity : view.getContext();
    }

    static String label(Context context, String english, String hebrew) {
        java.util.Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        String language = locale.getLanguage();
        return "he".equals(language) || "iw".equals(language) ? hebrew : english;
    }

    public static void show(Context context) {
        Dialog current = openDialog.get();
        if (context == null || opening || (current != null && current.isShowing())) return;
        opening = true;
        showWhenReady(context, 0);
    }

    private static void showWhenReady(Context context, int attempt) {
        Activity activity = Activities.forContext(context);
        if (activity == null) {
            opening = false;
            return;
        }
        if (!Activities.hasWindow(activity)) {
            if (attempt >= 20) { opening = false; return; }
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> showWhenReady(context, attempt + 1), 50L);
            return;
        }
        opening = false;
        try {
            Page page = newPage(activity);
            addPageTitle(page, label(activity, "Display and about", "תצוגה ואודות"),
                    label(activity, "Stories and messages at home. A separate following feed.",
                            "סטורי והודעות בבית. פיד נעקבים בכניסה נפרדת."));
            Dialog dialog = newPageDialog(activity, page.root);
            final boolean initial = Config.isGrayscale();
            final boolean[] selected = {initial};

            LinearLayout display = addSection(activity, page.content,
                    label(activity, "Display", "תצוגה"));
            LinearLayout row = makeRow(activity, display,
                    label(activity, "Black and white", "שחור ולבן"),
                    label(activity, "Changes the display only. Original media keeps its colors. Applying a change restarts the app.",
                            "משנה את התצוגה בלבד. המדיה המקורית נשארת בצבע. החלת שינוי מפעילה את האפליקציה מחדש."));
            ExpressiveSwitch toggle = new ExpressiveSwitch(activity);
            toggle.setChecked(initial);
            toggle.setContentDescription(label(activity, "Black and white", "שחור ולבן"));
            row.addView(toggle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
            sealGroup(activity, display);

            LinearLayout feed = addSection(activity, page.content, label(activity, "Following feed", "פיד הנעקבים"));
            LinearLayout mutes = makeRow(activity, feed, label(activity, "Manage hidden accounts", "ניהול חשבונות מוסתרים"),
                    label(activity, "Choose whose posts to hide locally, without unfollowing.", "בחר ממי להסתיר פרסומים בלי לבטל מעקב."));
            mutes.setOnClickListener(v -> FeedMuteSettings.show(activity));
            addChevron(mutes);
            sealGroup(activity, feed);

            LinearLayout about = addSection(activity, page.content, label(activity, "About", "אודות"));
            makeRow(activity, about, appName(activity), appVersion(activity));
            makeRow(activity, about, label(activity, "Content settings", "הגדרות תוכן"),
                    label(activity, "Content restrictions are part of this version and cannot be changed here.",
                            "החסימות הן חלק מהגרסה ואינן ניתנות לשינוי כאן."));
            LinearLayout license = makeRow(activity, about,
                    label(activity, "License and credits", "רישיון וקרדיטים"),
                    "FeurStagram contributors · Morphe · Piko · GPLv3");
            license.setOnClickListener(v -> showLicense(activity));
            addChevron(license);
            sealGroup(activity, about);

            Button done = page.addAction(CabessaUi.primaryButton(activity, label(activity, "Done", "סיום")));
            toggle.setOnCheckedChangeListener((button, enabled) -> {
                selected[0] = enabled;
                done.setText(enabled == initial ? label(activity, "Done", "סיום")
                        : label(activity, "Apply and restart", "החלה והפעלה מחדש"));
            });
            done.setOnClickListener(v -> {
                if (selected[0] == initial) { dialog.dismiss(); return; }
                if (!Config.setGrayscale(activity, selected[0])) {
                    Toast.makeText(activity, label(activity, "Could not save the display preference.",
                            "לא ניתן לשמור את העדפת התצוגה."), Toast.LENGTH_LONG).show();
                    return;
                }
                // No media or draft caches are cleared for a display preference.
                try {
                    activity.startActivity(RestartActivity.intentFor(activity, android.os.Process.myPid()));
                    dialog.dismiss();
                } catch (RuntimeException error) {
                    dialog.dismiss();
                    Toast.makeText(activity, label(activity,
                            "Saved. Close and reopen the app to apply the display change.",
                            "נשמר. יש לסגור ולפתוח מחדש את האפליקציה כדי להחיל את התצוגה."), Toast.LENGTH_LONG).show();
                }
            });
            // Back discards an unapplied selection; the stored preference stays unchanged.
            dialog.show();
            openDialog = new WeakReference<>(dialog);
        } catch (RuntimeException error) {
            Toast.makeText(activity, label(activity, "Could not open display settings.",
                    "לא ניתן לפתוח את הגדרות התצוגה."), Toast.LENGTH_SHORT).show();
        }
    }

    private static String appName(Context context) {
        return context.getPackageManager().getApplicationLabel(context.getApplicationInfo()).toString();
    }

    private static String appVersion(Context context) {
        try {
            String version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return label(context, "Version ", "גרסה ") + Config.RELEASE_VERSION
                    + " · Instagram " + (version == null ? "" : version);
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return "";
        }
    }

    private static void showLicense(Context context) {
        Page page = newPage(context);
        addPageTitle(page, label(context, "License and credits", "רישיון וקרדיטים"),
                label(context, "This independent build is based on FeurStagram.",
                        "גרסה עצמאית זו מבוססת על FeurStagram."));
        TextView text = new TextView(context);
        text.setText(LicenseText.NOTICE + "\n\n" + LicenseText.GPL3);
        body(text);
        text.setTextDirection(View.TEXT_DIRECTION_LTR);
        text.setTextIsSelectable(true);
        page.content.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Dialog dialog = newPageDialog(context, page.root);
        Button close = page.addAction(CabessaUi.secondaryButton(context, label(context, "Back", "חזרה")));
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    public static void styleWindow(Dialog dialog, int background, float dim) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(background));
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setDimAmount(dim);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);

        // Draw behind the bars and let the content pad itself. Without this the
        // window may not receive insets at all, and the padding below would be
        // computed from zeros.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        window.getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        // Deprecated (and ignored) from API 35, but still what tints the bars on
        // everything older.
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        // Each page follows the host appearance, including the system bar icons.
        boolean light = CabessaUi.isLight(background);
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light ? appearance : 0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            int appearance = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(light ? decor.getSystemUiVisibility() | appearance
                    : decor.getSystemUiVisibility() & ~appearance);
        }
    }

    /** Receives the space taken by system bars and display cutouts, in pixels. */
    public interface InsetTarget {
        void onInsets(int left, int top, int right, int bottom);
    }

    /**
     * Report the window's system-bar + cutout insets to {@code target}, now and on
     * every change (rotation, switching to three-button navigation, a foldable
     * unfolding).
     */
    public static void applyWindowInsets(View view, InsetTarget target) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            target.onInsets(left, top, right, bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    /**
     * Widest a column of text and controls is allowed to get. Past this a tablet,
     * an unfolded foldable or a landscape phone gives rows so wide the label and
     * its switch end up at opposite edges of the screen.
     */
    private static final int CONTENT_MAX_WIDTH_DP = 620;

    /**
     * Pad a full-screen root against the system bars and the cutout, and keep its
     * content centred within {@code maxWidthDp}.
     *
     * The width part has to run on every layout pass rather than only when the
     * insets change: {@code requestApplyInsets()} is asynchronous, so the first
     * layout happens with no insets at all, and a root that only listened for
     * insets would keep whatever it measured then.
     */
    public static void applyPageInsets(final View root, final int sideDp, final int verticalDp,
                                       final int maxWidthDp) {
        final Context context = root.getContext();
        final int[] insets = new int[4];
        Runnable apply = () -> {
            int usable = root.getWidth() - insets[0] - insets[2];
            int extra = Math.max(0, usable - dp(context, maxWidthDp)) / 2;
            int left = dp(context, sideDp) + insets[0] + extra;
            int top = dp(context, verticalDp) + insets[1];
            int right = dp(context, sideDp) + insets[2] + extra;
            int bottom = dp(context, verticalDp) + insets[3];
            if (root.getPaddingLeft() == left && root.getPaddingTop() == top
                    && root.getPaddingRight() == right && root.getPaddingBottom() == bottom) {
                return;
            }
            root.setPadding(left, top, right, bottom);
        };
        applyWindowInsets(root, (l, t, r, b) -> {
            insets[0] = l;
            insets[1] = t;
            insets[2] = r;
            insets[3] = b;
            apply.run();
        });
        root.getViewTreeObserver().addOnGlobalLayoutListener(apply::run);
    }

    // --- The full-screen page scaffold ----------------------------------------

    /**
     * The shape of each local full-screen settings page: a headline block, a
     * scrolling middle that absorbs whatever space is left, and an action row
     * pinned above the navigation bar. Local pages are built
     * on it, so they inset, centre and shrink identically.
     */
    public static final class Page {
        public final LinearLayout root;
        public final LinearLayout header;
        public final ScrollView scroll;
        public final LinearLayout content;
        public final LinearLayout actions;

        Page(LinearLayout root, LinearLayout header, ScrollView scroll,
             LinearLayout content, LinearLayout actions) {
            this.root = root;
            this.header = header;
            this.scroll = scroll;
            this.content = content;
            this.actions = actions;
        }

        /** Add an action that shares the row equally with its siblings. */
        public Button addAction(Button button) {
            Context context = actions.getContext();
            if (actions.getChildCount() > 0) {
                View spacer = new View(context);
                actions.addView(spacer, new LinearLayout.LayoutParams(dp(context, 8), 1));
            }
            actions.addView(button, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return button;
        }
    }

    public static Page newPage(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(context.getResources().getConfiguration().getLayoutDirection());
        root.setBackgroundColor(CabessaUi.colors(context).background);
        applyPageInsets(root, 16, 8, CONTENT_MAX_WIDTH_DP);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, dp(context, 12), 0, dp(context, 16));
        root.addView(header);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        // Preserve breathing room above the pinned action without masking rows.
        scroll.setVerticalFadingEdgeEnabled(false);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(context, 12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.setMargins(0, dp(context, 12), 0, 0);
        root.addView(actions, actionsLp);

        return new Page(root, header, scroll, content, actions);
    }

    /** Headline plus supporting line, in a page's header block. */
    public static void addPageTitle(Page page, String titleText, String subtitleText) {
        Context context = page.root.getContext();
        TextView title = new TextView(context);
        title.setText(titleText);
        headline(title);
        page.header.addView(title);

        if (subtitleText != null) {
            TextView subtitle = new TextView(context);
            subtitle.setText(subtitleText);
            body(subtitle);
            subtitle.setPadding(0, dp(context, 6), 0, 0);
            page.header.addView(subtitle);
        }
    }

    /** A full-screen page dialog, styled and edge-to-edge. */
    public static Dialog newPageDialog(Context context, View content) {
        Dialog dialog = new Dialog(context, android.R.style.Theme_Material_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        styleWindow(dialog, CabessaUi.colors(context).background, 0f);
        return dialog;
    }

    /** Sentence-case section heading, aligned with its rows. */
    static void addSectionLabel(Context context, LinearLayout parent, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        label.setTextColor(CabessaUi.colors(context).text);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setLetterSpacing(0f);
        label.setPadding(0, dp(context, 20), 0, dp(context, 8));
        parent.addView(label);
    }

    /** Section label plus the container its rows go into. */
    static LinearLayout addSection(Context context, LinearLayout parent, String text) {
        addSectionLabel(context, parent, text);
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        parent.addView(group);
        return group;
    }

    /** Flat native-style rows with a quiet divider, not a stack of large cards. */
    static void sealGroup(Context context, LinearLayout group) {
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View row = group.getChildAt(i);
            row.setBackground(CabessaUi.rowBackground(context, i < count - 1));
        }
    }

    /** The shared skeleton of a list row: label, supporting text, trailing control. */
    static LinearLayout makeRow(Context context, LinearLayout parent, String label, String support) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(0, dp(context, 14), 0, dp(context, 14));
        row.setMinimumHeight(dp(context, 64));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        labelView.setTextColor(CabessaUi.colors(context).text);
        texts.addView(labelView);

        if (support != null) {
            TextView supportView = new TextView(context);
            supportView.setText(support);
            supportView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            supportView.setTextColor(CabessaUi.colors(context).secondary);
            supportView.setLineSpacing(0f, 1.1f);
            supportView.setPadding(0, dp(context, 2), 0, 0);
            texts.addView(supportView);
        }

        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static void addChevron(LinearLayout row) {
        Context context = row.getContext();
        ImageView arrow = new ImageView(context);
        arrow.setImageDrawable(new CabessaUi.LineIcon(CabessaUi.Icon.CHEVRON, CabessaUi.colors(context).secondary));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 20), dp(context, 20));
        params.setMarginStart(dp(context, 12));
        row.addView(arrow, params);
    }

    public static void headline(TextView view) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        view.setTextColor(CabessaUi.colors(view.getContext()).text);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setLetterSpacing(0f);
    }

    /** A card or dialog title. */
    public static void titleLarge(TextView view) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        view.setTextColor(CabessaUi.colors(view.getContext()).text);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setLetterSpacing(0f);
    }

    /** Supporting text. */
    public static void body(TextView view) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        view.setTextColor(CabessaUi.colors(view.getContext()).secondary);
        view.setLineSpacing(0f, 1.15f);
    }

    public static int statusBarHeight(Context context) {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id == 0 ? 0 : context.getResources().getDimensionPixelSize(id);
    }

    public static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable roundedRect(int color, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    /** Per-corner variant, for the connected list groups. */
    public static GradientDrawable roundedRect(int color, Context context,
                                               float topLeftDp, float topRightDp,
                                               float bottomRightDp, float bottomLeftDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        float tl = dp(context, topLeftDp);
        float tr = dp(context, topRightDp);
        float br = dp(context, bottomRightDp);
        float bl = dp(context, bottomLeftDp);
        drawable.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        return drawable;
    }

    /** A quiet outlined control with the shared button radius. */
    public static Drawable outlined(Context context, int strokeColor) {
        GradientDrawable shape = roundedRect(Color.TRANSPARENT, 8, context);
        shape.setStroke(dp(context, 1), strokeColor);
        return ripple(CabessaUi.colors(context).ripple, shape);
    }

    public static Drawable ripple(int color, Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(color), content, null);
    }

    public static ColorStateList buildStateList(int checkedColor, int uncheckedColor) {
        int[][] states = {
                new int[]{android.R.attr.state_checked},
                new int[]{},
        };
        int[] colors = {checkedColor, uncheckedColor};
        return new ColorStateList(states, colors);
    }

    /** Shared 48dp action target, sentence case and an 8dp corner radius. */
    public static Button makeButton(Context context, String text, int bgColor, int textColor, boolean filled) {
        return CabessaUi.button(context, text, filled ? bgColor : Color.TRANSPARENT,
                textColor, CabessaUi.colors(context), filled);
    }

    public static View makeDivider(Context context, int color) {
        View divider = new View(context);
        divider.setBackgroundColor(color);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        return divider;
    }
}
