package com.feurstagram.extension;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/** A local allow/restore list for feed authors. Does not expose other block settings. */
final class FeedMuteSettings {
    private FeedMuteSettings() {}
    static void show(Context context) {
        Activity activity = Activities.forContext(context);
        if (activity == null || !Activities.hasWindow(activity)) return;
        try { showPage(activity); }
        catch (RuntimeException error) { Toast.makeText(activity,tr(activity,"Could not open feed settings.","לא ניתן לפתוח את ניהול הפיד."),Toast.LENGTH_SHORT).show(); }
    }
    private static void showPage(Activity activity) {
        Settings.Page page = Settings.newPage(activity);
        Settings.addPageTitle(page, tr(activity,"Manage your feed","ניהול הפיד שלך"),
                tr(activity,"Hide posts locally without unfollowing. Stories, profiles and messages are unchanged. Refresh the feed after changes.",
                        "הסתר פרסומים בלי לבטל מעקב. סטורי, פרופילים והודעות נשארים. לאחר שינוי יש לרענן את הפיד."));
        Dialog dialog = Settings.newPageDialog(activity,page.root);
        LinearLayout add = Settings.addSection(activity,page.content,tr(activity,"Hide a username","הסתרת שם משתמש"));
        CabessaUi.Palette palette = CabessaUi.colors(activity);
        EditText input = new EditText(activity);
        input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("@username"); input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setTextColor(palette.text); input.setHintTextColor(palette.secondary);
        input.setBackground(CabessaUi.rounded(activity,palette.raised,8));
        input.setBackgroundTintList(null);
        input.setMinimumHeight(Settings.dp(activity,48));
        input.setPadding(Settings.dp(activity,14),Settings.dp(activity,12),Settings.dp(activity,14),Settings.dp(activity,12));
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setContentDescription(tr(activity,"Instagram username to hide","שם משתמש באינסטגרם להסתרה"));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.bottomMargin = Settings.dp(activity,12);
        add.addView(input,inputParams);
        Button addButton = CabessaUi.primaryButton(activity,tr(activity,"Hide from feed","הסתר מהפיד"));
        add.addView(addButton,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout hidden=Settings.addSection(activity,page.content,tr(activity,"Hidden accounts","חשבונות מוסתרים"));
        LinearLayout recent=Settings.addSection(activity,page.content,tr(activity,"Recently loaded authors","חשבונות שהופיעו בפיד לאחרונה"));
        Runnable refresh=()->render(activity,hidden,recent);
        View.OnClickListener submit = v->{
            String name=FeedMuteRules.name(input.getText().toString());
            if(name==null) { input.setError(tr(activity,"Enter a valid Instagram username","יש להזין שם משתמש תקין")); return; }
            if(FeedMutes.hide(activity,null,name)) {
                input.setText(""); input.clearFocus();
                InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
                if(keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(),0);
                saved(activity); refresh.run();
            }
            else failed(activity);
        };
        addButton.setOnClickListener(submit);
        input.setOnEditorActionListener((view,action,event)->{
            if(action != EditorInfo.IME_ACTION_DONE) return false;
            submit.onClick(addButton);
            return true;
        });
        Button close=page.addAction(CabessaUi.secondaryButton(activity,tr(activity,"Done","סיום")));
        close.setOnClickListener(v->dialog.dismiss());
        refresh.run(); dialog.show();
    }
    private static void render(Context c,LinearLayout hidden,LinearLayout recent) {
        hidden.removeAllViews(); recent.removeAllViews();
        List<String> hiddenRules=FeedMutes.hidden(c);
        if(hiddenRules.isEmpty()) Settings.makeRow(c,hidden,tr(c,"No hidden accounts","אין חשבונות מוסתרים"),null);
        for(String r:hiddenRules) {
            LinearLayout row=accountRow(c,hidden,r,tr(c,"Hidden from feed","מוסתר מהפיד"));
            Button restore=accountAction(c,row,tr(c,"Show","הצג"),
                    tr(c,"Show @","הצג את @")+FeedMuteRules.ruleName(r)+tr(c," in feed"," בפיד"));
            restore.setOnClickListener(v->{if(FeedMutes.restore(c,r)) {saved(c);render(c,hidden,recent);} else failed(c);});
        }
        List<String> recentRules=FeedMutes.recent(c);
        if(recentRules.isEmpty()) Settings.makeRow(c,recent,tr(c,"Open and refresh the following feed first","פתח ורענן את פיד הנעקבים תחילה"),null);
        for(String r:recentRules) {
            LinearLayout row=accountRow(c,recent,r,null);
            Button hide=accountAction(c,row,tr(c,"Hide","הסתר"),
                    tr(c,"Hide @","הסתר את @")+FeedMuteRules.ruleName(r)+tr(c," from feed"," מהפיד"));
            hide.setOnClickListener(v->{if(FeedMutes.hide(c,FeedMuteRules.ruleId(r),FeedMuteRules.ruleName(r))) {saved(c);render(c,hidden,recent);} else failed(c);});
        }
        Settings.sealGroup(c,hidden); Settings.sealGroup(c,recent);
    }
    private static LinearLayout accountRow(Context c,LinearLayout group,String rule,String support) {
        LinearLayout row=Settings.makeRow(c,group,"@"+FeedMuteRules.ruleName(rule),support);
        LinearLayout texts=(LinearLayout)row.getChildAt(0);
        TextView username=(TextView)texts.getChildAt(0);
        username.setTextDirection(View.TEXT_DIRECTION_LTR);
        username.setSingleLine(true);
        username.setEllipsize(TextUtils.TruncateAt.END);
        return row;
    }
    private static Button accountAction(Context c,LinearLayout row,String title,String description) {
        Button button=CabessaUi.secondaryButton(c,title);
        button.setContentDescription(description);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(Settings.dp(c,12));
        row.addView(button,params);
        return button;
    }
    private static String tr(Context c,String en,String he) { return Settings.label(c,en,he); }
    private static void saved(Context c) { Toast.makeText(c,tr(c,"Saved. Refresh the feed to apply.","נשמר. רענן את הפיד כדי להחיל."),Toast.LENGTH_SHORT).show(); }
    private static void failed(Context c) { Toast.makeText(c,tr(c,"Could not save the selection.","לא ניתן לשמור את הבחירה."),Toast.LENGTH_LONG).show(); }
}
