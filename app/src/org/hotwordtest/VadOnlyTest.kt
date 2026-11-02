package org.hotwordtest

import android.content.res.AssetManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Compare VAD models on the same source PCM; no transcript or speaker inference. */
object VadOnlyTest {
 fun detect(assets:AssetManager,samples:FloatArray,key:String):JSONObject {
  val selected=VadModelFactory.normalize(key)
  if(selected==VadModelFactory.FSMN){
   require(samples.size<=60*VadModelFactory.SAMPLE_RATE){"FSMN VAD单次输入不能超过60秒"}
   val at=SystemClock.elapsedRealtimeNanos();val raw=FsmnVad.detect(samples);val segments=JSONArray()
   for(i in raw.indices step 2){
    val start=(raw[i].toLong()*VadModelFactory.SAMPLE_RATE/1000).coerceIn(0,samples.size.toLong()).toInt()
    val end=(raw[i+1].toLong()*VadModelFactory.SAMPLE_RATE/1000).coerceIn(start.toLong(),samples.size.toLong()).toInt()
    if(end>start)segments.put(JSONObject().put("start_sample",start).put("end_sample",end)
     .put("start_ms",raw[i]).put("end_ms",raw[i+1]))
   }
   return JSONObject().put("status","completed").put("vad_model",VadModelFactory.name(selected))
    .put("model_asset",VadModelFactory.asset(selected)).put("sample_rate",VadModelFactory.SAMPLE_RATE)
    .put("input_samples",samples.size).put("frame_shift_ms",10).put("max_speech_seconds",60)
    .put("speech_noise_threshold",0.5).put("window_ms",200).put("start_transition_ms",150)
    .put("silence_schedule","离线动态结束静音阈值；初始2000ms，减去150ms")
    .put("model_init_ms",JSONObject.NULL).put("process_ms",(SystemClock.elapsedRealtimeNanos()-at)/1000000.0)
    .put("timing_scope","包含模型加载和前处理、推理、分段；未单独测量模型初始化")
    .put("segments",segments).put("time_source","16kHz输入音频原始采样偏移；FSMN10ms帧精度")
  }
  val window=VadModelFactory.windowSize(selected)
  val initializedAt=SystemClock.elapsedRealtimeNanos()
  val vad=VadModelFactory.create(assets,selected)
  val processAt=SystemClock.elapsedRealtimeNanos()
  val segments=JSONArray()
  try {
   fun drain(){
    while(!vad.empty()) {
     val item=vad.front()
     val start=item.start.coerceIn(0,samples.size)
     val end=(item.start.toLong()+item.samples.size).coerceIn(start.toLong(),samples.size.toLong()).toInt()
     vad.pop()
     if(end>start)segments.put(JSONObject().put("start_sample",start).put("end_sample",end)
      .put("start_ms",start*1000L/VadModelFactory.SAMPLE_RATE).put("end_ms",end*1000L/VadModelFactory.SAMPLE_RATE))
    }
   }
   var pos=0;var detectedSince=-1
   while(pos<samples.size){
    val n=minOf(window,samples.size-pos);val frame=FloatArray(window)
    samples.copyInto(frame,0,pos,pos+n);vad.acceptWaveform(frame);pos+=n;drain()
    if(vad.isSpeechDetected()){
     if(detectedSince<0)detectedSince=pos-n
     if(pos-detectedSince>=VadModelFactory.MAX_SPEECH_SECONDS*VadModelFactory.SAMPLE_RATE){vad.flush();drain();detectedSince=pos}
    }else detectedSince=-1
   }
   vad.flush();drain()
   return JSONObject().put("status","completed").put("vad_model",VadModelFactory.name(selected))
    .put("model_asset",VadModelFactory.asset(selected)).put("sample_rate",VadModelFactory.SAMPLE_RATE)
    .put("input_samples",samples.size).put("frame_samples",window).put("threshold",0.5)
    .put("min_silence_seconds",0.6).put("min_speech_seconds",0.2).put("max_speech_seconds",20)
    .put("model_init_ms",(processAt-initializedAt)/1000000.0)
    .put("process_ms",(SystemClock.elapsedRealtimeNanos()-processAt)/1000000.0)
    .put("segments",segments).put("time_source","16kHz输入音频原始采样偏移；未加入前后补白")
  } finally {vad.release()}
 }
}
