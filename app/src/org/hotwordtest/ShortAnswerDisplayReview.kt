package org.hotwordtest

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Final file display only. References come from the unmodified task's long-window supports. */
internal object ShortAnswerDisplayReview {
 private const val RATE=16000
 private const val MIN_SHORT=7200
 private const val CONTINUATION_MIN=24000
 private const val CONTINUATION_ANCHOR_GAP=3*RATE
 private const val ACTIVITY_VIEW=10*RATE
 private const val ACTIVITY_EDGE_GUARD=RATE/10
 private const val MAX_SHORT=40000
 private const val MAX_CHANGE_CHECKS=2
 private const val MAX_ROWS=12
 private const val MAX_GROUPS=4
 private const val REVIEW_CONTEXT=30*RATE
 private const val MAX_EXTRACTIONS_PER_MODEL=96
 private const val MAX_REUSED_REFERENCES=256
 private const val MAX_CLIPPING_FRACTION=0.01
 private const val CLIPPED_SAMPLE_LEVEL=0.995f
 private const val SHORT_VERIFICATION_MINIMUM=0.60
 private val lengths=listOf(8000,16000,24000,32000)

 private data class Range(val start:Int,val end:Int) {
  val size:Int get()=end-start
  fun overlaps(other:Range)=start<other.end&&other.start<end
  fun contains(other:Range)=start<=other.start&&other.end<=end
 }
 private data class Activity(val track:String,val range:Range)
 private data class Reference(val range:Range,val family:Activity)
 private data class Query(val index:Int,val range:Range,val references:Map<String,List<Reference>>)
 private data class SpeakerChange(val query:Query,val rowSpan:Range,val originalId:String,val alternateId:String,val anchor:Range)
 private data class Match(val id:String,val independent:Double,val center:Double,val competitor:Double)
 private data class Verification(val matches:Map<Int,Match>,val compactMatches:Map<Int,Match>)

 /** An in-memory view of the three-second vectors extracted for this file. */
 internal class ReferenceCache(model:String,private val source:String) {
  private val model=SpeakerModels.normalize(model)
  private val vectors=linkedMapOf<Pair<Int,Int>,FloatArray>()
  fun record(vector:FloatArray,evidence:JSONObject) {
   if(evidence.optString("source")!=source||vectors.size>=MAX_REUSED_REFERENCES)return
   val start=evidence.optLong("start_sample",-1)
   val end=evidence.optLong("end_sample",-1)
   if(start<0||end>Int.MAX_VALUE||end-start!=3L*RATE)return
   vectors.putIfAbsent(start.toInt() to end.toInt(),vector)
  }
  fun get(model:String,start:Int,end:Int):FloatArray? =
   if(model==this.model)vectors[start to end] else null
 }

 private fun objects(array:JSONArray?)=if(array==null)emptyList() else
  List(array.length()){array.getJSONObject(it)}

 private fun range(row:JSONObject,count:Int):Range? {
  val start=row.optLong("start_sample",row.optLong("start_ms",-1)*16)
  val end=row.optLong("end_sample",row.optLong("end_ms",-1)*16)
  return if(start>=0&&end>start&&end<=count)Range(start.toInt(),end.toInt()) else null
 }

 private fun references(diagnostics:JSONObject,activities:List<Activity>,guards:List<Range>,
                        count:Int):Map<String,List<Reference>> {
  val resolved=mutableMapOf<String,String>()
  for(row in objects(diagnostics.optJSONArray("stable_windows"))){
   val id=(row.opt("stable_speaker_id") as? String)?.takeIf{it.startsWith("anonymous:")}?:continue
   val pending=row.optJSONObject("grouping")?.optString("pending_key").orEmpty()
   if(pending.isNotEmpty())resolved[pending]=id
  }
  val groups=linkedMapOf<String,MutableList<Reference>>()
  for(row in objects(diagnostics.optJSONArray("observe_probes"))){
   if(row.optString("kind")!="identity_probe_3s"||!row.optBoolean("independent_support"))continue
   val group=row.optJSONObject("grouping")?:continue
   if(!group.optBoolean("support_consumed"))continue
   val id=(group.opt("speaker_id") as? String)?.let{"anonymous:"+it}
    ?:resolved[group.optString("pending_key")]?:continue
   val interval=range(row,count)?:continue
   if(interval.size!=3*RATE||guards.any{it.overlaps(interval)})continue
   val family=activities.firstOrNull{it.track==row.optString("local_track")&&it.range.contains(interval)}?:continue
   val candidates=groups.getOrPut(id){mutableListOf()}
   if(candidates.none{it.family.range.overlaps(family.range)||it.range.overlaps(interval)})candidates.add(Reference(interval,family))
  }
  return groups
 }

