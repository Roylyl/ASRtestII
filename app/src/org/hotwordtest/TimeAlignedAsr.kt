package org.hotwordtest

import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipFile
import java.io.File
import kotlin.math.roundToInt

/** Offline VAD transcription plus speaker-activity association and independently decoded turns. */
object TimeAlignedAsr {
 private const val RATE=16000
 private data class Span(val start:Int,val end:Int,val speaker:Int)
 private data class Turn(val start:Int,var end:Int,val speakers:List<Int>,var inVad:Boolean,var inDiarization:Boolean)

 interface Progress {
  fun onVad(index:Int,text:String)
  fun onDiarization(done:Int,total:Int)
  fun onTurn(done:Int,total:Int,turn:JSONObject?)
  fun onGrouping()
 }

 /** Split sample intervals, not recognized text: no word timestamps are available.
  * Union coverage preserves VAD-only audio as unknown and diarization-only audio as extra input.
  */
 private fun speakerTurns(asr:VadFilePipeline.Outcome,diarization:JSONObject):List<Turn> {
  val speech=asr.segments.mapNotNull { seg ->
   val start=seg.startSample.coerceIn(0,asr.inputSamples)
   val end=seg.endSample.coerceIn(start,asr.inputSamples)
   if(end>start)start to end else null
  }
  val entries=diarization.getJSONArray("segments")
  val speakers=mutableListOf<Span>()
  for(i in 0 until entries.length()){
   val o=entries.getJSONObject(i)
   val start=(o.getDouble("start_seconds")*RATE).roundToInt().coerceIn(0,asr.inputSamples)
   val end=(o.getDouble("end_seconds")*RATE).roundToInt().coerceIn(start,asr.inputSamples)
   if(end>start)speakers.add(Span(start,end,o.getInt("speaker_id")))
  }
  val boundaries=(speech.flatMap{listOf(it.first,it.second)}+speakers.flatMap{listOf(it.start,it.end)})
   .distinct().sorted()
  val turns=mutableListOf<Turn>()
  for(i in 0 until boundaries.size-1){
   val start=boundaries[i];val end=boundaries[i+1]
   if(end<=start)continue
   val fromVad=speech.any{it.first<end&&it.second>start}
   val ids=speakers.filter{it.start<end&&it.end>start}.map{it.speaker}.distinct().sorted()
   if(!fromVad&&ids.isEmpty())continue
   val last=turns.lastOrNull()
   if(last!=null&&last.end==start&&last.speakers==ids){
    last.end=end;last.inVad=last.inVad||fromVad;last.inDiarization=last.inDiarization||ids.isNotEmpty()
   }else turns.add(Turn(start,end,ids,fromVad,ids.isNotEmpty()))
  }
  return turns
 }

 /** Public so callers using a different VAD can align the same ASR/speaker results. */
 fun align(asr:VadFilePipeline.Outcome, diarization:JSONObject):JSONObject {
  val arr=diarization.getJSONArray("segments")
  val spans=mutableListOf<Span>()
  for(i in 0 until arr.length()) {
   val o=arr.getJSONObject(i)
   val start=(o.getDouble("start_seconds")*RATE).roundToInt().coerceIn(0,asr.inputSamples)
   val end=(o.getDouble("end_seconds")*RATE).roundToInt().coerceIn(0,asr.inputSamples)
   if(end>start)spans.add(Span(start,end,o.getInt("speaker_id")))
  }
  val linked=JSONArray()
  for(seg in asr.segments) {
   // Use raw VAD boundaries for association: decoded text includes pre/post roll,
   // so even a single overlapping speaker is explicitly a candidate only.
   val begin=seg.vadStartSample.coerceIn(0,asr.inputSamples)
   val end=seg.vadEndSample.coerceIn(begin,asr.inputSamples)
   val overlaps=spans.mapNotNull { span ->
    val a=maxOf(begin,span.start);val b=minOf(end,span.end)
    if(b>a)Span(a,b,span.speaker) else null
   }
   val ids=overlaps.map{it.speaker}.distinct().sorted()
   val intervals=JSONArray()
   for(span in overlaps)intervals.put(JSONObject().put("speaker_id",span.speaker)
    .put("start_sample",span.start).put("end_sample",span.end)
    .put("start_ms",span.start*1000L/RATE).put("end_ms",span.end*1000L/RATE))
   val idArray=JSONArray();ids.forEach{idArray.put(it)}
   val rec=seg.json().put("speaker_candidates",idArray).put("speaker_activity_intersections",intervals)
    .put("speaker_id",JSONObject.NULL)
    .put("association_status",when(ids.size){0->"no_speaker_match";1->"single_candidate_unverified";else->"multiple_candidates_ambiguous"})
    .put("association_basis","VAD原始语音区间与说话人活动区间的时间交集；非逐词归属")
   linked.put(rec)
  }
  return JSONObject().put("status","completed").put("sample_rate",RATE)
   .put("duration_ms",asr.inputSamples*1000L/RATE)
   .put("asr_segments",linked).put("diarization",diarization)
   .put("speaker_identity_status","anonymous_local_to_file_unverified")
   .put("word_timestamp_status","not_implemented")
   .put("alignment_status","interval_overlap_candidates_only")
 }

