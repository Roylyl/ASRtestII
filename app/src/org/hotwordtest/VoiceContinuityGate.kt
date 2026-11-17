package org.hotwordtest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Additional evidence against a hidden speaker change inside one predicted activity track.
 * This is an experimental conflict detector, not proof of a single human speaker. */
internal object VoiceContinuityGate {
 const val VERSION=1
 private const val RATE=16000
 private const val WINDOW=24000
 // Conservative preliminary conflict boundary. Must be calibrated on independent device audio.
 private const val CLEAR_CONFLICT=0.35
 private const val LOW_CONFIDENCE=0.55
 private const val ANCHOR_CAM_MIN=0.33
 private const val ANCHOR_ERES_MIN=0.35
 // Auto-admission is intentionally stricter than manual confirmation. These
 // preliminary thresholds are experimental and must be evaluated on-device.
 private const val AUTO_INITIAL_MIN=0.70
 private const val AUTO_MARGIN=0.15
 private fun active(){if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("声纹学习已取消")}
 private fun similarity(a:FloatArray,b:FloatArray):Double? {
  if(a.isEmpty()||a.size!=b.size)return null
  var sum=0.0
  for(i in a.indices)sum+=a[i].toDouble()*b[i]
  return sum.takeIf{it.isFinite()}?.coerceIn(-1.0,1.0)
 }
 data class Result(val accepted:Boolean,val evidence:JSONObject,val reason:String)
 data class Trim(val start:Int,val end:Int,val evidence:JSONObject)

 /** One candidate confirmation owns this PCM snapshot and its acoustic vectors.
  * Identity scores are deliberately recomputed by each caller's matcher. */
 internal class SharedWindows(private val input:FloatArray) {
  private data class Key(val model:String,val start:Int,val end:Int)
  private val pcm=input.copyOf()
  private val vectors=LinkedHashMap<Key,FloatArray>(64,0.75f,true)

  fun verifyInput(samples:FloatArray){check(input===samples){"声纹复用范围与当前候选不一致"}}
  fun embedding(model:String,start:Int,end:Int,extract:(FloatArray)->FloatArray?):FloatArray? {
   active()
   val key=Key(model,start,end)
   vectors[key]?.let{return it}
   val value=extract(pcm.copyOfRange(start,end))?:return null
   vectors[key]=value
   if(vectors.size>64)vectors.remove(vectors.keys.first())
   return value
  }
 }

 private class WindowReader(val read:(Int,Int)->FloatArray?,private val release:()->Unit):AutoCloseable {
  override fun close()=release()
 }

 private fun openWindows(context:Context,samples:FloatArray,model:String,shared:SharedWindows?):WindowReader {
  if(shared!=null){
   shared.verifyInput(samples)
   var tracker:LocalSpeakerTracker?=null
   return WindowReader({start,end->
    shared.embedding(model,start,end){pcm->
     val extractor=tracker?:LocalSpeakerTracker(context.assets,model).also{tracker=it}
     extractor.embedding(pcm)
    }
   },{tracker?.close()})
  }
  val tracker=LocalSpeakerTracker(context.assets,model)
  return WindowReader({start,end->tracker.embedding(samples.copyOfRange(start,end))},tracker::close)
 }

