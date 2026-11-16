package org.hotwordtest

fun main() {
 fun isOverlap(status:String="",association:String="",method:String="",display:String="",local:Int=0) =
  LearningOverlapGate.isExplicit(status,association,method,display,local)
 // Real live turn schema: an overlap status, multiple local tracks and display.
 check(isOverlap(status="predicted_overlap"))
 check(isOverlap(local=2))
 check(isOverlap(display="多人重叠"))
 // Real file turn schema before and after StableSpeakerAssignments.resolve.
 check(isOverlap(association="multiple_candidates_ambiguous"))
 check(isOverlap(method="raw_activity_overlap"))
 // Multiple sequential source tracks merged as one stable person are not overlap.
 check(!isOverlap(association="sequential_tracks_same_identity"))
 check(!isOverlap(status="evidence_checked",association="single_candidate_unverified",local=1))
 check(!isOverlap(status="not_checked")) // Other acoustic checks are still required.
 check(!isOverlap())
 println("LearningOverlapGateTest: pass")
}
