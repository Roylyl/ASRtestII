package org.hotwordtest

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.json.JSONObject
import kotlin.math.sqrt

/** Experimental online assignment, not diarization or verified identity. */
class LocalSpeakerTracker(assets: AssetManager, modelKey: String = SpeakerModels.CAM, private val groupingThreshold:Double = EXPERIMENTAL_THRESHOLD, private val decisionConfig:SpeakerDecisionConfig = SpeakerDecisionConfig()) {
 companion object {
  const val EXPERIMENTAL_THRESHOLD = SpeakerDefaults.NATIVE_SIMILARITY_PERCENT / 100.0
  const val WEAK_CAM_SIMILARITY_THRESHOLD = 0.40
  const val WEAK_CAM_MARGIN_THRESHOLD = 0.08
 }
 val modelName=SpeakerModels.name(modelKey)
 private val model=SpeakerModels.normalize(modelKey)
 init{require(groupingThreshold in 0.0..1.0){"声纹归组阈值需在0.00至1.00之间"}}
 private val extractor=SpeakerEmbeddingExtractor(assets,SpeakerEmbeddingExtractorConfig(model=SpeakerModels.asset(modelKey),numThreads=2,provider="cpu"))
 private val references=mutableListOf<FloatArray>()
 private val counts=mutableListOf<Int>()
 val dimension:Int get()=extractor.dim()
 fun embedding(samples:FloatArray):FloatArray? {
  if(samples.size<16000) return null
  val rms=sqrt(samples.sumOf{it.toDouble()*it}/samples.size);if(rms<0.003) return null
  val stream=extractor.createStream()
  try {stream.acceptWaveform(samples,16000);stream.inputFinished();if(!extractor.isReady(stream))return null
   val vector=extractor.compute(stream);if(vector.isEmpty()||vector.any{!it.isFinite()})return null
   val norm=sqrt(vector.sumOf{it.toDouble()*it}.toFloat());if(norm<=1e-8f)return null
   return FloatArray(vector.size){vector[it]/norm}
  } finally {stream.release()}
 }
 fun reset(){references.clear();counts.clear()}
 fun snapshot():org.json.JSONArray {
  val all=org.json.JSONArray()
  for(i in references.indices){val values=org.json.JSONArray();references[i].forEach{values.put(it.toDouble())};all.put(org.json.JSONObject().put("count",counts[i]).put("embedding",values))}
  return all
 }
 fun restore(saved:org.json.JSONArray){
  require(saved.length()<=32){"声纹状态超过上限"}
  val restored=mutableListOf<FloatArray>();val restoredCounts=mutableListOf<Int>()
  for(i in 0 until saved.length()){
   val obj=saved.getJSONObject(i);val count=obj.getInt("count");val a=obj.getJSONArray("embedding")
   require(count>0&&a.length()==dimension){"声纹状态无效"}
   val v=FloatArray(a.length()){a.getDouble(it).toFloat()};require(v.all{it.isFinite()}){"声纹状态非有限数值"}
   val n=sqrt(v.sumOf{it.toDouble()*it}.toFloat());require(n>1e-8f){"声纹状态范数无效"}
   restored.add(FloatArray(v.size){v[it]/n});restoredCounts.add(count)
  }
  references.clear();references.addAll(restored);counts.clear();counts.addAll(restoredCounts)
 }
 fun assign(samples:FloatArray):JSONObject {
  val vector=embedding(samples)?:return JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","insufficient_audio").put("threshold",groupingThreshold)
  return assignEmbedding(vector,groupingThreshold)
 }
 /** Experimental matching with separate match, novelty and competing-speaker gates. */
 fun assignConservative(samples:FloatArray, rawVector:FloatArray?=null, singleSpeakerVerified:Boolean=false):JSONObject {
  if(samples.size<16000||samples.any{!it.isFinite()})return JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","insufficient_audio")
  val rms=sqrt(samples.sumOf{it.toDouble()*it}/samples.size)
  val strong=samples.size>=32000&&rms>=0.003
  var gain=1.0
  val vector=if(rms>=0.003)rawVector?:embedding(samples) else {
   val peak=samples.maxOf{kotlin.math.abs(it.toDouble())}
   if(rms<=0||peak<=0)null else {
    gain=minOf(6.0,0.006/rms,0.9/peak)
    if(rms*gain<0.003)null else embedding(FloatArray(samples.size){(samples[it]*gain).toFloat()})
   }
  }
  val matchThreshold=if(strong)decisionConfig.strongMatch else decisionConfig.weakMatch
  val out=JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status",if(strong)"speaker_undetermined" else "weak_candidate_rejected")
   .put("threshold",matchThreshold).put("new_speaker_max_similarity",decisionConfig.newSpeakerMax).put("margin_threshold",decisionConfig.ambiguityMargin).put("threshold_status","experimental_uncalibrated")
   .put("gain",gain).put("audio_rms",rms).put("candidate_kind",if(strong)"strong" else if(gain>1.0)"low_level" else "short_audio")
   .put("single_speaker_status",if(singleSpeakerVerified)"predicted_single" else "not_verified").put("overlap_status",if(singleSpeakerVerified)"predicted_nonoverlap" else "not_checked")
  if(vector==null)return out.put("speaker_status","insufficient_audio")
  if(vector.size!=dimension||vector.any{!it.isFinite()})return out.put("speaker_status","invalid_embedding")
  val scores=references.mapIndexed{i,r->i to r.indices.sumOf{(r[it]*vector[it]).toDouble()}}.sortedByDescending{it.second}
  val best=scores.firstOrNull()
  if(best==null){
   if(!strong)return out.put("speaker_status","no_existing_group")
   references.add(vector);counts.add(1)
   return out.put("speaker_id","S1").put("speaker_status","experimental_segment_assignment").put("decision","initial_group").put("candidate_kind","strong")
  }
  val gap=if(scores.size>1)best.second-scores[1].second else null
  out.put("similarity_to_previous",best.second).put("candidate_margin",gap?:JSONObject.NULL)
  if(best.second>=matchThreshold&&(gap==null||gap>=decisionConfig.ambiguityMargin)){
   if(strong){
    val count=counts[best.first];val old=references[best.first]
    val mean=FloatArray(vector.size){(old[it]*count+vector[it])/(count+1)}
    val n=sqrt(mean.sumOf{it.toDouble()*it}.toFloat())
    if(n<=1e-8f)return out.put("speaker_status","invalid_embedding")
    references[best.first]=FloatArray(mean.size){mean[it]/n};counts[best.first]=count+1
   }
   return out.put("speaker_id","S${best.first+1}").put("speaker_status",if(strong)"experimental_segment_assignment" else "weak_candidate").put("decision","match_existing").put("candidate_kind",if(strong)"strong" else out.getString("candidate_kind"))
  }
  if(strong&&best.second<decisionConfig.newSpeakerMax){
   if(references.size>=32)return out.put("speaker_status","speaker_limit_reached")
   references.add(vector);counts.add(1)
   return out.put("speaker_id","S${references.size}").put("speaker_status","experimental_segment_assignment").put("decision","new_group").put("candidate_kind","strong")
  }
  if(gap!=null&&gap<decisionConfig.ambiguityMargin)out.put("speaker_status","ambiguous_multiple")
  return out
 }
 // Exposed to the local evaluation probe to compare candidate thresholds without rerunning inference.
 fun assignEmbedding(vector:FloatArray, threshold:Double):JSONObject {
  require(threshold>=0.0 && threshold<=1.0)
  val scores=references.map { ref -> ref.indices.sumOf{(ref[it]*vector[it]).toDouble()} }
  val idx=if(scores.isEmpty()) -1 else scores.indices.maxByOrNull{scores[it]}!!
  // Exploratory threshold, uncalibrated for the device and acoustic conditions.
  if(idx>=0 && scores[idx]<threshold && references.size>=32)return JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","speaker_limit_reached").put("similarity_to_previous",scores[idx]).put("threshold",threshold)
  val chosen=if(idx<0||scores[idx]<threshold){references.add(vector);counts.add(1);references.size-1}else{val count=counts[idx];val old=references[idx];val mean=FloatArray(vector.size){(old[it]*count+vector[it])/(count+1)};val n=sqrt(mean.sumOf{it.toDouble()*it}.toFloat());references[idx]=FloatArray(mean.size){mean[it]/n};counts[idx]=count+1;idx}
  return JSONObject().put("speaker_id","S${chosen+1}").put("speaker_status","experimental_segment_assignment").put("similarity_to_previous",if(idx<0)JSONObject.NULL else scores[idx]).put("threshold",threshold)
 }
 fun close(){extractor.release()}
}
