"""Execute the production handoff and playback gate with deterministic lifecycle events.

The harness verifies ordering/races. Actual Android Activity/media lifecycle
requires separate runtime verification; this does not simulate Instagram.
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
    'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }',
    'android/os/Handler.java': '''package android.os;
import java.util.*;
public class Handler {
  public static final List<Runnable> pending=new ArrayList<>(),posted=new ArrayList<>();
  private final List<Runnable> owned=new ArrayList<>();
  public Handler(Looper loop){}
  public void postDelayed(Runnable action,long delay){owned.add(action);pending.add(action);}
  public void post(Runnable action){owned.add(action);posted.add(action);}
  public void removeCallbacks(Runnable action){pending.remove(action);posted.remove(action);owned.remove(action);}
  public void removeCallbacksAndMessages(Object token){pending.removeAll(owned);posted.removeAll(owned);owned.clear();}
  public static void runPosted(){for(Runnable r:new ArrayList<>(posted)){if(posted.remove(r))r.run();}}
  public static void expire(){for(Runnable r:new ArrayList<>(pending)){if(pending.contains(r))r.run();}}
}''',
    'android/app/Activity.java': '''package android.app;
public class Activity { private final Application app; public Activity(Application app){this.app=app;} public Application getApplication(){return app;} }''',
    'android/app/Application.java': '''package android.app;
import android.os.Bundle; import java.util.*;
public class Application {
 public interface ActivityLifecycleCallbacks {
  void onActivityCreated(Activity a,Bundle b);void onActivityStarted(Activity a);void onActivityResumed(Activity a);
  void onActivityPaused(Activity a);void onActivityStopped(Activity a);void onActivitySaveInstanceState(Activity a,Bundle b);void onActivityDestroyed(Activity a);
 }
 public final List<ActivityLifecycleCallbacks> observers=new ArrayList<>();
 public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){observers.add(c);}
 public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){observers.remove(c);}
 public void pause(Activity a){for(ActivityLifecycleCallbacks c:new ArrayList<>(observers))c.onActivityPaused(a);}
 public void stop(Activity a){for(ActivityLifecycleCallbacks c:new ArrayList<>(observers))c.onActivityStopped(a);}
 public void destroy(Activity a){for(ActivityLifecycleCallbacks c:new ArrayList<>(observers))c.onActivityDestroyed(a);}
}''',
    'com/feurstagram/extension/LifecycleHarness.java': '''package com.feurstagram.extension;
import android.app.*; import android.os.*;
public final class LifecycleHarness {
 static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
 static SingleReelPlaybackState visible(boolean autoplay){SingleReelPlaybackState p=new SingleReelPlaybackState(autoplay);p.resume();p.windowFocus(true);return p;}
 public static void main(String[] args){switch(args[0]){
 case "barrier": {
  SingleReelPlaybackState p=visible(true);
  check(!p.canPrepare(),"new Activity becoming visible must not start decoder before source stopped");
  p.prepared();check(!p.canStart(),"early preparation must not bypass source-stop barrier");
  p.sourceStopped();check(p.canPrepare()&&p.canStart(),"stopped source permits visible prepared playback");
  break;
 }
 case "late": {
  SingleReelPlaybackState p=visible(true);p.sourceStopped();p.pause();p.prepared();
  check(!p.canStart()&&!p.canPrepare(),"late onPrepared started behind another Activity");
  p.windowFocus(true);check(!p.canStart(),"stale focus callback bypassed onPause");
  p.resume();check(!p.canStart(),"return from background autoplayed");
  p.playRequested();check(p.canStart(),"explicit foreground Play was lost");
  p.windowFocus(false);check(!p.canStart(),"lost window focus did not suspend playback");
  p.windowFocus(true);check(!p.canStart(),"focus return autoplayed after interruption");
  break;
 }
 case "restore": {
  SingleReelPlaybackState p=visible(false);p.sourceStopped();p.prepared();
  check(!p.canStart(),"recreation or process restoration autoplayed");
  p.playRequested();check(p.canStart(),"restored player cannot be explicitly resumed");
  p.pauseRequested();check(!p.canStart(),"manual pause ignored");
  p.mediaReleased();p.playRequested();check(!p.canStart(),"released decoder still counted as prepared");
  p.prepared();check(p.canStart(),"fresh decoder cannot play after explicit request");break;
 }
 case "terminal": {
  SingleReelPlaybackState p=visible(true);p.sourceStopped();p.prepared();p.fail();p.playRequested();p.resume();p.windowFocus(true);
  check(!p.canStart()&&!p.canPrepare(),"error restarted via delayed callbacks");
  p=visible(true);p.sourceStopped();p.prepared();p.close();p.playRequested();p.resume();
  check(!p.canStart()&&!p.canPrepare(),"closed viewer restarted via delayed callbacks");break;
 }
 case "handoff": {
  Application app=new Application();Activity origin=new Activity(app),other=new Activity(app);
  SingleReelHandoff h=SingleReelHandoff.begin(origin);int[] notifications={0};h.attach(()->notifications[0]++);
  check(SingleReelHandoff.find(h.id)==h&&app.observers.size()==1,"handoff not registered before launch");
  app.pause(origin);app.stop(other);check(!h.isStopped()&&notifications[0]==0,"pause or unrelated stop released barrier");
  app.stop(origin);check(!h.isStopped()&&notifications[0]==0,"inline callback preceded FragmentActivity stop completion");Handler.runPosted();check(h.isStopped()&&!h.hasTimedOut()&&notifications[0]==1,"origin stop failed to release barrier");
  app.destroy(origin);Handler.expire();check(notifications[0]==1,"duplicate lifecycle or stale timeout notified again");
  check(app.observers.isEmpty(),"stopped origin retained lifecycle observer");
  h.detach();h.attach(()->notifications[0]++);check(notifications[0]==2,"recreated player lost completed handoff");
  h.close();check(SingleReelHandoff.find(h.id)==null&&Handler.pending.isEmpty(),"finished player retained handoff");
  h=SingleReelHandoff.begin(origin);app.destroy(origin);Handler.runPosted();check(h.isStopped(),"destroyed origin left barrier waiting");h.close();break;
 }
 case "timeout": {
  Application app=new Application();Activity origin=new Activity(app);SingleReelHandoff h=SingleReelHandoff.begin(origin);int[] notifications={0};
  h.attach(()->notifications[0]++);Handler.expire();
  check(h.hasTimedOut()&&!h.isStopped()&&notifications[0]==1,"missing source stop did not fail closed");
  app.stop(origin);check(!h.isStopped(),"late source stop resurrected failed handoff");
  check(app.observers.isEmpty(),"timed out handoff leaked observer");h.close();
  h=SingleReelHandoff.begin(origin);long id=h.id;app.stop(origin);Handler.runPosted();check(h.isStopped(),"unclaimed handoff did not observe source stop");Handler.expire();
  check(SingleReelHandoff.find(id)==null&&app.observers.isEmpty(),"uncreated target leaked pending handoff");
  h=SingleReelHandoff.begin(origin);h.attach(()->notifications[0]++);app.stop(origin);h.close();Handler.runPosted();
  check(notifications[0]==1,"queued stop callback resurrected a closed handoff");
  h=SingleReelHandoff.begin(origin);Object session=new Object();h.setSelection(session,"123");
  check(h.takeSelection()==null,"session exposed before source stopped");app.stop(origin);Handler.runPosted();
  SingleReelHandoff.Selection chosen=h.takeSelection();check(chosen!=null&&chosen.session==session&&chosen.mediaId.equals("123"),"private selection lost");
  check(h.takeSelection()==null,"session consumed more than once");h.close();break;
 }
 default:throw new AssertionError("unknown test");
 }}
}''',
}


class SingleReelLifecycleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (shutil.which('javac') and shutil.which('java')):
            raise RuntimeError('JDK required for player lifecycle tests')
        cls.workspace = tempfile.TemporaryDirectory()
        folder = Path(cls.workspace.name)
        sources = []
        for relative, body in STUBS.items():
            path = folder / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding='utf-8')
            sources.append(path)
        sources += [SOURCE / 'SingleReelPlaybackState.java', SOURCE / 'SingleReelHandoff.java']
        subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(folder), *map(str, sources)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def run_scenario(self, scenario):
        subprocess.run(['java', '-cp', self.workspace.name, 'com.feurstagram.extension.LifecycleHarness', scenario], check=True)

    def test_source_must_stop_before_prepare_or_start(self):
        self.run_scenario('barrier')

    def test_late_prepare_and_focus_cannot_restart_background(self):
        self.run_scenario('late')

    def test_return_recreation_and_explicit_play(self):
        self.run_scenario('restore')

    def test_failure_and_close_are_terminal(self):
        self.run_scenario('terminal')

    def test_handoff_tracks_origin_stop_not_pause(self):
        self.run_scenario('handoff')

    def test_handoff_timeout_and_cleanup(self):
        self.run_scenario('timeout')


if __name__ == '__main__':
    unittest.main()
