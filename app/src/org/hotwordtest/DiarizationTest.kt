package org.hotwordtest

import android.app.Activity
import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Offline speaker segmentation/embedding/clustering; IDs are anonymous and local to a file. */
object DiarizationTest {
 private const val SEGMENTATION="models/pyannote-segmentation.onnx"
 private const val THREADS=2
 private const val CLUSTER_THRESHOLD=0.5f
 private fun config(key:String, numClusters:Int, debug:Boolean=false,similarityThreshold:Double=SpeakerDefaults.NATIVE_SIMILARITY_PERCENT/100.0)=OfflineSpeakerDiarizationConfig(
  segmentation=OfflineSpeakerSegmentationModelConfig(pyannote=OfflineSpeakerSegmentationPyannoteModelConfig(model=SEGMENTATION,windowShiftRatio=0.1f),numThreads=THREADS,debug=debug,provider="cpu"),
  embedding=SpeakerEmbeddingExtractorConfig(model=SpeakerModels.asset(key),numThreads=THREADS,provider="cpu"),
  clustering=FastClusteringConfig(numClusters=numClusters,threshold=(1.0-similarityThreshold).toFloat()),
  minDurationOn=0.2f,minDurationOff=0.5f)
 private data class SegmentOutput(val segments:JSONArray,val boundaryAdjustments:JSONArray)
 // JNI looks up invoke(int,int,long):Integer. An indy lambda only exposes the erased bridge.
 private class NativeProgress(private val update:(Int,Int)->Unit):(Int,Int,Long)->Int {
  override fun invoke(done:Int,total:Int,arg:Long):Int {update(done,total);return 0}
 }
 private fun segmentJson(diarizer:OfflineSpeakerDiarization,samples:FloatArray,onProgress:((Int,Int)->Unit)?=null):SegmentOutput {
  val output=JSONArray();val adjustments=JSONArray();val duration=samples.size/16000.0
  val segments=if(onProgress==null)diarizer.process(samples) else diarizer.processWithCallback(samples,NativeProgress(onProgress))
  for(item in segments.sortedBy{it.start}) {
   require(item.start.isFinite()&&item.end.isFinite()){"切分返回无效时间"}
   val rawStart=item.start.toDouble();val rawEnd=item.end.toDouble()
   val start=rawStart.coerceIn(0.0,duration);val end=rawEnd.coerceIn(0.0,duration)
   val clipped=start!=rawStart||end!=rawEnd;val dropped=end<=start
   if(clipped||dropped)adjustments.put(JSONObject().put("speaker_id",item.speaker)
    .put("raw_start_seconds",rawStart).put("raw_end_seconds",rawEnd)
    .put("clamped_start_seconds",start).put("clamped_end_seconds",end)
    .put("action",if(dropped)"dropped_nonpositive_interval" else "clipped_to_audio"))
   if(dropped)continue
   val confidence:Any=if(item.confidence.isFinite()&&item.confidence>=-1f&&item.confidence<=1f)item.confidence.toDouble() else JSONObject.NULL
   output.put(JSONObject().put("start_seconds",start).put("end_seconds",end).put("speaker_id",item.speaker).put("confidence",confidence).put("time_clipped",clipped))
  }
  return SegmentOutput(output,adjustments)
 }
 class Session(assets:AssetManager,key:String,private val numClusters:Int,private val similarityThreshold:Double=SpeakerDefaults.NATIVE_SIMILARITY_PERCENT/100.0):AutoCloseable {
  private val k=SpeakerModels.normalize(key)
  private val initStarted=SystemClock.elapsedRealtimeNanos()
  private val diarizer=OfflineSpeakerDiarization(assets,config(k,numClusters,similarityThreshold=similarityThreshold))
  val initMs=(SystemClock.elapsedRealtimeNanos()-initStarted)/1000000.0
  fun process(samples:FloatArray,onProgress:((Int,Int)->Unit)?=null):JSONObject {
   require(diarizer.sampleRate()==16000){"说话人模型采样率不是16kHz"}
   val t1=SystemClock.elapsedRealtimeNanos();val segments=segmentJson(diarizer,samples,onProgress);val t2=SystemClock.elapsedRealtimeNanos()
   val singleWindow=samples.size<=160000
   return JSONObject().put("status","completed").put("speaker_model",SpeakerModels.name(k)).put("segmentation_model",SEGMENTATION)
    .put("processing_mode",if(singleWindow)"single_window_segmentation_only" else "multi_window_diarization_candidate")
    .put("speaker_embedding_execution",if(singleWindow)"not_executed" else "not_verified_for_this_input")
    .put("processing_mode_basis","pyannote模型窗口160000采样；多窗口可能因无语音等原因提前返回")
    .put("sample_rate",16000).put("audio_seconds",samples.size/16000.0).put("num_threads",THREADS)
    .put("clustering",JSONObject().put("mode",if(numClusters==2)"fixed_two_speakers_prior" else "automatic").put("num_clusters",numClusters).put("threshold",1.0-similarityThreshold).put("similarity_threshold",similarityThreshold).put("threshold_meaning","cosine_distance_1_minus_similarity").put("threshold_status","uncalibrated"))
    .put("model_init_ms",initMs).put("process_ms",(t2-t1)/1000000.0).put("segments",segments.segments).put("boundary_adjustments",segments.boundaryAdjustments)
  }
  override fun close(){diarizer.release()}
 }
 fun processOne(assets:AssetManager,samples:FloatArray,key:String,numClusters:Int):JSONObject {
  return Session(assets,key,numClusters).use { it.process(samples) }
 }
 fun runProbe(activity:Activity) {
  Thread {
   val destination=File(activity.getExternalFilesDir(null),"diarization-probe-result.json")
   try {
    val audio=mutableListOf<Pair<String,FloatArray>>()
    ZipInputStream(File(activity.getExternalFilesDir(null),"diarization-probe.zip").inputStream().buffered()).use{zip->
     while(true){val entry=zip.nextEntry?:break
      if(!entry.isDirectory&&entry.name.lowercase().endsWith(".wav")) {
       require(audio.size<100){"音频数量过多"};val out=ByteArrayOutputStream();val buf=ByteArray(32768);var count=0
       while(true){val n=zip.read(buf);if(n<0)break;count+=n;require(count<=50*1024*1024){"单条音频过大"};out.write(buf,0,n)}
       audio.add(entry.name to WavReader.read16k(out.toByteArray().inputStream()))
      }
      zip.closeEntry()
     }
    }
    require(audio.isNotEmpty()){ "ZIP中没有WAV" };audio.sortBy{it.first}
    val runs=JSONArray()
    for(key in listOf(SpeakerModels.CAM,SpeakerModels.ERES)) {
     // Two identical configurations per model; fixed-two is explicitly a prior, not detection.
     for(num in listOf(-1,2)) {
      val model=JSONObject().put("speaker_model",SpeakerModels.name(key)).put("clustering_mode",if(num==2)"fixed_two_speakers_prior" else "automatic")
      var diarizer:OfflineSpeakerDiarization?=null
      try {
       val t0=SystemClock.elapsedRealtimeNanos();val instance=OfflineSpeakerDiarization(activity.assets,config(key,num,debug=true));diarizer=instance
       model.put("model_init_ms",(SystemClock.elapsedRealtimeNanos()-t0)/1000000.0)
       require(instance.sampleRate()==16000){"说话人模型采样率不是16kHz"}
       val items=JSONArray()
       for((name,samples) in audio) {
        val entry=JSONObject().put("file",name).put("audio_seconds",samples.size/16000.0)
        val mode=if(num==2)"fixed_two_speakers_prior" else "automatic"
        Log.i("DiarizationProbe","RUN_START model=${SpeakerModels.name(key)} mode=$mode file=$name")
        try{val start=SystemClock.elapsedRealtimeNanos();val segments=segmentJson(instance,samples);entry.put("status","completed").put("process_ms",(SystemClock.elapsedRealtimeNanos()-start)/1000000.0).put("segments",segments.segments).put("boundary_adjustments",segments.boundaryAdjustments)}
        catch(e:Throwable){entry.put("status","failed").put("error",e.message?:e.javaClass.simpleName)}
        finally{Log.i("DiarizationProbe","RUN_END model=${SpeakerModels.name(key)} mode=$mode file=$name")}
        items.put(entry)
       }
       model.put("status","completed").put("items",items)
      }catch(e:Throwable){Log.e("HotwordTest","diarization probe failed",e);model.put("status","failed").put("error",e.message?:e.javaClass.simpleName)}
      finally{try{diarizer?.release()}catch(_:Throwable){}}
      runs.put(model)
     }
    }
    destination.writeText(JSONObject().put("status","completed").put("runtime","Android CPU").put("sample_rate",16000).put("segmentation_model",SEGMENTATION)
     .put("num_threads",THREADS).put("window_shift_ratio",0.1).put("min_duration_on",0.2).put("min_duration_off",0.5).put("native_debug_enabled",true).put("native_debug_location","logcat; RUN_START/RUN_END use DiarizationProbe tag")
     .put("cluster_threshold",CLUSTER_THRESHOLD.toDouble()).put("cluster_threshold_status","uncalibrated").put("runs",runs).toString())
   }catch(e:Throwable){Log.e("HotwordTest","diarization probe failed",e);destination.writeText(JSONObject().put("status","failed").put("error",e.message?:e.javaClass.simpleName).toString())}
  }.start()
 }
}