 @JvmOverloads
 @Throws(InterruptedException::class)
 fun apply(assets:AssetManager,decisionConfig:SpeakerDecisionConfig,samples:FloatArray,activity:JSONObject,
           rows:JSONArray,enableShiftedSeconds:Boolean=false,reviewLongActivity:Boolean=false,
           referenceCache:ReferenceCache?=null,cancelled:()->Boolean):JSONArray {
  val confirmedChanges=mutableMapOf<Range,JSONObject>()
  val direct=reviewDirect(assets,decisionConfig,samples,activity,rows,reviewLongActivity,referenceCache,confirmedChanges,cancelled)
  val reviewed=HalfSecondAnswerReview.apply(assets,decisionConfig,samples,activity,direct,enableShiftedSeconds,cancelled)
  if(!reviewLongActivity)return reviewed
  val continued=restoreContinuations(reviewed,activity,samples.size,cancelled)
  if(confirmedChanges.isEmpty())return continued
  // Other pending lines have already used the unmodified neighbors as anchors.
  return JSONArray().apply {
   for(row in objects(continued)){
    val change=confirmedChanges[range(row,samples.size)]
    if(change==null||directAnonymousId(row)!=change.optString("original_id")){put(row);continue}
    val revised=JSONObject(row.toString())
    change.put("original_provenance",revised.optJSONObject("speaker_identity_provenance"))
    put(revised.put("stable_speaker_id",JSONObject.NULL).put("speaker_display","说话人有变化")
     .put("association_status","conflicting_speaker_evidence").put("speaker_identity_provenance",change))
   }
  }
 }

 private data class Anchor(val id:String,val track:String,val span:Range)

 /** Display-only continuation for a split row between two independent matches. */
 private fun restoreContinuations(rows:JSONArray,activity:JSONObject,count:Int,cancelled:()->Boolean):JSONArray {
  val diagnostics=activity.optJSONObject("refinement_diagnostics")?:return rows
  val anchors=objects(diagnostics.optJSONArray("observe_probes")).mapNotNull{probe->
   if(probe.optString("kind")!="identity_probe_3s"||!probe.optBoolean("independent_support"))return@mapNotNull null
   val grouping=probe.optJSONObject("grouping")?:return@mapNotNull null
   if(!grouping.optBoolean("support_consumed"))return@mapNotNull null
   val id=(grouping.opt("speaker_id") as? String)?.takeIf{it.isNotBlank()}?:return@mapNotNull null
   val span=range(probe,count)?.takeIf{it.size==3*RATE}?:return@mapNotNull null
   Anchor("anonymous:"+id,probe.optString("local_track"),span)
  }
  if(anchors.isEmpty())return rows
  val guards=objects(activity.optJSONArray("protected_intervals")).mapNotNull{range(it,count)}
  val overlap=objects(activity.optJSONArray("predicted_overlap")).mapNotNull{range(it,count)}
  val tracks=objects(activity.optJSONArray("activities")).mapNotNull{row->
   range(row,count)?.let{Activity(row.optString("local_track"),it)}
  }.distinct()
  val spoken=objects(rows).withIndex().filter{it.value.optString("text").isNotBlank()}
  var result:JSONArray?=null
  for(i in 1 until spoken.lastIndex){
   if(cancelled()||Thread.currentThread().isInterrupted)throw InterruptedException("已停止")
   val (index,row)=spoken[i]
   if(!row.isNull("stable_speaker_id")||row.optString("speaker_display")!="说话人未识别")continue
   if(row.optJSONObject("speaker_identity_provenance")?.optString("method")=="conflicting_voice_evidence")continue
   val span=range(row,count)?:continue
   if(span.size !in CONTINUATION_MIN until MAX_SHORT||guards.any{it.overlaps(span)})continue
   val activityTrack=tracks.filter{it.range.overlaps(span)}.singleOrNull()?:continue
   if(!activityTrack.range.contains(span)||activityTrack.track.isBlank())continue
   val before=spoken[i-1]
   val after=spoken[i+1]
   val id=directAnonymousId(before.value)?:continue
   if(id!=directAnonymousId(after.value)||range(before.value,count)?.end!=span.start)continue
   val right=range(after.value,count)?.start?:continue
   if(right !in span.end..(span.end+RATE))continue
   val interval=Range(span.start,right)
   if(overlap.any{it.overlaps(interval)}||anchors.any{it.id!=id&&it.span.overlaps(interval)})continue
   val between=(before.index+1 until after.index).map(rows::getJSONObject)
   if(between.any{it.optString("speaker_display")=="多人重叠"||
     (it.opt("stable_speaker_id") as? String)?.let{other->other!=id}==true||
     it.optJSONObject("speaker_identity_provenance")?.optString("method")=="conflicting_voice_evidence"})continue
   val leftAnchor=anchors.lastOrNull{it.id==id&&it.track==activityTrack.track&&
     it.span.end in (span.start-CONTINUATION_ANCHOR_GAP)..span.start}?:continue
   val rightAnchor=anchors.firstOrNull{it.id==id&&it.track==activityTrack.track&&
     it.span.start in span.end..(span.end+CONTINUATION_ANCHOR_GAP)}?:continue
   if(!hasEdgeSupport(before.value,activityTrack.track,"window_end_sample",span.start)||
     !hasEdgeSupport(after.value,activityTrack.track,"window_start_sample",span.end))continue
   val output=result?:JSONArray(rows.toString()).also{result=it}
   output.getJSONObject(index)
    .put("stable_speaker_id",id).put("speaker_display",SpeakerTranscript.groupName(id.substringAfter(':')))
    .put("speaker_identity_provenance",JSONObject().put("method","confirmed_same_speaker_continuation")
     .put("recognition_only",true).put("learning_allowed",false)
     .put("left_reference_start_sample",leftAnchor.span.start).put("left_reference_end_sample",leftAnchor.span.end)
     .put("right_reference_start_sample",rightAnchor.span.start).put("right_reference_end_sample",rightAnchor.span.end))
  }
  return result?:rows
 }

