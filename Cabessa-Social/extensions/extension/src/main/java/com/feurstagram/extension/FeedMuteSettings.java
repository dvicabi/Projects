package com.feurstagram.extension;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
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
        EditText input = new EditText(activity);
        input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("@username"); input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setTextColor(Settings.ON_SURFACE); input.setHintTextColor(Settings.ON_SURFACE_VARIANT);
        add.addView(input,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        Button addButton = Settings.makeButton(activity,tr(activity,"Hide from feed","הסתר מהפיד"),Settings.PRIMARY,Settings.ON_PRIMARY,true);
        add.addView(addButton);
        LinearLayout hidden=Settings.addSection(activity,page.content,tr(activity,"Hidden accounts","חשבונות מוסתרים"));
        LinearLayout recent=Settings.addSection(activity,page.content,tr(activity,"Recently loaded authors","חשבונות שהופיעו בפיד לאחרונה"));
        Runnable refresh=()->render(activity,hidden,recent);
        addButton.setOnClickListener(v->{
            String name=FeedMuteRules.name(input.getText().toString());
            if(name==null) { input.setError(tr(activity,"Enter a valid Instagram username","יש להזין שם משתמש תקין")); return; }
            if(FeedMutes.hide(activity,null,name)) { input.setText(""); saved(activity); refresh.run(); }
            else failed(activity);
        });
        Button close=page.addAction(Settings.makeButton(activity,tr(activity,"Done","סיום"),Settings.PRIMARY,Settings.ON_PRIMARY,true));
        close.setOnClickListener(v->dialog.dismiss());
        refresh.run(); dialog.show();
    }
    private static void render(Context c,LinearLayout hidden,LinearLayout recent) {
        hidden.removeAllViews(); recent.removeAllViews();
        List<String> hiddenRules=FeedMutes.hidden(c);
        if(hiddenRules.isEmpty()) Settings.makeRow(c,hidden,tr(c,"No hidden accounts","אין חשבונות מוסתרים"),null);
        for(String r:hiddenRules) {
            LinearLayout row=Settings.makeRow(c,hidden,"@"+FeedMuteRules.ruleName(r),
                    tr(c,"Tap to show in feed again","לחץ כדי להחזיר לפיד"));
            row.setOnClickListener(v->{if(FeedMutes.restore(c,r)) {saved(c);render(c,hidden,recent);} else failed(c);});
        }
        List<String> recentRules=FeedMutes.recent(c);
        if(recentRules.isEmpty()) Settings.makeRow(c,recent,tr(c,"Open and refresh the following feed first","פתח ורענן את פיד הנעקבים תחילה"),null);
        for(String r:recentRules) {
            LinearLayout row=Settings.makeRow(c,recent,"@"+FeedMuteRules.ruleName(r),
                    tr(c,"Tap to hide from feed","לחץ כדי להסתיר מהפיד"));
            row.setOnClickListener(v->{if(FeedMutes.hide(c,FeedMuteRules.ruleId(r),FeedMuteRules.ruleName(r))) {saved(c);render(c,hidden,recent);} else failed(c);});
        }
        Settings.sealGroup(c,hidden); Settings.sealGroup(c,recent);
    }
    private static String tr(Context c,String en,String he) { return Settings.label(c,en,he); }
    private static void saved(Context c) { Toast.makeText(c,tr(c,"Saved. Refresh the feed to apply.","נשמר. רענן את הפיד כדי להחיל."),Toast.LENGTH_SHORT).show(); }
    private static void failed(Context c) { Toast.makeText(c,tr(c,"Could not save the selection.","לא ניתן לשמור את הבחירה."),Toast.LENGTH_LONG).show(); }
}
