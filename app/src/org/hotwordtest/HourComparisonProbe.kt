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

/** Internal offline experiment. Completed files, rather than a mutable checkpoint, are authoritative. */
object HourComparisonProbe {
 private val running=AtomicBoolean(false)
 private const val RATE=16000
 private const val MAX_SAMPLES=RATE*60L
 private const val PIPELINE="hour-v1-xasr1920-greedy-pyannote-auto-speakerturn"
 private val SAFE_FILE=Regex("[A-Za-z0-9_-]+\\.wav")
 private data class Item(val name:String,val start:Long,val sourceStart:Long,val count:Long)
 private data class CachedDiarization(val value:JSONObject,val sourceGroup:String,
                                     val priorProcessMs:Double,val audioModifiedMs:Long,
                                     val sourceResultModifiedMs:Long)

 private fun writeAtomic(path:File,json:JSONObject){
  val atomic=AtomicFile(path);val out=atomic.startWrite()
  try{out.write(json.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(out)}
  catch(t:Throwable){atomic.failWrite(out);throw t}
 }
 private fun readAtomic(path:File):JSONObject?{
  val atomic=AtomicFile(path)
  if(!path.exists()&&!File(path.path+".bak").exists())return null
  return JSONObject(atomic.openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
 }

 /** Launch only from the UI thread; the supplied model directory must contain the X-ASR assets. */
 fun start(activity:Activity,runId:String,vadKey:String,speakerKey:String,ensureModel:()->File,status:(String)->Unit){
  if(!runId.matches(Regex("[A-Za-z0-9_-]{1,80}"))){status("批测编号无效");return}
  if(vadKey !in listOf(VadModelFactory.SILERO,VadModelFactory.TEN,VadModelFactory.FSMN)) {status("VAD参数无效");return}
  if(speakerKey !in listOf(SpeakerModels.CAM,SpeakerModels.ERES)){status("声纹模型参数无效");return}
  if(!running.compareAndSet(false,true)){status("已有批测任务正在运行");return}
  activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  Thread {
   val base=File(activity.getExternalFilesDir(null),"hour-compare/$runId")
   val outDir=File(base,"results/${vadKey}_${speakerKey}")
   val configFile=File(outDir,"configuration.json")
   val progressFile=File(outDir,"progress.json")
   var session:TimeAlignedAsr.Session?=null
   var done=0;var failed=0;var total=0;var current="";var fatal:String?=null
   fun saveProgress(state:String){
    if(!outDir.isDirectory)return
    val data=JSONObject().put("status",state).put("run_id",runId).put("vad_key",vadKey)
     .put("speaker_key",speakerKey).put("completed",done).put("failed_this_attempt",failed)
     .put("total",total).put("current_file",current).put("updated_ms",System.currentTimeMillis())
    if(fatal!=null)data.put("error",fatal)
    writeAtomic(progressFile,data)
   }
   try{
    require(base.isDirectory){"任务目录不存在"}
    val manifestText=File(base,"manifest.json").readText(Charsets.UTF_8)
    val manifest=JSONObject(manifestText)
    require(manifest.getInt("sample_rate")==RATE){"清单采样率不是16kHz"}
    val entries=manifest.getJSONArray("items");require(entries.length() in 1..1000){"清单条数无效"}
    val items=mutableListOf<Item>();val unique=mutableSetOf<String>()
    for(i in 0 until entries.length()){
     val entry=entries.getJSONObject(i);val file=entry.getString("file")
     val start=entry.getLong("start_sample");val sourceStart=entry.getLong("source_start_sample");val count=entry.getLong("samples")
     require(SAFE_FILE.matches(file)&&unique.add(file)){"WAV名称无效或重复：$file"}
     require(start>=0 && sourceStart>=0 && count in 1..MAX_SAMPLES && start<=Long.MAX_VALUE-count && sourceStart<=Long.MAX_VALUE-count){"时间或采样数无效：$file"}
     require(File(base,file).isFile){"缺少音频：$file"}
     items.add(Item(file,start,sourceStart,count))
    }
    total=items.size
    require(outDir.mkdirs()||outDir.isDirectory){"无法创建结果目录"}
    val expected=JSONObject().put("schema_version",1).put("pipeline",PIPELINE)
     .put("run_id",runId).put("vad_key",vadKey).put("speaker_key",speakerKey)
     .put("sample_rate",RATE).put("asr_model","X-ASR1920ms")
     .put("decoding","greedy_search").put("hotwords",JSONArray())
     .put("speaker_identity_scope","anonymous_per_file")
     .put("manifest_text",manifestText)
    val previous=readAtomic(configFile)
    if(previous==null)writeAtomic(configFile,expected)
    else for(key in listOf("schema_version","pipeline","run_id","vad_key","speaker_key","sample_rate","asr_model","decoding","speaker_identity_scope","manifest_text"))
     require(previous.get(key).toString()==expected.get(key).toString()){ "配置或清单已更改，拒绝续跑：$key" }
    fun resultFile(index:Int)=File(outDir,String.format(java.util.Locale.ROOT,"%04d.json",index))
    fun isSaved(index:Int,item:Item):Boolean{
     val result=readAtomic(resultFile(index))?:return false
     require(result.optString("status")=="completed" && result.optString("pipeline")==PIPELINE &&
      result.optString("run_id")==runId && result.optString("vad_key")==vadKey &&
      result.optString("speaker_key")==speakerKey && result.optInt("index",-1)==index &&
      result.optString("file")==item.name && result.optLong("start_sample",-1L)==item.start && result.optLong("source_start_sample",-1L)==item.sourceStart &&
      result.optLong("samples",-1L)==item.count && result.optJSONObject("result")?.optString("status")=="completed")
      {"已有结果与任务不匹配，拒绝覆盖：${item.name}"}
     return true
    }
    // Old results were saved without a PCM content fingerprint. These checks establish
    // matching identity and a filesystem-time precondition, not byte-for-byte proof.
    fun cachedDiarization(index:Int,item:Item):CachedDiarization? {
     if(vadKey==VadModelFactory.SILERO)return null
     val sourceGroup="${VadModelFactory.SILERO}_${speakerKey}"
     val sourceDir=File(base,"results/$sourceGroup")
     val sourceFile=File(sourceDir,String.format(java.util.Locale.ROOT,"%04d.json",index))
     try {
      val config=readAtomic(File(sourceDir,"configuration.json"))?:return null
      require(config.getInt("schema_version")==1 && config.getString("pipeline")==PIPELINE &&
       config.getString("run_id")==runId && config.getString("vad_key")==VadModelFactory.SILERO &&
       config.getString("speaker_key")==speakerKey && config.getInt("sample_rate")==RATE &&
       config.getString("asr_model")=="X-ASR1920ms" && config.getString("decoding")=="greedy_search" &&
       config.getString("speaker_identity_scope")=="anonymous_per_file" && config.getString("manifest_text")==manifestText &&
       config.getJSONArray("hotwords").length()==0){"来源配置不匹配"}
      val old=readAtomic(sourceFile)?:return null
      require(old.getString("status")=="completed" && old.getString("pipeline")==PIPELINE &&
       old.getString("run_id")==runId && old.getString("vad_key")==VadModelFactory.SILERO &&
       old.getString("speaker_key")==speakerKey && old.getInt("index")==index &&
       old.getString("file")==item.name && old.getLong("start_sample")==item.start &&
       old.getLong("source_start_sample")==item.sourceStart && old.getLong("samples")==item.count &&
       old.getInt("sample_rate")==RATE){"来源片段身份不匹配"}
      val result=old.getJSONObject("result")
      require(result.getString("status")=="completed" && result.getString("vad_model")==VadModelFactory.name(VadModelFactory.SILERO) &&
       result.getString("asr_model")=="X-ASR1920ms" && result.getString("decoding")=="greedy_search" &&
       result.getInt("sample_rate")==RATE && result.getLong("duration_ms")==item.count*1000/RATE){"来源转写配置不匹配"}
      val diar=result.getJSONObject("diarization");val cluster=diar.getJSONObject("clustering")
      require(diar.getString("status")=="completed" && diar.getString("speaker_model")==SpeakerModels.name(speakerKey) &&
       diar.getString("segmentation_model")=="models/pyannote-segmentation.onnx" &&
       diar.getInt("sample_rate")==RATE && diar.getDouble("audio_seconds")==item.count.toDouble()/RATE &&
       diar.getInt("num_threads")==2 && cluster.getString("mode")=="automatic" && cluster.getInt("num_clusters")==-1 &&
       cluster.getDouble("threshold")==0.5){"来源说话人配置不匹配"}
      val priorMs=diar.getDouble("process_ms");require(priorMs.isFinite() && priorMs>=0){"来源耗时无效"}
      val segments=diar.getJSONArray("segments");val duration=item.count.toDouble()/RATE
      for(i in 0 until segments.length()){
       val s=segments.getJSONObject(i);val a=s.getDouble("start_seconds");val b=s.getDouble("end_seconds")
       require(a.isFinite() && b.isFinite() && a>=0 && b>a && b<=duration && s.getInt("speaker_id")>=0){"来源说话人时间区间无效"}
      }
      val audioModified=File(base,item.name).lastModified();val sourceModified=sourceFile.lastModified()
      require(audioModified>0 && sourceModified>0 && audioModified<=sourceModified){"音频修改时间晚于来源结果或不可读取"}
      return CachedDiarization(diar,sourceGroup,priorMs,audioModified,sourceModified)
     } catch(e:Exception){Log.w("HourComparisonProbe","说话人缓存不可用，重新计算：${item.name}: ${e.message}");return null}
    }
    val saved=BooleanArray(total){i->isSaved(i,items[i])};done=saved.count{it}
    saveProgress(if(done==total)"completed" else "running")
    if(done==total){status("批测已完成：$vadKey/$speakerKey $done/$total");return@Thread}
    if(vadKey==VadModelFactory.FSMN)FsmnVad.configure(activity)
    status("批测加载模型：$vadKey/$speakerKey，已完成$done/$total")
    val model=ensureModel()
    val initStarted=SystemClock.elapsedRealtimeNanos()
    val active=TimeAlignedAsr.Session(activity.assets,model,speakerKey,hotwords="",vadKey=vadKey,
     lazyDiarization=vadKey!=VadModelFactory.SILERO)
    session=active
    val sessionInit=(SystemClock.elapsedRealtimeNanos()-initStarted)/1000000.0
    for(index in items.indices){
     if(saved[index])continue
     val item=items[index];current=item.name
     status("批测$vadKey/$speakerKey：${done+1}/$total ${item.name}")
     saveProgress("running")
     try{
      val start=SystemClock.elapsedRealtimeNanos()
      val cached=cachedDiarization(index,item)
      val samples=File(base,item.name).inputStream().use{WavReader.read16k(it)}
      require(samples.size.toLong()==item.count){"音频样本数与清单不一致：${item.name}"}
      val result=active.process(samples,cachedDiarization=cached?.value,cachedSourceGroup=cached?.sourceGroup)
      require(result.optString("status")=="completed"){"结果未完成：${item.name}"}
      val actualMs=(SystemClock.elapsedRealtimeNanos()-start)/1000000.0
      val record=JSONObject().put("status","completed").put("pipeline",PIPELINE)
       .put("run_id",runId).put("vad_key",vadKey).put("speaker_key",speakerKey)
       .put("index",index).put("file",item.name).put("start_sample",item.start).put("source_start_sample",item.sourceStart)
       .put("samples",item.count).put("sample_rate",RATE)
       .put("coordinate_note","结果时间为当前文件相对时间；拼接时间=清单start_sample/16000加相对时间，原始播客时间=source_start_sample/16000加相对时间。说话人编号仅本文件有效。")
       .put("session_init_ms",sessionInit).put("file_elapsed_ms",actualMs)
       .put("diarization_source",if(cached==null)"computed" else "reused")
       .put("diarization_cache_source_group",cached?.sourceGroup?:JSONObject.NULL)
       .put("diarization_prior_process_ms",cached?.priorProcessMs?:JSONObject.NULL)
       .put("estimated_uncached_file_elapsed_ms",if(cached==null)JSONObject.NULL else actualMs+cached.priorProcessMs)
       .put("estimated_uncached_note",if(cached==null)JSONObject.NULL else "估算=本次文件处理耗时＋来源任务此前切分耗时；不同时间的测量相加，非本组合完整链路实测，不含模型初始化。")
       .put("cache_input_identity_note",if(cached==null)JSONObject.NULL else "基于相同清单、路径、采样数及音频修改时间不晚于来源结果；旧结果无历史PCM内容凭证，不能证明字节完全一致。")
       .put("cache_audio_modified_ms",cached?.audioModifiedMs?:JSONObject.NULL)
       .put("cache_source_result_modified_ms",cached?.sourceResultModifiedMs?:JSONObject.NULL)
       .put("result",result)
      writeAtomic(resultFile(index),record);done++;saved[index]=true
     }catch(t:Exception){
      failed++
      val errorDir=File(outDir,"errors");require(errorDir.mkdirs()||errorDir.isDirectory){"无法创建错误目录"}
      val name=String.format(java.util.Locale.ROOT,"%04d-%d.json",index,System.currentTimeMillis())
      writeAtomic(File(errorDir,name),JSONObject().put("status","failed").put("run_id",runId)
       .put("vad_key",vadKey).put("speaker_key",speakerKey).put("index",index).put("file",item.name)
       .put("error",t.message?:t.javaClass.simpleName).put("retry","重新启动同一任务会重试此项"))
      Log.e("HourComparisonProbe","文件处理失败：${item.name}",t)
     }
     saveProgress("running")
    }
    current=""
    saveProgress(if(done==total)"completed" else "incomplete")
    status("批测$vadKey/$speakerKey：完成$done/$total，本次失败$failed")
   }catch(t:Throwable){
    fatal=t.message?:t.javaClass.simpleName
    Log.e("HourComparisonProbe","批测中断",t)
    try{saveProgress("interrupted")}catch(_:Throwable){}
    status("批测中断：$fatal；已保存项可续跑")
   }finally{
    try{session?.close()}catch(_:Throwable){}
    running.set(false)
    activity.runOnUiThread{activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
   }
  }.start()
 }
}
