package org.hotwordtest

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Internal replay test; the profile is isolated from all actual user enrollment data. */
object LiveSpeakerProbe {
 fun run(activity:Activity,modelDir:()->File) {
  Thread {
   val root=File(activity.getExternalFilesDir(null),"wearer-voice-probe")
   val output=JSONObject().put("test_only",true).put("mode","live_vad_replay")
    .put("fixture_basis","MELI语料F01A片段，同人标签来自语料，未逐段人工听音核实")
   var ctx:Context?=null;var tmp:File?=null;var engine:OnlineRecognizer?=null
   try {
    val dir=File.createTempFile("live-voice-probe-",".tmp",activity.cacheDir)
    check(dir.delete() && dir.mkdir()){ "无法创建隔离测试目录" };tmp=dir
    val local=object:ContextWrapper(activity.applicationContext){override fun getFilesDir():File=dir};ctx=local
    fun wav(name:String)=File(root,name).inputStream().use{WavReader.read16k(it)}
    val enrolled=wav("F01A_en_218.wav");val input=wav("F01A_en_341.wav")
    val crossLanguage=wav("F01A_zh_343.wav")
    WearerVoiceStore.enrollNew(local,SpeakerModels.CAM,enrolled,"测试用户")
    val models=modelDir()
    val recognizer=OnlineRecognizer(config=OnlineRecognizerConfig(
     featConfig=FeatureConfig(sampleRate=16000,featureDim=80),
     modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig(
      encoder=File(models,"encoder-1920ms.onnx").path,decoder=File(models,"decoder-1920ms.onnx").path,
      joiner=File(models,"joiner-1920ms.onnx").path),tokens=File(models,"tokens.txt").path,
      numThreads=2,provider="cpu",modelType="zipformer2"),
     enableEndpoint=false,decodingMethod="greedy_search"))
    engine=recognizer
    val segments=JSONArray()
    LiveSpeakerLabeler(activity.assets,local,SpeakerModels.CAM).use{labeler->
     val segmenter=VadLiveSegmenter(activity.assets,VadModelFactory.SILERO){segment->
      val stream=recognizer.createStream("")
      val text=try{
       var p=0
       while(p<segment.samples.size){val end=minOf(p+800,segment.samples.size)
        stream.acceptWaveform(segment.samples.copyOfRange(p,end),16000);p=end
        while(recognizer.isReady(stream))recognizer.decode(stream)}
       stream.acceptWaveform(FloatArray(2580*16),16000);stream.inputFinished()
       while(recognizer.isReady(stream))recognizer.decode(stream)
       recognizer.getResult(stream).text
      }finally{stream.release()}
      val label=labeler.classify(segment.samples)
      segments.put(JSONObject().put("start_ms",segment.startSample*1000/16000)
       .put("end_ms",segment.endSample*1000/16000).put("text",text).put("label",label))
     }
     try{var p=0;while(p<input.size){val end=minOf(p+800,input.size)
       segmenter.accept(input.copyOfRange(p,end));p=end};segmenter.finish()}finally{segmenter.close()}
     val short=labeler.classify(FloatArray(1000))
     require(short.getString("display")=="待确认"){"短片段未回退为待确认"}
     output.put("short_fallback",short)
     // Same-speaker cross-language fixture exercises the exploratory low-score UI band.
     val low=labeler.classify(crossLanguage);val lowIdentity=low.getJSONObject("identity")
     require(lowIdentity.optString("status")=="uncertain" && lowIdentity.optBoolean("low_confidence_candidate_shown"))
      {"跨语言片段未进入低置信候选档：${lowIdentity.optDouble("similarity")}"}
     require(low.getString("display").contains("测试用户（低置信候选）") &&
      !low.getJSONObject("speaker").isNull("speaker_id")){"低置信候选未保留匿名编号与姓名"}
     output.put("low_confidence_candidate",low)
    }
    var found=false
    for(i in 0 until segments.length()){
     val entry=segments.getJSONObject(i);val label=entry.getJSONObject("label")
     require(label.getString("single_speaker_status")=="not_verified" && label.getString("overlap_status")=="not_checked"){"实时状态错误"}
     if(label.getString("display")=="测试用户（候选）" && entry.getString("text").isNotBlank())found=true
    }
    require(found){"VAD重放没有输出姓名及转写"}
    val duplicate=WearerVoiceStore.enrollNew(local,SpeakerModels.CAM,enrolled,"相似用户")
    LiveSpeakerLabeler(activity.assets,local,SpeakerModels.CAM).use{labeler->
     val ambiguous=labeler.classify(input)
     require(ambiguous.getJSONObject("identity").optString("status")=="uncertain"){"相近声纹未回退为待确认"}
     require(!ambiguous.getString("display").contains("测试用户")){"相近声纹错误输出姓名"}
     val ambiguousLow=labeler.classify(crossLanguage)
     require(!ambiguousLow.getString("display").contains("测试用户") &&
      !ambiguousLow.getJSONObject("identity").optBoolean("low_confidence_candidate_shown"))
      {"低置信且候选接近时错误输出姓名"}
     output.put("ambiguous_fallback",ambiguous)
     output.put("ambiguous_low_fallback",ambiguousLow)
    }
    WearerVoiceStore.deleteById(local,duplicate.id)
    output.put("named_text_found",found)
    output.put("segments",segments).put("status","completed")
   } catch(t:Throwable){Log.e("LiveSpeakerProbe","测试失败",t);output.put("status","failed").put("error",t.message?:t.javaClass.simpleName)}
   finally {
    try{engine?.release()}catch(_:Throwable){}
    try{ctx?.let{c->WearerVoiceStore.listProfiles(c).forEach{WearerVoiceStore.deleteById(c,it.id)}}}catch(_:Throwable){}
    try{tmp?.let{d->File(d,"voice-profiles").delete();d.delete()}}catch(_:Throwable){}
    try{root.mkdirs();File(root,"live-result.json").writeText(output.toString(),Charsets.UTF_8)}
    catch(t:Throwable){Log.e("LiveSpeakerProbe","无法写入结果",t)}
   }
  }.start()
 }
}