 /** Diagnose a rejected candidate with one continuous Silero stream. Only remove a
  * low-speech edge, preserve 100ms of context, and never splice interior audio. */
 fun lowSpeechTrim(context:Context,samples:FloatArray):Trim? {
  active()
  if(samples.size<2*WINDOW)return null
  val vad=VadModelFactory.create(context.assets,VadModelFactory.SILERO)
  val spans=mutableListOf<Pair<Int,Int>>()
  fun drain(){
   while(!vad.empty()){
    val segment=vad.front();val start=segment.start.coerceIn(0,samples.size)
    val end=(segment.start.toLong()+segment.samples.size).coerceIn(0L,samples.size.toLong()).toInt()
    if(end>start)spans.add(start to end)
    vad.pop()
   }
  }
  try {
   val frameSize=VadModelFactory.windowSize(VadModelFactory.SILERO);var pos=0
   while(pos<samples.size){
    active();val n=minOf(frameSize,samples.size-pos);val frame=FloatArray(frameSize)
    samples.copyInto(frame,0,pos,pos+n);vad.acceptWaveform(frame);pos+=n;drain()
   }
   vad.flush();drain()
  }finally{vad.release()}
  if(spans.isEmpty())return null
  val merged=mutableListOf<Pair<Int,Int>>()
  for(span in spans.sortedBy{it.first}){
   val prev=merged.lastOrNull()
   if(prev!=null&&span.first<=prev.second)merged[merged.lastIndex]=prev.first to maxOf(prev.second,span.second)
   else merged.add(span)
  }
  fun speechIn(a:Int,b:Int)=merged.sumOf{maxOf(0,minOf(b,it.second)-maxOf(a,it.first))}
  val first=speechIn(0,WINDOW);val last=speechIn(samples.size-WINDOW,samples.size)
  val cropStart=if(first.toDouble()/WINDOW<0.25)maxOf(0,merged.first().first-1600) else 0
  val cropEnd=if(last.toDouble()/WINDOW<0.25)minOf(samples.size,merged.last().second+1600) else samples.size
  if((cropStart==0&&cropEnd==samples.size)||cropEnd-cropStart<2*WINDOW)return null
  val info=JSONObject().put("method","one_pass_silero_low_speech_edges")
   .put("vad_model",VadModelFactory.SILERO).put("edge_window_samples",WINDOW).put("low_speech_fraction",0.25)
   .put("start_speech_fraction",first.toDouble()/WINDOW).put("end_speech_fraction",last.toDouble()/WINDOW)
   .put("context_samples",1600).put("original_samples",samples.size).put("trim_start_sample",cropStart).put("trim_end_sample",cropEnd)
   .put("speech_intervals",JSONArray().apply{for((a,b) in merged)put(JSONObject().put("start_sample",a).put("end_sample",b))})
   .put("note","VAD区间包含模型边界平滑，只用于保守裁边；中间音频未拼接")
  return Trim(cropStart,cropEnd,info)
 }

 fun check(context:Context,samples:FloatArray,sourceStart:Long=0L):Result = check(context,samples,sourceStart,null)

