package org.hotwordtest

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.json.JSONArray
import org.json.JSONObject

/** File input only: positions refer to the input waveform after conversion to 16kHz mono. */
class VadFilePipeline(private val assets: AssetManager, private val vadKey:String=VadModelFactory.SILERO) {
 companion object {
  const val SAMPLE_RATE=16000
  const val WINDOW=512
  const val PRE_ROLL=2400 // 150ms before VAD output
  const val POST_ROLL=1600 // 100ms after VAD output; never overlaps next segment
  const val MAX_SPEECH_SECONDS=20f // force-flush sustained speech at this duration
  const val TAIL_SAMPLES=41280 // 1920ms + 660ms; excluded from timestamps
 }
 data class Segment(val index:Int,val startSample:Int,val endSample:Int,val vadStartSample:Int,val vadEndSample:Int,val text:String) {
  fun json()=JSONObject().put("index",index).put("start_sample",startSample).put("end_sample",endSample)
   .put("start_ms",startSample*1000L/SAMPLE_RATE).put("end_ms",endSample*1000L/SAMPLE_RATE)
   .put("vad_start_sample",vadStartSample).put("vad_end_sample",vadEndSample)
   .put("text",text).put("speaker_id",JSONObject.NULL).put("word_timestamps",JSONObject.NULL)
   .put("confidence",JSONObject.NULL).put("time_source","转换为16kHz单声道后的输入样本偏移，VAD边界及补白")
 }
 data class Outcome(val segments:List<Segment>,val inputSamples:Int,val vadKey:String=VadModelFactory.SILERO) {
  fun json():JSONObject {val array=JSONArray();segments.forEach{array.put(it.json())};return JSONObject()
   .put("status","completed").put("sample_rate",SAMPLE_RATE).put("input_samples",inputSamples)
   .put("duration_ms",inputSamples*1000L/SAMPLE_RATE).put("segments",array)
   .put("speaker_status","not_implemented").put("word_timestamp_status","not_implemented")
   .put("partial_scope","已完成VAD分段的ASR中间结果；非麦克风实时输出")
   .put("vad_settings",settings(vadKey))}
  private fun settings(key:String):JSONObject {
   val config=JSONObject().put("model",VadModelFactory.asset(key)).put("pre_roll_samples",PRE_ROLL)
    .put("post_roll_samples",POST_ROLL).put("asr_tail_silence_samples",TAIL_SAMPLES)
   return if(VadModelFactory.normalize(key)==VadModelFactory.FSMN) config.put("frame_shift_ms",10)
    .put("speech_noise_threshold",0.5).put("window_ms",200).put("start_transition_ms",150)
    .put("max_speech_seconds",60).put("silence_schedule","离线动态结束静音阈值")
    else config.put("threshold",0.5).put("min_silence_seconds",0.6).put("min_speech_seconds",0.2)
    .put("window_samples",VadModelFactory.windowSize(key)).put("max_speech_seconds",MAX_SPEECH_SECONDS)
  }
 }

 /** Runs synchronously on a background worker. Caller owns and releases recognizer.
  * onPartial may revise the current segment until onFinal; both run on this worker.
  */
 fun process(samples:FloatArray, recognizer:OnlineRecognizer, hotwords:String,
             cancelled:()->Boolean={false},
             onPartial:(Int,Int,Int,String)->Unit={_,_,_,_->},
             onFinal:(Segment)->Unit={}):Outcome {
  val selected=VadModelFactory.normalize(vadKey)
  if(selected==VadModelFactory.FSMN)require(samples.size<=60*SAMPLE_RATE){"FSMN VAD单次输入不能超过60秒"}
  val results=mutableListOf<Segment>();var lastEnd=0
  fun checkCancelled(){if(cancelled())throw InterruptedException("已停止")}
  fun consume(rawStart:Int,rawEnd:Int){
    checkCancelled()
    if(rawEnd<=rawStart)return
    // A prior post-roll may already have consumed a prefix of this segment.
    // Do not coerce back to rawStart: that would duplicate audio and timestamps.
    val start=maxOf(lastEnd,rawStart-PRE_ROLL)
    val end=minOf(samples.size,rawEnd+POST_ROLL)
    if(end<=start)return
    val stream=recognizer.createStream(hotwords)
    try{
     var p=start;var previous=""
     while(p<end){checkCancelled();val q=minOf(p+800,end);stream.acceptWaveform(samples.copyOfRange(p,q),SAMPLE_RATE);p=q
      while(recognizer.isReady(stream))recognizer.decode(stream)
      val current=recognizer.getResult(stream).text
      if(current!=previous){previous=current;onPartial(results.size,start,end,current)}
     }
     stream.acceptWaveform(FloatArray(TAIL_SAMPLES),SAMPLE_RATE);stream.inputFinished()
     while(recognizer.isReady(stream)){checkCancelled();recognizer.decode(stream)}
     val text=recognizer.getResult(stream).text
     val segment=Segment(results.size,start,end,rawStart,rawEnd,text);results.add(segment);lastEnd=end;onFinal(segment)
    }finally{stream.release()}
  }
  if(selected==VadModelFactory.FSMN){
   checkCancelled();val raw=FsmnVad.detect(samples)
   for(i in raw.indices step 2){
    val start=(raw[i].toLong()*SAMPLE_RATE/1000).coerceIn(0,samples.size.toLong()).toInt()
    val end=(raw[i+1].toLong()*SAMPLE_RATE/1000).coerceIn(start.toLong(),samples.size.toLong()).toInt()
    consume(start,end)
   }
   return Outcome(results.toList(),samples.size,vadKey)
  }
  val window=VadModelFactory.windowSize(selected)
  val vad=VadModelFactory.create(assets,selected)
  fun drain(){
   while(!vad.empty()){
    checkCancelled()
    val detected=vad.front();val rawStart=detected.start.coerceIn(0,samples.size)
    val rawEnd=(detected.start.toLong()+detected.samples.size).coerceIn(rawStart.toLong(),samples.size.toLong()).toInt()
    vad.pop();consume(rawStart,rawEnd)
   }
  }
  try{
   var pos=0;var detectedSince=-1
   while(pos<samples.size){checkCancelled();val n=minOf(window,samples.size-pos)
    val frame=FloatArray(window);samples.copyInto(frame,0,pos,pos+n);vad.acceptWaveform(frame);pos+=n;drain()
    if(vad.isSpeechDetected()){
     if(detectedSince<0)detectedSince=pos-n
     // Native maxSpeechDuration only tightens VAD thresholds; it is not a hard cap.
     // Force-flush long uninterrupted speech so the native 60s circular buffer cannot overrun.
     if(pos-detectedSince>=MAX_SPEECH_SECONDS*SAMPLE_RATE){vad.flush();drain();detectedSince=pos}
    }else detectedSince=-1
   }
   checkCancelled();vad.flush();drain()
   return Outcome(results.toList(),samples.size,vadKey)
  }finally{vad.release()}
 }
}
