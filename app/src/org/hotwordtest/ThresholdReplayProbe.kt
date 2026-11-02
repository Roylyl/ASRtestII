package org.hotwordtest

import android.app.Activity
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Experimental offline replay over previously detected speaker intervals. */
object ThresholdReplayProbe {
 private const val RATE=16000
 private const val MIN_SAMPLES=RATE*2
 private const val SOURCE_RUN="allday-20261030"
 private const val RUN="allday-threshold-20261030"
 private val running=AtomicBoolean(false)
 private val thresholds=listOf(0.35,0.40,0.45,0.50,0.55,0.60,0.65)
 private data class Span(val start:Int,val end:Int)

 private fun save(path:File,obj:JSONObject){
  require(path.parentFile?.let{it.isDirectory||it.mkdirs()}==true){"无法创建输出目录"}
  val atomic=AtomicFile(path);val stream=atomic.startWrite()
  try{stream.write(obj.toString().toByteArray(Charsets.UTF_8));stream.flush();stream.fd.sync();atomic.finishWrite(stream)}
  catch(e:Throwable){atomic.failWrite(stream);throw e}
 }
 private fun load(path:File):JSONObject?{
  if(!path.exists()&&!File(path.path+".bak").exists())return null
  return JSONObject(AtomicFile(path).openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
 }
 private fun idFile(i:Int)=String.format(Locale.ROOT,"%04d.json",i)

 /** Reproduces LocalSpeakerTracker.assignEmbedding without loading seven native models. */
 private class Replay(private val threshold:Double){
  private val refs=mutableListOf<FloatArray>();private val counts=mutableListOf<Int>()
  fun assign(vector:FloatArray):JSONObject {
   val scores=refs.map{ref->ref.indices.sumOf{(ref[it]*vector[it]).toDouble()}}
   val idx=if(scores.isEmpty())-1 else scores.indices.maxByOrNull{scores[it]}!!
   if(idx>=0&&scores[idx]<threshold&&refs.size>=32)
    return JSONObject().put("speaker_id",JSONObject.NULL).put("status","speaker_limit_reached").put("similarity",scores[idx])
   val chosen=if(idx<0||scores[idx]<threshold){refs.add(vector);counts.add(1);refs.size-1}else{
    val count=counts[idx];val old=refs[idx]
    val mean=FloatArray(vector.size){(old[it]*count+vector[it])/(count+1)}
    val n=sqrt(mean.sumOf{it.toDouble()*it}.toFloat())
    refs[idx]=FloatArray(mean.size){mean[it]/n};counts[idx]=count+1;idx
   }
   return JSONObject().put("speaker_id","S${chosen+1}").put("status","assigned_candidate")
    .put("similarity",if(idx<0)JSONObject.NULL else scores[idx])
  }
 }

 fun start(activity:Activity,status:(String)->Unit){
  if(!running.compareAndSet(false,true)){status("阈值测试正在运行");return}
  activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  Thread {
   val source=File(activity.getExternalFilesDir(null),"allday-probe/$SOURCE_RUN")
   val output=File(activity.getExternalFilesDir(null),"threshold-replay/$RUN")
   val privateCache=File(activity.filesDir,"threshold-replay/$RUN/features")
   val progress=File(output,"progress.json")
   var tracker:LocalSpeakerTracker?=null;var completed=0;var total=0;var current="";var failure:String?=null
   val start=SystemClock.elapsedRealtime()
   fun publish(state:String,phase:String){
    val value=JSONObject().put("status",state).put("phase",phase).put("run_id",RUN)
     .put("source_run_id",SOURCE_RUN).put("completed_files",completed).put("total_files",total)
     .put("current_file",current).put("updated_ms",System.currentTimeMillis())
     .put("session_elapsed_ms",SystemClock.elapsedRealtime()-start)
    if(failure!=null)value.put("error",failure)
    save(progress,value)
   }
   try {
    val manifestText=File(source,"manifest.json").readText(Charsets.UTF_8)
    val manifest=JSONObject(manifestText);require(manifest.getInt("sample_rate")==RATE){"采样率不匹配"}
    val items=manifest.getJSONArray("items");total=items.length();require(total in 1..1000){"文件数量无效"}
    val config=File(privateCache.parentFile,"configuration.json")
    val previous=load(config)
    if(previous==null)save(config,JSONObject().put("source_run_id",SOURCE_RUN).put("manifest_text",manifestText)
      .put("model","campplus").put("min_samples",MIN_SAMPLES).put("rms_min",0.003))
    else require(previous.optString("manifest_text")==manifestText&&previous.optString("source_run_id")==SOURCE_RUN&&
      previous.optString("model")=="campplus"&&previous.optInt("min_samples")==MIN_SAMPLES&&previous.optDouble("rms_min")==0.003){"缓存配置不匹配"}
    val extractor=LocalSpeakerTracker(activity.assets,SpeakerModels.CAM);tracker=extractor
    for(index in 0 until total){
     val item=items.getJSONObject(index);val name=item.getString("file");val count=item.getLong("samples")
     require(name.matches(Regex("[A-Za-z0-9_-]+\\.wav"))&&count in 1..(RATE*600L)){"音频清单无效"}
     current=name
     val record=load(File(source,"results/${idFile(index)}"))?:error("缺少原结果：$index")
     require(record.optString("run_id")==SOURCE_RUN&&record.optInt("index",-1)==index&&
      record.optString("file")==name&&record.optLong("samples",-1)==count&&record.optString("status")=="completed"){"原结果与清单不匹配：$index"}
     val turns=record.getJSONObject("result").getJSONArray("speaker_turn_transcripts")
     val cachePath=File(privateCache,idFile(index));val cached=load(cachePath)
     if(cached!=null){
      require(cached.optString("file")==name&&cached.optInt("index",-1)==index&&cached.optLong("samples",-1)==count){"声纹缓存不匹配：$index"}
     }else{
      val samples=File(source,name).inputStream().use{WavReader.read16k(it)}
      require(samples.size.toLong()==count){"音频帧数不匹配：$name"}
      val groups=linkedMapOf<Int,MutableList<Span>>()
      for(i in 0 until turns.length()){
       val t=turns.getJSONObject(i);val ids=t.getJSONArray("speaker_candidates")
       val a=t.getLong("start_sample").coerceIn(0,count).toInt();val b=t.getLong("end_sample").coerceIn(a.toLong(),count).toInt()
       if(ids.length()==1&&b>a)groups.getOrPut(ids.getInt(0)){mutableListOf()}.add(Span(a,b))
      }
      val entries=JSONArray()
      for((id,spans) in groups.entries.sortedBy{it.value.first().start}){
       val size=spans.sumOf{it.end-it.start};val entry=JSONObject().put("local_speaker_id",id).put("samples",size)
       if(size<MIN_SAMPLES)entry.put("status","insufficient_single_speaker_audio")
       else {
        val speech=FloatArray(size);var position=0
        for(span in spans){samples.copyInto(speech,position,span.start,span.end);position+=span.end-span.start}
        val vector=extractor.embedding(speech)
        if(vector==null)entry.put("status","insufficient_audio") else {
         val values=JSONArray();vector.forEach{values.put(it.toDouble())}
         entry.put("status","completed").put("vector",values)
        }
       }
       entries.put(entry)
      }
      save(cachePath,JSONObject().put("index",index).put("file",name).put("samples",count).put("features",entries))
     }
     completed=index+1;publish("running","embedding")
    }
    extractor.close();tracker=null
    val clusterers=thresholds.map{Replay(it)};val outputSegments=thresholds.map{JSONArray()}
    var mergedOffset=0L;var controlCompared=0;var controlDifferences=0;val mismatches=JSONArray()
    for(index in 0 until total){
     val item=items.getJSONObject(index);val name=item.getString("file");val count=item.getLong("samples")
     val record=load(File(source,"results/${idFile(index)}"))?:error("原结果缺失")
     val result=record.getJSONObject("result");val turns=result.getJSONArray("speaker_turn_transcripts")
     val features=(load(File(privateCache,idFile(index)))?:error("声纹缓存缺失")).getJSONArray("features")
     val lookup=thresholds.map{mutableMapOf<Int,JSONObject>()}
     for(i in 0 until features.length()){
      val entry=features.getJSONObject(i);val id=entry.getInt("local_speaker_id");val state=entry.getString("status")
      if(state=="completed"){
       val v=entry.getJSONArray("vector");val vector=FloatArray(v.length()){v.getDouble(it).toFloat()}
       thresholds.indices.forEach{ti->lookup[ti][id]=clusterers[ti].assign(vector)}
      } else thresholds.indices.forEach{ti->lookup[ti][id]=JSONObject().put("speaker_id",JSONObject.NULL).put("status",state)}
     }
     for(i in 0 until turns.length()){
      val turn=turns.getJSONObject(i);val ids=turn.getJSONArray("speaker_candidates")
      val local=if(ids.length()==1)ids.getInt(0) else null
      val a=turn.getLong("start_sample");val b=turn.getLong("end_sample")
      val original=turn.opt("global_speaker_id")?:JSONObject.NULL
      thresholds.indices.forEach {ti->
       val assigned=if(local==null)null else lookup[ti][local]
       val speakerId=assigned?.opt("speaker_id")?:JSONObject.NULL
       val speakerStatus=if(assigned!=null)assigned.optString("status") else if(ids.length()==0)"no_speaker_match" else "ambiguous_overlap"
       outputSegments[ti].put(JSONObject().put("file_index",index).put("turn_index",i)
        .put("local_speaker_id",local?:JSONObject.NULL)
        .put("merged_start_ms",(mergedOffset+a)*1000L/RATE).put("merged_end_ms",(mergedOffset+b)*1000L/RATE)
        .put("start_wall_ms",item.getLong("start_wall_ms")+a*1000L/RATE)
        .put("end_wall_ms",item.getLong("start_wall_ms")+b*1000L/RATE)
        .put("speaker_id",speakerId).put("status",speakerStatus)
        .put("similarity",assigned?.opt("similarity")?:JSONObject.NULL))
       if(thresholds[ti]==0.45){
        controlCompared++
        val old=if(original==JSONObject.NULL)null else original.toString()
        val new=if(speakerId==JSONObject.NULL)null else speakerId.toString()
        val oldState=turn.optString("global_speaker_status")
        if(old!=new||oldState!=speakerStatus){controlDifferences++;if(mismatches.length()<20)mismatches.put(JSONObject().put("file_index",index).put("turn_index",i).put("old_id",old?:JSONObject.NULL).put("new_id",new?:JSONObject.NULL).put("old_status",oldState).put("new_status",speakerStatus))}
       }
      }
     }
     mergedOffset+=count
    }
    for(ti in thresholds.indices){
     val value=thresholds[ti]
     val suffix=String.format(Locale.ROOT,"t%03d.json",(value*100).toInt())
     save(File(output,suffix),JSONObject().put("status","completed").put("run_id",RUN).put("source_run_id",SOURCE_RUN)
      .put("threshold",value).put("sample_rate",RATE).put("total_files",total).put("segments",outputSegments[ti])
      .put("control_compared",controlCompared).put("control_differences",controlDifferences).put("control_mismatches",mismatches)
      .put("note","基于同一组CAM++特征重放在线聚类；原单人段少于2秒、低电平、无局部说话人及重叠不赋编号。"))
    }
    current="";publish("completed","replay");status("阈值测试完成：$total/$total")
   }catch(t:Throwable){failure=t.message?:t.javaClass.simpleName;Log.e("ThresholdReplayProbe","阈值测试失败",t);try{publish("interrupted","failed")}catch(_:Throwable){};status("阈值测试中断：$failure")}
   finally{try{tracker?.close()}catch(_:Throwable){};running.set(false);activity.runOnUiThread{activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}}
  }.start()
 }
}
