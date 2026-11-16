import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import sun.misc.Unsafe;

public class EligibilityOutcomeAuditTest {
 static void set(Object o,String field,Object value) throws Exception {Field f=o.getClass().getDeclaredField(field);f.setAccessible(true);f.set(o,value);}
 static Object invoke(Object o,String name,Class<?>[] types,Object... args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 public static void main(String[] args)throws Exception{
  Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);Unsafe u=(Unsafe)f.get(null);
  Class<?> type=Class.forName("org.hotwordtest.VoiceLearningRun");Object run=u.allocateInstance(type);
  Map<String,String> ineligible=new LinkedHashMap<>();ineligible.put("a","未确认已登记人物");ineligible.put("b","质量补救候选仅供人工确认");ineligible.put("c","旧任务候选");
  Map<String,Object> eligible=new LinkedHashMap<>();eligible.put("c",null);eligible.put("d",null);eligible.put("e",null);
  Set<String> started=new HashSet<>();started.add("d");started.add("e");
  List<JSONObject> out=new ArrayList<>();out.add(new JSONObject().put("candidate_id","e").put("status","pending"));
  set(run,"autoIneligible",ineligible);set(run,"autoEligible",eligible);set(run,"autoStarted",started);set(run,"autoOutcomes",out);
  invoke(run,"publishUnattempted",new Class<?>[]{String.class,boolean.class},"任务已取消",false);
  check(out.size()==3,"仅发布未满足资格的两个候选");
  invoke(run,"publishUnattempted",new Class<?>[]{String.class,boolean.class},"任务已取消",true);
  invoke(run,"publishInterrupted",new Class<?>[]{});
  invoke(run,"publishUnattempted",new Class<?>[]{String.class,boolean.class},"重复调用",true);
  check(out.size()==5,"每个候选只记录一次");
  Map<String,JSONObject> byId=new HashMap<>();for(JSONObject item:out)byId.put(item.getString("candidate_id"),item);
  check("not_attempted".equals(byId.get("a").getString("status")),"未登记不应被判为失败");
  check("质量补救候选仅供人工确认".equals(byId.get("b").getString("reason")),"保留候选原因");
  check("任务已取消".equals(byId.get("c").getString("reason")),"有资格者以取消原因记录");
  check("interrupted".equals(byId.get("d").getString("status")),"已开始与未开始应区分");
  check("pending".equals(byId.get("e").getString("status")),"已有最终状态不得覆盖");
  System.out.println("eligibility outcome and cancellation audit checks passed");
 }
}
