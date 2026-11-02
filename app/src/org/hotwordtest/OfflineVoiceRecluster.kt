package org.hotwordtest

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt

/** Second-stage evidence only when the existing online grouping found fewer than two stable groups. */
object OfflineVoiceRecluster {
 fun apply(assets:AssetManager,samples:FloatArray,item:JSONObject,speakerKey:String,
           decisionConfig:SpeakerDecisionConfig,cancelled:()->Boolean):JSONObject {
  fun stop(){if(cancelled())throw CancellationException("说话人归组已取消")}
  val metadata=item.optJSONObject("offline_voice_grouping")
   ?:return JSONObject().put("status","skipped").put("reason","missing_existing_grouping")
  if(metadata.optInt("stable_group_count",0)>=2)return JSONObject().put("status","skipped").put("reason","existing_groups")
  if(SpeakerModels.normalize(speakerKey)!=SpeakerModels.CAM)return JSONObject().put("status","skipped").put("reason","cam_required")
  if(item.optJSONObject("diarization")?.optString("processing_mode")=="single_window_segmentation_only")
   return JSONObject().put("status","skipped").put("reason","window_local")
  val turns=item.optJSONArray("speaker_turn_transcripts")
   ?:return JSONObject().put("status","skipped").put("reason","missing_turns")
  val tracker=LocalSpeakerTracker(assets,speakerKey,decisionConfig=decisionConfig)
  val observations=mutableListOf<OfflineVoiceCluster.Sample>()
  try {
   for(i in 0 until turns.length()){
    stop();val t=turns.getJSONObject(i);val ids=t.optJSONArray("speaker_candidates")
    if(ids?.length()!=1||t.optString("text").isBlank())continue
    val begin=t.optLong("start_sample",-1);val end=t.optLong("end_sample",-1)
    if(begin<0||end>samples.size||end<=begin||end-begin<16000)continue
    val x=samples.copyOfRange(begin.toInt(),end.toInt())
    val rms=sqrt(x.sumOf{it.toDouble()*it}/x.size)
    val strong=x.size>=32000&&rms>=0.003
    val signal=if(rms>=.003)x else {
     val peak=x.maxOf{abs(it.toDouble())}
     if(rms<=0||peak<=0)continue
     val gain=minOf(6.0,.006/rms,.9/peak)
     if(rms*gain<.003)continue
     FloatArray(x.size){(x[it]*gain).toFloat()}
    }
    val v=tracker.embedding(signal)?:continue
    observations.add(OfflineVoiceCluster.Sample(i,begin*1000/16000,end*1000/16000,v,strong))
   }
  }finally{tracker.close()}
  stop()
  val clustered=OfflineVoiceCluster.infer(observations,match=decisionConfig.strongMatch,margin=decisionConfig.ambiguityMargin)
   ?:return JSONObject().put("status","skipped").put("reason","insufficient_separate_evidence").put("valid_turns",observations.size)
  val ms=linkedMapOf<String,Long>();val rows=linkedMapOf<String,Int>()
  for(i in 0 until turns.length()){
   val t=turns.getJSONObject(i);val ids=t.optJSONArray("speaker_candidates")?:JSONArray()
   val group=if(ids.length()==1)clustered.labels[i] else null
   val names=t.optJSONObject("wearer_identity")
   val registered=if(names?.optString("status")=="wearer_candidate"&&!names.isNull("display_name"))names.optString("display_name").takeIf{it.isNotBlank()} else null
   val category=when{ids.length()>1->"overlap";ids.length()==1&&registered!=null->"registered_candidate";group!=null->"grouped";else->"ungrouped"}
   val display=when(category){"overlap"->"多人重叠，身份待确认";"registered_candidate"->registered!!;"grouped"->"说话人"+group!!.removePrefix("S");else->"说话人待确认"}
   val assigned=JSONArray();val reasons=JSONArray()
   for(j in 0 until ids.length()){assigned.put(if(ids.length()==1)group?:JSONObject.NULL else JSONObject.NULL);reasons.put(if(group!=null)"offline_cluster" else "unassigned")}
   t.put("speaker_display",display).put("offline_voice_groups",assigned).put("offline_voice_group_reasons",reasons)
    .put("offline_voice_display_category",category).put("voice_recluster_applied",true)
    .put("offline_voice_recluster_strong",i in clustered.strongIndices)
   if(t.optString("text").isNotBlank()){
    ms[category]=(ms[category]?:0L)+(t.optLong("end_ms")-t.optLong("start_ms")).coerceAtLeast(0)
    rows[category]=(rows[category]?:0)+1
   }
  }
  val counts=JSONObject();val times=JSONObject();rows.forEach{(k,v)->counts.put(k,v)};ms.forEach{(k,v)->times.put(k,v)}
  item.put("offline_voice_grouping",JSONObject().put("method","cam_average_linkage_repeated_evidence_v1")
   .put("stable_group_count",clustered.clusterCount).put("similarity",.58).put("match_similarity",decisionConfig.strongMatch)
   .put("margin",decisionConfig.ambiguityMargin).put("stable_observations",2).put("stable_duration_ms",4000)
   .put("text_rows_by_category",counts).put("text_row_duration_ms_by_category",times)
   .put("note","声纹分组为候选身份；重叠声音和证据不足保留待确认，时间边界沿用原切分"))
  return JSONObject().put("status","applied").put("stable_group_count",clustered.clusterCount).put("valid_turns",observations.size)
 }
}
