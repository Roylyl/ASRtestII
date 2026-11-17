package org.hotwordtest

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/** Only independent, clean activity windows can introduce or update a task-scoped voice group. */
internal class SpeakerEvidenceGroups(private val dimension:Int,private val config:SpeakerDecisionConfig) {
 data class Support(val source:String,val start:Long,val end:Long,val window:String,
                    val analysisWindowStart:Long=start,val analysisWindowEnd:Long=end,val activityTrack:String="",
                    val familyStart:Long=start,val familyEnd:Long=end) {
  fun json()=JSONObject().put("source",source).put("start_sample",start).put("end_sample",end).put("window_id",window)
   .put("analysis_window_start",analysisWindowStart).put("analysis_window_end",analysisWindowEnd).put("local_track",activityTrack)
   .put("family_start_sample",familyStart).put("family_end_sample",familyEnd)
 }
 private data class Reference(val vector:FloatArray,val support:Support)
 private data class Group(val id:String,val center:Center,var count:Int,val supports:MutableList<Support>,val initial:List<Reference> = emptyList())
 private data class Pending(val id:String,val center:Center,val supports:MutableList<Support>,val initial:MutableList<Reference> = mutableListOf())
 private class Center(val sum:DoubleArray,var direction:FloatArray,val legacyWeight:Int)
 private data class ShortSupport(val id:String,val vector:FloatArray,val support:Support)
 private data class ShortGroup(val id:String,val members:List<ShortSupport>)
 private val groups=mutableListOf<Group>()
 private val pending=mutableListOf<Pending>()
 private val shortPending=mutableListOf<ShortSupport>()
 private val shortGroups=mutableListOf<ShortGroup>()
 private val shortConsumed=mutableListOf<Support>()
 private val shortSeen=linkedMapOf<String,JSONObject>()
 private val promoted=linkedMapOf<String,String>()
 private val seen=linkedMapOf<String,JSONObject>()
 private val consumed=mutableListOf<Support>()
 private var nextPending=1
 private var nextShort=1
 private var nextGroup=1
 private fun normalized(value:FloatArray):FloatArray {
  require(value.size==dimension && value.all{it.isFinite()}){"声纹向量无效"}
  val norm=sqrt(value.sumOf{it.toDouble()*it}).toFloat();require(norm>1e-8f){"声纹向量范数无效"}
  return FloatArray(value.size){value[it]/norm}
 }
 private fun similarity(a:FloatArray,b:FloatArray)=a.indices.sumOf{a[it].toDouble()*b[it]}
 private fun freshCenter(vector:FloatArray,legacyWeight:Int=0):Center =
  Center(DoubleArray(dimension){vector[it].toDouble()*maxOf(1,legacyWeight)},vector,legacyWeight)

 private fun direction(sum:DoubleArray):FloatArray {
  val norm=sqrt(sum.sumOf{it*it})
  require(norm.isFinite()&&norm>1e-8){"声纹中心向量无效"}
  return FloatArray(dimension){(sum[it]/norm).toFloat()}
 }

