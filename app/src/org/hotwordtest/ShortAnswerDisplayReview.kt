package org.hotwordtest

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/** Final file display only. References come from the unmodified task's long-window supports. */
internal object ShortAnswerDisplayReview {
 private const val RATE=16000
 private const val MIN_SHORT=7200
 private const val MAX_SHORT=40000
 private const val MAX_ROWS=12
 private const val MAX_GROUPS=4
 private const val MAX_EXTRACTIONS_PER_MODEL=96
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
 private data class Match(val id:String,val independent:Double,val center:Double,val competitor:Double)

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

 @Throws(InterruptedException::class)
 fun apply(assets:AssetManager,decisionConfig:SpeakerDecisionConfig,samples:FloatArray,activity:JSONObject,
           rows:JSONArray,cancelled:()->Boolean):JSONArray =
  apply(assets,decisionConfig,samples,activity,rows,enableShiftedSeconds=false,cancelled=cancelled)

 @Throws(InterruptedException::class)
 fun apply(assets:AssetManager,decisionConfig:SpeakerDecisionConfig,samples:FloatArray,activity:JSONObject,
           rows:JSONArray,enableShiftedSeconds:Boolean,cancelled:()->Boolean):JSONArray {
  val direct=reviewDirect(assets,decisionConfig,samples,activity,rows,cancelled)
  return HalfSecondAnswerReview.apply(assets,decisionConfig,samples,activity,direct,enableShiftedSeconds,cancelled)
 }

 private fun reviewDirect(assets:AssetManager,decisionConfig:SpeakerDecisionConfig,samples:FloatArray,activity:JSONObject,
                          rows:JSONArray,cancelled:()->Boolean):JSONArray {
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
  for(i in 0 until rows.length()){
   if(queries.size==MAX_ROWS)break
   val row=rows.getJSONObject(i)
   if(!row.isNull("stable_speaker_id")||row.optString("speaker_display")!="说话人未识别")continue
   if(row.optString("text").isBlank()||row.optJSONObject("speaker_identity_provenance")?.optString("method")=="conflicting_voice_evidence")continue
   val query=range(row,samples.size)?:continue
   if(query.size !in MIN_SHORT until MAX_SHORT||guards.any{it.overlaps(query)})continue
   val family=activities.filter{it.range.overlaps(query)}.singleOrNull()?:continue
   if(family.range.size>=MAX_SHORT||!family.range.contains(query))continue
   if(!hasUsableQueryAudio(samples,query))continue
   val choices=groups.mapValues{(_,items)->items.asSequence()
    .filter{!it.family.range.overlaps(family.range)&&!it.range.overlaps(query)}
    .sortedBy{abs((it.range.start.toLong()+it.range.end)-(query.start.toLong()+query.end))}
    .take(3).toList()}
   if(choices.values.all{it.size>=2})queries.add(Query(i,query,choices))
  }
  if(queries.isEmpty())return rows
  val matches=try {
   val cam=verify(assets,SpeakerModels.CAM,decisionConfig,samples,queries,::active)
   if(cam.isEmpty())return rows
   val eres=verify(assets,SpeakerModels.ERES,decisionConfig,samples,queries.filter{it.index in cam},::active)
   cam.mapNotNull{(index,first)->eres[index]?.takeIf{it.id==first.id}?.let{Triple(index,first,it)}}
  }catch(e:InterruptedException){throw e}
  catch(e:Exception){active();return rows}
  active()
  if(matches.isEmpty())return rows
  val output=JSONArray(rows.toString())
  for((index,cam,eres) in matches){
   val row=output.getJSONObject(index)
   row.put("stable_speaker_id",cam.id).put("speaker_display",SpeakerTranscript.groupName(cam.id.substringAfter(':')))
   fun proof(match:Match)=JSONObject().put("independent_similarity",match.independent)
    .put("reference_center_similarity",match.center).put("strongest_competitor",match.competitor)
   row.put("speaker_identity_provenance",JSONObject().put("method","independent_short_file_references")
    .put("recognition_only",true).put("learning_allowed",false)
    .put(SpeakerModels.CAM,proof(cam)).put(SpeakerModels.ERES,proof(eres)))
  }
  return output
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
                    queries:List<Query>,active:()->Unit):Map<Int,Match> {
  val extractor=LocalSpeakerTracker(assets,model)
  val cache=mutableMapOf<Range,FloatArray?>()
  val identityMinimum=maxOf(config.strongMatch,config.clusterThreshold)
  val centerMinimum=maxOf(identityMinimum,SHORT_VERIFICATION_MINIMUM)
  fun embedding(range:Range):FloatArray? {
   active()
   if(cache.containsKey(range))return cache[range]
   if(cache.size>=MAX_EXTRACTIONS_PER_MODEL)return null
   val pcm=samples.copyOfRange(range.start,range.end)
   val value=if(pcm.count{abs(it)>=CLIPPED_SAMPLE_LEVEL}.toDouble()/pcm.size>MAX_CLIPPING_FRACTION)null else extractor.reviewShortEmbedding(pcm)
   cache[range]=value
   return value
  }
  try {
   val verified=linkedMapOf<Int,Match>()
   for(query in queries){
    val length=lengths.minBy{abs(it-query.range.size)}
    val required=(listOf(query.range)+query.references.values.flatten().flatMap{referenceRanges(it.range,length)}).toSet()
    if(required.count{it !in cache}>MAX_EXTRACTIONS_PER_MODEL-cache.size)continue
    val vector=embedding(query.range)?:continue
    data class Vote(val score:Double,val vector:FloatArray)
    data class GroupScore(val id:String,val strongest:Double,val independent:Double,val center:Double)
    val scores=query.references.mapNotNull{(id,references)->
     val votes=references.mapNotNull{reference->
      referenceRanges(reference.range,length).mapNotNull{embedding(it)?.let{v->Vote(similarity(vector,v),v)}}
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
    if(scores.size!=query.references.size)continue
    val best=scores.maxBy{it.independent}
    val others=scores.filter{it.id!=best.id}
    val rival=others.maxOf{maxOf(it.strongest,it.center)}
    if(best.independent>=identityMinimum&&best.center>=centerMinimum&&
     best.independent-others.maxOf{it.strongest}>=config.ambiguityMargin&&best.center-rival>=config.ambiguityMargin)
     verified[query.index]=Match(best.id,best.independent,best.center,rival)
   }
   return verified
  }finally{extractor.close()}
 }
}
