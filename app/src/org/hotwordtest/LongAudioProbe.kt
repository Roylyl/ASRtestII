package org.hotwordtest

import android.app.Activity
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import android.view.WindowManager
import com.k2fsa.sherpa.onnx.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** File-boundary resumable on-device experiment. This does not implement diarization. */
object LongAudioProbe {
 private val running=AtomicBoolean(false)
 private const val SAMPLE_RATE=16000
 private const val MODEL_ID="3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced"
 private fun writeAtomic(file:File, value:JSONObject){
  val atomic=AtomicFile(file);val stream=atomic.startWrite()
  try{stream.write(value.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}catch(t:Throwable){atomic.failWrite(stream);throw t}
 }
 private fun readAtomic(file:File):JSONObject?{
  val atomic=AtomicFile(file)
  if(!file.exists()&&!File(file.path+".bak").exists())return null
  return JSONObject(atomic.openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
 }
 fun start(activity:Activity, runId:String, ensureModel:()->File, status:(String)->Unit){
  if(!runId.matches(Regex("[A-Za-z0-9_-]{1,80}"))){status("长音频任务编号无效");return}
  if(!running.compareAndSet(false,true)){status("已有长音频任务正在运行");return}
  activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  Thread {
   var engine:OnlineRecognizer?=null;var speaker:LocalSpeakerTracker?=null
   val base=File(activity.getExternalFilesDir(null),"long-probe/$runId");val stateFile=File(base,"checkpoint.json");val errorFile=File(base,"error.json")
   try{
    require(base.isDirectory){"任务目录不存在"}
    val rawManifest=File(base,"manifest.json").readText(Charsets.UTF_8)
    val manifest=JSONObject(rawManifest);val items=manifest.getJSONArray("items")
    require(manifest.optInt("sample_rate")==SAMPLE_RATE && items.length() in 1..1000){"清单采样率或文件数量无效"}
    var end=0L;val names=mutableSetOf<String>()
    for(i in 0 until items.length()){
     val item=items.getJSONObject(i);val name=item.getString("file");val start=item.getLong("start_sample");val count=item.getLong("samples")
     require(name.matches(Regex("[A-Za-z0-9_-]+\\.wav"))&&names.add(name)){"WAV名称无效或重复"}
     require(start==end && count in 1..(SAMPLE_RATE*600L)){"清单样本时间轴不连续或单文件超过10分钟"};end=start+count
    }
    val previous=readAtomic(stateFile)
    if(previous!=null){
     require(previous.getString("manifest_text")==rawManifest){"任务清单已更改，拒绝续跑"}
     require(previous.getString("speaker_model")==MODEL_ID && previous.getDouble("speaker_threshold")==LocalSpeakerTracker.EXPERIMENTAL_THRESHOLD){"模型或阈值已改变，拒绝续跑"}
     require(previous.getInt("next_index") in 0..items.length()){ "进度无效" }
    }
    val records=previous?.getJSONArray("results")?:JSONArray()
    var next=previous?.getInt("next_index")?:0
    require(records.length()==next){"进度与结果不一致"}
    if(next==items.length()){status("长音频任务已完成：$next/${items.length()}");return@Thread}
    val model=ensureModel()
    val recognizer=OnlineRecognizer(config=OnlineRecognizerConfig(featConfig=FeatureConfig(sampleRate=SAMPLE_RATE,featureDim=80),modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig(encoder=File(model,"encoder-1920ms.onnx").path,decoder=File(model,"decoder-1920ms.onnx").path,joiner=File(model,"joiner-1920ms.onnx").path),tokens=File(model,"tokens.txt").path,numThreads=2,provider="cpu",modelType="zipformer2"),enableEndpoint=false,decodingMethod="greedy_search"))
    engine=recognizer
    val tracker=LocalSpeakerTracker(activity.assets)
    speaker=tracker
    if(previous!=null && next>0)tracker.restore(previous.optJSONArray("speaker_state")?:error("缺少声纹状态，拒绝续跑"))
    var totalElapsed=previous?.optLong("completed_elapsed_ms",0L)?:0L
    while(next<items.length()){
     val item=items.getJSONObject(next);val name=item.getString("file");val offset=item.getLong("start_sample");val expected=item.getLong("samples")
     val file=File(base,name);require(file.isFile){"缺少输入音频：$name"}
     status("长音频处理${next+1}/${items.length()}：$name")
     val started=SystemClock.elapsedRealtime();val samples=file.inputStream().use{WavReader.read16k(it)}
     require(samples.size.toLong()==expected){"WAV样本数与清单不一致：$name"}
     val speakerResults=mutableListOf<JSONObject>()
     val result=VadFilePipeline(activity.assets).process(samples,recognizer,"",onFinal={seg->
      speakerResults.add(tracker.assign(samples.copyOfRange(seg.vadStartSample,seg.vadEndSample)))
     })
     val out=result.json();val segs=out.getJSONArray("segments")
     require(segs.length()==speakerResults.size){"说话人标注数量不一致"}
     for(i in 0 until segs.length()){
      val segment=segs.getJSONObject(i);val sp=speakerResults[i];val absoluteStart=offset+segment.getLong("start_sample");val absoluteEnd=offset+segment.getLong("end_sample")
      segment.put("absolute_start_sample",absoluteStart).put("absolute_end_sample",absoluteEnd)
       .put("absolute_start_ms",absoluteStart*1000L/SAMPLE_RATE).put("absolute_end_ms",absoluteEnd*1000L/SAMPLE_RATE)
       .put("speaker_id",sp.opt("speaker_id")?:JSONObject.NULL).put("speaker_status",sp.optString("speaker_status"))
       .put("speaker_similarity",sp.opt("similarity_to_previous")?:JSONObject.NULL).put("speaker_threshold",sp.opt("threshold")?:JSONObject.NULL)
     }
     val elapsed=SystemClock.elapsedRealtime()-started;totalElapsed+=elapsed
     out.put("file",name).put("file_start_sample",offset).put("elapsed_ms",elapsed)
      .put("speaker_status","experimental_segment_assignment").put("speaker_threshold",LocalSpeakerTracker.EXPERIMENTAL_THRESHOLD)
     records.put(out);next++
     val saved=JSONObject().put("version",1).put("status",if(next==items.length())"completed" else "running")
      .put("run_id",runId).put("manifest_text",rawManifest).put("next_index",next).put("total_files",items.length())
      .put("sample_rate",SAMPLE_RATE).put("speaker_model",MODEL_ID).put("speaker_threshold",LocalSpeakerTracker.EXPERIMENTAL_THRESHOLD)
      .put("decoding","greedy_search").put("hotwords",JSONArray()).put("results",records)
      .put("speaker_state",tracker.snapshot()).put("completed_elapsed_ms",totalElapsed)
      .put("time_source","16kHz输入样本偏移；仅在文件边界恢复，不代表词级时间或说话人边界")
     writeAtomic(stateFile,saved)
    }
    if(errorFile.exists())errorFile.delete()
    status("长音频任务完成：$next/${items.length()}")
   }catch(t:Throwable){
    Log.e("HotwordTest","long audio probe failed",t)
    try{if(base.isDirectory)writeAtomic(errorFile,JSONObject().put("status","failed").put("error",t.message?:t.javaClass.simpleName).put("resume","可从最后成功保存的文件边界重试"))}catch(_:Throwable){}
    status("长音频任务中断：${t.message?:t.javaClass.simpleName}")
   }finally{
    try{speaker?.close()}catch(_:Throwable){};try{engine?.release()}catch(_:Throwable){}
    running.set(false);activity.runOnUiThread{activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
   }
  }.start()
 }
}
