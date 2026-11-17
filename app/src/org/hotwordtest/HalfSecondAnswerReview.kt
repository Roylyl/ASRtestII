package org.hotwordtest

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/** Uses shifted activity to connect a short display turn to independently verified long voices. */
internal object HalfSecondAnswerReview {
 private const val RATE=16000
 private const val WINDOW=10*RATE
 private const val STEP=RATE/2
 private const val CONTEXT=2*RATE
 private const val GUARD=RATE/10
 private const val MIN_ANCHOR=3*RATE/2
 private const val MAX_QUERIES=4
 private const val MAX_ANCHORS_PER_PERSON=8

 private data class Span(val start:Int,val end:Int) {
  val size:Int get()=end-start
  fun overlaps(other:Span)=start<other.end&&other.start<end
  fun contains(other:Span)=start<=other.start&&other.end<=end
  fun coverage(other:Span)=(minOf(end,other.end)-maxOf(start,other.start)).coerceAtLeast(0)
 }
 private data class Activity(val span:Span,val track:String)
 private data class Anchor(val id:String,val span:Span,val originalWindow:Span)
 private data class Query(val index:Int,val span:Span,val views:List<Int>)
 private data class Link(val id:String,val span:Span)
 private data class Decision(val id:String,val span:Span,val viewCount:Int)

 private fun objects(arr:JSONArray?)=if(arr==null)emptyList() else List(arr.length()){arr.getJSONObject(it)}
 private fun coords(row:JSONObject,count:Int,offset:Int=0):Span? {
  val a=row.optLong("start_sample",row.optLong("start_ms",-1)*16)
  val b=row.optLong("end_sample",row.optLong("end_ms",-1)*16)
  return if(a>=0&&b>a&&b<=count)Span(offset+a.toInt(),offset+b.toInt()) else null
 }

 private fun queries(rows:JSONArray,activities:List<Activity>,overlaps:List<Span>,count:Int):List<Query> {
  val result=mutableListOf<Query>()
  for(i in 0 until rows.length()){
   if(result.size==MAX_QUERIES)break
   val row=rows.getJSONObject(i)
   if(!row.isNull("stable_speaker_id")||row.optString("speaker_display")!="说话人未识别"||row.optString("text").isBlank())continue
   if(row.optJSONObject("speaker_identity_provenance")?.optString("method")=="conflicting_voice_evidence")continue
   val target=coords(row,count)?:continue
   if(target.size !in (RATE*35/100)..(RATE*80/100))continue
   val protected=Span(target.start-GUARD,target.end+GUARD)
   if(activities.filter{it.span.overlaps(protected)}.singleOrNull()?.span!=target||overlaps.any{it.overlaps(protected)})continue
   val first=((target.end+CONTEXT-WINDOW).coerceAtLeast(0)+STEP-1)/STEP*STEP
   val last=minOf(target.start-CONTEXT,count-WINDOW)
   if(first<=last)result.add(Query(i,target,(first..last step STEP).toList()))
  }
  return result
 }

 private fun anchors(diagnostics:JSONObject,count:Int):List<Anchor> {
  val aliases=mutableMapOf<String,String>()
  for(row in objects(diagnostics.optJSONArray("stable_windows"))){
   val id=(row.opt("stable_speaker_id") as? String)?.takeIf{it.startsWith("anonymous:")}?:continue
   val alias=row.optJSONObject("grouping")?.optString("pending_key").orEmpty()
   if(alias.isNotEmpty())aliases[alias]=id
  }
  val found=mutableListOf<Anchor>()
  for(row in objects(diagnostics.optJSONArray("observe_probes"))){
   if(row.optString("kind")!="identity_probe_3s"||!row.optBoolean("independent_support"))continue
   val group=row.optJSONObject("grouping")?:continue
   if(!group.optBoolean("support_consumed"))continue
   val proof=group.optJSONObject("activity_evidence")?:continue
   val range=coords(row,count)?:continue
   if(proof.optString("kind")!="predicted_exclusive_local_activity"||coords(proof,count)!=range||range.size!=3*RATE)continue
   val groupId=(group.opt("speaker_id") as? String)?.let{"anonymous:"+it}
    ?:aliases[group.optString("pending_key")]?:continue
   val start=proof.optInt("analysis_window_start",-1);val end=proof.optInt("analysis_window_end",-1)
   if(start<0||end>count||start>=end)continue
   val window=Span(start,end)
   if(!window.contains(range))continue
   val known=found.filter{it.id==groupId}
   if(known.size<MAX_ANCHORS_PER_PERSON&&known.none{it.originalWindow.overlaps(window)})found.add(Anchor(groupId,range,window))
  }
  return found
 }

 private fun exclusive(rows:List<Activity>,track:String,range:Span):Int {
  val relevant=rows.filter{it.span.overlaps(range)}
  val edges=relevant.flatMap{listOf(maxOf(it.span.start,range.start),minOf(it.span.end,range.end))}.distinct().sorted()
  return edges.zipWithNext().sumOf{(start,end)->
   if(relevant.filter{it.span.overlaps(Span(start,end))}.map{it.track}.toSet()==setOf(track))end-start else 0
  }
 }