 /** Reuse the ASR and speaker models for a sequence of files. Not thread-safe. */
 class Session(private val assets:AssetManager,modelDir:File,private val speakerKey:String,
               private val hotwords:String="",private val vadKey:String=VadModelFactory.SILERO,
               private val lazyDiarization:Boolean=false,private val context:Context?=null,
               private val speakerThreshold:Double=LocalSpeakerTracker.EXPERIMENTAL_THRESHOLD,
               private val decisionConfig:SpeakerDecisionConfig=SpeakerDecisionConfig()):AutoCloseable {
  private val t0=SystemClock.elapsedRealtimeNanos()
  private val withHotwords=hotwords.isNotEmpty()
  private val recognizer=OnlineRecognizer(config=OnlineRecognizerConfig(
   featConfig=FeatureConfig(sampleRate=RATE,featureDim=80),
   modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig(
    encoder=File(modelDir,"encoder-1920ms.onnx").path,
    decoder=File(modelDir,"decoder-1920ms.onnx").path,
    joiner=File(modelDir,"joiner-1920ms.onnx").path),
    tokens=File(modelDir,"tokens.txt").path,numThreads=2,provider="cpu",modelType="zipformer2",
    modelingUnit=if(withHotwords)"bpe" else "",bpeVocab=if(withHotwords)File(modelDir,"bpe.vocab").path else ""),
   enableEndpoint=false,decodingMethod=if(withHotwords)"modified_beam_search" else "greedy_search",maxActivePaths=4,hotwordsScore=4f))
  val asrInitMs=(SystemClock.elapsedRealtimeNanos()-t0)/1000000.0
  private var diarizer:DiarizationTest.Session?=if(lazyDiarization)null else
   try { DiarizationTest.Session(assets,speakerKey,-1,speakerThreshold) }
   catch(e:Throwable){recognizer.release();throw e}
  private fun getDiarizer():DiarizationTest.Session {
   val existing=diarizer;if(existing!=null)return existing
   return DiarizationTest.Session(assets,speakerKey,-1,speakerThreshold).also{diarizer=it}
  }

  private var sharedVoiceGroups:LocalSpeakerTracker?=null
  private fun voiceGroups():LocalSpeakerTracker? {
   if(context==null)return null
   return sharedVoiceGroups?:LocalSpeakerTracker(assets,speakerKey,speakerThreshold,decisionConfig).also{sharedVoiceGroups=it}
  }
  // Acoustic groups span a batch. Recency and diarizer IDs only apply within one WAV.
  private inner class FileTurnLabels {
   private var lastLocalId:Int?=null
   private var lastGroup:String?=null
   private var lastMatchedEnd=0
   fun label(entry:JSONObject,turn:Turn):String {
    val localId=turn.speakers.singleOrNull()
    if(localId==null){
     lastLocalId=null
     return if(turn.speakers.isEmpty())"说话人未识别" else "多人重叠"
    }
    if(localId!=lastLocalId)lastGroup=null
    lastLocalId=localId
    val voice=entry.optJSONObject("voice_group_candidate")
    var id=voice?.takeUnless{it.isNull("speaker_id")}?.getString("speaker_id")
    if(id!=null){
     lastGroup=id
     lastMatchedEnd=turn.end
    }else if(lastGroup!=null && turn.end-turn.start<RATE*3/2 && turn.start-lastMatchedEnd in 0..RATE*3){
     val canExtend=when(voice?.optString("speaker_status")){
      "insufficient_audio"->true
      "weak_candidate_rejected"->voice.optString("best_existing_speaker_id")==lastGroup &&
       voice.optInt("existing_group_count")==1 &&
       voice.optDouble("similarity_to_previous",-1.0)>=decisionConfig.weakMatch-decisionConfig.ambiguityMargin
      else->false
     }
     if(canExtend){
      id=lastGroup
      entry.put("speaker_continuity",JSONObject().put("method","short_nearby_same_diarizer_track").put("speaker_id",id))
     }
    }
    val identity=entry.optJSONObject("wearer_identity")
    if(identity?.optString("status")=="wearer_candidate"&&!identity.isNull("display_name")){
     identity.optString("display_name").takeIf{it.isNotBlank()}?.let{return it}
    }
    return id?.let(SpeakerTranscript::groupName)?:"说话人未识别"
   }
  }

