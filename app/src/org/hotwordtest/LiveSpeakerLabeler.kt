package org.hotwordtest

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject

/** One embedding per VAD segment, shared by anonymous clustering and enrolled-voice matching. */
class LiveSpeakerLabeler(assets:AssetManager,context:Context,modelKey:String,groupingThreshold:Double=LocalSpeakerTracker.EXPERIMENTAL_THRESHOLD,decisionConfig:SpeakerDecisionConfig=SpeakerDecisionConfig()):AutoCloseable {
 private val model=SpeakerModels.normalize(modelKey)
 private val speaker=LocalSpeakerTracker(assets,modelKey,groupingThreshold,decisionConfig)
 // Keep anonymous ASR working even when registered profiles cannot be read.
 private val matcher=try{WearerVoiceStore.Matcher(context,modelKey,loadExtractor=false)}catch(_:Exception){null}

 fun snapshotAnonymousGroups():org.json.JSONArray = speaker.snapshot()
 fun restoreAnonymousGroups(state:org.json.JSONArray) = speaker.restore(state)

 fun classify(samples:FloatArray,singleSpeakerVerified:Boolean=false):JSONObject {
  val vector=try{speaker.embedding(samples)}catch(_:Exception){null}
  val anonymous=try{
   speaker.assignConservative(samples,vector,singleSpeakerVerified)
  }catch(_:Exception){JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","comparison_failed")}
  // A live VAD segment may contain several speakers; no overlap/single-speaker check is performed.
  val identity=try{matcher?.identifyEmbedding(vector,samples.size,true)}catch(_:Exception){null}
   ?:JSONObject().put("status","comparison_failed")
  identity.put("single_speaker_status",if(singleSpeakerVerified)"predicted_single" else "not_verified")
   .put("overlap_status",if(singleSpeakerVerified)"predicted_nonoverlap" else "not_checked")
   .put("note",if(singleSpeakerVerified)"说话人活动模型预测单人且无重叠；身份及预测均未人工确认" else "实时VAD整段声纹候选；未检测多人重叠，可能误认；阈值未经校准")
  val name=if(identity.optString("status")=="wearer_candidate")identity.optString("display_name") else ""
  val score=identity.optDouble("similarity",Double.NaN)
  val margin=identity.optDouble("candidate_margin",Double.NaN)
  val nearest=identity.optString("nearest_candidate_name","")
  // CAM++ only: exploratory display band. This never changes uncertain into a match.
  // If multiple profiles compete closely, do not show any name.
  val lowCandidate=model==SpeakerModels.CAM && identity.optString("status")=="uncertain" &&
   score.isFinite() && score>=0.45 && score<0.65 &&
   (identity.isNull("runner_up_similarity") || (margin.isFinite() && margin>=0.08)) && nearest.isNotBlank()
  identity.put("low_confidence_candidate_shown",lowCandidate)
   .put("low_confidence_threshold",if(model==SpeakerModels.CAM)0.45 else JSONObject.NULL)
   .put("low_confidence_threshold_status","experimental_uncalibrated")
  val anonymousName=SpeakerTranscript.anonymousLabel(anonymous)
  val display=when {
   name.isNotBlank()->"$name（候选）"
   lowCandidate->"$anonymousName · $nearest（低置信候选）"
   else->anonymousName
  }
  return JSONObject().put("speaker",anonymous).put("identity",identity).put("display",display)
   .put("single_speaker_status",if(singleSpeakerVerified)"predicted_single" else "not_verified").put("overlap_status",if(singleSpeakerVerified)"predicted_nonoverlap" else "not_checked")
 }
 override fun close(){try{matcher?.close()}finally{speaker.close()}}
}
