"""Run the real loader lifecycle with minimal Android/native transport doubles.

Only the empty patch-time bridge bodies are replaced for this JVM harness.
This verifies cancellation, capability revocation and selected-media dispatch;
it does not simulate Android rendering, real callbacks or device playback.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'extensions/extension/src/main/java/com/feurstagram/extension'
STUBS = {
'android/os/Bundle.java': 'package android.os; public class Bundle {}',
'android/os/Looper.java': '''package android.os; public class Looper {
static final Looper MAIN=new Looper(), WORKER=new Looper();public static boolean main=true;public static Looper getMainLooper(){return MAIN;}public static Looper myLooper(){return main?MAIN:WORKER;}}
''',
'android/os/Handler.java': '''package android.os; public class Handler {
static final java.util.List<Handler> all=new java.util.ArrayList<>();final java.util.List<Runnable> tasks=new java.util.ArrayList<>(), posted=new java.util.ArrayList<>();
public Handler(Looper l){all.add(this);}public boolean post(Runnable r){posted.add(r);return true;}public boolean postDelayed(Runnable r,long ms){tasks.add(r);return true;}
public void removeCallbacksAndMessages(Object o){tasks.clear();posted.clear();}public static void runPosted(){for(Handler h:new java.util.ArrayList<>(all)){java.util.List<Runnable> copy=new java.util.ArrayList<>(h.posted);h.posted.clear();for(Runnable r:copy)r.run();}}public static void fireTimers(){for(Handler h:new java.util.ArrayList<>(all)){java.util.List<Runnable> copy=new java.util.ArrayList<>(h.tasks);h.tasks.clear();for(Runnable r:copy)r.run();}}}
''',
'android/content/Context.java': '''package android.content; public class Context {public android.content.res.Resources getResources(){return new android.content.res.Resources();}}''',
'android/content/res/Resources.java': '''package android.content.res; public class Resources {
public Configuration getConfiguration(){return new Configuration();}public Metrics getDisplayMetrics(){return new Metrics();}public static class Metrics {public float density=1;}}''',
'android/content/res/Configuration.java': '''package android.content.res; public class Configuration {public java.util.List<java.util.Locale> getLocales(){return java.util.Collections.singletonList(java.util.Locale.ENGLISH);}}''',
'android/content/DialogInterface.java': '''package android.content; public interface DialogInterface {
void dismiss();interface OnClickListener {void onClick(DialogInterface d,int w);}interface OnCancelListener {void onCancel(DialogInterface d);}interface OnDismissListener {void onDismiss(DialogInterface d);}}''',
'android/app/Activity.java': '''package android.app; public class Activity extends android.content.Context {
public boolean paused;final Application app=new Application();public Application getApplication(){return app;}public void pause(){paused=true;for(Application.ActivityLifecycleCallbacks c:new java.util.ArrayList<>(app.callbacks))c.onActivityPaused(this);}}''',
'android/app/Application.java': '''package android.app;public class Application {
public final java.util.List<ActivityLifecycleCallbacks> callbacks=new java.util.ArrayList<>();public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){callbacks.add(c);}public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){callbacks.remove(c);}
public interface ActivityLifecycleCallbacks {void onActivityPaused(Activity a);void onActivityStopped(Activity a);void onActivityDestroyed(Activity a);void onActivityCreated(Activity a,android.os.Bundle b);void onActivityStarted(Activity a);void onActivityResumed(Activity a);void onActivitySaveInstanceState(Activity a,android.os.Bundle b);}}''',
'android/app/AlertDialog.java': '''package android.app; public class AlertDialog implements android.content.DialogInterface {
public static AlertDialog last;public boolean shown;android.content.DialogInterface.OnCancelListener cancel;android.content.DialogInterface.OnDismissListener dismiss;
public void setCanceledOnTouchOutside(boolean b){}public void setOnCancelListener(android.content.DialogInterface.OnCancelListener l){cancel=l;}public void setOnDismissListener(android.content.DialogInterface.OnDismissListener l){dismiss=l;}
public void show(){shown=true;last=this;}public void dismiss(){shown=false;if(dismiss!=null)dismiss.onDismiss(this);}public void cancel(){if(cancel!=null)cancel.onCancel(this);dismiss();}
public static class Builder {public Builder(Activity a){}public Builder setTitle(String s){return this;}public Builder setView(android.view.View v){return this;}public Builder setNegativeButton(String s,android.content.DialogInterface.OnClickListener l){return this;}public AlertDialog create(){return new AlertDialog();}}}''',
'android/view/View.java': 'package android.view; public class View {}',
'android/view/ViewGroup.java': '''package android.view;public class ViewGroup extends View {public static class LayoutParams {public static final int MATCH_PARENT=-1,WRAP_CONTENT=-2;public LayoutParams(int a,int b){}}}''',
'android/view/Gravity.java': 'package android.view;public class Gravity {public static final int CENTER=1;}',
'android/widget/LinearLayout.java': '''package android.widget;public class LinearLayout extends android.view.ViewGroup {
public static final int VERTICAL=1;public LinearLayout(android.content.Context c){}public void setOrientation(int x){}public void setGravity(int x){}public void setPadding(int a,int b,int c,int d){}public void addView(android.view.View v,LayoutParams p){}
public static class LayoutParams extends android.view.ViewGroup.LayoutParams {public LayoutParams(int a,int b){super(a,b);}}}''',
'android/widget/ProgressBar.java': 'package android.widget;public class ProgressBar extends android.view.View {public ProgressBar(android.content.Context c){}}',
'android/widget/TextView.java': '''package android.widget;public class TextView extends android.view.View {public TextView(android.content.Context c){}public void setText(String x){}public void setPadding(int a,int b,int c,int d){}public void setGravity(int x){}}''',
'com/feurstagram/extension/Activities.java': '''package com.feurstagram.extension;class Activities {
static android.app.Activity forContext(android.content.Context c){return c instanceof android.app.Activity?(android.app.Activity)c:null;}static boolean hasWindow(android.app.Activity a){return a!=null&&!a.paused;}}''',
'com/feurstagram/extension/Config.java': 'package com.feurstagram.extension;class Config {static boolean isGrayscale(){return false;}}',
'com/feurstagram/extension/Grayscale.java': 'package com.feurstagram.extension;class Grayscale {static void apply(android.view.View v){}}',
'com/feurstagram/extension/SingleReelPlayer.java': '''package com.feurstagram.extension;class SingleReelPlayer {
static void resolve(android.content.Context c,Object session,String id){SharedReelLoader.loadInPlayer((android.app.Activity)c,session,id);}
static int opens,errors;static Object selected;static boolean open(android.content.Context c,Object media){opens++;selected=media;return true;}static void unavailable(android.content.Context c){errors++;}}
''',
'com/feurstagram/extension/FakeNative.java': '''package com.feurstagram.extension;
class FakeNative {
static final class Request {NativeReelCallback callback;java.net.URI uri;int cancels;String json;}
static final java.util.List<Request> requests=new java.util.ArrayList<>();static boolean throwsOnFetch,synchronous;
static Object fetch(Object session,String json,String module,NativeReelCallback callback){
Request r=new Request();r.callback=callback;r.json=json;r.uri=java.net.URI.create("https://i.instagram.com/api/v1/clips/items/?clips_media_ids="+java.net.URLEncoder.encode(json,java.nio.charset.StandardCharsets.UTF_8)+"&container_module="+module);requests.add(r);
if(!SharedReelRequestGate.allows(r.uri))throw new AssertionError("selected native fetch has no capability");
if(throwsOnFetch)throw new RuntimeException("simulated native scheduler failure");if(synchronous)callback.success(java.util.List.of("123_4"));return r;}
static void cancel(Object request){((Request)request).cancels++;}
static Request last(){return requests.get(requests.size()-1);}
}
''',
'com/feurstagram/extension/LoaderHarness.java': '''package com.feurstagram.extension;
import android.app.Activity;import android.app.AlertDialog;import android.os.Handler;
public class LoaderHarness {
static void check(boolean v,String reason){if(!v)throw new AssertionError(reason);}
static FakeNative.Request start(Activity a,String id){SharedReelLoader.open(a,new Object(),id,null);return FakeNative.last();}
static void cleaned(Activity a,FakeNative.Request r){check(!SharedReelRequestGate.allows(r.uri),"capability not revoked");check(r.cancels==1,"native request not cancelled exactly once");check(a.getApplication().callbacks.isEmpty(),"lifecycle callback leaked");}
public static void main(String[] args){
Activity a=new Activity();FakeNative.Request r=start(a,"123_4");check(!a.getApplication().callbacks.isEmpty(),"lookup has no lifecycle owner");r.callback.success(java.util.List.of("123_4"));cleaned(a,r);check(SingleReelPlayer.opens==1&&"123_4".equals(SingleReelPlayer.selected),"selected media not dispatched");
int opened=SingleReelPlayer.opens, errors=SingleReelPlayer.errors;
a=new Activity();r=start(a,"123");r.callback.success(java.util.List.of("999_4"));cleaned(a,r);check(SingleReelPlayer.opens==opened&&SingleReelPlayer.errors==++errors,"mismatched media played");
a=new Activity();r=start(a,"123");r.callback.success(java.util.List.of("123_4","123_4"));cleaned(a,r);check(SingleReelPlayer.errors==++errors,"ambiguous response accepted");
a=new Activity();r=start(a,"123");a.pause();cleaned(a,r);r.callback.success(java.util.List.of("123_4"));check(SingleReelPlayer.opens==opened&&SingleReelPlayer.errors==errors,"cancelled response opened viewer");
a=new Activity();FakeNative.Request old=start(a,"123");r=start(a,"456");check(!SharedReelRequestGate.allows(old.uri)&&old.cancels==1,"old selection remains active");old.callback.success(java.util.List.of("123_4"));check(SingleReelPlayer.opens==opened,"stale callback opened viewer");r.callback.success(java.util.List.of("456_7"));cleaned(a,r);check(SingleReelPlayer.opens==++opened&&"456_7".equals(SingleReelPlayer.selected),"second selection lost");
a=new Activity();r=start(a,"123");Handler.fireTimers();cleaned(a,r);check(SingleReelPlayer.errors==++errors,"timeout omitted error");r.callback.success(java.util.List.of("123_4"));check(SingleReelPlayer.opens==opened,"late timeout callback opened viewer");
a=new Activity();r=start(a,"123");a.pause();cleaned(a,r);r.callback.success(java.util.List.of("123_4"));check(SingleReelPlayer.errors==errors&&SingleReelPlayer.opens==opened,"background callback reopened player");
a=new Activity();r=start(a,"123");android.os.Looper.main=false;r.callback.success(java.util.List.of("123_4"));android.os.Looper.main=true;a.pause();Handler.runPosted();cleaned(a,r);check(SingleReelPlayer.errors==errors&&SingleReelPlayer.opens==opened,"queued success reopened after pause");
a=new Activity();r=start(a,"123");r.callback.failure(new Object());cleaned(a,r);check(SingleReelPlayer.errors==++errors,"failure omitted error");
a=new Activity();FakeNative.throwsOnFetch=true;r=start(a,"123");FakeNative.throwsOnFetch=false;check(!SharedReelRequestGate.allows(r.uri)&&a.getApplication().callbacks.isEmpty(),"scheduling exception leaked grant/lifecycle");check(SingleReelPlayer.errors==++errors,"scheduling exception not shown");
a=new Activity();FakeNative.synchronous=true;r=start(a,"123");FakeNative.synchronous=false;cleaned(a,r);check(SingleReelPlayer.opens==++opened,"synchronous callback lost");
a=new Activity();SharedReelLoader.open(a,new Object(),null,"test-shortcode");r=FakeNative.last();check(r.json.equals("[\\\"777\\\"]"),"shortcode not converted");a.pause();cleaned(a,r);
int count=FakeNative.requests.size();a=new Activity();SharedReelLoader.open(a,new Object(),"123\\\",\\\"456",null);check(FakeNative.requests.size()==count&&SingleReelPlayer.errors==++errors,"malformed selection reached native fetch");
// Story shares reuse the same restricted request and lifecycle, not a second player.
a=new Activity();SharedReelLoader.openFromStory(a,new Object(),"456_7","999");r=FakeNative.last();check(r.json.equals("["+((char)34)+"456_7"+((char)34)+"]"),"story primary ID lost");r.callback.success(java.util.List.of("456_7"));cleaned(a,r);check(SingleReelPlayer.opens==++opened&&"456_7".equals(SingleReelPlayer.selected),"story video not opened");
a=new Activity();SharedReelLoader.openFromStory(a,new Object(),null,"888");r=FakeNative.last();check(r.json.equals("["+((char)34)+"888"+((char)34)+"]"),"story fallback ID lost");a.pause();cleaned(a,r);r.callback.success(java.util.List.of("888_1"));check(SingleReelPlayer.opens==opened,"dismissed story reopened");
count=FakeNative.requests.size();a=new Activity();SharedReelLoader.openFromStory(a,new Object(),"not-an-id","888");check(FakeNative.requests.size()==count&&SingleReelPlayer.errors==++errors,"malformed story ID was replaced with a different media ID");
a=new Activity();SharedReelLoader.openFromStory(a,new Object(),null,null);check(FakeNative.requests.size()==count&&SingleReelPlayer.errors==++errors,"missing story ID reached network");
System.out.println("Shared Reel loader: selected-only dispatch, ID mismatch, duplicate response, cancel, stale callbacks, timeout, pause, errors, synchronous completion and shortcode: OK");
}
}
''',
}


class SharedReelLoaderTests(unittest.TestCase):
    def test_selected_request_lifecycle(self):
        self.assertTrue(shutil.which('javac') and shutil.which('java'))
        loader = (SOURCE / 'SharedReelLoader.java').read_text()
        bridges = {
            'public static Object nativeFetch(Object session, String jsonIds, String module, NativeReelCallback callback) { return null; }': 'public static Object nativeFetch(Object session, String jsonIds, String module, NativeReelCallback callback) { return FakeNative.fetch(session,jsonIds,module,callback); }',
            'public static void nativeCancel(Object request) {}': 'public static void nativeCancel(Object request) { FakeNative.cancel(request); }',
            'public static List<?> nativeResponseMedia(Object response) { return null; }': 'public static List<?> nativeResponseMedia(Object response) { return (List<?>)response; }',
            'public static String nativeMediaId(Object media) { return null; }': 'public static String nativeMediaId(Object media) { return (String)media; }',
            'public static String nativeIdFromCode(String shortcode) { return null; }': 'public static String nativeIdFromCode(String shortcode) { return "777"; }',
        }
        for original, fake in bridges.items():
            self.assertIn(original, loader, 'Patch-time bridge contract changed')
            loader = loader.replace(original, fake)
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            files = []
            for relative, source in {**STUBS, 'com/feurstagram/extension/SharedReelLoader.java': loader}.items():
                path = folder / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding='utf-8')
                files.append(path)
            files += [SOURCE / name for name in ('NativeReelCallback.java', 'SharedReelRequestGate.java', 'RequestPolicy.java')]
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', directory, *map(str, files)], check=True)
            subprocess.run(['java', '-cp', directory, 'com.feurstagram.extension.LoaderHarness'], check=True)


if __name__ == '__main__':
    unittest.main()