  private fun transcribeTurn(samples:FloatArray,turn:Turn,cancelled:()->Boolean):JSONObject {
   if(cancelled())throw InterruptedException("已停止")
   val stream=recognizer.createStream(hotwords)
   val text=try{
    var pos=turn.start
    while(pos<turn.end){
     if(cancelled())throw InterruptedException("已停止")
     val end=minOf(pos+800,turn.end)
     stream.acceptWaveform(samples.copyOfRange(pos,end),RATE);pos=end
     while(recognizer.isReady(stream))recognizer.decode(stream)
    }
    // Flush the 1920ms model at EOF without extending source timestamps.
    stream.acceptWaveform(FloatArray(VadFilePipeline.TAIL_SAMPLES),RATE)
    stream.inputFinished()
    while(recognizer.isReady(stream)){
     if(cancelled())throw InterruptedException("已停止")
     recognizer.decode(stream)
    }
    recognizer.getResult(stream).text
   }finally{stream.release()}
   val ids=JSONArray();turn.speakers.forEach{ids.put(it)}
   val coverage=JSONArray();if(turn.inVad)coverage.put("vad");if(turn.inDiarization)coverage.put("diarization")
   return JSONObject().put("start_sample",turn.start).put("end_sample",turn.end)
    .put("start_ms",turn.start*1000L/RATE).put("end_ms",turn.end*1000L/RATE)
    .put("text",text).put("speaker_candidates",ids).put("speaker_id",JSONObject.NULL)
    .put("association_status",when(turn.speakers.size){0->"no_speaker_match";1->"single_candidate_unverified";else->"multiple_candidates_ambiguous"})
    .put("recognition_status",if(text.isBlank())"empty" else "completed")
    .put("short_interval",turn.end-turn.start<RATE/5).put("coverage_sources",coverage)
    .put("time_source","输入音频样本偏移，边界由VAD和说话人切分预测，非词级时间")
    .put("text_source","对该音频区间独立运行X-ASR；短片段和边界可能漏字")
  }

