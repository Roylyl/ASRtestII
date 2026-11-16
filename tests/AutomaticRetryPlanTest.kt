package org.hotwordtest

import org.json.JSONArray
import org.json.JSONObject

fun main() {
 fun raw(failureModel:String="CAM++",failureIndex:Int=0):JSONObject {
  val models=JSONObject()
  for(name in listOf("CAM++","ERes2NetV2")){
   val windows=JSONArray()
   for((i,span) in listOf(0L to 24000L,24000L to 48000L,40000L to 64000L).withIndex()){
    val score=if(name==failureModel&&i==failureIndex)0.65 else 0.82
    val match=JSONObject().put("status",if(score<0.70)"uncertain" else "wearer_candidate")
     .put("best_profile_id","target").put("profile_id",if(score<0.70)JSONObject.NULL else "target")
     .put("base_similarity",score).put("similarity",score).put("runner_up_similarity",0.20).put("candidate_margin",score-0.20)
    windows.put(JSONObject().put("start_sample",span.first).put("end_sample",span.second).put("independent",i<2).put("match",match))
   }
   models.put(name,JSONObject().put("model",name).put("windows",windows))
  }
  return JSONObject().put("status","window_not_high_confidence").put("models",models)
 }
 fun diagnose(r:JSONObject)=VoiceLearningDiagnostic.failure("automatic","failed",r,"target")
 fun span(r:JSONObject)=AutomaticRetryPlan.failedSpan(diagnose(r),0,64000)
 check(span(raw("CAM++",0))==(0L to 24000L))
 check(span(raw("ERes2NetV2",1))==(24000L to 48000L))
 val missing=raw();missing.getJSONObject("models").getJSONObject("CAM++").getJSONArray("windows").getJSONObject(0).getJSONObject("match").remove("runner_up_similarity")
 check(span(missing)==null)
 val conflict=raw();conflict.getJSONObject("models").getJSONObject("ERes2NetV2").getJSONArray("windows").getJSONObject(1).getJSONObject("match").put("best_profile_id","other")
 check(span(conflict)==null)
 val noModel=raw();noModel.getJSONObject("models").remove("ERes2NetV2");check(span(noModel)==null)
 check(AutomaticRetryPlan.failedSpan(diagnose(raw()),0,47000)==null)
 val truncated=raw();truncated.getJSONObject("models").getJSONObject("CAM++").getJSONArray("windows").getJSONObject(0).put("end_sample",23000)
 check(span(truncated)==null)
 val separated=raw();val m=separated.getJSONObject("models").getJSONObject("ERes2NetV2").getJSONArray("windows").getJSONObject(2).getJSONObject("match")
 m.put("status","uncertain").put("base_similarity",0.65).put("similarity",0.65).put("candidate_margin",0.45);check(span(separated)==null)
 check(AutomaticRetryPlan.outsideFailure(0,100000,0,24000)==listOf(25600L to 100000L))
 check(AutomaticRetryPlan.outsideFailure(0,50000,0,24000).isEmpty())
 val oldConflict=JSONObject().put("edge_rescue",JSONObject().put("first_continuity",JSONObject().put("status","acoustic_conflict")))
 check(AutomaticRetryPlan.edgeRescueReviewOnly(oldConflict))
 val newConflict=JSONObject().put("edge_rescue",JSONObject().put("automatic_learning_allowed",false))
 check(AutomaticRetryPlan.edgeRescueReviewOnly(newConflict))
 val noConflict=JSONObject().put("edge_rescue",JSONObject().put("automatic_learning_allowed",true)
  .put("first_continuity",JSONObject().put("status","feature_unavailable")))
 check(AutomaticRetryPlan.edgeRescueReviewOnly(noConflict))
 check(AutomaticRetryPlan.edgeRescueReviewOnly(JSONObject().put("edge_rescue",JSONObject())))
 check(AutomaticRetryPlan.edgeRescueReviewOnly(JSONObject().put("edge_rescue",JSONObject().put("first_rejection","有效人声不足"))))
 check(!AutomaticRetryPlan.edgeRescueReviewOnly(JSONObject()))
 check(!AutomaticRetryPlan.edgeRescueReviewOnly(null))
 println("automatic-retry diagnostic and boundary checks passed")
}

