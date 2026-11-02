package org.hotwordtest

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Shared experiment settings; identifiers are stable across activity recreation. */
object VadModelFactory {
 const val SILERO = "silero"
 const val TEN = "ten"
 const val FSMN = "fsmn"
 const val SAMPLE_RATE = 16000
 const val MAX_SPEECH_SECONDS = 20f
 fun normalize(key:String?):String = when(key) {
  null, "", SILERO -> SILERO
  TEN -> TEN
  FSMN -> FSMN
  else -> throw IllegalArgumentException("不支持的VAD模型：$key")
 }
 fun name(key:String?):String = when(normalize(key)){ TEN->"TEN VAD";FSMN->"FSMN VAD";else->"Silero VAD" }
 fun asset(key:String?):String = when(normalize(key)){ TEN->"models/ten-vad.onnx";FSMN->"fsmn/fsmn-vad.gguf";else->"models/silero_vad.onnx" }
 fun windowSize(key:String?):Int = when(normalize(key)){TEN->256;FSMN->throw IllegalArgumentException("FSMN暂不支持实时流式VAD");else->512}
 fun create(assets:AssetManager,key:String?):Vad {
  val selected=normalize(key);require(selected!=FSMN){"FSMN暂不支持实时流式VAD"};val window=windowSize(selected)
  val config=if(selected==TEN) VadModelConfig(tenVadModelConfig=TenVadModelConfig(
   model=asset(selected),threshold=0.5f,minSilenceDuration=0.6f,minSpeechDuration=0.2f,
   windowSize=window,maxSpeechDuration=MAX_SPEECH_SECONDS),sampleRate=SAMPLE_RATE,numThreads=1,provider="cpu")
  else VadModelConfig(sileroVadModelConfig=SileroVadModelConfig(
   model=asset(selected),threshold=0.5f,minSilenceDuration=0.6f,minSpeechDuration=0.2f,
   windowSize=window,maxSpeechDuration=MAX_SPEECH_SECONDS),sampleRate=SAMPLE_RATE,numThreads=1,provider="cpu")
  return Vad(assets,config)
 }
}
