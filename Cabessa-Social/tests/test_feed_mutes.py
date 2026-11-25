"""Exercise actual local storage and incremental timeline filtering with JVM Android doubles."""
import json, base64, subprocess, tempfile, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'extensions/extension/src/main/java/com/feurstagram/extension'
STUBS={
'android/content/SharedPreferences.java': '''package android.content; import java.util.*;
public interface SharedPreferences {Set<String> getStringSet(String key,Set<String> fallback); Editor edit(); interface Editor {Editor putStringSet(String key,Set<String> value); boolean commit();}}''',
'android/content/Context.java': '''package android.content; import java.util.*;
public class Context {public static final int MODE_PRIVATE=0; public boolean fail=false; private final Map<String,Set<String>> values=new HashMap<>(); public Context getApplicationContext(){return this;}
public SharedPreferences getSharedPreferences(String file,int mode){return new SharedPreferences(){public Set<String> getStringSet(String key,Set<String> fallback){return new HashSet<>(values.getOrDefault(file+key,fallback));} public Editor edit(){return new Editor(){String k;Set<String> v; public Editor putStringSet(String key,Set<String> value){k=file+key;v=new HashSet<>(value);return this;} public boolean commit(){if(fail)return false;values.put(k,v);return true;}};}};} }''',
'com/feurstagram/extension/Config.java': '''package com.feurstagram.extension; class Config {static final android.content.Context context=new android.content.Context(); static android.content.Context getAppContext(){return context;}}''',
'com/feurstagram/extension/MuteHarness.java': r'''package com.feurstagram.extension;
import java.util.*;import java.nio.*;import java.io.*;import java.nio.charset.StandardCharsets;
public class MuteHarness {
 static void check(boolean yes,String what){if(!yes)throw new AssertionError(what);}
 static void resetMemory() throws Exception {java.lang.reflect.Field f=FeedMutes.class.getDeclaredField("rules");f.setAccessible(true);f.set(null,null);}
 public static void main(String[] args)throws Exception{
  android.content.Context c=Config.context;
  check(FeedMuteRules.name(" @OlD.Friend ").equals("old.friend"),"canonical");
  check(FeedMuteRules.name("https://instagram.com/user")==null,"invalid");
  check(!FeedMutes.observeAndHide("12","old.friend"),"initial visibility");
  check(FeedMutes.recent(c).contains("12|old.friend"),"observed author");
  check(FeedMutes.hide(c,null,"@OLD.FRIEND"),"hide");
  resetMemory();check(FeedMutes.observeAndHide("12","renamed"),"persistent after restart + rename");
  check(!FeedMutes.observeAndHide("99","old.friend"),"handle reuse");
  check(FeedMutes.hidden(c).contains("12|renamed"),"renamed display");
  check(FeedMutes.restore(c,"12|old.friend"),"restore stale UI");resetMemory();
  check(!FeedMutes.observeAndHide("12","renamed"),"persistent restore");
  c.fail=true;check(!FeedMutes.hide(c,"77","failure"),"write failure");c.fail=false;
  check(!FeedMutes.observeAndHide("77","failure"),"no unpersisted rules");
  check(FeedMutes.hide(c,null,"old.friend"),"resolve current handle");
  check(FeedMutes.hide(c,null,"future"),"manual pending");resetMemory();
  check(FeedMutes.observeAndHide("88","future"),"manual match");resetMemory();
  check(FeedMutes.observeAndHide("88","future_new"),"upgrade persisted");
  check(!FeedMutes.observeAndHide("89","future"),"known ID authoritative");
  check(FeedMutes.hide(c,"12","renamed"),"fixture stable id");
  BufferedReader reader=new BufferedReader(new InputStreamReader(System.in));String line;
  while((line=reader.readLine())!=null){String[] parts=line.split(" ",2);int size=Integer.parseInt(parts[0]);byte[] bytes=Base64.getDecoder().decode(parts[1]);
   FollowingMuteFilter filter=new FollowingMuteFilter();ByteArrayOutputStream out=new ByteArrayOutputStream();
   for(int off=0;off<bytes.length;off+=size){int n=Math.min(size,bytes.length-off);ByteBuffer b=ByteBuffer.allocateDirect(n+2);b.position(2);b.put(bytes,off,n);b.flip();b.position(2);ByteBuffer ro=b.asReadOnlyBuffer();int pos=ro.position();ByteBuffer result=filter.filter(ro);check(ro.position()==pos,"source consumed");while(result.hasRemaining())out.write(result.get());}
   System.out.println(Base64.getEncoder().encodeToString(out.toByteArray()));}
 }
}'''}

class FeedMuteTests(unittest.TestCase):
 def test_persistent_rules_and_timeline_chunks(self):
  def post(pk,name,i):return {"media_or_ad":{"id":str(i),"taken_at":1000-i,"user":{"pk":pk,"username":name},"caption":{"text":"שלום 🌟 {\\\"} [ ]"}}}
  a=post(12,'renamed',1);b=post('50','keep',2);c=post('88','future_new',3);d=post('51','keep2',4);tag=post('52','tagged',5);tag['media_or_ad']['caption']['user']={'pk':'12','username':'renamed'}
  unknown={'media_or_ad':{'caption':{'user':{'pk':'12','username':'renamed'}}}}
  cases=[]
  for key in ['feed_items','items','items_with_ads']:
   for items,expected in [([a,b,c,d,tag,unknown],[b,d,tag,unknown]),([a,c],[]),([b,d],[b,d]),([],[]),([1,a,'str',None,c,{},d],[1,'str',None,{},d])]:
    payload={key:items,'paging_info':{'has_more':True,'max_id':'opaque'},'nested':{key:[a,c]},'more_available':True,'next_max_id':'cursor-abc','status':'ok'}
    answer=dict(payload);answer[key]=expected
    for n in [1,2,7,97,4096]:cases.append((n,json.dumps(payload,ensure_ascii=False).encode(),answer))
  # Unknown schema, nested tagged account, multiple arrays and a unicode-escaped root key.
  cases.append((3,b'{"feed_\\u0069tems":[{"media_or_ad":{"user":{"pk":12,"username":"renamed"}}}],"next_max_id":"x"}',{'feed_items':[],'next_max_id':'x'}))
  big=post('12','renamed',6);big['media_or_ad']['caption']['text']='x'*(1024*1024+100)
  # The bounded fallback must preserve an oversized item instead of corrupting the response.
  cases.append((8192,json.dumps({'items':[a,big,c,d]}).encode(),{'items':[big,d]}))
  with tempfile.TemporaryDirectory() as tmp:
   paths=[]
   for name,content in STUBS.items():
    p=Path(tmp)/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content);paths.append(str(p))
   paths += [str(SRC/n) for n in ['FeedMuteRules.java','FeedMutes.java','FeedAuthor.java','FollowingMuteFilter.java']]
   subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*paths],check=True)
   data=''.join(f'{n} {base64.b64encode(raw).decode()}\n' for n,raw,_ in cases)
   result=subprocess.run(['java','-cp',tmp,'com.feurstagram.extension.MuteHarness'],input=data,text=True,capture_output=True)
   self.assertEqual(result.returncode,0,result.stderr)
   actual=result.stdout.splitlines();self.assertEqual(len(actual),len(cases))
   for i,(line,(_,_,expected)) in enumerate(zip(actual,cases)):
    self.assertEqual(json.loads(base64.b64decode(line)),expected,f'case {i}')

if __name__=='__main__':unittest.main()
