package org.hotwordtest

import android.app.Activity
import android.util.Log
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Local, fixed-input comparison. VAD intervals are observations, not reference labels. */
object VadComparisonProbe {
 fun run(activity:Activity) {
  Thread {
   val destination=File(activity.getExternalFilesDir(null),"vad-comparison-result.json")
   try {
    val input=mutableListOf<Pair<String,FloatArray>>()
    ZipInputStream(File(activity.getExternalFilesDir(null),"vad-comparison.zip").inputStream().buffered()).use { zip ->
     while(true) {val entry=zip.nextEntry?:break
      if(!entry.isDirectory&&entry.name.lowercase().endsWith(".wav")) {
       require(input.size<100){"测试音频数量过多"};val out=ByteArrayOutputStream();val buf=ByteArray(32768);var total=0
       while(true){val n=zip.read(buf);if(n<0)break;total+=n;require(total<=50*1024*1024){"WAV过大"};out.write(buf,0,n)}
       input.add(entry.name to WavReader.read16k(out.toByteArray().inputStream()))
      };zip.closeEntry()
     }
    }
    require(input.isNotEmpty()){ "没有测试音频" };input.sortBy{it.first};val runs=JSONArray()
    for(key in listOf(VadModelFactory.SILERO,VadModelFactory.TEN,VadModelFactory.FSMN)) {
     val prepareAt=SystemClock.elapsedRealtimeNanos()
     var prepareError:Throwable?=null
     if(key==VadModelFactory.FSMN) try{FsmnVad.configure(activity)}catch(e:Throwable){prepareError=e}
     val prepareMs=(SystemClock.elapsedRealtimeNanos()-prepareAt)/1000000.0
     val results=JSONArray()
     for((name,samples) in input) {
      val item=try {prepareError?.let{throw it};VadOnlyTest.detect(activity.assets,samples,key).put("file",name)}
       catch(e:Throwable){Log.e("HotwordTest","VAD comparison failed",e);JSONObject().put("file",name).put("status","failed").put("error",e.message?:e.javaClass.simpleName)}
      results.put(item)
     }
     runs.put(JSONObject().put("vad_model",VadModelFactory.name(key)).put("model_prepare_ms",if(key==VadModelFactory.FSMN)prepareMs else JSONObject.NULL).put("items",results))
    }
    destination.writeText(JSONObject().put("status","completed").put("runtime","Android CPU").put("sample_rate",16000).put("runs",runs)
     .put("reference_status","未提供逐帧人工标注，模型输出不代表准确率").toString())
   } catch(e:Throwable){Log.e("HotwordTest","VAD comparison probe failed",e);destination.writeText(JSONObject().put("status","failed").put("error",e.message?:e.javaClass.simpleName).toString())}
  }.start()
 }
}
