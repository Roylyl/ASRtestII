package org.hotwordtest

/** Aggregate attempted safe clips without allowing a late quality failure to hide a conflict. */
internal class LearningRejectionSummary {
 private val stageCounts=linkedMapOf<String,Int>()
 private var bestPriority=Int.MIN_VALUE
 fun record(stage:String):Boolean {
  val label=stage.ifBlank{"unknown"}
  stageCounts[label]=(stageCounts[label]?:0)+1
  val priority=LearningCandidateSequence.rejectionPriority(label)
  if(priority<=bestPriority)return false
  bestPriority=priority
  return true
 }
 fun counts():Map<String,Int> = stageCounts.toMap()
}