 fun check(context:Context,samples:FloatArray,sourceStart:Long,shared:SharedWindows?):Result {
  active()
  val result=JSONObject().put("version",VERSION).put("method","independent_1500ms_embedding_consistency")
   .put("status","feature_unavailable").put("conclusion","experimental_not_single_speaker_proof")
   .put("source_start_sample",sourceStart).put("source_end_sample",sourceStart+samples.size)
   .put("window_samples",WINDOW).put("clear_conflict_threshold",CLEAR_CONFLICT)
   .put("low_confidence_threshold",LOW_CONFIDENCE)
  val records=JSONObject();result.put("models",records)
  if(samples.size<2*WINDOW || samples.any{!it.isFinite()})return Result(false,result,"有效语音不足以检查轨内说话人一致性")
  val ranges=mutableListOf<Pair<Int,Int>>();var pos=0
  while(pos+WINDOW<=samples.size){ranges.add(pos to pos+WINDOW);pos+=WINDOW}
  // Ensure the end is examined too; the recorded overlap is not an independent window.
  if(pos<samples.size&&samples.size-WINDOW>ranges.last().first)ranges.add(samples.size-WINDOW to samples.size)
  val minScores=mutableListOf<Double>();val matchedByModel=mutableMapOf<String,MutableMap<String,Int>>()
  val independentMatches=mutableMapOf<String,MutableMap<String,Int>>()
  for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES)){
   active()
   val modelInfo=JSONObject().put("name",SpeakerModels.name(model));records.put(model,modelInfo)
   val windows=JSONArray();modelInfo.put("windows",windows)
   val vectors=mutableListOf<FloatArray>()
   val tracker=try{openWindows(context,samples,model,shared)}catch(e:Exception){
    if(e is java.util.concurrent.CancellationException)throw e
    modelInfo.put("error",e.message?:e.javaClass.simpleName)
    return Result(false,result,"声纹模型不可用，无法检查轨内一致性")
   }
   try {
    WearerVoiceStore.Matcher(context,model,loadExtractor=false).use{matcher->
     for((i,range) in ranges.withIndex()){
      active()
      val vec=tracker.read(range.first,range.second)
      val independent=i==0||range.first>=ranges[i-1].second
      val row=JSONObject().put("start_sample",sourceStart+range.first).put("end_sample",sourceStart+range.second)
       .put("independent",independent)
      windows.put(row)
      if(vec==null){row.put("status","feature_unavailable");return Result(false,result,"短语音窗无法提取有效声纹，请重新录制")}
      vectors.add(vec)
      val match=matcher.identifyEmbedding(vec,WINDOW,true)
      row.put("status","available").put("registered_status",match.optString("status"))
       .put("registered_profile_id",if(match.optString("status")=="wearer_candidate")match.optString("profile_id") else JSONObject.NULL)
       .put("registered_similarity",match.opt("similarity")?:JSONObject.NULL)
       .put("registered_margin",match.opt("candidate_margin")?:JSONObject.NULL)
      if(match.optString("status")=="wearer_candidate"){
       val id=match.optString("profile_id");if(id.isNotBlank()){
        val counts=matchedByModel.getOrPut(model){mutableMapOf()};counts[id]=(counts[id]?:0)+1
        if(independent){val independentCounts=independentMatches.getOrPut(model){mutableMapOf()};independentCounts[id]=(independentCounts[id]?:0)+1}
       }
      }
     }
    }
   }catch(e:Exception){
    if(e is java.util.concurrent.CancellationException)throw e
    modelInfo.put("error",e.message?:e.javaClass.simpleName)
    return Result(false,result,"声纹检查失败，未纳入学习")
   }finally{tracker.close()}
   var minimum=1.0;val comparisons=JSONArray()
   for(i in vectors.indices)for(j in i+1 until vectors.size){
    if(ranges[i].second>ranges[j].first)continue
    val score=similarity(vectors[i],vectors[j])?:return Result(false,result,"短语音窗声纹维度无效")
    minimum=minOf(minimum,score)
    comparisons.put(JSONObject().put("first",i).put("second",j).put("similarity",score))
   }
   if(comparisons.length()==0)return Result(false,result,"缺少互不重叠的声纹检查窗口")
   modelInfo.put("pairwise",comparisons).put("minimum_similarity",minimum)
   minScores.add(minimum)
  }
  if(matchedByModel.values.any{it.keys.size>=2}){result.put("status","registered_identity_conflict");return Result(false,result,"同一片段出现多个已登记人物的冲突证据")}
  if(minScores.any{it<CLEAR_CONFLICT}){result.put("status","acoustic_conflict");return Result(false,result,"短语音窗一致性不足，暂不纳入学习")}
  val low=minScores.any{it<LOW_CONFIDENCE}
  result.put("status",if(low)"low_confidence_review_required" else "review_required")
   .put("matched_profile_counts",JSONObject().apply{for((model,counts) in matchedByModel)put(model,JSONObject().apply{for((id,n) in counts)put(id,n)})})
   .put("independent_profile_counts",JSONObject().apply{for((model,counts) in independentMatches)put(model,JSONObject().apply{for((id,n) in counts)put(id,n)})})
  return Result(true,result,if(low)"轨内声纹一致性较低，请仔细试听确认" else "已通过初步轨内冲突筛选，仍需确认")
 }

 @JvmStatic fun diagnose(context:Context,samples:FloatArray,sourceStart:Long=0L):JSONObject {
  val result=check(context,samples,sourceStart)
  return result.evidence.put("accepted",result.accepted).put("reason",result.reason)
 }

 /** Experimental enrollment guard: require independent windows to support the selected
  * person's immutable initial templates. Normal recognition thresholds are not changed. */
 fun initialAnchorSupport(context:Context,samples:FloatArray,profileId:String,sourceStart:Long=0L):Result =
  initialAnchorSupport(context,samples,profileId,sourceStart,null)

 fun initialAnchorSupport(context:Context,samples:FloatArray,profileId:String,sourceStart:Long,shared:SharedWindows?):Result {
  active()
  val evidence=JSONObject().put("version",1).put("method","independent_windows_initial_templates_only")
   .put("experimental",true).put("profile_id",profileId).put("window_samples",WINDOW)
   .put("minimum_scores",JSONObject().put(SpeakerModels.CAM,ANCHOR_CAM_MIN).put(SpeakerModels.ERES,ANCHOR_ERES_MIN))
   .put("strong_score",0.40).put("minimum_margin",0.10)
   .put("required_windows_each_model",2).put("required_strong_windows_one_model",2)
   .put("source_start_sample",sourceStart).put("source_end_sample",sourceStart+samples.size)
  val models=JSONObject();evidence.put("models",models)
  if(profileId.isBlank()||sourceStart<0||samples.size<2*WINDOW||samples.any{!it.isFinite()})
   return Result(false,evidence.put("status","insufficient_audio"),"有效单人语音不足，请重新录制更清晰的声音")
  val supported=mutableListOf<Int>();val strong=mutableListOf<Int>()
  for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES)){
   active();val minimumScore=if(model==SpeakerModels.CAM)ANCHOR_CAM_MIN else ANCHOR_ERES_MIN
   val info=JSONObject().put("model",SpeakerModels.name(model)).put("minimum_score",minimumScore).put("windows",JSONArray());models.put(model,info)
   if(!WearerVoiceStore.hasInitialTemplate(context,profileId,model))
    return Result(false,evidence.put("status","initial_template_missing"),"该声纹缺少${SpeakerModels.name(model)}初始模板，请先在声纹管理中补录以支持两种模型")
   val extractor=try{openWindows(context,samples,model,shared)}catch(e:Exception){
    if(e is java.util.concurrent.CancellationException)throw e
    return Result(false,evidence.put("status","model_unavailable"),"无法校验初始声纹，请稍后重试")
   }
   try{
    val matcher=WearerVoiceStore.Matcher(context,model,loadExtractor=false,initialOnly=true,acceptanceThreshold=minimumScore,identityMargin=0.10)
    try{
     var count=0;var high=0;var pos=0
     while(pos+WINDOW<=samples.size){
      active();val row=JSONObject().put("start_sample",sourceStart+pos).put("end_sample",sourceStart+pos+WINDOW).put("independent",true)
      info.getJSONArray("windows").put(row)
      val vector=extractor.read(pos,pos+WINDOW)
      if(vector==null)row.put("status","feature_unavailable") else {
       val match=matcher.identifyEmbedding(vector,WINDOW,true);row.put("match",match)
       val hit=match.optString("status")=="wearer_candidate"&&match.optString("profile_id")==profileId
       val similarity=match.optDouble("similarity",Double.NaN)
       row.put("supports_target",hit).put("strong_support",hit&&similarity>=0.40)
       if(hit){count++;if(similarity>=0.40)high++}
      }
      pos+=WINDOW
     }
     info.put("supported_windows",count).put("strong_windows",high);supported.add(count);strong.add(high)
    }finally{matcher.close()}
   }catch(e:Exception){
    if(e is java.util.concurrent.CancellationException)throw e
    return Result(false,evidence.put("status","check_failed"),"初始声纹校验失败，请重新录制更清晰的声音")
   }finally{extractor.close()}
  }
  val accepted=supported.size==2&&supported.all{it>=2}&&strong.any{it>=2}
  evidence.put("status",if(accepted)"experimental_support_passed" else "insufficient_target_support")
  return Result(accepted,evidence,if(accepted)"初始声纹支持通过，仍需结合其他人物冲突检查" else "无法确认属于所选人物，请重新录制更清晰的声音")
 }

 /** All windows must independently agree with the immutable initial template.
  * The final overlapping tail is also checked, but not counted as independent. */
 @JvmOverloads fun automaticAnchorSupport(context:Context,samples:FloatArray,profileId:String,sourceStart:Long=0L,requireCompetitor:Boolean=false):Result =
  automaticAnchorSupport(context,samples,profileId,sourceStart,requireCompetitor,null)

 fun automaticAnchorSupport(context:Context,samples:FloatArray,profileId:String,sourceStart:Long,requireCompetitor:Boolean,shared:SharedWindows?):Result {
  active()
  val evidence=JSONObject().put("version",1).put("method","strict_initial_template_all_windows")
   .put("experimental",true).put("minimum_similarity",AUTO_INITIAL_MIN).put("minimum_margin",AUTO_MARGIN)
   .put("window_samples",WINDOW).put("required_independent_windows_each_model",2)
   .put("source_start_sample",sourceStart).put("source_end_sample",sourceStart+samples.size)
   .put("require_registered_competitor",requireCompetitor)
  val models=JSONObject();evidence.put("models",models)
  if(profileId.isBlank()||sourceStart<0||samples.size<2*WINDOW||samples.any{!it.isFinite()})
   return Result(false,evidence.put("status","insufficient_audio"),"自动学习需要至少两个有效语音窗")
  val ranges=mutableListOf<Pair<Int,Int>>();var at=0
  while(at+WINDOW<=samples.size){ranges.add(at to at+WINDOW);at+=WINDOW}
  if(at<samples.size&&samples.size-WINDOW>ranges.last().first)ranges.add(samples.size-WINDOW to samples.size)
  var windowFailed=false
  for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES)){
   active();val info=JSONObject().put("model",SpeakerModels.name(model)).put("windows",JSONArray());models.put(model,info)
   if(!WearerVoiceStore.hasInitialTemplate(context,profileId,model))
    return Result(false,evidence.put("status","initial_template_missing"),"自动学习需要两种模型的初始声纹")
   val tracker=try{openWindows(context,samples,model,shared)}catch(e:Exception){
    if(e is java.util.concurrent.CancellationException)throw e
    return Result(false,evidence.put("status","model_unavailable"),"自动校验模型不可用")
   }
   try{
    WearerVoiceStore.Matcher(context,model,loadExtractor=false,initialOnly=true,acceptanceThreshold=AUTO_INITIAL_MIN,identityMargin=AUTO_MARGIN).use{matcher->
     var independent=0
     for((i,range) in ranges.withIndex()){
      active();val vector=tracker.read(range.first,range.second)
      val match=matcher.identifyEmbedding(vector,WINDOW,true)
      val disjoint=i==0||range.first>=ranges[i-1].second
      if(disjoint)independent++
      info.getJSONArray("windows").put(JSONObject().put("start_sample",sourceStart+range.first)
       .put("end_sample",sourceStart+range.second).put("independent",disjoint).put("match",match))
      if(requireCompetitor&&(match.isNull("runner_up_similarity")||!match.optDouble("runner_up_similarity",Double.NaN).isFinite()))
       return Result(false,evidence.put("status","registered_competitor_unavailable"),"缺少已登记对照人物，子段保留待确认")
      if(match.optString("status")!="wearer_candidate"||match.optString("profile_id")!=profileId)
       windowFailed=true
     }
     if(independent<2)return Result(false,evidence.put("status","insufficient_independent_windows"),"自动校验独立窗口不足")
    }
   }catch(e:Exception){
    if(e is java.util.concurrent.CancellationException)throw e
    return Result(false,evidence.put("status","check_failed"),"自动校验失败，候选保留待确认")
   }finally{tracker.close()}
  }
  if(windowFailed)return Result(false,evidence.put("status","window_not_high_confidence"),"自动校验未通过，候选保留待确认")
  return Result(true,evidence.put("status","strict_initial_support_passed"),"高可信初始声纹检查通过")
 }
 /** A human correction may resolve uncertain evidence but may not override repeated
  * positive evidence for a different already registered identity. */
 fun targetCompatible(evidence:JSONObject,profileId:String):Boolean {
  val models=evidence.optJSONObject("independent_profile_counts")?:return true
  for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES)){
   val counts=models.optJSONObject(model)?:continue
   if(counts.keys().asSequence().any{it!=profileId&&counts.optInt(it)>=2})return false
  }
  return true
 }
}
