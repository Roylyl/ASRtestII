package org.hotwordtest

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.json.JSONObject
import kotlin.math.sqrt

/** Voice features plus evidence-gated task groups. A local activity ID never becomes an identity. */
class LocalSpeakerTracker(assets: AssetManager, modelKey: String = SpeakerModels.CAM, private val groupingThreshold:Double = EXPERIMENTAL_THRESHOLD, private val decisionConfig:SpeakerDecisionConfig = SpeakerDecisionConfig()) {
 companion object {
  const val EXPERIMENTAL_THRESHOLD = SpeakerDefaults.NATIVE_SIMILARITY_PERCENT / 100.0
  const val WEAK_CAM_SIMILARITY_THRESHOLD = 0.40
  const val WEAK_CAM_MARGIN_THRESHOLD = 0.08
  internal const val MIN_EMBEDDING_RMS = 0.003
 }
 val modelName=SpeakerModels.name(modelKey)
 init{require(groupingThreshold in 0.0..1.0){"声纹归组阈值需在0.00至1.00之间"}}
 private val extractor=SpeakerEmbeddingExtractor(assets,SpeakerEmbeddingExtractorConfig(model=SpeakerModels.asset(modelKey),numThreads=2,provider="cpu"))
 private val groups=SpeakerEvidenceGroups(extractor.dim(),decisionConfig)
 internal var evidenceObserver:((FloatArray,JSONObject)->Unit)?=null
 val dimension:Int get()=extractor.dim()
 fun embedding(samples:FloatArray):FloatArray? = embedding(samples,16000)
 internal fun reviewShortEmbedding(samples:FloatArray):FloatArray? = embedding(samples,7200)
 private fun embedding(samples:FloatArray,minSamples:Int):FloatArray? {
  if(samples.size<minSamples||samples.any{!it.isFinite()})return null
  val rms=sqrt(samples.sumOf{it.toDouble()*it}/samples.size);if(rms<MIN_EMBEDDING_RMS)return null
  val stream=extractor.createStream()
  try{
   stream.acceptWaveform(samples,16000);stream.inputFinished();if(!extractor.isReady(stream))return null
   val vector=extractor.compute(stream);if(vector.isEmpty()||vector.any{!it.isFinite()})return null
   val norm=sqrt(vector.sumOf{it.toDouble()*it}.toFloat());if(norm<=1e-8f)return null
   return FloatArray(vector.size){vector[it]/norm}
  }finally{stream.release()}
 }
 fun reset()=groups.clear()
 fun snapshot():org.json.JSONArray=groups.snapshot()
 fun restore(saved:org.json.JSONArray)=groups.restore(saved)
 fun resolvePending(pendingKey:String?):String?=groups.resolved(pendingKey)

 internal fun observeShort(vector:FloatArray,support:SpeakerEvidenceGroups.Support):JSONObject =
  groups.observeShort(vector,support)

 internal fun verifyShortWindow(vector:FloatArray,support:SpeakerEvidenceGroups.Support,targetId:String):JSONObject =
  groups.verifyShortWindow(vector,support,targetId)
 /** Test observer sees the same three-second windows even when an enrolled identity takes precedence. */
 internal fun traceRegisteredPure(candidate:SpeechEvidence.PureCandidate,identity:JSONObject,cachedVector:FloatArray?=null){
  val observer=evidenceObserver?:return
  val samples=candidate.samples();var at=0
  while(samples.size-at>=3*16000){
   val end=at+3*16000;val wave=samples.copyOfRange(at,end);val vector=if(at==0 && samples.size==3*16000) cachedVector?:embedding(wave) else embedding(wave)
   if(vector!=null){
    val startSample=candidate.startSample+at;val endSample=candidate.startSample+end
    observer(vector.copyOf(),JSONObject().put("source",candidate.source).put("start_sample",startSample)
     .put("end_sample",endSample).put("window_id",candidate.source+"@"+startSample+":"+endSample)
     .put("sample_count",wave.size).put("audio_rms",sqrt(wave.sumOf{it.toDouble()*it}/wave.size))
     .put("activity_evidence",candidate.evidenceJson()).put("identity",identity)
     .put("decision","registered_identity_precedence").put("speaker_status","registered_identity_match"))
   }
   at=end
  }
 }
 /** Caller passes an analyzer-owned pure interval. Quality filtering precedes this method. */
 fun assignPure(candidate:SpeechEvidence.PureCandidate,rawVector:FloatArray?=null):JSONObject {
  val samples=candidate.samples()
  val rms=sqrt(samples.sumOf{it.toDouble()*it}/samples.size)
  val clipped=samples.count{kotlin.math.abs(it)>=0.995f}.toDouble()/samples.size
  if(samples.size<3*16000 || rms<MIN_EMBEDDING_RMS || clipped>0.01)return JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","low_quality_window")
  var assigned=JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","insufficient_audio")
  var at=0
  val supports=org.json.JSONArray()
  while(samples.size-at>=3*16000){
   val end=at+3*16000
   val vector=if(at==0 && samples.size==3*16000)rawVector?:embedding(samples) else embedding(samples.copyOfRange(at,end))
   if(vector!=null){
    val startSample=candidate.startSample+at;val endSample=candidate.startSample+end
    val support=SpeakerEvidenceGroups.Support(candidate.source,startSample,endSample,candidate.source+"@"+startSample+":"+endSample,
     candidate.analysisWindowStart,candidate.analysisWindowEnd,candidate.localTrack)
    assigned=groups.observe(vector,support)
    val localRms=sqrt((at until end).sumOf{samples[it].toDouble()*samples[it]}/(end-at))
    evidenceObserver?.invoke(vector.copyOf(),JSONObject(assigned.toString()).put("source",candidate.source)
     .put("start_sample",startSample).put("end_sample",endSample).put("window_id",support.window)
     .put("sample_count",end-at).put("audio_rms",localRms).put("activity_evidence",candidate.evidenceJson()))
    supports.put(JSONObject(assigned.toString()))
   }
   at=end
  }
  return assigned.put("activity_evidence",candidate.evidenceJson()).put("audio_rms",rms).put("window_observations",supports)
 }
 /** Compatibility path: a Boolean never grants permission to create/update a voice group. */
 fun assignConservative(samples:FloatArray,rawVector:FloatArray?=null,singleSpeakerVerified:Boolean=false):JSONObject {
  val vector=rawVector?:embedding(samples)
   ?:return JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","insufficient_audio")
  return groups.nearest(vector).put("single_speaker_status","raw_activity_evidence_required")
   .put("legacy_single_speaker_claim",singleSpeakerVerified).put("threshold",decisionConfig.strongMatch)
 }
 fun assign(samples:FloatArray):JSONObject=assignConservative(samples)
 /** Explicit developer replay only. Production audio uses assignPure with raw activity evidence. */
 fun assignEmbedding(vector:FloatArray,threshold:Double):JSONObject {
  require(threshold in 0.0..1.0)
  return groups.probeAssign(vector,threshold)
 }
 fun close(){extractor.release()}
}
