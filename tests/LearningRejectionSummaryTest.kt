package org.hotwordtest

fun main(){
 val summary=LearningRejectionSummary()
 var selected=""
 for(stage in listOf("quality","continuity","quality","activity","quality"))
  if(summary.record(stage))selected=stage
 check(selected=="continuity")
 check(summary.counts()==mapOf("quality" to 3,"continuity" to 1,"activity" to 1))
 val unknown=LearningRejectionSummary()
 check(unknown.record("quality"))
 check(unknown.record(""))
 check(!unknown.record("quality"))
 check(unknown.counts()==mapOf("quality" to 2,"unknown" to 1))
 val samePriority=LearningRejectionSummary()
 check(samePriority.record("target_conflict"))
 check(!samePriority.record("continuity"))
 println("LearningRejectionSummaryTest: pass")
}