 private fun directAnonymousId(row:JSONObject):String? {
  if(row.optJSONObject("speaker_identity_provenance")?.optString("method")!="pure_activity_voice_evidence")return null
  return (row.opt("stable_speaker_id") as? String)?.takeIf{it.startsWith("anonymous:")}
 }

 private fun hasEdgeSupport(row:JSONObject,track:String,edge:String,sample:Int):Boolean =
  objects(row.optJSONObject("speaker_identity_provenance")?.optJSONArray("supports")).any{
   it.optInt(edge,-1)==sample&&it.optJSONObject("activity")?.optString("local_track")==track
  }

 private fun reviewDirect(assets:AssetManager,decisionConfig:SpeakerDecisionConfig,samples:FloatArray,activity:JSONObject,
                          rows:JSONArray,reviewLongActivity:Boolean,referenceCache:ReferenceCache?,
                          confirmedChanges:MutableMap<Range,JSONObject>,cancelled:()->Boolean):JSONArray {
  fun active(){if(cancelled()||Thread.currentThread().isInterrupted)throw InterruptedException("已停止")}
  active()
  val diagnostics=activity.optJSONObject("refinement_diagnostics")?:return rows
  val activities=objects(activity.optJSONArray("activities")).mapNotNull{row->
   range(row,samples.size)?.let{Activity(row.optString("local_track"),it)}
  }.distinct()
  val guards=objects(activity.optJSONArray("protected_intervals")).mapNotNull{range(it,samples.size)}
  val groups=references(diagnostics,activities,guards,samples.size)
  if(groups.size !in 2..MAX_GROUPS)return rows
  val queries=mutableListOf<Query>()
  val extraQueries=mutableListOf<Query>()
  val continuations=linkedMapOf<Query,String>()
  val seamQueries=linkedMapOf<Query,String>()
  val seams=if(referenceCache!=null)objects(activity.optJSONArray("activities"))
   .map{it.optInt("analysis_window_end",-1)}.filter{it in 1 until samples.size}.toSet() else emptySet()
  val overlaps=if(referenceCache!=null)objects(activity.optJSONArray("predicted_overlap"))
   .mapNotNull{range(it,samples.size)} else emptyList()
  for(i in 0 until rows.length()){
   if(queries.size==MAX_ROWS)break
   val row=rows.getJSONObject(i)
   if(!row.isNull("stable_speaker_id")||row.optString("speaker_display")!="说话人未识别")continue
   if(row.optString("text").isBlank()||row.optJSONObject("speaker_identity_provenance")?.optString("method")=="conflicting_voice_evidence")continue
   val query=range(row,samples.size)?:continue
   if(query.size !in MIN_SHORT until MAX_SHORT)continue
   val protected=guards.filter{it.overlaps(query)}
   val newSpeaker=if(reviewLongActivity&&query.size<CONTINUATION_MIN&&protected.isNotEmpty())
    newSpeakerAtSeam(rows,i,query,protected,seams,overlaps,samples.size) else null
   if(protected.isNotEmpty()&&newSpeaker==null)continue
   if(newSpeaker!=null&&seamQueries.size>=2)continue
   val family=activities.filter{it.range.overlaps(query)}.singleOrNull()?:continue
   if(!family.range.contains(query))continue
   val pool=if(family.range.size<MAX_SHORT)queries else extraQueries
   if(pool===extraQueries&&!reviewLongActivity)continue
   val continuation=if(pool===extraQueries&&referenceCache!=null&&family.range.start<query.start)
    continuationSpeaker(rows,i,query,overlaps,samples.size) else null
   if(newSpeaker==null&&pool.size>=MAX_ROWS&&(continuation==null||continuations.size>=MAX_ROWS))continue
   if(!hasUsableQueryAudio(samples,query))continue
   val choices=groups.mapValues{(_,items)->items.asSequence()
    .filter{!it.family.range.overlaps(family.range)&&!it.range.overlaps(query)}
    .sortedBy{abs((it.range.start.toLong()+it.range.end)-(query.start.toLong()+query.end))}
    .take(3).toList()}
   if(choices.values.all{it.size>=2}){
    val candidate=Query(i,query,choices)
    if(newSpeaker!=null){seamQueries[candidate]=newSpeaker;continue}
    if(pool.size<MAX_ROWS)pool.add(candidate)
    if(continuation!=null&&continuations.size<MAX_ROWS)continuations[candidate]=continuation
   }
  }
  val extra=extraQueries.take(MAX_ROWS-queries.size)
  queries.addAll(extra)
  val changes=if(reviewLongActivity&&referenceCache!=null)
   speakerChanges(rows,diagnostics,activity.optString("source"),groups.keys,guards+overlaps,decisionConfig,samples,::active)
   else emptyList()
  val changesByIndex=changes.associateBy{it.query.index}
  val compactQueries=continuations+seamQueries+changes.associate{it.query to it.alternateId}
  if(queries.isEmpty()&&compactQueries.isEmpty())return rows
  val cam:Verification
  val eres:Verification
  try {
   cam=verify(assets,SpeakerModels.CAM,decisionConfig,samples,queries,compactQueries,referenceCache,::active)
   if(cam.matches.isEmpty()&&cam.compactMatches.isEmpty())return rows
   eres=verify(assets,SpeakerModels.ERES,decisionConfig,samples,queries.filter{it.index in cam.matches},
    compactQueries.filterKeys{it.index in cam.compactMatches},referenceCache,::active)
  }catch(e:InterruptedException){throw e}
  catch(e:Exception){active();return rows}
  val matches=(cam.matches+cam.compactMatches).mapNotNull{(index,first)->
   val second=(if(index in cam.compactMatches)eres.compactMatches else eres.matches)[index]
   second?.takeIf{it.id==first.id}?.let{Triple(index,first,it)}
  }
  active()
  if(matches.isEmpty())return rows
  // Only additional matches need the contextual check. Ordinary short turns keep priority and their original decision.
  val matchedIndices=matches.mapTo(mutableSetOf()){it.first}
  val extraMatches=(extra+continuations.keys.filter{it.index in cam.compactMatches})
   .filter{it.index in matchedIndices}.distinctBy{it.index}
  val checked=consistentActivity(assets,decisionConfig,samples,extraMatches,::active)
  val extraIndices=extraMatches.mapTo(mutableSetOf()){it.index}
  val seamMatches=seamQueries.keys.filter{it.index in matchedIndices}
  val checkedSeams=checkSeamActivity(assets,samples,seamMatches,::active)
  val seamIndices=seamMatches.mapTo(mutableSetOf()){it.index}
  val output=JSONArray(rows.toString())
  for((index,camMatch,eresMatch) in matches){
   if(index in extraIndices&&index !in checked)continue
   if(index in seamIndices&&index !in checkedSeams)continue
   val row=output.getJSONObject(index)
   fun proof(match:Match)=JSONObject().put("independent_similarity",match.independent)
    .put("reference_center_similarity",match.center).put("strongest_competitor",match.competitor)
   val change=changesByIndex[index]
   if(change!=null){
    confirmedChanges[change.rowSpan]=JSONObject().put("method","confirmed_speaker_change_within_line")
     .put("learning_allowed",false).put("recognition_only",true).put("original_id",change.originalId)
     .put("alternate_id",change.alternateId).put("short_start_sample",change.query.range.start)
     .put("short_end_sample",change.query.range.end).put("anchor_start_sample",change.anchor.start)
     .put("anchor_end_sample",change.anchor.end).put(SpeakerModels.CAM,proof(camMatch)).put(SpeakerModels.ERES,proof(eresMatch))
    continue
   }
   row.put("stable_speaker_id",camMatch.id).put("speaker_display",SpeakerTranscript.groupName(camMatch.id.substringAfter(':')))
   row.put("speaker_identity_provenance",JSONObject().put("method","independent_short_file_references")
    .put("recognition_only",true).put("learning_allowed",false)
    .put(SpeakerModels.CAM,proof(camMatch)).put(SpeakerModels.ERES,proof(eresMatch)))
   if(index in checked)row.getJSONObject("speaker_identity_provenance").put("activity_check","single_track_across_row_30s_context")
   if(index in cam.compactMatches)row.getJSONObject("speaker_identity_provenance")
    .put("reference_windows",if(index in checkedSeams)"independent_3s_window_edge" else "independent_3s_continuation")
   if(index in checkedSeams)row.getJSONObject("speaker_identity_provenance")
    .put("activity_check","two_shifted_views_single_track")
  }
  return output
 }

