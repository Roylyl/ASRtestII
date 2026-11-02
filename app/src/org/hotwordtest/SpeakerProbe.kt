package org.hotwordtest

import android.app.Activity
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Test-only: compare embeddings extracted locally from named WAV inputs. */
fun runSpeakerProbe(activity:Activity) {
 Thread {
  val result=File(activity.getExternalFilesDir(null),"speaker-probe-result.json")
  var tracker:LocalSpeakerTracker?=null
  try {
   val start=System.nanoTime();val recognizer=LocalSpeakerTracker(activity.assets);tracker=recognizer
   val initMs=(System.nanoTime()-start)/1e6
   val items=JSONArray();val vectors=mutableListOf<FloatArray>();val names=mutableListOf<String>()
   ZipInputStream(File(activity.getExternalFilesDir(null),"speaker-probe.zip").inputStream().buffered()).use { zip ->
    while(true) {val entry=zip.nextEntry?:break
     if(!entry.isDirectory&&entry.name.lowercase().endsWith(".wav")) {
      require(names.size<100){"too many inputs"};val bytes=ByteArrayOutputStream();val buf=ByteArray(32768);var total=0
      while(true){val n=zip.read(buf);if(n<0)break;total+=n;require(total<=50*1024*1024){"WAV too large"};bytes.write(buf,0,n)}
      val samples=WavReader.read16k(bytes.toByteArray().inputStream());val t=System.nanoTime();val vec=recognizer.embedding(samples);val elapsed=(System.nanoTime()-t)/1e6
      val rec=JSONObject().put("file",entry.name).put("seconds",samples.size/16000.0).put("extract_ms",elapsed)
      if(vec==null){rec.put("status","insufficient_audio")}else {rec.put("status","completed");vectors.add(vec);names.add(entry.name)}
      items.put(rec)
     };zip.closeEntry()
    }
   }
   val comparisons=JSONArray()
   for(i in vectors.indices)for(j in i+1 until vectors.size){val a=vectors[i];val b=vectors[j];require(a.size==b.size);val sim=a.indices.sumOf{(a[it]*b[it]).toDouble()};comparisons.put(JSONObject().put("a",names[i]).put("b",names[j]).put("cosine",sim))}
   // The same local embeddings are evaluated at multiple thresholds; order affects online clustering.
   val simulations=JSONArray()
   for(threshold in listOf(0.45,0.50,0.55,0.60,0.65,0.70,0.75)) {
    recognizer.reset();val assignments=JSONArray()
    for(i in vectors.indices){val assignment=recognizer.assignEmbedding(vectors[i],threshold);assignment.put("file",names[i]);assignments.put(assignment)}
    simulations.put(JSONObject().put("threshold",threshold).put("assignments",assignments))
   }
   result.writeText(JSONObject().put("status","completed").put("model","CAM++ zh-en 16k common advanced").put("runtime","Android CPU").put("model_init_ms",initMs).put("embedding_dimension",recognizer.dimension).put("items",items).put("comparisons",comparisons).put("cluster_simulations",simulations).toString())
  }catch(t:Throwable){Log.e("HotwordTest","speaker probe failed",t);result.writeText(JSONObject().put("status","failed").put("error",t.message?:t.javaClass.simpleName).toString())}
  finally{tracker?.close()}
 }.start()
}
