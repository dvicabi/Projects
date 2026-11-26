"""Exercise the actual Java shortcut with minimal Android view doubles.

These tests cover adaptive action layout, row reparenting and tab routing, not Android rendering or
Instagram's live adapter implementation.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'extensions/extension/src/main/java/com/feurstagram/extension'
STUBS = {
'android/content/Context.java': '''package android.content;
public class Context { private final android.content.res.Resources resources=new android.content.res.Resources(); public android.content.res.Resources getResources() { return resources; } }''',
'android/content/res/Resources.java': '''package android.content.res;
public class Resources { private final Configuration config=new Configuration();public Configuration getConfiguration() { return config; } public Metrics getDisplayMetrics() { return new Metrics(); }
public static class Metrics { public float density = 1; } }''',
'android/content/res/Configuration.java': '''package android.content.res;
public class Configuration { public float fontScale=1;public java.util.Locale locale=java.util.Locale.getDefault();public java.util.List<java.util.Locale> getLocales() { return java.util.Collections.singletonList(locale); } public int getLayoutDirection(){return locale.getLanguage().equals("he")?1:0;} }''',
'android/view/Gravity.java': 'package android.view;public class Gravity {public static final int CENTER_VERTICAL=16;}',
'android/view/ViewParent.java': 'package android.view; public interface ViewParent {}',
'android/view/View.java': '''package android.view;
public class View { public static final int LAYOUT_DIRECTION_LTR=0,LAYOUT_DIRECTION_RTL=1,LAYOUT_DIRECTION_LOCALE=3;private int layoutDirection;public String name,description; public boolean shown=true, enabled=true; public int clicks; ViewParent parent;public ViewGroup.LayoutParams params; public interface OnClickListener {void onClick(View v);} OnClickListener listener;
protected final android.content.Context context; public View(android.content.Context c){context=c;}
public android.content.Context getContext(){return context;}public void setLayoutDirection(int d){layoutDirection=d;}public int getLayoutDirection(){return layoutDirection==LAYOUT_DIRECTION_LOCALE?(java.util.Locale.getDefault().getLanguage().equals("he")?1:0):layoutDirection;} public void setContentDescription(String s){description=s;}
public void setLayoutParams(ViewGroup.LayoutParams p){params=p;}public void setPadding(int a,int b,int c,int d){}
public static class MeasureSpec {public static int getSize(int s){return s;}}protected void onMeasure(int w,int h){}public void measure(int w,int h){onMeasure(w,h);}
public android.content.res.Resources getResources(){return context.getResources();}
public ViewParent getParent(){return parent;} public boolean isShown(){return shown;} public boolean isEnabled(){return enabled;}
public void setOnClickListener(OnClickListener l){listener=l;}
public boolean performClick(){clicks++; if(listener!=null){listener.onClick(this);return true;} return false;} }''',
'android/view/ViewGroup.java': '''package android.view;
public class ViewGroup extends View implements ViewParent { private final java.util.List<View> children=new java.util.ArrayList<>();
public ViewGroup(android.content.Context c){super(c);} public static class LayoutParams {public static final int MATCH_PARENT=-1, WRAP_CONTENT=-2;public int width,height; public LayoutParams(int w,int h){width=w;height=h;}}
public void addView(View v, LayoutParams p){addView(v,children.size(),p);} public void addView(View v,int i,LayoutParams p){if(v.parent!=null)throw new IllegalStateException();children.add(i,v);v.parent=this;v.params=p;}
public void removeView(View v){if(children.remove(v))v.parent=null;} public int getChildCount(){return children.size();} public View getChildAt(int i){return children.get(i);} }''',
'android/widget/LinearLayout.java': '''package android.widget;
public class LinearLayout extends android.view.ViewGroup { public static final int VERTICAL=1,HORIZONTAL=0;private int orientation; public LinearLayout(android.content.Context c){super(c);}public void setOrientation(int v){orientation=v;}public int getOrientation(){return orientation;}public void setGravity(int g){}
public static class LayoutParams extends android.view.ViewGroup.LayoutParams {public int topMargin,startMargin;public float weight; public LayoutParams(int w,int h){super(w,h);}public LayoutParams(int w,int h,float weight){super(w,h);this.weight=weight;}public void setMargins(int a,int b,int c,int d){}public void setMarginStart(int n){startMargin=n;} } }''',
'android/widget/TextView.java': '''package android.widget;
public class TextView extends android.view.View {public String text;public TextView(android.content.Context c){super(c);}public void setText(String t){text=t;}public void setTextSize(float f){}public void setTextColor(int c){} }''',
'android/widget/Button.java': '''package android.widget;
public class Button extends TextView { public Button(android.content.Context c){super(c);}public void setAllCaps(boolean b){}public void setMinHeight(int n){} }''',
'android/widget/Toast.java': '''package android.widget;
public class Toast { public static final int LENGTH_SHORT=0;public static int shown;public static Toast makeText(android.content.Context c,String s,int t){return new Toast();}public void show(){shown++;} }''',
'com/feurstagram/extension/Hiders.java': '''package com.feurstagram.extension;
public class Hiders { static android.view.ViewGroup bar;static android.view.ViewGroup tabBar(android.view.View v){return bar;}static String tabName(android.view.View v){return v.name;} }''',
'com/feurstagram/extension/FeedMuteSettings.java': 'package com.feurstagram.extension; class FeedMuteSettings {static int opens; static void show(android.content.Context c){opens++;}}',
'com/feurstagram/extension/CabessaUi.java': '''package com.feurstagram.extension;
public class CabessaUi {public static class Palette {public int secondary;}public static Palette colors(android.content.Context c){return new Palette();}public static int dp(android.content.Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}public static android.widget.Button primaryButton(android.content.Context c,String s){android.widget.Button b=new android.widget.Button(c);b.setText(s);return b;}public static android.widget.Button secondaryButton(android.content.Context c,String s){return primaryButton(c,s);} }''',
'com/feurstagram/extension/Quoicouview.java': 'package com.feurstagram.extension; public class Quoicouview {}',
'com/feurstagram/extension/FollowingHarness.java': '''package com.feurstagram.extension;
import android.content.Context;import android.view.*;import android.widget.*;
public class FollowingHarness {
static void check(boolean result){if(!result)throw new AssertionError();}
public static void main(String[] args){
 java.util.Locale.setDefault(java.util.Locale.ENGLISH);Context c=new Context();c.getResources().getConfiguration().locale=java.util.Locale.forLanguageTag("he");
 StoriesTrayRow.Host first=new StoriesTrayRow.Host(c);check(first.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL);check(java.util.Locale.getDefault().equals(java.util.Locale.ENGLISH));check(first.getChildCount()==1);LinearLayout panel=(LinearLayout)first.getChildAt(0);LinearLayout actions=(LinearLayout)panel.getChildAt(0);Button shortcut=(Button)actions.getChildAt(0);check(shortcut.text.equals("פרסומים מהחברים"));check(((TextView)panel.getChildAt(1)).text.equals("מהחדש לישן"));check(shortcut.description.contains("מהחדש לישן"));
 first.measure(320,800);check(actions.getOrientation()==LinearLayout.VERTICAL&&shortcut.params.width==-1);first.measure(360,800);check(actions.getOrientation()==LinearLayout.HORIZONTAL&&shortcut.params.width==0);c.getResources().getConfiguration().fontScale=1.4f;first.measure(500,800);check(actions.getOrientation()==LinearLayout.VERTICAL);c.getResources().getConfiguration().fontScale=1f;
 View tray=new View(c);first.attachTray(tray);first.attachTray(tray);check(first.getChildCount()==2&&first.getChildAt(0)==tray&&first.getChildAt(1)==panel); ((Button)actions.getChildAt(1)).performClick();check(FeedMuteSettings.opens==1);
 StoriesTrayRow.Host second=new StoriesTrayRow.Host(c);second.attachTray(tray);check(first.getChildCount()==1&&second.getChildAt(0)==tray);
 View replacement=new View(c);second.attachTray(replacement);check(second.getChildCount()==2&&tray.getParent()==null&&second.getChildAt(0)==replacement);
 StoriesTrayRow.Host english=new StoriesTrayRow.Host(new Context());check(english.getLayoutDirection()==View.LAYOUT_DIRECTION_LTR);LinearLayout englishPanel=(LinearLayout)english.getChildAt(0);check(((Button)((LinearLayout)englishPanel.getChildAt(0)).getChildAt(0)).text.equals("Friends' posts"));check(((TextView)englishPanel.getChildAt(1)).text.equals("Newest first"));
 ViewGroup bar=new ViewGroup(c);View wrong=new View(c);wrong.name="clips_tab";wrong.setOnClickListener(v->{});bar.addView(wrong,new ViewGroup.LayoutParams(1,1));View right=new View(c);right.name="feed_tab";right.setOnClickListener(v->{});bar.addView(right,new ViewGroup.LayoutParams(1,1));Hiders.bar=bar;
 shortcut.performClick();check(right.clicks==1&&wrong.clicks==0&&Toast.shown==0);
 right.enabled=false;shortcut.performClick();check(right.clicks==1&&Toast.shown==1);
 Hiders.bar=null;shortcut.performClick();check(Toast.shown==2);
 System.out.println("Shortcut: Hebrew context/English process RTL, English LTR, adaptive width/font layout, tray recycling, native tab routing, missing or disabled feed: OK");
}
}''',
}

class FollowingShortcutTests(unittest.TestCase):
    def test_shortcut_routes_and_retains_stories(self):
        self.assertTrue(shutil.which('javac') and shutil.which('java'))
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            java_files = []
            for relative, source in STUBS.items():
                path = folder / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding='utf-8')
                java_files.append(path)
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', directory, *map(str,java_files), str(SOURCE / 'StoriesTrayRow.java')], check=True)
            subprocess.run(['java', '-cp', directory, 'com.feurstagram.extension.FollowingHarness'], check=True)

if __name__ == '__main__':
    unittest.main()