 /** A short probe may contradict a whole-line label. Require another original, disjoint accepted probe within the line. */
 private fun speakerChanges(rows:JSONArray,diagnostics:JSONObject,source:String,ids:Set<String>,guards:List<Range>,
                            config:SpeakerDecisionConfig,samples:FloatArray,active:()->Unit):List<SpeakerChange> {
  if(source.isBlank())return emptyList()
  val count=samples.size
  val direct=objects(rows).mapIndexedNotNull{index,row->
   if(row.optString("text").isBlank()||directAnonymousId(row)==null)return@mapIndexedNotNull null
   range(row,count)?.takeIf{it.size>=CONTINUATION_MIN+3*RATE}?.let{it to index}
  }.sortedBy{it.first.start}
  if(direct.isEmpty())return emptyList()
  val result=mutableListOf<SpeakerChange>()
  for(probe in objects(diagnostics.optJSONArray("observe_probes"))){
   active()
   if(probe.optString("kind")!="short_recurrence_probe"||probe.optString("source")!=source)continue
   val group=probe.optJSONObject("grouping")?:continue
   if(group.optString("speaker_status")!="short_old_identity_diagnostic"||
     group.optString("reference_mode")!="immutable_initial_two_independent_of_family")continue
   if(!(group.optDouble("similarity_to_previous")>=group.optDouble("short_pair_threshold"))||
     !(group.optDouble("candidate_margin")>=config.ambiguityMargin))continue
   val short=range(probe,count)?.takeIf{it.size in CONTINUATION_MIN until 3*RATE}?:continue
   val found=direct.binarySearchBy(short.start){it.first.start}
   val (span,index)=direct.getOrNull(if(found>=0)found else -found-2)?:continue
   if(!span.contains(short)||result.any{it.query.index==index}||guards.any{it.overlaps(short)})continue
   val row=rows.getJSONObject(index)
   val original=directAnonymousId(row)?:continue
   val alternate="anonymous:"+group.optString("best_existing_speaker_id")
   if(alternate==original||alternate !in ids)continue
   val anchor=objects(row.getJSONObject("speaker_identity_provenance").optJSONArray("supports")).asSequence()
    .mapNotNull{it.optJSONObject("voice")}.filter{it.optBoolean("support_consumed")&&"anonymous:"+it.optString("speaker_id")==original}
    .mapNotNull{it.optJSONObject("provenance")}.filter{it.optString("source")==source}
    .mapNotNull{range(it,count)}.firstOrNull{it.size==3*RATE&&span.contains(it)&&!it.overlaps(short)&&guards.none{g->g.overlaps(it)}}?:continue
   val choices=linkedMapOf<String,List<Reference>>()
   for(candidate in objects(group.optJSONArray("reference_supports"))){
    val id="anonymous:"+candidate.optString("speaker_id")
    val refs=objects(candidate.optJSONArray("supports")).mapNotNull{item->
     val ref=range(item,count)?:return@mapNotNull null
     if(item.optString("source")!=source||ref.size!=3*RATE||ref.overlaps(span))return@mapNotNull null
     Reference(ref,Activity(item.optString("local_track"),ref))
    }
    if(refs.size==2&&!refs[0].range.overlaps(refs[1].range))choices[id]=refs
   }
   if(choices.keys!=ids||!hasUsableQueryAudio(samples,short))continue
   result.add(SpeakerChange(Query(index,short,choices),span,original,alternate,anchor))
   if(result.size>=MAX_CHANGE_CHECKS)break
  }
  return result
 }

