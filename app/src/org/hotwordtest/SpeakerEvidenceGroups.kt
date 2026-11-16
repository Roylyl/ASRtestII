package org.hotwordtest

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/** Only independent, clean activity windows can introduce or update a task-scoped voice group. */
internal class SpeakerEvidenceGroups(private val dimension:Int,private val config:SpeakerDecisionConfig) {
 data class Support(val source:String,val start:Long,val end:Long,val window:String,
                    val analysisWindowStart:Long=start,val analysisWindowEnd:Long=end,val activityTrack:String="") {
  fun json()=JSONObject().put("source",source).put("start_sample",start).put("end_sample",end).put("window_id",window)
   .put("analysis_window_start",analysisWindowStart).put("analysis_window_end",analysisWindowEnd).put("local_track",activityTrack)
 }
 private data class Group(val id:String,var vector:FloatArray,var count:Int,val supports:MutableList<Support>)
 private data class Pending(val id:String,var vector:FloatArray,val supports:MutableList<Support>)
 private val groups=mutableListOf<Group>()
 private val pending=mutableListOf<Pending>()
 private val promoted=linkedMapOf<String,String>()
 private val seen=linkedMapOf<String,JSONObject>()
 private val consumed=mutableListOf<Support>()
 private var nextPending=1
 private fun normalized(value:FloatArray):FloatArray {
  require(value.size==dimension && value.all{it.isFinite()}){"声纹向量无效"}
  val norm=sqrt(value.sumOf{it.toDouble()*it}).toFloat();require(norm>1e-8f){"声纹向量范数无效"}
  return FloatArray(value.size){value[it]/norm}
 }
 private fun similarity(a:FloatArray,b:FloatArray)=a.indices.sumOf{a[it].toDouble()*b[it]}
 private fun mean(a:FloatArray,b:FloatArray,count:Int)=normalized(FloatArray(a.size){(a[it]*count+b[it])/(count+1)})
 private fun independent(known:List<Support>,next:Support)=known.none{
  it.source==next.source && it.start<next.end && next.start<it.end
 }
 private fun consumedOverlap(next:Support)=consumed.any{
  it.source==next.source&&it.start<next.end&&next.start<it.end
 }
 private fun key(value:Support)=value.source+"|"+value.start+"|"+value.end+"|"+value.window
 private fun remember(support:Support,output:JSONObject):JSONObject {
  seen[key(support)]=JSONObject(output.toString())
  while(seen.size>2048)seen.remove(seen.keys.first())
  return output
 }
 fun clear(){groups.clear();pending.clear();promoted.clear();seen.clear();consumed.clear();nextPending=1}
 fun resolved(pendingKey:String?):String?=pendingKey?.let{promoted[it]}
 fun nearest(raw:FloatArray):JSONObject {
  val v=normalized(raw)
  val scores=groups.map{it.id to similarity(it.vector,v)}.sortedByDescending{it.second}
  val best=scores.firstOrNull()
  return JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","activity_evidence_required")
   .put("best_existing_speaker_id",best?.first?:JSONObject.NULL)
   .put("similarity_to_previous",best?.second?:JSONObject.NULL).put("existing_group_count",groups.size)
   .put("candidate_margin",if(scores.size>1)scores[0].second-scores[1].second else JSONObject.NULL)
 }
 fun observe(raw:FloatArray,support:Support):JSONObject {
  val previous=seen[key(support)]
  if(previous!=null)return JSONObject(previous.toString()).put("center_updated",false).put("support_consumed",false).put("duplicate_support",true)
  require(support.source.isNotBlank()&&support.start>=0 && support.end-support.start>=3*16000L && support.window.isNotBlank()){"声纹支持窗口不足3秒"}
  val vector=normalized(raw)
  val scores=groups.map{it to similarity(it.vector,vector)}.sortedByDescending{it.second}
  val best=scores.firstOrNull();val margin=if(scores.size>1)best!!.second-scores[1].second else null
  val output=JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","speaker_undetermined")
   .put("threshold",maxOf(config.strongMatch,config.clusterThreshold)).put("clustering_threshold",config.clusterThreshold).put("new_speaker_max_similarity",config.newSpeakerMax)
   .put("candidate_kind","pure_window").put("existing_group_count",groups.size)
   .put("single_speaker_status","predicted_single_raw_activity").put("overlap_status","predicted_nonoverlap")
   .put("provenance",support.json()).put("center_updated",false).put("support_consumed",false)
  if(best!=null)output.put("best_existing_speaker_id",best.first.id).put("similarity_to_previous",best.second)
   .put("candidate_margin",margin?:JSONObject.NULL)
  // A changed crop or a different activity context must not reuse the same original audio to
  // seed another pending identity. Nearest matching is still allowed without consuming support.
  if(consumedOverlap(support)){
   output.put("duplicate_support",true).put("decision","overlapping_source_support_not_consumed")
   if(best!=null&&best.second>=maxOf(config.strongMatch,config.clusterThreshold)&&(margin==null||margin>=config.ambiguityMargin))
    output.put("speaker_id",best.first.id).put("speaker_status","existing_match_without_new_support")
   else output.put("speaker_status","overlapping_source_evidence_not_reused")
   return remember(support,output)
  }
  if(best!=null && best.second>=maxOf(config.strongMatch,config.clusterThreshold) && (margin==null || margin>=config.ambiguityMargin)){
   val group=best.first
   if(best.second>=maxOf(config.strongMatch,0.60) && independent(group.supports,support)){
    group.vector=mean(group.vector,vector,group.count);group.count++;group.supports.add(support)
    consumed.add(support);output.put("center_updated",true).put("support_consumed",true)
   }
   output.put("speaker_id",group.id).put("speaker_status","confirmed_task_voice_match").put("decision","match_existing")
  }else if(best!=null && best.second>=config.newSpeakerMax){
   output.put("speaker_status",if(margin!=null&&margin<config.ambiguityMargin)"ambiguous_multiple" else "novelty_not_established")
  }else{
   val possible=pending.map{it to similarity(it.vector,vector)}.sortedByDescending{it.second}
   val pool=possible.firstOrNull()?.takeIf{it.second>=maxOf(config.strongMatch,config.clusterThreshold,0.60)}?.first
   val item=if(pool!=null)pool else if(pending.size<32)Pending("P"+nextPending++,vector,mutableListOf()).also{pending.add(it)} else null
   if(item==null)output.put("speaker_status","pending_limit_reached")
   else{
    if(independent(item.supports,support)){
     item.vector=if(item.supports.isEmpty())vector else mean(item.vector,vector,item.supports.size);item.supports.add(support)
     consumed.add(support);output.put("support_consumed",true)
    }
    val total=item.supports.sumOf{it.end-it.start}
    output.put("pending_key",item.id).put("support_windows",item.supports.size).put("support_samples",total)
     .put("speaker_status","pending_anonymous_confirmation")
    if(item.supports.size>=2 && total>=6*16000L && groups.size<32){
     val group=Group("S"+(groups.size+1),item.vector,item.supports.size,item.supports.toMutableList())
     groups.add(group);pending.remove(item);promoted[item.id]=group.id
     output.put("speaker_id",group.id).put("speaker_status","confirmed_new_task_voice").put("decision","two_independent_pure_windows")
    }
   }
  }
  return remember(support,output)
 }
 private fun vector(values:JSONArray)=normalized(FloatArray(values.length()){values.getDouble(it).toFloat()})
 private fun vectors(value:FloatArray)=JSONArray().also{out->value.forEach{out.put(it.toDouble())}}
 private fun supports(value:JSONArray)=MutableList(value.length()){i->val o=value.getJSONObject(i);Support(o.getString("source"),o.getLong("start_sample"),o.getLong("end_sample"),o.getString("window_id"),o.optLong("analysis_window_start",o.getLong("start_sample")),o.optLong("analysis_window_end",o.getLong("end_sample")),o.optString("local_track"))}
 fun snapshot():JSONArray {
  val out=JSONArray()
  groups.forEach{g->out.put(JSONObject().put("embedding",vectors(g.vector)).put("count",g.count).put("supports",JSONArray().apply{g.supports.forEach{put(it.json())}}))}
  val extra=JSONObject().put("type","activity_evidence_state").put("next_pending",nextPending)
  extra.put("pending",JSONArray().apply{pending.forEach{p->put(JSONObject().put("id",p.id).put("embedding",vectors(p.vector)).put("supports",JSONArray().apply{p.supports.forEach{put(it.json())}}))}})
  extra.put("promoted",JSONObject().apply{promoted.forEach{(p,g)->put(p,g)}})
  extra.put("seen",JSONObject().apply{seen.forEach{(k,v)->put(k,v)}})
  extra.put("consumed_supports",JSONArray().apply{consumed.forEach{put(it.json())}})
  out.put(extra);return out
 }
 fun restore(saved:JSONArray){
  require(saved.length()<=33){"声纹状态超过上限"}
  val nextGroups=mutableListOf<Group>();val nextPending=mutableListOf<Pending>();val aliases=linkedMapOf<String,String>();val evidence=linkedMapOf<String,JSONObject>();var counter=1
  var savedConsumed:List<Support>?=null
  for(i in 0 until saved.length()){
   val o=saved.getJSONObject(i)
   if(o.optString("type")=="activity_evidence_state"){
    counter=o.getInt("next_pending");require(counter>=1)
    val candidates=o.getJSONArray("pending");require(candidates.length()<=32)
    for(j in 0 until candidates.length()){val p=candidates.getJSONObject(j);nextPending.add(Pending(p.getString("id"),vector(p.getJSONArray("embedding")),supports(p.getJSONArray("supports"))))}
    val map=o.getJSONObject("promoted");for(k in map.keys())aliases[k]=map.getString(k)
    val rows=o.getJSONObject("seen");require(rows.length()<=2048);for(k in rows.keys())evidence[k]=rows.getJSONObject(k)
    savedConsumed=o.optJSONArray("consumed_supports")?.let(::supports)
   }else{
    val count=o.getInt("count");require(count>0)
    nextGroups.add(Group("S"+(nextGroups.size+1),vector(o.getJSONArray("embedding")),count,o.optJSONArray("supports")?.let(::supports)?:mutableListOf()))
   }
  }
  // Old task snapshots already carry the original support ranges inside each group/pending row.
  val restoredConsumed=savedConsumed?:(nextGroups.flatMap{it.supports}+nextPending.flatMap{it.supports}).distinctBy(::key)
  clear();groups.addAll(nextGroups);pending.addAll(nextPending);promoted.putAll(aliases);seen.putAll(evidence)
  consumed.addAll(restoredConsumed);this.nextPending=counter
 }
 /** Compatibility for explicit embedding-only developer replays; production callers use observe. */
 fun probeAssign(raw:FloatArray,threshold:Double):JSONObject {
  val vector=normalized(raw);val scores=groups.map{it to similarity(it.vector,vector)}.sortedByDescending{it.second};val best=scores.firstOrNull()
  val chosen=if(best!=null&&best.second>=threshold)best.first else{
   if(groups.size>=32)return JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","speaker_limit_reached")
   Group("S"+(groups.size+1),vector,1,mutableListOf()).also{groups.add(it)}
  }
  return JSONObject().put("speaker_id",chosen.id).put("speaker_status","embedding_probe_only").put("similarity_to_previous",best?.second?:JSONObject.NULL).put("threshold",threshold)
 }
}
