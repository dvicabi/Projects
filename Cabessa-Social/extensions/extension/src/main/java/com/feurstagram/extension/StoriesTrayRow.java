package com.feurstagram.extension;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * The stories row of the Direct inbox in Quoicouview mode: a row type of
 * Instagram's own list adapter ({@code IgRecyclerViewAdapter}), like the Notes
 * tray row or a thread.
 *
 * Instagram's row types extend an obfuscated "item definition" base class
 * (create a view holder, name the model class it shows, bind a model to a
 * holder). This class cannot name it at compile time, so the Quoicouview patch
 * makes it a subclass of that base when patching and adds the three methods
 * under the base's obfuscated names, each calling into {@link Quoicouview}:
 * {@code createStoriesRow} (in a {@link Holder}), the {@link Model} class,
 * {@code bindStoriesRow}.
 * Likewise {@link Model} is made to implement Instagram's list-model interface,
 * whose obfuscated content-equality method is bridged to
 * {@link Model#sameContentAs}.
 */
public final class StoriesTrayRow {

    public StoriesTrayRow() {}

    /** The one model of the row: there is a single stories tray. */
    public static final class Model {
        static final Model INSTANCE = new Model();

        private Model() {}

        /** Identity of the row for the adapter's list diffing. */
        public Object getKey() {
            return "feurstagram_stories_tray";
        }

        /** The row's content never changes from the list's point of view: the tray updates itself. */
        public boolean sameContentAs(Object other) {
            return other instanceof Model;
        }
    }

    /**
     * The row's view holder. Instagram's holder base class is abstract and
     * obfuscated: the patch makes this its subclass and gives it a constructor
     * taking the row's view, as Instagram's own holders have.
     */
    public static final class Holder {
        private Holder() {}
    }

    /** Stories remain a direct child so existing story visibility hooks still work. */
    static final class Host extends LinearLayout {
        private final Button following;
        private final Button manage;
        private final LinearLayout actions;
        private View currentTray;

        Host(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setLayoutDirection(context.getResources().getConfiguration().getLayoutDirection());
            Locale locale = context.getResources().getConfiguration().getLocales().get(0);
            if (locale == null) locale = Locale.getDefault();
            boolean hebrew = locale != null && ("he".equals(locale.getLanguage())
                    || "iw".equals(locale.getLanguage()));
            LinearLayout panel = new LinearLayout(context);
            panel.setOrientation(VERTICAL);
            actions = new LinearLayout(context);
            actions.setOrientation(HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            following = CabessaUi.primaryButton(context,hebrew ? "פרסומים מהחברים" : "Friends' posts");
            following.setContentDescription(hebrew ? "פרסומים מהחברים, מהחדש לישן" : "Friends' posts, newest first");
            actions.addView(following,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1f));
            manage = CabessaUi.secondaryButton(context,hebrew ? "ניהול הפיד" : "Manage feed");
            manage.setContentDescription(hebrew ? "ניהול הפיד והסתרת חשבונות" : "Manage feed and hidden accounts");
            LayoutParams manageParams = new LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT);
            manageParams.setMarginStart(CabessaUi.dp(context,8));
            actions.addView(manage,manageParams);
            panel.addView(actions,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
            TextView chronology = new TextView(context);
            chronology.setText(hebrew ? "מהחדש לישן" : "Newest first");
            chronology.setTextSize(12);
            chronology.setTextColor(CabessaUi.colors(context).secondary);
            chronology.setPadding(0,CabessaUi.dp(context,6),0,0);
            panel.addView(chronology,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
            LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);
            int margin = CabessaUi.dp(context,16);
            params.setMargins(margin,CabessaUi.dp(context,8),margin,CabessaUi.dp(context,12));
            addView(panel,params);
            manage.setOnClickListener(view -> FeedMuteSettings.show(context));
            following.setOnClickListener(view -> {
                if (!openFollowing(view)) {
                    Toast.makeText(context, hebrew ? "פיד הנעקבים אינו זמין כרגע" : "Following feed is not available right now", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            int width=View.MeasureSpec.getSize(widthSpec);
            boolean stacked=getResources().getConfiguration().fontScale >= 1.3f
                    || (width > 0 && width < CabessaUi.dp(getContext(),340));
            int orientation=stacked ? VERTICAL : HORIZONTAL;
            if(actions.getOrientation()!=orientation) {
                actions.setOrientation(orientation);
                following.setLayoutParams(stacked
                        ? new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT)
                        : new LayoutParams(0,LayoutParams.WRAP_CONTENT,1f));
                LayoutParams params=new LayoutParams(stacked ? LayoutParams.MATCH_PARENT : LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT);
                params.setMarginStart(stacked ? 0 : CabessaUi.dp(getContext(),8));
                params.topMargin=stacked ? CabessaUi.dp(getContext(),8) : 0;
                manage.setLayoutParams(params);
            }
            super.onMeasure(widthSpec,heightSpec);
        }

        /** Move only the native tray; never remove or duplicate the shortcut. */
        void attachTray(View tray) {
            if (tray == null) return;
            if (currentTray != null && currentTray != tray && currentTray.getParent() == this) {
                removeView(currentTray);
            }
            ViewParent parent = tray.getParent();
            if (parent != this) {
                if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(tray);
                addView(tray, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            }
            currentTray = tray;
        }
    }

    /** Invoke the existing native feed tab, which is fixed to Following by LimitFeed. */
    static boolean openFollowing(View origin) {
        ViewGroup bar = Hiders.tabBar(origin);
        if (bar == null) return false;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View tab = bar.getChildAt(i);
            if ("feed_tab".equals(Hiders.tabName(tab)) && tab.isShown() && tab.isEnabled()) {
                return tab.performClick();
            }
        }
        return false;
    }
}
