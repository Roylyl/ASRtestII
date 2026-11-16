package org.hotwordtest

/** Reject explicit overlap labels only; absence of a label is not proof of purity. */
internal object LearningOverlapGate {
 fun isExplicit(overlapStatus:String, associationStatus:String, provenanceMethod:String,
                display:String, liveLocalSpeakerCount:Int):Boolean =
  overlapStatus=="predicted_overlap" ||
   associationStatus=="multiple_candidates_ambiguous" ||
   provenanceMethod=="raw_activity_overlap" ||
   display=="多人重叠" || liveLocalSpeakerCount>1
}