  fun process(samples:FloatArray,cancelled:()->Boolean={false},
              cachedDiarization:JSONObject?=null,cachedSourceGroup:String?=null,progress:Progress?=null):JSONObject {
   if(cancelled())throw InterruptedException("已停止")
   val fileStart=SystemClock.elapsedRealtimeNanos()
   val asrStart=SystemClock.elapsedRealtimeNanos()
   progress?.onVad(0,"")
   val asr=VadFilePipeline(assets,vadKey).process(samples,recognizer,hotwords,cancelled=cancelled,
    onPartial={i,_,_,text->progress?.onVad(i+1,text)},onFinal={seg->progress?.onVad(seg.index+1,seg.text)})
   val asrEnd=SystemClock.elapsedRealtimeNanos()
   if(cancelled())throw InterruptedException("已停止")
   require(cachedDiarization==null || !cachedSourceGroup.isNullOrEmpty()){"缓存来源未指定"}
   progress?.onDiarization(0,0)
   val diarization=if(cachedDiarization==null)getDiarizer().process(samples,progress?.let{it::onDiarization})
    else JSONObject(cachedDiarization.toString())
   if(cancelled())throw InterruptedException("已停止")
   val out=align(asr,diarization)
   val turnsStarted=SystemClock.elapsedRealtimeNanos()
   val turns=JSONArray()
   val intervals=speakerTurns(asr,diarization)
   progress?.onTurn(0,intervals.size,null)
   // The diarizer indicates possible overlap; only one-candidate intervals may receive
   // an experimental voice comparison. Failure does not discard the original ASR.
   val matcher=if(context==null)null else try{WearerVoiceStore.Matcher(context,speakerKey)}catch(_:Exception){null}
   // Independent from pyannote speaker IDs; disabled for batch/probe callers without a UI context.
   val fileLabels=FileTurnLabels()
   val voiceGroups=try{voiceGroups()}catch(e:Exception){Log.w("HotwordTest","无法初始化声纹归组",e);null}
   try {
    for(turn in intervals){
     val entry=transcribeTurn(samples,turn,cancelled)
     if(turn.speakers.size==1 && voiceGroups!=null){
      val group=if(entry.optString("text").isBlank()){
       JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","empty_asr_not_grouped")
      }else try{voiceGroups.assignConservative(samples.copyOfRange(turn.start,turn.end),singleSpeakerVerified=true)}catch(_:Exception){JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","comparison_failed")}
      entry.put("voice_group_candidate",group)
     }
     if(matcher!=null) {
      val identity=try { matcher.identify(samples.copyOfRange(turn.start,turn.end),turn.speakers.size==1) }
       catch(_:Exception){JSONObject().put("status","comparison_failed")}
      entry.put("wearer_identity",identity)
     }
     if(context!=null)entry.put("speaker_display",fileLabels.label(entry,turn))
     turns.put(entry)
     progress?.onTurn(turns.length(),intervals.size,entry)
    }
   } finally {matcher?.close()}
   if(voiceGroups!=null){
    progress?.onGrouping()
    out.put("offline_voice_grouping",JSONObject().put("method","threshold_gated_cross_clip_embedding_v2")
     .put("new_speaker_max_similarity",decisionConfig.newSpeakerMax).put("strong_match",decisionConfig.strongMatch)
     .put("weak_match",decisionConfig.weakMatch).put("raw_window_ids_used_for_identity",false))
   }
   val turnsEnded=SystemClock.elapsedRealtimeNanos()
   out.put("speaker_turn_transcripts",turns)
    .put("speaker_turn_alignment_status","speaker_activity_intervals_independent_asr")
    .put("speaker_turn_note","按VAD与说话人活动的并集切分音频并独立转写；仅为模型预测，重叠及短片段可能丢字，原VAD转写保留在asr_segments")
    .put("voice_group_candidate_scope","同一任务内跨文件按声纹归组。只有足够清晰且与已有声纹差异足够大的单人语音才能新建分组，重叠片段不创建人物。")
    .put("speaker_turn_process_ms",(turnsEnded-turnsStarted)/1000000.0)
   if(voiceGroups!=null){
    out.put("speaker_identity_status","task_scoped_voice_group_and_enrollment_predictions")
    out.put("offline_voice_recluster",JSONObject().put("status","skipped").put("reason","threshold_gated_session_grouping_authoritative"))
   }
   out.put("diarization_source",if(cachedDiarization==null)"computed" else "reused")
    .put("diarization_cache_source_group",cachedSourceGroup?:JSONObject.NULL)
    .put("diarization_prior_process_ms",if(cachedDiarization==null)JSONObject.NULL else diarization.getDouble("process_ms"))
    .put("diarization_timing_note",if(cachedDiarization==null)"本次切分耗时" else "diarization.process_ms为来源任务先前耗时，不是本次耗时")
   out.put("vad_model",VadModelFactory.name(vadKey)).put("asr_model","X-ASR1920ms")
    .put("decoding",if(withHotwords)"modified_beam_search" else "greedy_search")
    .put("asr_model_init_ms",asrInitMs).put("diarization_model_init_ms",if(cachedDiarization==null)diarizer!!.initMs else JSONObject.NULL)
    .put("vad_asr_process_ms",(asrEnd-asrStart)/1000000.0)
    .put("total_ms",(SystemClock.elapsedRealtimeNanos()-fileStart)/1000000.0)
    .put("timing_note",if(cachedDiarization==null)
     "文件处理按顺序执行；total_ms包含说话人切分和逐片段转写，不含复用的模型初始化；非实时延迟"
     else "total_ms为本次VAD、转写和逐片段转写耗时，不含来源任务的说话人切分；非完整链路耗时或实时延迟")
   return out
  }
  override fun close(){
   try{sharedVoiceGroups?.close()}finally{try{diarizer?.close()}finally{recognizer.release()}}
  }
 }
 fun process(assets:AssetManager,samples:FloatArray,modelDir:File,speakerKey:String,
             hotwords:String="",vadKey:String=VadModelFactory.SILERO,cancelled:()->Boolean={false},
             context:Context?=null,speakerThreshold:Double=LocalSpeakerTracker.EXPERIMENTAL_THRESHOLD,
             decisionConfig:SpeakerDecisionConfig=SpeakerDecisionConfig()):JSONObject {
  return Session(assets,modelDir,speakerKey,hotwords,vadKey,context=context,speakerThreshold=speakerThreshold,decisionConfig=decisionConfig).use{it.process(samples,cancelled)}
 }

