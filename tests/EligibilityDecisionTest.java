import java.lang.reflect.*;
import org.json.*;
import sun.misc.Unsafe;
public class EligibilityDecisionTest {
 static Unsafe u;
 static Object allocate(String n)throws Exception{return u.allocateInstance(Class.forName(n));}
 static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
 static void number(Object o,String name,long v)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.setLong(o,v);}
 static Object call(Object o,String method,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(o,args);}
 static void check(boolean x,String reason){if(!x)throw new AssertionError(reason);}
 static Object decision(Object run,JSONObject entry,Object candidate,Object saved)throws Exception{return call(run,"targetDecision",new Class[]{JSONObject.class,String.class,candidate.getClass(),saved.getClass(),boolean.class},entry,"src",candidate,saved,false);}
 static String id(Object d)throws Exception{return (String)call(d,"getId",new Class[]{});}
 static String reason(Object d)throws Exception{return (String)call(d,"getReason",new Class[]{});}
 public static void main(String[] args)throws Exception{
  Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);u=(Unsafe)f.get(null);
  Object run=allocate("org.hotwordtest.VoiceLearningRun");set(run,"enabledAtStart",true);set(run,"collectionRunId","run");
  Object owner=allocate("org.hotwordtest.SpeechEvidence");set(owner,"source","src");
  Object activity=allocate("org.hotwordtest.SpeechEvidence$Activity");set(activity,"track","track");number(activity,"windowStart",0);number(activity,"windowEnd",64000);
  Object interval=allocate("org.hotwordtest.SpeechEvidence$Exclusive");number(interval,"start",0);number(interval,"end",64000);set(interval,"activity",activity);
  Object candidate=allocate("org.hotwordtest.SpeechEvidence$PureCandidate");set(candidate,"owner",owner);set(candidate,"interval",interval);
  Object saved=allocate("org.hotwordtest.VoiceLearningStore$CandidateInfo");set(saved,"collectionRunId","run");set(saved,"source","src");number(saved,"startSample",0);number(saved,"endSample",64000);
  JSONObject hit=new JSONObject().put("status","wearer_candidate").put("profile_id","person").put("base_similarity",0.80).put("candidate_margin",0.20);
  JSONObject evidence=new JSONObject().put("source","src").put("local_track","track").put("analysis_window_start",0).put("analysis_window_end",64000);
  JSONObject support=new JSONObject().put("activity",evidence).put("window_start_sample",0).put("window_end_sample",64000).put("identity",hit);
  JSONObject proof=new JSONObject().put("method","pure_activity_voice_evidence").put("registered_profile_id","person").put("supports",new JSONArray().put(support));
  JSONObject entry=new JSONObject().put("stable_speaker_id","profile:person").put("voice_identity",new JSONObject().put("status","wearer_candidate").put("profile_id","person")).put("speaker_identity_provenance",proof);
  check("person".equals(id(decision(run,entry,candidate,saved))),"valid eligibility changed");
  entry.put("stable_speaker_id","anonymous:S1");check(reason(decision(run,entry,candidate,saved)).contains("未确认"),"anonymous reason");entry.put("stable_speaker_id","profile:person");
  hit.put("base_similarity",0.69);check(reason(decision(run,entry,candidate,saved)).contains("校验条件"),"similarity reason");hit.put("base_similarity",0.80);
  evidence.put("local_track","other");check(reason(decision(run,entry,candidate,saved)).contains("未完整覆盖"),"track coverage reason");evidence.put("local_track","track");
  support.put("window_end_sample",32000);check(reason(decision(run,entry,candidate,saved)).contains("未完整覆盖"),"coverage reason");support.put("window_end_sample",64000);
  set(saved,"collectionRunId","old");check(reason(decision(run,entry,candidate,saved)).contains("不是本次"),"old task reason");set(saved,"collectionRunId","run");
  set(run,"enabledAtStart",false);check(reason(decision(run,entry,candidate,saved)).contains("未开启"),"disabled reason");
  System.out.println("eligibility decision and original threshold checks passed");
 }
}
