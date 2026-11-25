"""Exercise the actual Java shortcut with minimal Android view doubles.

These tests cover row reparenting and tab routing, not Android rendering or
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
public class Context { public android.content.res.Resources getResources() { return new android.content.res.Resources(); } }''',
'android/content/res/Resources.java': '''package android.content.res;
public class Resources { public Configuration getConfiguration() { return new Configuration(); } public Metrics getDisplayMetrics() { return new Metrics(); }
public static class Metrics { public float density = 1; } }''',
'android/content/res/Configuration.java': '''package android.content.res;
public class Configuration { public java.util.List<java.util.Locale> getLocales() { return java.util.Collections.singletonList(java.util.Locale.getDefault()); } }''',
'android/view/ViewParent.java': 'package android.view; public interface ViewParent {}',
'android/view/View.java': '''package android.view;
public class View { public String name; public boolean shown=true, enabled=true; public int clicks; ViewParent parent; public interface OnClickListener {void onClick(View v);} OnClickListener listener;
protected final android.content.Context context; public View(android.content.Context c){context=c;}
public android.content.res.Resources getResources(){return context.getResources();}
public ViewParent getParent(){return parent;} public boolean isShown(){return shown;} public boolean isEnabled(){return enabled;}
public void setOnClickListener(OnClickListener l){listener=l;}
public boolean performClick(){clicks++; if(listener!=null){listener.onClick(this);return true;} return false;} }''',
'android/view/ViewGroup.java': '''package android.view;
public class ViewGroup extends View implements ViewParent { private final java.util.List<View> children=new java.util.ArrayList<>();
public ViewGroup(android.content.Context c){super(c);} public static class LayoutParams {public static final int MATCH_PARENT=-1, WRAP_CONTENT=-2; public LayoutParams(int w,int h){}}
public void addView(View v, LayoutParams p){addView(v,children.size(),p);} public void addView(View v,int i,LayoutParams p){if(v.parent!=null)throw new IllegalStateException();children.add(i,v);v.parent=this;}
public void removeView(View v){if(children.remove(v))v.parent=null;} public int getChildCount(){return children.size();} public View getChildAt(int i){return children.get(i);} }''',
'android/widget/LinearLayout.java': '''package android.widget;
public class LinearLayout extends android.view.ViewGroup { public static final int VERTICAL=1; public LinearLayout(android.content.Context c){super(c);}public void setOrientation(int v){}
public static class LayoutParams extends android.view.ViewGroup.LayoutParams { public LayoutParams(int w,int h){super(w,h);}public void setMargins(int a,int b,int c,int d){} } }''',
'android/widget/Button.java': '''package android.widget;
public class Button extends android.view.View { public String text; public Button(android.content.Context c){super(c);}public void setText(String t){text=t;}public void setAllCaps(boolean b){}public void setMinHeight(int n){} }''',
'android/widget/Toast.java': '''package android.widget;
public class Toast { public static final int LENGTH_SHORT=0;public static int shown;public static Toast makeText(android.content.Context c,String s,int t){return new Toast();}public void show(){shown++;} }''',
'com/feurstagram/extension/Hiders.java': '''package com.feurstagram.extension;
public class Hiders { static android.view.ViewGroup bar;static android.view.ViewGroup tabBar(android.view.View v){return bar;}static String tabName(android.view.View v){return v.name;} }''',
'com/feurstagram/extension/FeedMuteSettings.java': 'package com.feurstagram.extension; class FeedMuteSettings {static int opens; static void show(android.content.Context c){opens++;}}',
'com/feurstagram/extension/Quoicouview.java': 'package com.feurstagram.extension; public class Quoicouview {}',
'com/feurstagram/extension/FollowingHarness.java': '''package com.feurstagram.extension;
import android.content.Context;import android.view.*;import android.widget.*;
public class FollowingHarness {
static void check(boolean result){if(!result)throw new AssertionError();}
public static void main(String[] args){
 Context c=new Context();java.util.Locale.setDefault(java.util.Locale.forLanguageTag("he"));
 StoriesTrayRow.Host first=new StoriesTrayRow.Host(c);check(first.getChildCount()==2);Button shortcut=(Button)first.getChildAt(0);check(shortcut.text.equals("פרסומים מהחברים · מהחדש לישן"));
 View tray=new View(c);first.attachTray(tray);first.attachTray(tray);check(first.getChildCount()==3&&first.getChildAt(0)==tray&&first.getChildAt(1)==shortcut); ((Button)first.getChildAt(2)).performClick();check(FeedMuteSettings.opens==1);
 StoriesTrayRow.Host second=new StoriesTrayRow.Host(c);second.attachTray(tray);check(first.getChildCount()==2&&second.getChildAt(0)==tray);
 View replacement=new View(c);second.attachTray(replacement);check(second.getChildCount()==3&&tray.getParent()==null&&second.getChildAt(0)==replacement);
 java.util.Locale.setDefault(java.util.Locale.ENGLISH);StoriesTrayRow.Host english=new StoriesTrayRow.Host(c);check(((Button)english.getChildAt(0)).text.equals("Friends' posts · Newest first"));
 ViewGroup bar=new ViewGroup(c);View wrong=new View(c);wrong.name="clips_tab";wrong.setOnClickListener(v->{});bar.addView(wrong,new ViewGroup.LayoutParams(1,1));View right=new View(c);right.name="feed_tab";right.setOnClickListener(v->{});bar.addView(right,new ViewGroup.LayoutParams(1,1));Hiders.bar=bar;
 shortcut.performClick();check(right.clicks==1&&wrong.clicks==0&&Toast.shown==0);
 right.enabled=false;shortcut.performClick();check(right.clicks==1&&Toast.shown==1);
 Hiders.bar=null;shortcut.performClick();check(Toast.shown==2);
 System.out.println("Shortcut: Hebrew/English, tray recycling, native tab routing, missing or disabled feed: OK");
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
