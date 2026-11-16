package org.hotwordtest

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Shared evidence/identity resolver for live and imported audio. */
class LiveSpeakerLabeler(assets:AssetManager,context:Context,modelKey:String,
 groupingThreshold:Double=LocalSpeakerTracker.EXPERIMENTAL_THRESHOLD,
 decisionConfig:SpeakerDecisionConfig=SpeakerDecisionConfig()):AutoCloseable {
 private val assignments=StableSpeakerAssignments(assets,context,modelKey,decisionConfig)
 private val legacySource="live-labeler:"+UUID.randomUUID()
 private var legacyPosition=0L
 private val rowsBySource=linkedMapOf<String,JSONArray>()

 data class Classification(val voice:JSONObject,val pureCandidates:List<SpeechEvidence.PureCandidate>,
                           val unresolvedCandidates:List<SpeechEvidence.PureCandidate>)

 fun observe(samples:FloatArray,source:String,startSample:Long=0L,evidence:SpeechEvidence?=null):List<SpeechEvidence.PureCandidate> =
  assignments.observe(samples,source,startSample,evidence)

 fun takeUnresolvedLearningCandidates(source:String):List<SpeechEvidence.PureCandidate> =
  assignments.takeUnresolvedLearningCandidates(source)

 fun activityEvidence(source:String):JSONObject = assignments.activityEvidence(source)

 fun resolve(rows:JSONArray,source:String,offsetSamples:Long=0L,final:Boolean=false):JSONArray =
  assignments.resolve(rows,source,offsetSamples,final)

 /** One analysis supplies both the prediction and its protected candidate intervals. */
 fun classifyWithEvidence(samples:FloatArray,source:String,startSample:Long):Classification {
  val pure=observe(samples,source,startSample)
  val unresolved=takeUnresolvedLearningCandidates(source)
  val end=Math.addExact(startSample,samples.size.toLong())
  val rows=rowsBySource.getOrPut(source){JSONArray()}
  rows.put(JSONObject().put("start_sample",startSample).put("end_sample",end)
   .put("start_ms",startSample*1000/16000).put("end_ms",end*1000/16000).put("text","语音"))
  val all=resolve(rows,source)
  val row=all.getJSONObject(all.length()-1)
  val voice=JSONObject().put("speaker",row.optJSONObject("voice_group_candidate")?:JSONObject().put("speaker_id",JSONObject.NULL))
   .put("identity",row.optJSONObject("wearer_identity")?:JSONObject().put("status","uncertain"))
   .put("display",row.optString("speaker_display","说话人未识别"))
   .put("stable_speaker_id",row.opt("stable_speaker_id")?:JSONObject.NULL)
   .put("source",source).put("start_sample",startSample).put("end_sample",end)
   .put("single_speaker_status",row.optString("single_speaker_status","evidence_checked"))
   .put("overlap_status",row.optString("overlap_status","evidence_checked"))
   .put("speaker_identity_provenance",row.optJSONObject("speaker_identity_provenance")?:JSONObject.NULL)
   .put("voice_gate","统一原始语音活动证据；无布尔绕过")
  return Classification(voice,pure,unresolved)
 }

 /** Compatibility entry. The historical Boolean never bypasses acoustic analysis. */
 @Suppress("UNUSED_PARAMETER")
 fun classify(samples:FloatArray,singleSpeakerVerified:Boolean=false):JSONObject {
  val start=legacyPosition
  val result=classifyWithEvidence(samples,legacySource,start)
  legacyPosition=Math.addExact(start,samples.size.toLong())
  return result.voice
 }
 override fun close(){assignments.close()}
}