 /** Artificial window padding may only be revisited on a line with a nearby confirmed change of person. */
 private fun newSpeakerAtSeam(rows:JSONArray,index:Int,query:Range,protected:List<Range>,
                              seams:Set<Int>,overlaps:List<Range>,count:Int):String? {
  val seam=seams.firstOrNull{boundary->protected.all{
   it.start>=boundary-ACTIVITY_EDGE_GUARD&&it.end<=boundary+ACTIVITY_EDGE_GUARD
  }}?:return null
  if(query.start<seam&&query.end>seam)return null
  val seamGuard=Range(seam-ACTIVITY_EDGE_GUARD,seam+ACTIVITY_EDGE_GUARD)
  if(overlaps.any{it.overlaps(seamGuard)||it.overlaps(query)})return null
  val left=(index-1 downTo 0).asSequence().map(rows::getJSONObject)
   .firstOrNull{it.optString("text").isNotBlank()}?:return null
  val right=(index+1 until rows.length()).asSequence().map(rows::getJSONObject)
   .firstOrNull{it.optString("text").isNotBlank()}?:return null
  val before=directAnonymousId(left)?:return null
  val after=directAnonymousId(right)?.takeIf{it!=before}?:return null
  val leftEnd=range(left,count)?.end?:return null
  val rightStart=range(right,count)?.start?:return null
  if(query.start-leftEnd !in 0..RATE||rightStart-query.end !in 0..RATE)return null
  if(overlaps.any{it.overlaps(Range(leftEnd,rightStart))})return null
  return after
 }