 private fun addSupport(center:Center,vector:FloatArray) {
  for(i in vector.indices)center.sum[i]+=vector[i].toDouble()
  center.direction=direction(center.sum)
 }
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
 fun clear(){groups.clear();pending.clear();promoted.clear();seen.clear();consumed.clear();nextPending=1
  shortPending.clear();shortGroups.clear();shortConsumed.clear();shortSeen.clear();nextShort=1;nextGroup=1}
 fun resolved(pendingKey:String?):String?=pendingKey?.let{promoted[it]}
 fun nearest(raw:FloatArray):JSONObject {
  val v=normalized(raw)
  val scores=groups.map{it.id to similarity(it.center.direction,v)}.sortedByDescending{it.second}
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
  val scores=groups.map{it to similarity(it.center.direction,vector)}.sortedByDescending{it.second}
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
    addSupport(group.center,vector);group.count++;group.supports.add(support)
    consumed.add(support);output.put("center_updated",true).put("support_consumed",true)
   }
   output.put("speaker_id",group.id).put("speaker_status","confirmed_task_voice_match").put("decision","match_existing")
  }else if(best!=null && best.second>=config.newSpeakerMax){
   output.put("speaker_status",if(margin!=null&&margin<config.ambiguityMargin)"ambiguous_multiple" else "novelty_not_established")
  }else{
   val possible=pending.map{it to similarity(it.center.direction,vector)}.sortedByDescending{it.second}
   val pool=possible.firstOrNull()?.takeIf{it.second>=maxOf(config.strongMatch,config.clusterThreshold,0.60)}?.first
   val item=if(pool!=null)pool else if(pending.size<32)Pending("P"+nextPending++,freshCenter(vector),mutableListOf()).also{pending.add(it)} else null
   if(item==null)output.put("speaker_status","pending_limit_reached")
   else{
    if(independent(item.supports,support)){
     if(item.initial.size<2)item.initial.add(Reference(vector.copyOf(),support))
     if(item.supports.isNotEmpty())addSupport(item.center,vector)
     item.supports.add(support)
     consumed.add(support);output.put("support_consumed",true)
    }
    val total=item.supports.sumOf{it.end-it.start}
    output.put("pending_key",item.id).put("support_windows",item.supports.size).put("support_samples",total)
     .put("speaker_status","pending_anonymous_confirmation")
    if(item.supports.size>=2 && total>=6*16000L && groups.size<32){
     val group=Group("S"+nextGroup++,item.center,item.supports.size,item.supports.toMutableList(),item.initial.toList())
     groups.add(group);pending.remove(item);promoted[item.id]=group.id
     output.put("speaker_id",group.id).put("speaker_status","confirmed_new_task_voice").put("decision","two_independent_pure_windows")
    }
   }
  }
  return remember(support,output)
 }
 private fun sameFamily(a:Support,b:Support)=a.source==b.source&&(a.window==b.window||
  a.familyStart<b.familyEnd&&b.familyStart<a.familyEnd)
 private fun shortReused(support:Support)=shortConsumed.any{sameFamily(it,support)||
  it.source==support.source&&it.start<support.end&&support.start<it.end}
 private fun shortRemember(support:Support,out:JSONObject):JSONObject {
  shortSeen[key(support)]=JSONObject(out.toString());while(shortSeen.size>2048)shortSeen.remove(shortSeen.keys.first())
  return out
 }
 private fun referenceScores(candidate:Support,candidateVector:FloatArray):List<Pair<Group,Double>>? {
  if(groups.isEmpty())return null
  val result=mutableListOf<Pair<Group,Double>>()
  for(group in groups){
   val refs=group.initial
   if(refs.size!=2||!independent(listOf(refs[0].support),refs[1].support)||refs.any{ref->
     sameFamily(ref.support,candidate)||ref.support.source==candidate.source&&
      ref.support.start<candidate.familyEnd&&candidate.familyStart<ref.support.end})return null
   val center=normalized(FloatArray(dimension){at->refs[0].vector[at]+refs[1].vector[at]})
   result.add(group to similarity(center,candidateVector))
  }
  return result
 }
 /** Read-only one-second verification. Neither the query nor its raw activity family
  * may supply a reference; a positive result is not permission to create a template. */
 fun verifyShortWindow(raw:FloatArray,support:Support,targetId:String):JSONObject {
  val match=maxOf(config.strongMatch,config.clusterThreshold)
  val pairMinimum=maxOf(config.strongMatch,config.clusterThreshold,0.60)
  val out=JSONObject().put("verified",false).put("target_id",targetId).put("provenance",support.json())
   .put("learning_allowed",false).put("support_consumed",false).put("center_updated",false)
   .put("match_threshold",match).put("pair_threshold",pairMinimum).put("ambiguity_margin",config.ambiguityMargin)
   .put("reference_mode","independent_families_and_immutable_long_initials")
  fun no(reason:String)=out.put("status",reason)
  if(support.source.isBlank()||support.window.isBlank()||support.start<0||support.end-support.start!=16000L||
   support.familyStart<0||support.familyStart>support.start||support.familyEnd<support.end)return no("invalid_probe_interval")
  val vector=try{normalized(raw)}catch(_:IllegalArgumentException){return no("invalid_probe_embedding")}
  val target=shortGroups.firstOrNull{it.id==targetId}?:return no("short_target_unavailable")
  fun independentMembers(group:ShortGroup):List<ShortSupport> {
   val chosen=mutableListOf<ShortSupport>()
   for(member in group.members){val origin=member.support
    if(sameFamily(origin,support)||origin.source==support.source&&origin.start<support.familyEnd&&support.familyStart<origin.end)continue
    if(chosen.none{sameFamily(it.support,origin)}&&independent(chosen.map{it.support},origin))chosen.add(member)
   }
   return chosen
  }
  fun rows(members:List<ShortSupport>)=JSONArray().apply{members.forEach{put(JSONObject()
   .put("pending_key",it.id).put("provenance",it.support.json()).put("similarity",similarity(it.vector,vector)))}}
  val references=independentMembers(target)
  out.put("supports",rows(references)).put("independent_support_count",references.size)
  if(references.size<2)return no("insufficient_independent_target_supports")
  val minimum=references.minOf{similarity(it.vector,vector)}
  out.put("minimum_pair_similarity",minimum)
  val longScores=referenceScores(support,vector)?:return no("independent_long_reference_unavailable")
  val bestLong=longScores.maxOf{it.second}
  out.put("best_long_score",bestLong).put("long_margin",minimum-bestLong)
   .put("long_references",JSONArray().apply{longScores.forEach{(group,score)->put(JSONObject().put("speaker_id",group.id)
    .put("similarity",score).put("supports",JSONArray().apply{group.initial.forEach{put(it.support.json())}}))}})
  val competitorRows=JSONArray();val competitorScores=mutableListOf<Double>()
  for(group in shortGroups)if(group.id!=targetId){
   val members=independentMembers(group);val eligible=members.size>=2
   val score=if(eligible)members.minOf{similarity(it.vector,vector)} else null
   competitorRows.put(JSONObject().put("speaker_id",group.id).put("independent_support_count",members.size)
    .put("eligible",eligible).put("minimum_pair_similarity",score?:JSONObject.NULL).put("supports",rows(members)))
   if(score!=null)competitorScores.add(score)
  }
  val competitor=competitorScores.maxOrNull()
  out.put("short_competitors",competitorRows).put("best_other_short_score",competitor?:JSONObject.NULL)
   .put("short_margin",competitor?.let{minimum-it}?:JSONObject.NULL)
  if(!minimum.isFinite()||!bestLong.isFinite()||competitor?.isFinite()==false)return no("invalid_reference_score")
  if(minimum<pairMinimum)return no("insufficient_target_similarity")
  if(bestLong>=match)return no("existing_long_identity_conflict")
  if(minimum-bestLong<config.ambiguityMargin)return no("insufficient_long_margin")
  if(competitor!=null&&minimum-competitor<config.ambiguityMargin)return no("ambiguous_short_groups")
  return out.put("verified",true).put("status","independent_short_window_verified")
 }
 /** Separate recognition-only evidence: it never updates a long group or its consumed ranges. */
 fun observeShort(raw:FloatArray,support:Support):JSONObject {
  require(support.source.isNotBlank()&&support.window.isNotBlank()&&support.start>=0&&
   support.end-support.start in 24000L until 48000L&&support.familyStart>=0&&
   support.familyStart<=support.start&&support.familyEnd>=support.end){"短语音原始活动范围无效"}
  shortSeen[key(support)]?.let{return JSONObject(it.toString()).put("center_updated",false)
   .put("support_consumed",false).put("duplicate_support",true)}
  val vector=normalized(raw);val match=maxOf(config.strongMatch,config.clusterThreshold)
  val pairMinimum=maxOf(config.strongMatch,config.clusterThreshold,0.60)
  val out=JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","pending_short_confirmation")
   .put("candidate_kind","short_recognition_only").put("learning_allowed",false).put("recognition_only",true)
   .put("center_updated",false).put("support_consumed",false).put("provenance",support.json())
   .put("threshold",match).put("short_pair_threshold",pairMinimum).put("minimum_family_count",3)
   .put("reference_mode","immutable_initial_two_independent_of_family")
  val scores=referenceScores(support,vector)?.sortedByDescending{it.second}
   ?:return shortRemember(support,out.put("speaker_status","short_independent_reference_unavailable"))
  val best=scores.first();val margin=scores.getOrNull(1)?.let{best.second-it.second}
  out.put("best_existing_speaker_id",best.first.id).put("similarity_to_previous",best.second)
   .put("candidate_margin",margin?:JSONObject.NULL)
   .put("reference_supports",JSONArray().apply{scores.forEach{(g,s)->put(JSONObject().put("speaker_id",g.id)
    .put("similarity",s).put("supports",JSONArray().apply{g.initial.forEach{put(it.support.json())}}))}})
  // A diagnostic match to an old group never relabels a short interval.
  if(best.second>=match)return shortRemember(support,out.put("speaker_status","short_old_identity_diagnostic"))
  val shortMatches=shortGroups.map{g->g to g.members.minOf{similarity(it.vector,vector)}}.sortedByDescending{it.second}
  val known=shortMatches.firstOrNull()
  if(known!=null&&known.second>=pairMinimum&&known.second-best.second>=config.ambiguityMargin&&
   (shortMatches.size<2||known.second-shortMatches[1].second>=config.ambiguityMargin))
   return shortRemember(support,out.put("speaker_id",known.first.id).put("speaker_status","short_readonly_match")
    .put("decision","confirmed_short_group_readonly").put("minimum_pair_similarity",known.second))
  if(shortReused(support))return shortRemember(support,out.put("speaker_status","short_source_already_promoted").put("duplicate_support",true))
  if(shortPending.size>=32)return shortRemember(support,out.put("speaker_status","short_pending_limit_reached"))
  val added=ShortSupport("Q"+nextShort++,vector,support);shortPending.add(added)
  out.put("pending_key",added.id).put("support_consumed",true)
  val longScores=shortPending.mapNotNull{item->referenceScores(item.support,item.vector)?.maxOf{it.second}?.takeIf{it<match}?.let{item.id to it}}.toMap()
  fun compatible(a:ShortSupport,b:ShortSupport):Boolean {
   if(sameFamily(a.support,b.support)||a.support.source==b.support.source&&a.support.start<b.support.end&&b.support.start<a.support.end)return false
   val score=similarity(a.vector,b.vector)
   return score>=pairMinimum&&score-longScores.getValue(a.id)>=config.ambiguityMargin&&score-longScores.getValue(b.id)>=config.ambiguityMargin
  }
  val neighbours=shortPending.filter{it.id!=added.id&&it.id in longScores&&compatible(added,it)}
  var chosen:List<ShortSupport>?=null;var strongest=-1.0
  for(i in neighbours.indices)for(j in i+1 until neighbours.size){val a=neighbours[i];val b=neighbours[j]
   if(!compatible(a,b))continue
   val score=minOf(similarity(added.vector,a.vector),similarity(added.vector,b.vector),similarity(a.vector,b.vector))
   if(score>strongest){strongest=score;chosen=listOf(a,b,added)}
  }
  val members=chosen
  if(members!=null&&shortGroups.size<32){
   val group=ShortGroup("S"+nextGroup++,members);shortGroups.add(group)
   members.forEach{promoted[it.id]=group.id;shortConsumed.add(it.support)}
   shortPending.removeAll{shortReused(it.support)}
   out.put("speaker_id",group.id).put("speaker_status","confirmed_short_task_voice")
    .put("decision","three_independent_short_families").put("support_windows",3)
    .put("support_samples",members.sumOf{it.support.end-it.support.start}).put("minimum_pair_similarity",strongest)
    .put("short_supports",JSONArray().apply{members.forEach{put(JSONObject().put("pending_key",it.id).put("provenance",it.support.json()))}})
  }else if(shortGroups.size>=32)out.put("speaker_status","short_group_limit_reached")
  return shortRemember(support,out)
 }
 private fun vector(values:JSONArray)=normalized(FloatArray(values.length()){values.getDouble(it).toFloat()})
 private fun vectors(value:FloatArray)=JSONArray().also{out->value.forEach{out.put(it.toDouble())}}
 private fun support(o:JSONObject)=Support(o.getString("source"),o.getLong("start_sample"),o.getLong("end_sample"),o.getString("window_id"),o.optLong("analysis_window_start",o.getLong("start_sample")),o.optLong("analysis_window_end",o.getLong("end_sample")),o.optString("local_track"),o.optLong("family_start_sample",o.getLong("start_sample")),o.optLong("family_end_sample",o.getLong("end_sample")))
 private fun supports(value:JSONArray)=MutableList(value.length()){support(value.getJSONObject(it))}
 private fun references(rows:List<Reference>)=JSONArray().apply{rows.forEach{put(JSONObject().put("embedding",vectors(it.vector)).put("support",it.support.json()))}}
 private fun readReferences(rows:JSONArray?)=if(rows==null)emptyList() else List(rows.length()){i->val row=rows.getJSONObject(i);Reference(vector(row.getJSONArray("embedding")),support(row.getJSONObject("support")))}
 private fun shortJson(item:ShortSupport)=JSONObject().put("id",item.id).put("embedding",vectors(item.vector)).put("support",item.support.json())
 private fun shortItem(row:JSONObject)=ShortSupport(row.getString("id"),vector(row.getJSONArray("embedding")),support(row.getJSONObject("support")))
 private fun writeCenter(out:JSONObject,center:Center):JSONObject =
  out.put("embedding",vectors(center.direction))
   .put("embedding_sum",JSONArray().apply{center.sum.forEach{put(it)}})
   .put("legacy_center_weight",center.legacyWeight)

 private fun readCenter(row:JSONObject,count:Int):Center {
  val stored=row.optJSONArray("embedding_sum")
  // Older tasks retain only a unit direction. Use it as a weighted historical
  // reference and keep this approximation explicit when saving the new snapshot.
  if(stored==null)return freshCenter(vector(row.getJSONArray("embedding")),if(count>1)count else 0)
  require(stored.length()==dimension)
  val legacyWeight=row.optInt("legacy_center_weight",0)
  require(legacyWeight==0||legacyWeight in 2..count)
  val sum=DoubleArray(dimension){stored.getDouble(it)}
  val restored=direction(sum)
  return Center(sum,restored,legacyWeight)
 }

 fun snapshot():JSONArray {
  val out=JSONArray()
  groups.forEach{g->out.put(writeCenter(JSONObject().put("id",g.id),g.center).put("count",g.count).put("supports",JSONArray().apply{g.supports.forEach{put(it.json())}}).put("initial_references",references(g.initial)))}
  val extra=JSONObject().put("type","activity_evidence_state").put("next_pending",nextPending).put("next_short",nextShort).put("next_group",nextGroup)
  extra.put("pending",JSONArray().apply{pending.forEach{p->put(writeCenter(JSONObject().put("id",p.id),p.center).put("supports",JSONArray().apply{p.supports.forEach{put(it.json())}}).put("initial_references",references(p.initial)))}})
  extra.put("short_pending",JSONArray().apply{shortPending.forEach{put(shortJson(it))}})
  extra.put("short_groups",JSONArray().apply{shortGroups.forEach{g->put(JSONObject().put("id",g.id).put("members",JSONArray().apply{g.members.forEach{put(shortJson(it))}}))}})
  extra.put("short_consumed",JSONArray().apply{shortConsumed.forEach{put(it.json())}})
  extra.put("short_seen",JSONObject().apply{shortSeen.forEach{(k,v)->put(k,v)}})
  extra.put("promoted",JSONObject().apply{promoted.forEach{(p,g)->put(p,g)}})
  extra.put("seen",JSONObject().apply{seen.forEach{(k,v)->put(k,v)}})
  extra.put("consumed_supports",JSONArray().apply{consumed.forEach{put(it.json())}})
  out.put(extra);return out
 }
 fun restore(saved:JSONArray){
  require(saved.length()<=33){"声纹状态超过上限"}
  val nextGroups=mutableListOf<Group>();val nextPending=mutableListOf<Pending>();val aliases=linkedMapOf<String,String>();val evidence=linkedMapOf<String,JSONObject>();var counter=1
  val restoredShort=mutableListOf<ShortSupport>();val restoredShortGroups=mutableListOf<ShortGroup>()
  val restoredShortConsumed=mutableListOf<Support>();val restoredShortSeen=linkedMapOf<String,JSONObject>()
  var shortCounter=1;var groupCounter=1
  var savedConsumed:List<Support>?=null
  for(i in 0 until saved.length()){
   val o=saved.getJSONObject(i)
   if(o.optString("type")=="activity_evidence_state"){
    counter=o.getInt("next_pending");require(counter>=1)
    val candidates=o.getJSONArray("pending");require(candidates.length()<=32)
    for(j in 0 until candidates.length()){val p=candidates.getJSONObject(j);val refs=readReferences(p.optJSONArray("initial_references"));require(refs.size<=2)
     val ranges=supports(p.getJSONArray("supports"))
     nextPending.add(Pending(p.getString("id"),readCenter(p,ranges.size),ranges,refs.toMutableList()))}
    shortCounter=o.optInt("next_short",1);groupCounter=o.optInt("next_group",1);require(shortCounter>=1&&groupCounter>=1)
    o.optJSONArray("short_pending")?.let{rows->require(rows.length()<=32);for(j in 0 until rows.length())restoredShort.add(shortItem(rows.getJSONObject(j)))}
    o.optJSONArray("short_groups")?.let{rows->require(rows.length()<=32);for(j in 0 until rows.length()){
     val row=rows.getJSONObject(j);val members=row.getJSONArray("members");require(members.length()==3)
     restoredShortGroups.add(ShortGroup(row.getString("id"),List(members.length()){shortItem(members.getJSONObject(it))}))
    }}
    o.optJSONArray("short_consumed")?.let{restoredShortConsumed.addAll(supports(it))}
    o.optJSONObject("short_seen")?.let{rows->require(rows.length()<=2048);for(k in rows.keys())restoredShortSeen[k]=rows.getJSONObject(k)}
    val map=o.getJSONObject("promoted");for(k in map.keys())aliases[k]=map.getString(k)
    val rows=o.getJSONObject("seen");require(rows.length()<=2048);for(k in rows.keys())evidence[k]=rows.getJSONObject(k)
    savedConsumed=o.optJSONArray("consumed_supports")?.let(::supports)
   }else{
    val count=o.getInt("count");require(count>0)
    val refs=readReferences(o.optJSONArray("initial_references"));require(refs.size<=2)
    nextGroups.add(Group(o.optString("id","S"+(nextGroups.size+1)),readCenter(o,count),count,o.optJSONArray("supports")?.let(::supports)?:mutableListOf(),refs))
   }
  }
  // Old task snapshots already carry the original support ranges inside each group/pending row.
  val restoredConsumed=savedConsumed?:(nextGroups.flatMap{it.supports}+nextPending.flatMap{it.supports}).distinctBy(::key)
  val groupIds=nextGroups.map{it.id}+restoredShortGroups.map{it.id}
  require(groupIds.all{it.matches(Regex("S[1-9][0-9]*"))}&&groupIds.distinct().size==groupIds.size){"声纹组编号无效"}
  groupCounter=maxOf(groupCounter,(groupIds.maxOfOrNull{it.substring(1).toInt()}?:0)+1)
  clear();groups.addAll(nextGroups);pending.addAll(nextPending);promoted.putAll(aliases);seen.putAll(evidence)
  consumed.addAll(restoredConsumed);this.nextPending=counter
  shortPending.addAll(restoredShort);shortGroups.addAll(restoredShortGroups);shortConsumed.addAll(restoredShortConsumed)
  shortSeen.putAll(restoredShortSeen);nextShort=shortCounter;nextGroup=groupCounter
 }
 /** Compatibility for explicit embedding-only developer replays; production callers use observe. */
 fun probeAssign(raw:FloatArray,threshold:Double):JSONObject {
  val vector=normalized(raw);val scores=groups.map{it to similarity(it.center.direction,vector)}.sortedByDescending{it.second};val best=scores.firstOrNull()
  val chosen=if(best!=null&&best.second>=threshold)best.first else{
   if(groups.size>=32)return JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","speaker_limit_reached")
   Group("S"+nextGroup++,freshCenter(vector),1,mutableListOf()).also{groups.add(it)}
  }
  return JSONObject().put("speaker_id",chosen.id).put("speaker_status","embedding_probe_only").put("similarity_to_previous",best?.second?:JSONObject.NULL).put("threshold",threshold)
 }
}