 private fun link(query:Span,view:List<Activity>,anchors:List<Anchor>):Link? {
  val onTarget=view.filter{it.span.overlaps(query)}
  val track=onTarget.map{it.track}.distinct().singleOrNull()?:return null
  val querySpan=onTarget.maxBy{it.span.coverage(query)}.span
  if(querySpan.coverage(query)*100<query.size*85)return null
  val linked=view.map{it.track}.distinct().associateWith{trackId->
   anchors.filter{!it.span.overlaps(query)&&exclusive(view,trackId,it.span)>=MIN_ANCHOR}.map{it.id}.toSet()
  }
  if(linked.values.any{it.size>1})return null
  val target=linked[track]?.singleOrNull()?:return null
  if(linked.none{(key,ids)->key!=track&&ids.isNotEmpty()&&target !in ids})return null
  return Link(target,querySpan)
 }

 private fun decision(query:Query,views:Map<Int,List<Activity>>,refs:List<Anchor>):Decision? {
  val links=query.views.mapNotNull{link(query.span,views.getValue(it),refs)}
  if(links.size<2)return null
  val id=links.map{it.id}.distinct().singleOrNull()?:return null
  // Keep the original 100 ms boundary guards, and use only the common interior of agreeing views.
  val core=Span(maxOf(query.span.start+GUARD,links.maxOf{it.span.start}),
                minOf(query.span.end-GUARD,links.minOf{it.span.end}))
  if(core.size*2<query.span.size)return null
  return Decision(id,core,links.size)
 }

 private fun verifiedAnchors(assets:AssetManager,refs:List<Anchor>,pcm:FloatArray,config:SpeakerDecisionConfig,
                             active:()->Unit):List<Anchor> {
  val minimum=maxOf(0.60,config.strongMatch,config.clusterThreshold)
  var verified=refs
  for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES)){
   if(verified.isEmpty())break
   val tracker=LocalSpeakerTracker(assets,model)
   try{
    val vectors=refs.associateWith{anchor->active();tracker.embedding(pcm.copyOfRange(anchor.span.start,anchor.span.end))}
    verified=verified.filter{anchor->
     val query=vectors[anchor]?:return@filter false
     val scores=refs.filter{!it.originalWindow.overlaps(anchor.originalWindow)}.mapNotNull{other->
      vectors[other]?.let{v->other.id to query.indices.sumOf{query[it].toDouble()*v[it]}}
     }
     val same=scores.filter{it.first==anchor.id}.maxOfOrNull{it.second}?:return@filter false
     val rival=scores.filter{it.first!=anchor.id}.maxOfOrNull{it.second}?:return@filter false
     same>=minimum&&same-rival>=config.ambiguityMargin
    }
   }finally{tracker.close()}
  }
  return verified
 }

 @Throws(InterruptedException::class)
 fun apply(assets:AssetManager,config:SpeakerDecisionConfig,pcm:FloatArray,activity:JSONObject,
           rows:JSONArray,cancelled:()->Boolean):JSONArray {
  fun active(){if(cancelled()||Thread.currentThread().isInterrupted)throw InterruptedException("已停止")}
  active()
  val raw=objects(activity.optJSONArray("activities")).mapNotNull{row->
   coords(row,pcm.size)?.let{Activity(it,row.optString("local_track"))}
  }.distinct()
  val overlap=objects(activity.optJSONArray("predicted_overlap")).mapNotNull{coords(it,pcm.size)}
  val queries=queries(rows,raw,overlap,pcm.size)
  if(queries.isEmpty())return rows
  val evidence=activity.optJSONObject("refinement_diagnostics")?:return rows
  val refs=anchors(evidence,pcm.size)
  if(refs.map{it.id}.distinct().size !in 2..4)return rows
  val decisions=try{
   val starts=queries.flatMap{it.views}.distinct()
   val views=DiarizationTest.ActivitySession(assets).use{session->
    starts.associateWith{start->
     active()
     val output=session.process(pcm.copyOfRange(start,start+WINDOW)).getJSONObject("unsmoothed")
     objects(output.optJSONArray("segments")).mapNotNull{segment->
      coords(segment,WINDOW,start)?.let{Activity(it,segment.optString("speaker_id"))}
     }
    }
   }
   val eligible=queries.filter{decision(it,views,refs)!=null}
   if(eligible.isEmpty())return rows
   val verified=verifiedAnchors(assets,refs,pcm,config,::active)
   eligible.mapNotNull{query->decision(query,views,verified)?.let{query.index to it}}.toMap()
  }catch(e:InterruptedException){throw e}
  catch(e:Exception){active();return rows}
  active()
  if(decisions.isEmpty())return rows
  return JSONArray().apply {
   for(i in 0 until rows.length()){
    val row=rows.getJSONObject(i)
    val match=decisions[i]
    if(match==null){put(row);continue}
    val original=coords(row,pcm.size)!!
    if(original.start<match.span.start)put(asDisplayRow(row,Span(original.start,match.span.start)).put("text",""))
    put(asDisplayRow(row,match.span).put("stable_speaker_id",match.id)
     .put("speaker_display",SpeakerTranscript.groupName(match.id.substringAfter(':')))
     .put("speaker_identity_provenance",JSONObject().put("method","shifted_activity_independent_anchors")
      .put("recognition_only",true).put("learning_allowed",false).put("agreeing_views",match.viewCount)
      .put("original_start_sample",original.start).put("original_end_sample",original.end)))
    if(match.span.end<original.end)put(asDisplayRow(row,Span(match.span.end,original.end)).put("text",""))
   }
  }
 }

 private fun asDisplayRow(row:JSONObject,range:Span):JSONObject=JSONObject(row.toString())
  .put("start_sample",range.start).put("end_sample",range.end)
  .put("start_ms",range.start*1000L/RATE).put("end_ms",range.end*1000L/RATE)
}