 /** Shifted views can veto conflicting activity; identity still comes from both models on the original line. */
 private fun checkSeamActivity(assets:AssetManager,samples:FloatArray,queries:List<Query>,
                               active:()->Unit):Set<Int> {
  if(queries.isEmpty()||samples.size<ACTIVITY_VIEW+RATE)return emptySet()
  val accepted=mutableSetOf<Int>()
  try {
   DiarizationTest.ActivitySession(assets).use{session->
    for(query in queries){
     active()
     val center=query.range.start+query.range.size/2
     val origins=listOf(center-5*RATE,center-4*RATE).map{it.coerceIn(0,samples.size-ACTIVITY_VIEW)}.distinct()
     if(origins.size!=2)continue
     val clean=origins.all{origin->
      active()
      val result=session.process(samples.copyOfRange(origin,origin+ACTIVITY_VIEW)).getJSONObject("unsmoothed")
      val inside=objects(result.getJSONArray("segments")).mapNotNull{segment->
       val a=maxOf(query.range.start,origin+ceil(segment.getDouble("start_seconds")*RATE).toInt())
       val b=minOf(query.range.end,origin+floor(segment.getDouble("end_seconds")*RATE).toInt())
       if(b>a)Activity(segment.optString("speaker_id"),Range(a,b)) else null
      }
      val track=inside.map{it.track}.distinct().singleOrNull()
      val covered=inside.sortedBy{it.range.start}.fold(0 to query.range.start){(total,end),piece->
       (total+(piece.range.end-maxOf(end,piece.range.start)).coerceAtLeast(0)) to maxOf(end,piece.range.end)
      }.first
      !track.isNullOrBlank()&&covered.toDouble()/query.range.size>=0.55
     }
     if(clean)accepted.add(query.index)
    }
   }
  }catch(e:InterruptedException){throw e}
  catch(e:Exception){active()}
  return accepted
 }

