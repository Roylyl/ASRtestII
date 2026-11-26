package org.hotwordtest

import org.json.JSONArray
import org.json.JSONObject

/** A second opinion for unresolved file subtitles using the first pass's independent voice windows. */
internal object FileAnonymousCoverageReview {
 private const val NEW_GROUP_MAX = 0.33
 private const val WINDOW_SAMPLES = 48_000
 private data class Span(val start:Int, val end:Int) {
  fun overlap(other:Span)=(minOf(end,other.end)-maxOf(start,other.start)).coerceAtLeast(0)
 }
 private data class Decision(val span:Span,val oldId:String?,val consumed:Boolean,val value:JSONObject)
 private data class Cell(val span:Span,val identity:String?,val additional:Boolean)

 private fun rows(array:JSONArray?)=if(array==null)emptyList() else
  List(array.length()){array.getJSONObject(it)}
 private fun span(row:JSONObject)=row.optInt("start_sample",-1).let{start->
  row.optInt("end_sample",-1).takeIf{start>=0&&it>start}?.let{Span(start,it)}
 }
 private fun id(row:JSONObject,key:String)=(row.opt(key) as? String)?.takeIf{it.isNotBlank()}

 fun apply(source:String,model:String,config:SpeakerDecisionConfig,activity:JSONObject,
           input:JSONArray,cache:ShortAnswerDisplayReview.ReferenceCache,
           cancelled:()->Boolean):JSONArray {
  if(activity.optString("source")!=source||config.newSpeakerMax>=NEW_GROUP_MAX||
     minOf(config.strongMatch,config.weakMatch)<=NEW_GROUP_MAX)return input
  val diagnostics=activity.optJSONObject("refinement_diagnostics")?:return input
  if(diagnostics.optInt("probes_dropped")>0||diagnostics.optInt("stable_windows_dropped")>0)return input
  val stable=rows(diagnostics.optJSONArray("stable_windows"))
  val aliases=stable.mapNotNull{cell->
   val pending=id(cell.optJSONObject("grouping")?:return@mapNotNull null,"pending_key")?:return@mapNotNull null
   id(cell,"stable_speaker_id")?.takeIf{it.startsWith("anonymous:")}?.let{pending to it.substringAfter(':')}
  }.toMap()
  var groups:SpeakerEvidenceGroups?=null
  val decisions=linkedMapOf<Span,Decision>()
  for(probe in rows(diagnostics.optJSONArray("observe_probes"))){
   if(cancelled())throw InterruptedException("已停止")
   if(probe.optString("kind")!="identity_probe_3s"||!probe.optBoolean("independent_support"))continue
   val identity=probe.optJSONObject("identity")
   if(identity?.optString("status")=="wearer_candidate")continue
   val grouping=probe.optJSONObject("grouping")?:continue
   val provenance=grouping.optJSONObject("provenance")?:continue
   val area=span(probe)?:continue
   if(area.end-area.start!=WINDOW_SAMPLES||span(provenance)!=area||provenance.optString("source")!=source)continue
   // A partial replay would change candidate order. Leave grouping alone if the file exceeds the cache.
   val vector=cache.get(model,area.start,area.end)?:return input
   val next=groups?:SpeakerEvidenceGroups(vector.size,config.copy(newSpeakerMax=NEW_GROUP_MAX)).also{groups=it}
   val support=SpeakerEvidenceGroups.Support(source,area.start.toLong(),area.end.toLong(),
    provenance.getString("window_id"),provenance.getLong("analysis_window_start"),
    provenance.getLong("analysis_window_end"),provenance.getString("local_track"),
    provenance.getLong("family_start_sample"),provenance.getLong("family_end_sample"))
   val current=next.observe(vector,support)
   val old=id(grouping,"speaker_id")?:id(grouping,"pending_key")?.let{aliases[it]}
   decisions[area]=Decision(area,old,grouping.optBoolean("support_consumed"),current)
  }
  val grouping=groups?:return input
  fun suggested(row:JSONObject)=id(row,"speaker_id")?:grouping.resolved(id(row,"pending_key"))
  val accepted=decisions.values.mapNotNull{value->suggested(value.value)?.let{it to value}}.groupBy({it.first},{it.second})
  val candidates=linkedMapOf<String,String>()
  for((candidate,votes) in accepted){
   val known=votes.mapNotNull{it.oldId}.distinct()
   if(known.size==1&&votes.count{it.consumed&&it.oldId==known.single()}>=2)candidates[candidate]=known.single()
  }
  val shared=candidates.values.groupingBy{it}.eachCount()
  val mapping=candidates.filterValues{shared[it]==1}.toMutableMap()
  val occupied=(stable+rows(input)).mapNotNull{row->id(row,"stable_speaker_id")?.takeIf{it.startsWith("anonymous:S")}
   ?.substringAfter("anonymous:S")?.toIntOrNull()}.maxOrNull()?:0
  var serial=occupied+1
  for((candidate,votes) in accepted){
   if(votes.any{it.oldId!=null})continue
   val bases=votes.filter{it.value.optBoolean("support_consumed")}.distinctBy{it.span}
   if(bases.size>=2&&bases.first().span.overlap(bases.last().span)==0)mapping[candidate]="S"+serial++
  }
  if(mapping.isEmpty())return input
  val cells=stable.mapNotNull{cell->
   val area=span(cell)?:return@mapNotNull null
   val voice=cell.optJSONObject("grouping")
   val old=id(cell,"stable_speaker_id")
   val full=voice?.optJSONObject("provenance")?.let(::span)
   val alternate=full?.let{decisions[it]}?.let{suggested(it.value)}?.let{mapping[it]}
   Cell(area,old?:alternate?.let{"anonymous:$it"},old==null&&alternate!=null)
  }
  val protected=rows(activity.optJSONArray("protected_intervals")).mapNotNull(::span)+
   rows(activity.optJSONArray("predicted_overlap")).mapNotNull(::span)
  var out:JSONArray?=null
  for(i in 0 until input.length()){
   if(cancelled())throw InterruptedException("已停止")
   val row=input.getJSONObject(i)
   if(row.optString("speaker_display")!="说话人未识别"||!row.isNull("stable_speaker_id")||
      row.optString("text").isBlank())continue
   val method=row.optJSONObject("speaker_identity_provenance")?.optString("method")
   if(method!="awaiting_independent_voice_windows"&&method!="insufficient_pure_activity")continue
   val area=span(row)?:continue
   if(protected.any{it.overlap(area)>0})continue
   val support=cells.filter{it.span.overlap(area)>0}.sortedBy{it.span.start}
   if(support.none{it.additional})continue
   val candidate=support.map{it.identity}.distinct().singleOrNull()?:continue
   if(!candidate.startsWith("anonymous:"))continue
   var coverage=0L
   var coveredThrough=area.start
   for(cell in support){
    val end=minOf(area.end,cell.span.end)
    coverage+=(end-maxOf(coveredThrough,cell.span.start)).coerceAtLeast(0)
    coveredThrough=maxOf(coveredThrough,end)
   }
   if(coverage*2<area.end-area.start)continue
   val edited=out?:JSONArray(input.toString()).also{out=it}
   edited.getJSONObject(i).put("speaker_display",SpeakerTranscript.groupName(candidate.substringAfter(':')))
    .put("stable_speaker_id",candidate)
    .put("speaker_identity_provenance",JSONObject().put("method","additional_independent_file_windows")
     .put("recognition_only",true).put("learning_allowed",false))
  }
  return out?:input
 }
}
