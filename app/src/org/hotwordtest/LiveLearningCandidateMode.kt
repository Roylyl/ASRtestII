package org.hotwordtest

/** Mutually exclusive routes: resolved, review-only unresolved, or no collection. */
internal object LiveLearningCandidateMode {
 enum class Route { RESOLVED, UNRESOLVED, BLOCKED }
 /** A partial known interval is only offered as anonymous review when the whole
  * source row is unresolved. It is never enough to identify the whole row. */
 fun mayReviewSubintervals(route:Route,knownCount:Int,unresolvedCount:Int):Boolean =
  route==Route.UNRESOLVED && knownCount>=0 && unresolvedCount>=0 && (knownCount>0||unresolvedCount>0)
 fun decide(sourceMatches:Boolean,explicitOverlap:Boolean,validStable:Boolean,stableMissing:Boolean,
            display:String,method:String,hasSupports:Boolean):Route {
  if(!sourceMatches||explicitOverlap)return Route.BLOCKED
  if(validStable&&display.isNotBlank()&&display !in setOf("说话人未识别","多人重叠","待确认","无文字")&&
   method=="pure_activity_voice_evidence"&&hasSupports)return Route.RESOLVED
  if(stableMissing&&display=="说话人未识别"&&
   method in setOf("awaiting_independent_voice_windows","insufficient_pure_activity"))return Route.UNRESOLVED
  return Route.BLOCKED
 }
}