 private fun continuationSpeaker(rows:JSONArray,index:Int,query:Range,overlaps:List<Range>,count:Int):String? {
  fun directId(row:JSONObject):String? {
   if(row.optJSONObject("speaker_identity_provenance")?.optString("method")!="pure_activity_voice_evidence")return null
   return (row.opt("stable_speaker_id") as? String)?.takeIf{it.startsWith("anonymous:")}
  }
  val before=(index-1 downTo 0).asSequence().map(rows::getJSONObject)
   .firstOrNull{it.optString("text").isNotBlank()}?:return null
  if(range(before,count)?.end!=query.start)return null
  val id=directId(before)?:return null
  for(next in index+1 until rows.length()){
   val row=rows.getJSONObject(next)
   val span=range(row,count)?:return null
   if(span.start<query.end||span.start-query.end>RATE)return null
   val label=row.opt("stable_speaker_id") as? String
   if((label!=null&&label!=id)||row.optString("speaker_display")=="多人重叠")return null
   if(row.optString("text").isBlank())continue
   return id.takeIf{directId(row)==id&&overlaps.none{it.overlaps(Range(query.start,span.start))}}
  }
  return null
 }

 private fun consistentActivity(assets:AssetManager,config:SpeakerDecisionConfig,samples:FloatArray,
                                queries:List<Query>,active:()->Unit):Set<Int> {
  if(queries.isEmpty())return emptySet()
  val size=minOf(samples.size,REVIEW_CONTEXT)
  val byOrigin=queries.groupBy{(it.range.start+it.range.size/2-size/2).coerceIn(0,samples.size-size)}
  val verified=mutableSetOf<Int>()
  try {
   active()
   DiarizationTest.Session(assets,SpeakerModels.CAM,-1,similarityThreshold=config.clusterThreshold,rawActivity=true).use{session->
    for((origin,group) in byOrigin){
     active()
     val segments=session.process(samples.copyOfRange(origin,origin+size)).getJSONArray("segments")
     active()
     for(query in group){
      if(singleTrackThroughout(segments,query.range.start-origin,query.range.end-origin))verified.add(query.index)
     }
    }
   }
  }catch(e:InterruptedException){throw e}
  catch(e:Exception){active()}
  return verified
 }

 /** Native segments are ordered by start time. Scan past full coverage to detect a second overlapping track. */
 private fun singleTrackThroughout(segments:JSONArray,start:Int,end:Int):Boolean {
  var track:Int?=null
  var covered=start
  for(i in 0 until segments.length()){
   val span=segments.getJSONObject(i)
   val left=maxOf(start,ceil(span.getDouble("start_seconds")*RATE).toInt())
   val right=minOf(end,floor(span.getDouble("end_seconds")*RATE).toInt())
   if(right<=left)continue
   val id=span.getInt("speaker_id")
   if(left>covered||(track!=null&&id!=track))return false
   track=id
   covered=maxOf(covered,right)
  }
  return covered==end
 }

 private fun hasUsableQueryAudio(samples:FloatArray,query:Range):Boolean {
  var power=0.0
  var clipped=0
  for(index in query.start until query.end){
   val sample=samples[index]
   if(!sample.isFinite())return false
   power+=sample.toDouble()*sample
   if(abs(sample)>=CLIPPED_SAMPLE_LEVEL)clipped++
  }
  return sqrt(power/query.size)>=LocalSpeakerTracker.MIN_EMBEDDING_RMS&&
   clipped.toDouble()/query.size<=MAX_CLIPPING_FRACTION
 }

 private fun similarity(a:FloatArray,b:FloatArray)=a.indices.sumOf{a[it].toDouble()*b[it]}

