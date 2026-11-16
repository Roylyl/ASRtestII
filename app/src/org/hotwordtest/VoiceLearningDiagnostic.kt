package org.hotwordtest

import org.json.JSONArray
import org.json.JSONObject

/** Sanitized local-only evidence. Never includes PCM, embedding vectors or profile identities. */
internal class VoiceLearningRejection(val diagnostic:JSONObject,message:String):IllegalArgumentException(message)

internal object VoiceLearningDiagnostic {
 private fun number(source:JSONObject,key:String):Any =
  if(source.has(key)&&!source.isNull(key)&&source.optDouble(key,Double.NaN).isFinite())source.optDouble(key) else JSONObject.NULL
 private fun modelName(value:String):String = when {
  value.contains("eres",true)->"ERes2NetV2"
  value.contains("cam",true)->"CAM++"
  else->"未知模型"
 }
 fun failure(stage:String,reason:String,evidence:JSONObject?=null,targetProfileId:String?=null):JSONObject {
  val source=evidence?:JSONObject()
  val out=JSONObject().put("version",1).put("stage",stage).put("status",source.optString("status","rejected"))
   .put("reason",reason).put("sample_rate",16000)
  for(key in listOf("source_start_sample","source_end_sample","window_samples"))
   if(source.has(key)&&!source.isNull(key))out.put(key,source.optLong(key))
  for(key in listOf("minimum_similarity","minimum_margin","clear_conflict_threshold","low_confidence_threshold"))
   if(source.has(key))out.put(key,number(source,key))
  val all=source.optJSONObject("models")
  if(all!=null){
   val models=JSONArray();val names=all.keys()
   while(names.hasNext()){
    val key=names.next();val m=all.optJSONObject(key)?:continue
    val entry=JSONObject().put("model",modelName(m.optString("model",m.optString("name",key))))
    if(m.has("minimum_score"))entry.put("minimum_score",number(m,"minimum_score"))
    if(m.has("minimum_similarity"))entry.put("window_consistency",number(m,"minimum_similarity"))
    if(m.has("error"))entry.put("model_error",true)
    val windows=JSONArray();val raw=m.optJSONArray("windows")
    if(raw!=null)for(i in 0 until raw.length()){
     val w=raw.optJSONObject(i)?:continue;val match=w.optJSONObject("match")
     val row=JSONObject().put("start_sample",w.optLong("start_sample",-1)).put("end_sample",w.optLong("end_sample",-1))
      .put("independent",w.optBoolean("independent",false))
     if(match!=null){
      val best=match.optString("best_profile_id");val identified=best.isNotBlank()&&best!="null"
      val isTarget=identified&&targetProfileId!=null&&best==targetProfileId
      row.put("match_status",match.optString("status","unavailable"))
       .put("best_is_target",if(identified&&targetProfileId!=null)isTarget else JSONObject.NULL)
       .put("target_score",if(isTarget)number(match,"base_similarity") else JSONObject.NULL)
       .put("other_best_score",if(!identified)JSONObject.NULL else if(isTarget)number(match,"runner_up_similarity") else number(match,"similarity"))
       .put("margin",if(isTarget)number(match,"candidate_margin") else JSONObject.NULL)
     }else{
      row.put("match_status",w.optString("registered_status",w.optString("status","unavailable")))
       .put("score",number(w,"registered_similarity")).put("margin",number(w,"registered_margin"))
     }
     windows.put(row)
    }
    entry.put("windows",windows);models.put(entry)
   }
   out.put("models",models)
  }
  out.put("note","仅供本机排查，分数不是身份概率；未知表示未获取")
  return out
 }
}
