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

/** Offline candidate matching. Cached embeddings stay in app-private storage. */
object OptimizedSpeakerProbe {
 private const val RATE=16000
 private const val SOURCE="allday-20261030"
 private const val BASE="allday-threshold-20261030"
 private const val RUN="allday-optimized-20261030"
 private const val MATCH=0.60
 private const val MARGIN=0.08
 private const val MAX_GAIN=6.0
 private const val TARGET_RMS=0.006
 private const val MIN_RMS=0.003
 private val running=AtomicBoolean(false)
 private data class Span(val start:Int,val end:Int)
 private fun name(i:Int)=String.format(Locale.ROOT,"%04d.json",i)
 private fun read(p:File):JSONObject?=if(!p.exists()&&!File(p.path+".bak").exists())null else JSONObject(AtomicFile(p).openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
 private fun save(p:File,o:JSONObject){
  require(p.parentFile?.let{it.isDirectory||it.mkdirs()}==true)
  val f=AtomicFile(p);val s=f.startWrite()
  try{s.write(o.toString().toByteArray(Charsets.UTF_8));s.flush();f.finishWrite(s)}catch(e:Throwable){f.failWrite(s);throw e}
 }
 private fun vec(a:JSONArray)=FloatArray(a.length()){a.getDouble(it).toFloat()}
 private fun json(v:FloatArray)=JSONArray().apply{v.forEach{put(it.toDouble())}}
 private class Cluster {
  val refs=mutableListOf<FloatArray>();private val counts=mutableListOf<Int>()
  fun add(v:FloatArray):String?{
   val scores=refs.map{r->r.indices.sumOf{(r[it]*v[it]).toDouble()}}
   val i=scores.indices.maxByOrNull{scores[it]}?:-1
   if(i<0||scores[i]<0.50){if(refs.size>=32)return null;refs.add(v);counts.add(1);return "S${refs.size}"}
   val count=counts[i];val mean=FloatArray(v.size){(refs[i][it]*count+v[it])/(count+1)}
   val n=sqrt(mean.sumOf{it.toDouble()*it}.toFloat());refs[i]=FloatArray(v.size){mean[it]/n};counts[i]=count+1;return "S${i+1}"
  }
  fun candidate(v:FloatArray):JSONObject{
   val scores=refs.mapIndexed{i,r->i to r.indices.sumOf{(r[it]*v[it]).toDouble()}}.sortedByDescending{it.second}
   val best=scores.firstOrNull();val gap=if(scores.size>1)best!!.second-scores[1].second else null
   val accepted=best!=null&&best.second>=MATCH&&(gap==null||gap>=MARGIN)
   return JSONObject().put("speaker_id",if(accepted)"S${best!!.first+1}" else JSONObject.NULL)
    .put("status",if(accepted)"weak_candidate" else "weak_candidate_rejected")
    .put("similarity",best?.second?:JSONObject.NULL).put("margin",gap?:JSONObject.NULL)
  }
 }
 fun start(activity:Activity,status:(String)->Unit){
  if(!running.compareAndSet(false,true)){status("测试正在运行");return}
  activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  Thread{
   val src=File(activity.getExternalFilesDir(null),"allday-probe/$SOURCE")
   val base=File(activity.filesDir,"threshold-replay/$BASE/features")
   val out=File(activity.getExternalFilesDir(null),"threshold-replay/$RUN")
   val cache=File(activity.filesDir,"threshold-replay/$RUN/features")
   val t0=SystemClock.elapsedRealtime();var done=0;var total=0;var current="";var extractor:LocalSpeakerTracker?=null
   fun progress(state:String,phase:String,error:String?=null){save(File(out,"progress.json"),JSONObject().put("status",state).put("phase",phase).put("completed_files",done).put("total_files",total).put("current_file",current).put("elapsed_ms",SystemClock.elapsedRealtime()-t0).put("error",error?:JSONObject.NULL))}
   try{
    val manifestText=File(src,"manifest.json").readText();val items=JSONObject(manifestText).getJSONArray("items");total=items.length()
    require(total in 1..1000)
    val config=File(cache.parentFile,"configuration.json");val previous=read(config)
    val definition=JSONObject().put("version",1).put("manifest_text",manifestText).put("min_samples",RATE).put("max_gain",MAX_GAIN).put("target_rms",TARGET_RMS).put("min_rms",MIN_RMS)
    if(previous==null)save(config,definition) else require(previous.toString()==definition.toString()){ "缓存配置不匹配" }
    val baseline=read(File(activity.getExternalFilesDir(null),"threshold-replay/$BASE/t050.json"))?:error("缺少0.50基线")
    require(baseline.getDouble("threshold")==0.50&&baseline.getInt("total_files")==total)
    val expected=mutableMapOf<Pair<Int,Int>,String?>();val baseSegments=baseline.getJSONArray("segments")
    for(i in 0 until baseSegments.length()){
     val s=baseSegments.getJSONObject(i);if(s.isNull("local_speaker_id"))continue
     val k=s.getInt("file_index") to s.getInt("local_speaker_id");val value=if(s.isNull("speaker_id"))null else s.getString("speaker_id")
     if(expected.containsKey(k))require(expected[k]==value){"基线同组编号不一致:$k"} else expected[k]=value
    }
    val tracker=LocalSpeakerTracker(activity.assets,SpeakerModels.CAM);extractor=tracker
    val cluster=Cluster()
    for(index in 0 until total){
     val item=items.getJSONObject(index);val file=item.getString("file");val count=item.getLong("samples");current=file
     require(file.matches(Regex("[A-Za-z0-9_-]+\\.wav"))&&count in 1..(RATE*600L))
     val original=read(File(src,"results/${name(index)}"))?:error("原结果缺失:$index")
     require(original.optString("run_id")==SOURCE&&original.optInt("index",-1)==index&&original.optString("file")==file&&original.optLong("samples",-1)==count&&original.optString("status")=="completed")
     val turns=original.getJSONObject("result").getJSONArray("speaker_turn_transcripts")
     val old=(read(File(base,name(index)))?:error("基线特征缺失:$index"))
     require(old.getString("file")==file&&old.getInt("index")==index&&old.getLong("samples")==count)
     val oldEntries=old.getJSONArray("features");val strong=mutableMapOf<Int,JSONObject>()
     for(i in 0 until oldEntries.length()){val e=oldEntries.getJSONObject(i);if(e.optString("status")=="completed"){
      val id=e.getInt("local_speaker_id");strong[id]=e;val k=index to id
      require(expected.containsKey(k)&&cluster.add(vec(e.getJSONArray("vector")))==expected[k]){"强样本重放与0.50基线不符:$k"}
     }}
     val path=File(cache,name(index));val existing=read(path)
     if(existing!=null){require(existing.getInt("index")==index&&existing.getString("file")==file&&existing.getLong("samples")==count)} else {
      val groups=linkedMapOf<Int,MutableList<Span>>()
      for(i in 0 until turns.length()){
       val t=turns.getJSONObject(i);val ids=t.getJSONArray("speaker_candidates");val a=t.getLong("start_sample").coerceIn(0,count).toInt();val b=t.getLong("end_sample").coerceIn(a.toLong(),count).toInt()
       if(ids.length()==1&&b>a)groups.getOrPut(ids.getInt(0)){mutableListOf()}.add(Span(a,b))
      }
      val need=groups.filter{(id,spans)->!strong.containsKey(id)&&spans.sumOf{it.end-it.start}>=RATE}
      val samples=if(need.isNotEmpty())File(src,file).inputStream().use{WavReader.read16k(it)} else null
      if(samples!=null)require(samples.size.toLong()==count)
      val entries=JSONArray()
      for((id,spans) in groups){
       val size=spans.sumOf{it.end-it.start};val e=JSONObject().put("local_speaker_id",id).put("samples",size)
       if(strong.containsKey(id))e.put("status","baseline_strong") else if(size<RATE)e.put("status","too_short") else {
        val speech=FloatArray(size);var pos=0;for(s in spans){samples!!.copyInto(speech,pos,s.start,s.end);pos+=s.end-s.start}
        val rms=sqrt(speech.sumOf{it.toDouble()*it}/size);val peak=speech.maxOf{kotlin.math.abs(it.toDouble())}
        var gain=1.0
        if(rms<MIN_RMS&&rms>0&&peak>0)gain=minOf(MAX_GAIN,TARGET_RMS/rms,0.9/peak)
        e.put("rms",rms).put("peak",peak).put("gain",gain)
        if(rms*gain<MIN_RMS){e.put("status","low_level_rejected")}else{
         if(gain>1)for(i in speech.indices)speech[i]=(speech[i]*gain).toFloat()
         val v=tracker.embedding(speech)
         if(v==null)e.put("status","embedding_failed") else e.put("status",if(gain>1)"gain_weak" else "short_weak").put("vector",json(v))
        }
       };entries.put(e)
      }
      save(path,JSONObject().put("index",index).put("file",file).put("samples",count).put("features",entries))
     }
     done=index+1;progress("running","features")
    }
    tracker.close();extractor=null
    val segments=baseline.getJSONArray("segments");val output=JSONArray();var checked=0
    val tallies=JSONObject();fun tally(key:String){tallies.put(key,tallies.optInt(key)+1)}
    var idx=-1;var byId=emptyMap<Int,JSONObject>()
    for(i in 0 until segments.length()){
     val orig=segments.getJSONObject(i);val fileIndex=orig.getInt("file_index")
     if(fileIndex!=idx){idx=fileIndex;val a=(read(File(cache,name(idx)))?:error("优化缓存缺失")).getJSONArray("features");byId=(0 until a.length()).associate{j->val e=a.getJSONObject(j);tally(e.getString("status"));e.getInt("local_speaker_id") to e}}
     val copy=JSONObject(orig.toString());val id=if(orig.isNull("local_speaker_id"))null else orig.getInt("local_speaker_id");val e=if(id==null)null else byId[id]
     if(e!=null&&orig.isNull("speaker_id")&&(e.optString("status")=="short_weak"||e.optString("status")=="gain_weak")){
      val candidate=cluster.candidate(vec(e.getJSONArray("vector")))
      copy.put("speaker_id",candidate.opt("speaker_id")?:JSONObject.NULL).put("status",candidate.getString("status")).put("similarity",candidate.opt("similarity")?:JSONObject.NULL).put("margin",candidate.opt("margin")?:JSONObject.NULL).put("candidate_kind",e.getString("status"))
     } else if(!orig.isNull("speaker_id")){checked++;require(copy.opt("speaker_id")==orig.opt("speaker_id"))}
     output.put(copy)
    }
    save(File(out,"optimized.json"),JSONObject().put("status","completed").put("run_id",RUN).put("source_run_id",SOURCE).put("threshold",0.50).put("sample_rate",RATE).put("total_files",total).put("method","offline_weak_candidate_no_center_update").put("weak_match",MATCH).put("weak_margin",MARGIN).put("max_gain",MAX_GAIN).put("target_rms",TARGET_RMS).put("min_samples",RATE).put("strong_baseline_preserved",checked).put("feature_group_counts",tallies).put("segments",output))
    current="";progress("completed","finished");status("声纹测试完成：$total/$total")
   }catch(t:Throwable){Log.e("OptimizedSpeakerProbe","failed",t);try{progress("interrupted","failed",t.message?:t.javaClass.simpleName)}catch(_:Throwable){};status("声纹测试失败：${t.message}")}
   finally{try{extractor?.close()}catch(_:Throwable){};running.set(false);activity.runOnUiThread{activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}}
  }.start()
 }
}
