package org.hotwordtest

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Formats only speaker-aligned transcripts; original VAD text is never a fallback. */
object SpeakerTranscript {
 private val groupId=Regex("S([1-9][0-9]*)")
 private val scopedId=Regex("说话人([1-9][0-9]*)（(?:本文件|本段)）")
 private val oldLocalId=Regex("本段说话人([1-9][0-9]*)")
 fun groupName(id:String):String=groupId.matchEntire(id)?.let{"说话人${it.groupValues[1]}"}?:id

 fun anonymousLabel(group:JSONObject):String {
  if(group.isNull("speaker_id"))return "待确认"
  return groupName(group.getString("speaker_id"))
 }

 // Legacy speaker_display strings could also include enrolled names. Convert only
 // complete identifier slots, and leave an ambiguous candidate name intact.
 private fun legacyLabel(value:String,weakId:String?=null):String {
  if(value.startsWith("重叠："))return "重叠："+value.removePrefix("重叠：").split('、').joinToString("、"){legacyLabel(it,weakId)}
  val split=value.indexOf(" · ")
  if(split>0&&value.endsWith("（低置信候选）")){
   val left=value.substring(0,split)
   val id=left.removeSuffix("（候选）")
   if(groupId.matches(id))return groupName(id)+left.removePrefix(id)+value.substring(split)
  }
  if(weakId!=null&&value=="$weakId（候选）")return groupName(weakId)
  return (oldLocalId.matchEntire(value)?:scopedId.matchEntire(value))?.let{"说话人${it.groupValues[1]}"}?:groupName(value)
 }

 fun segmentLabel(segment:JSONObject):String {
  if(segment.isNull("speaker_display")){
   if(segment.isNull("speaker_id")&&segment.optString("speaker_status") in setOf("","not_implemented"))return ""
   return anonymousLabel(segment)
  }
  val value=segment.getString("speaker_display")
  if(segment.optJSONObject("voice_identity")?.optString("status")=="wearer_candidate")return value
  val weakId=if(segment.optString("speaker_status")=="weak_candidate"&&!segment.isNull("speaker_id"))segment.getString("speaker_id") else null
  return legacyLabel(value,weakId)
 }

 fun turnLabel(turn:JSONObject):String {
  val identity=turn.optJSONObject("wearer_identity")
  if(!turn.isNull("speaker_display")){
   val value=turn.getString("speaker_display")
   return if(identity?.optString("status")=="wearer_candidate")value else legacyLabel(value)
  }
  if(identity?.optString("status")=="wearer_candidate"&&!identity.isNull("display_name")){
   val name=identity.optString("display_name")
   if(name.isNotBlank())return name
  }
  val candidates=turn.optJSONArray("speaker_candidates")?:return "说话人未识别"
  val labels=(0 until candidates.length()).map{"说话人${candidates.getInt(it)+1}"}
  return when(labels.size){0->"说话人未识别";1->labels[0];else->"重叠：${labels.joinToString("、")}"}
 }

 private fun singleLine(value:String):String=value.replace(Regex("\\r\\n|[\\r\\n\\u2028\\u2029]")," ")

 private fun timestamp(ms:Long):String{
  val cs=(ms.coerceAtLeast(0)+5)/10
  val hours=cs/360000
  val msPart=String.format(Locale.ROOT,"%02d:%02d.%02d",cs/6000%60,cs/100%60,cs%100)
  return if(hours==0L)msPart else "$hours:$msPart"
 }
 fun line(startMs:Long,endMs:Long,label:String,text:String):String {
  if(text.isBlank())return ""
  val speaker=singleLine(label).ifBlank{"说话人未识别"}
  return "[${timestamp(startMs)}–${timestamp(endMs)}]$speaker：${singleLine(text).trim()}"
 }

 fun live(turns:JSONArray):String {
  val rows=mutableListOf<String>()
  for(i in 0 until turns.length()){
   val turn=turns.optJSONObject(i)?:continue
   val text=if(turn.isNull("text"))"" else turn.optString("text","")
   if(text.isBlank())continue
   rows.add(line(turn.getLong("start_ms"),turn.getLong("end_ms"),turnLabel(turn),text))
  }
  return rows.joinToString("\n")
 }

 fun aligned(outcome:JSONObject):String {
  val turns=outcome.optJSONArray("speaker_turn_transcripts")
   ?:return "未生成按说话人切分的转写结果"
  if(turns.length()==0)return "未生成按说话人切分的转写结果"
  val rows=mutableListOf<String>()
  for(i in 0 until turns.length()){
   val turn=turns.optJSONObject(i)?:continue
   val text=if(turn.isNull("text"))"" else turn.optString("text","")
   if(text.isBlank())continue
   rows.add(line(turn.getLong("start_ms"),turn.getLong("end_ms"),turnLabel(turn),text))
  }
  return rows.joinToString("\n").ifEmpty{"未识别到文字"}
 }
}
