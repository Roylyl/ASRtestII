package org.hotwordtest

import android.app.Activity
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Local, file-boundary resumable VAD + diarization + ASR experiment. */
object AllDayBatchProbe {
 private const val RATE = 16000
 private const val MIN_SINGLE_SAMPLES = RATE
 private const val PIPELINE = "allday-v4-silero-pyannote-campplus-xasr1920-decisions"
 private val running = AtomicBoolean(false)
 private data class Item(val file:String,val samples:Long,val startWallMs:Long)
 private data class Interval(val start:Int,val end:Int)

 private fun writeAtomic(path:File,json:JSONObject) {
  require(path.parentFile?.let{it.isDirectory || it.mkdirs()}==true){"无法创建结果目录"}
  val atomic=AtomicFile(path); val out=atomic.startWrite()
  try { out.write(json.toString().toByteArray(Charsets.UTF_8)); out.flush(); out.fd.sync(); atomic.finishWrite(out) }
  catch(t:Throwable){ atomic.failWrite(out); throw t }
 }
 private fun readAtomic(path:File):JSONObject? {
  if(!path.exists()&&!File(path.path+".bak").exists())return null
  return JSONObject(AtomicFile(path).openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
 }

 /** Calls remain on the main thread; decoding and speaker comparison run on a worker. */
 fun start(activity:Activity,runId:String,ensureModel:()->File,status:(String)->Unit){
  if(!runId.matches(Regex("[A-Za-z0-9_-]{1,80}"))){status("任务编号无效");return}
  if(!running.compareAndSet(false,true)){status("已有全天音频任务正在运行");return}
  activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  Thread {
   val base=File(activity.getExternalFilesDir(null),"allday-probe/$runId")
   val resultsDir=File(base,"results")
   val publicProgress=File(base,"progress.json")
   // Speaker vectors are kept in app-private storage, never in the exported progress or results.
   val checkpoint=File(activity.filesDir,"allday-state/$runId.json")
   var session:TimeAlignedAsr.Session?=null
   var tracker:LocalSpeakerTracker?=null
   var next=0; var total=0; var elapsedTotal=0L; var current=""; var failure:String?=null;var authorizedWrite=false
   fun publish(state:String){
    if(!base.isDirectory||!authorizedWrite)return
    val p=JSONObject().put("status",state).put("run_id",runId).put("pipeline",PIPELINE)
     .put("next_index",next).put("completed",next).put("total_files",total).put("current_file",current)
     .put("completed_elapsed_ms",elapsedTotal).put("updated_ms",System.currentTimeMillis())
    if(failure!=null)p.put("error",failure)
    writeAtomic(publicProgress,p)
   }
   try {
    require(base.isDirectory){"任务目录不存在"}
    val manifestText=File(base,"manifest.json").readText(Charsets.UTF_8)
    val manifest=JSONObject(manifestText);require(manifest.getInt("sample_rate")==RATE){"采样率不是16kHz"}
    val entries=manifest.getJSONArray("items");require(entries.length() in 1..1000){"文件数量无效"}
    val items=mutableListOf<Item>();val names=mutableSetOf<String>()
    var previousWall=-1L
    for(i in 0 until entries.length()){
     val e=entries.getJSONObject(i);val file=e.getString("file");val count=e.getLong("samples");val wall=e.getLong("start_wall_ms")
     require(file.matches(Regex("[A-Za-z0-9_-]+\\.wav")) && names.add(file)){"文件名无效或重复"}
     require(count in 1..(RATE*600L) && wall>=0 && wall>=previousWall){"音频长度或时间顺序无效：$file"}
     require(File(base,file).isFile){"缺少输入音频：$file"}
     previousWall=wall;items.add(Item(file,count,wall))
    }
    total=items.size
    val saved=readAtomic(checkpoint)
    require(resultsDir.mkdirs()||resultsDir.isDirectory){"无法创建结果目录"}
    if(saved==null){
     require(resultsDir.listFiles()?.none{it.name.matches(Regex("[0-9]{4}\\.json"))}!=false){"已有结果但缺少私有断点，拒绝覆盖"}
    }else{
     require(saved.optString("pipeline")==PIPELINE && saved.optString("run_id")==runId &&
      saved.optString("manifest_text")==manifestText && saved.optDouble("speaker_threshold")==LocalSpeakerTracker.EXPERIMENTAL_THRESHOLD)
      {"断点与清单或模型配置不一致"}
     next=saved.getInt("next_index");elapsedTotal=saved.optLong("completed_elapsed_ms",0L)
     require(next in 0..total){"断点进度无效"}
     if(next>0)require(File(resultsDir,String.format(java.util.Locale.ROOT,"%04d.json",next-1)).isFile){"断点指向的结果缺失"}
    }
    authorizedWrite=true
    if(next==total){current="";publish("completed");status("全天音频已完成：$next/$total");return@Thread}
    publish("running")
    val model=ensureModel()
    val active=TimeAlignedAsr.Session(activity.assets,model,SpeakerModels.CAM,hotwords="",vadKey=VadModelFactory.SILERO)
    session=active
    val speaker=LocalSpeakerTracker(activity.assets,SpeakerModels.CAM);tracker=speaker
    if(saved!=null && next>0)speaker.restore(saved.optJSONArray("speaker_state")?:error("断点缺少声纹状态"))
    while(next<total){
     val item=items[next];current=item.file;publish("running");status("全天音频${next+1}/$total：${item.file}")
     val t0=SystemClock.elapsedRealtime()
     val samples=File(base,item.file).inputStream().use{WavReader.read16k(it)}
     require(samples.size.toLong()==item.samples){"音频帧数与清单不符：${item.file}"}
     // Context is intentionally omitted: enrolled personal names are not consulted.
     val result=active.process(samples)
     require(result.optString("status")=="completed"){"转写未完成：${item.file}"}
     val turns=result.getJSONArray("speaker_turn_transcripts")
     val intervals=linkedMapOf<Int,MutableList<Interval>>()
     for(i in 0 until turns.length()){
      val turn=turns.getJSONObject(i);val ids=turn.getJSONArray("speaker_candidates")
      val a=turn.getLong("start_sample").coerceIn(0,samples.size.toLong()).toInt()
      val b=turn.getLong("end_sample").coerceIn(a.toLong(),samples.size.toLong()).toInt()
      if(ids.length()==1 && b>a)intervals.getOrPut(ids.getInt(0)){mutableListOf()}.add(Interval(a,b))
     }
     val maps=linkedMapOf<Int,JSONObject>()
     // Process local speakers by first occurrence so the global candidate labels are reproducible.
     for((id,ranges) in intervals.entries.sortedBy{it.value.first().start}){
      val length=ranges.sumOf{it.end-it.start}
      val map=JSONObject().put("local_speaker_id",id).put("single_speaker_samples",length)
      if(length<MIN_SINGLE_SAMPLES){
       map.put("global_speaker_id",JSONObject.NULL).put("global_speaker_status","insufficient_single_speaker_audio")
      }else{
       val speech=FloatArray(length);var pos=0
       for(span in ranges){samples.copyInto(speech,pos,span.start,span.end);pos+=span.end-span.start}
       val assigned=speaker.assignConservative(speech);val candidate=assigned.opt("speaker_id")?:JSONObject.NULL
       val rawStatus=assigned.optString("speaker_status")
       map.put("global_speaker_id",candidate)
        .put("global_speaker_status",if(candidate!=JSONObject.NULL && rawStatus=="experimental_segment_assignment")"assigned_candidate" else rawStatus)
        .put("speaker_similarity",assigned.opt("similarity_to_previous")?:JSONObject.NULL)
        .put("speaker_threshold",assigned.opt("threshold")?:LocalSpeakerTracker.EXPERIMENTAL_THRESHOLD)
        .put("speaker_candidate_margin",assigned.opt("candidate_margin")?:JSONObject.NULL)
        .put("speaker_candidate_kind",assigned.opt("candidate_kind")?:JSONObject.NULL)
      }
      maps[id]=map
     }
     for(i in 0 until turns.length()){
      val turn=turns.getJSONObject(i);val ids=turn.getJSONArray("speaker_candidates")
      val map=if(ids.length()==1)maps[ids.getInt(0)] else null
      val id=map?.opt("global_speaker_id")?:JSONObject.NULL
      val turnStatus=when {ids.length()==0->"no_speaker_match";ids.length()>1->"ambiguous_overlap";map==null->"insufficient_single_speaker_audio";else->map.optString("global_speaker_status")}
      turn.put("global_speaker_id",id).put("global_speaker_status",turnStatus)
       .put("start_wall_ms",item.startWallMs+turn.getLong("start_ms"))
       .put("end_wall_ms",item.startWallMs+turn.getLong("end_ms"))
     }
     val mapArray=JSONArray();maps.values.forEach{mapArray.put(it)}
     result.put("global_speaker_map",mapArray)
      .put("global_speaker_scope","跨文件实验性候选，仅使用此前已形成的组；短或低电平语音只作保守候选，重叠保持未知；未校准或确认身份")
      .put("original_file_start_wall_ms",item.startWallMs)
     val elapsed=SystemClock.elapsedRealtime()-t0
     val record=JSONObject().put("schema_version",1).put("status","completed").put("run_id",runId)
      .put("pipeline",PIPELINE).put("index",next).put("file",item.file).put("samples",item.samples)
      .put("sample_rate",RATE).put("start_wall_ms",item.startWallMs).put("elapsed_ms",elapsed).put("result",result)
     writeAtomic(File(resultsDir,String.format(java.util.Locale.ROOT,"%04d.json",next)),record)
     val checkpointValue=JSONObject().put("pipeline",PIPELINE).put("run_id",runId).put("manifest_text",manifestText)
      .put("next_index",next+1).put("total_files",total).put("speaker_threshold",LocalSpeakerTracker.EXPERIMENTAL_THRESHOLD)
      .put("speaker_state",speaker.snapshot()).put("completed_elapsed_ms",elapsedTotal+elapsed)
     writeAtomic(checkpoint,checkpointValue)
     next++;elapsedTotal+=elapsed
     publish("running")
    }
    current="";publish("completed");status("全天音频处理完成：$next/$total")
   }catch(t:Throwable){
    failure=t.message?:t.javaClass.simpleName
    Log.e("AllDayBatchProbe","批处理失败",t)
    try{publish("interrupted")}catch(_:Throwable){}
    status("全天音频处理中断：$failure，已保存结果可续跑")
   }finally{
    try{tracker?.close()}catch(_:Throwable){};try{session?.close()}catch(_:Throwable){}
    running.set(false);activity.runOnUiThread{activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
   }
  }.start()
 }
}