 private fun referenceRanges(original:Range,length:Int):List<Range> {
  val spare=original.size-length
  return listOf(original)+(1..3).map{quarter->
   val start=original.start+spare*quarter/4
   Range(start,start+length)
  }
 }

 private fun verify(assets:AssetManager,model:String,config:SpeakerDecisionConfig,samples:FloatArray,
                    queries:List<Query>,continuations:Map<Query,String>,referenceCache:ReferenceCache?,
                    active:()->Unit):Verification {
  val extractor=LocalSpeakerTracker(assets,model)
  val cache=mutableMapOf<Range,FloatArray?>()
  var newRanges=0
  val identityMinimum=maxOf(config.strongMatch,config.clusterThreshold)
  val centerMinimum=maxOf(identityMinimum,SHORT_VERIFICATION_MINIMUM)
  fun reused(range:Range)=referenceCache?.get(model,range.start,range.end)
  fun embedding(range:Range):FloatArray? {
   active()
   if(cache.containsKey(range))return cache[range]
   val previous=reused(range)
   if(previous==null){
    if(newRanges>=MAX_EXTRACTIONS_PER_MODEL)return null
    newRanges++
   }
   val pcm=samples.copyOfRange(range.start,range.end)
   val value=if(pcm.count{abs(it)>=CLIPPED_SAMPLE_LEVEL}.toDouble()/pcm.size>MAX_CLIPPING_FRACTION)null
    else previous?:extractor.reviewShortEmbedding(pcm)
   cache[range]=value
   return value
  }
  fun match(query:Query,crops:(Reference)->List<Range>):Match? {
    val vector=embedding(query.range)?:return null
    data class Vote(val score:Double,val vector:FloatArray)
    data class GroupScore(val id:String,val strongest:Double,val independent:Double,val center:Double)
    val scores=query.references.mapNotNull{(id,references)->
     val votes=references.mapNotNull{reference->
      crops(reference).mapNotNull{embedding(it)?.let{v->Vote(similarity(vector,v),v)}}
       .maxByOrNull{it.score}
     }.sortedByDescending{it.score}
     if(votes.size<2)null else {
      val a=votes[0].vector;val b=votes[1].vector
      val sum=DoubleArray(a.size){a[it].toDouble()+b[it]}
      val norm=sqrt(sum.sumOf{it*it})
      if(norm<=1e-8)null else GroupScore(id,votes[0].score,votes[1].score,
       sum.indices.sumOf{sum[it]*vector[it]}/norm)
     }
    }
    if(scores.size!=query.references.size)return null
    val best=scores.maxBy{it.independent}
    val others=scores.filter{it.id!=best.id}
    val rival=others.maxOf{maxOf(it.strongest,it.center)}
    if(best.independent>=identityMinimum&&best.center>=centerMinimum&&
     best.independent-others.maxOf{it.strongest}>=config.ambiguityMargin&&best.center-rival>=config.ambiguityMargin)
     return Match(best.id,best.independent,best.center,rival)
    return null
  }
  try {
   val attempted=mutableSetOf<Int>()
   val verified=linkedMapOf<Int,Match>()
   for(query in queries){
    val length=lengths.minBy{abs(it-query.range.size)}
    val crops:(Reference)->List<Range> = {reference->referenceRanges(reference.range,length)}
    val required=(listOf(query.range)+query.references.values.flatten().flatMap(crops)).toSet()
    // Preserve the existing admission and scoring order for regular reviews.
    if(required.count{it !in cache}>MAX_EXTRACTIONS_PER_MODEL-cache.size)continue
    attempted.add(query.index)
    match(query,crops)?.let{verified[query.index]=it}
   }
   val compact=linkedMapOf<Int,Match>()
   for((query,id) in continuations){
    if(attempted.size>=MAX_ROWS)break
    if(query.index in attempted)continue
    val required=(listOf(query.range)+query.references.values.flatten().map{it.range}).toSet()
    if(required.count{it !in cache&&reused(it)==null}>MAX_EXTRACTIONS_PER_MODEL-newRanges)continue
    attempted.add(query.index)
    val decision=try{match(query){listOf(it.range)}}
    catch(e:InterruptedException){throw e}
    catch(e:Exception){active();null}
    decision?.takeIf{it.id==id}?.let{compact[query.index]=it}
   }
   return Verification(verified,compact)
  }finally{extractor.close()}
 }
}