 /** Developer probe. Caller must launch only when the app is otherwise idle. */
 fun runProbe(activity:Activity,modelDir:()->File) {
  Thread {
   val resultFile=File(activity.getExternalFilesDir(null),"alignment-probe-result.json")
   val partialFile=File(resultFile.parentFile,"alignment-probe-result.json.tmp")
   val items=JSONArray()
   var speakerModel="unknown"
   var vadModel="unknown"
   var isolatedDir:File?=null
   fun save(status:String,error:String?=null) {
    val result=JSONObject().put("status",status).put("runtime","Android CPU")
     .put("speaker_model",speakerModel).put("vad_model",vadModel).put("items",items)
    if(error!=null)result.put("error",error)
    partialFile.outputStream().use{stream->
     stream.write(result.toString().toByteArray(Charsets.UTF_8));stream.flush();stream.fd.sync()
    }
    check(partialFile.renameTo(resultFile)){"无法原子更新测试结果文件"}
   }
   try {
    val key=SpeakerModels.normalize(activity.intent.getStringExtra("alignmentSpeakerModel"))
    val vadKey=VadModelFactory.normalize(activity.intent.getStringExtra("alignmentVadModel"))
    speakerModel=SpeakerModels.name(key);vadModel=VadModelFactory.name(vadKey)
    save("running")
    val dir=modelDir()
    val temp=File.createTempFile("alignment-voice-probe-",".tmp",activity.cacheDir)
    check(temp.delete()&&temp.mkdir()){ "无法创建隔离目录" };isolatedDir=temp
    val isolated=object:android.content.ContextWrapper(activity.applicationContext){override fun getFilesDir():File=temp}
    // Metadata only: do not keep WAV byte arrays or decoded audio for all entries in memory.
    ZipFile(File(activity.getExternalFilesDir(null),"alignment-probe.zip")).use { zip ->
     val entries=zip.entries().asSequence().filter{!it.isDirectory&&it.name.lowercase().endsWith(".wav")}.toList().sortedBy{it.name}
     require(entries.isNotEmpty()){ "ZIP中没有WAV" };require(entries.size<=50){"音频数量过多"}
     Session(activity.assets,dir,key,vadKey=vadKey,context=isolated).use { session ->
      for(entry in entries){
       val item=try{
        val samples=zip.getInputStream(entry).use{WavReader.read16k(it)}
        session.process(samples).put("file",entry.name)
       }catch(e:OutOfMemoryError){throw e}
        catch(e:Exception){JSONObject().put("file",entry.name).put("status","failed").put("error",e.message?:e.javaClass.simpleName)}
       items.put(item);save("running")
      }
     }
    }
    save("completed")
   } catch(e:Throwable){
    Log.e("HotwordTest","alignment probe failed",e)
    try{save("failed",e.message?:e.javaClass.simpleName)}catch(writeError:Throwable){Log.e("HotwordTest","alignment probe result update failed",writeError)}
   } finally {isolatedDir?.let{d->File(d,"voice-profiles").delete();d.delete()}}
  }.start()
 }
}
