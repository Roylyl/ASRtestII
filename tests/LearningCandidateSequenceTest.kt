package org.hotwordtest

fun main(){
 data class Sample(val key:String,val length:Long)
 val a=Sample("a",100);val b=Sample("b",90);val c=Sample("c",120)
 val ordered=LearningCandidateSequence.order(listOf(b,a),listOf(c,a),{it.key},{it.length})
 check(ordered.map{it.value.key}==listOf("a","b","c"))
 check(ordered.map{it.known}==listOf(true,true,false))
 val calls=mutableListOf<String>()
 val result=LearningCandidateSequence.firstAccepted(ordered){item->
  calls.add(item.value.key);if(item.value.key=="c")"accepted" else null
 }
 check(result=="accepted"&&calls==listOf("a","b","c"))
 calls.clear()
 check(LearningCandidateSequence.firstAccepted(ordered){item->calls.add(item.value.key);if(item.value.key=="b")"ok" else null}=="ok")
 check(calls==listOf("a","b"))
 check(LearningCandidateSequence.order((1..30).map{Sample("$it",it.toLong())},emptyList(),{it.key},{it.length}).size==24)
 check(LearningCandidateSequence.safeHalves(16000,16000+5*16000L,48000).isEmpty())
 check(LearningCandidateSequence.safeHalves(16000,16000+6*16000L,48000)==listOf(16000L to 64000L,64000L to 112000L))
 check(LearningCandidateSequence.safeHalves(10,10+7*16000L+1,48000)==listOf(10L to 56010L,56010L to 112011L))
 check(LearningCandidateSequence.safeHalves(-1,96000,48000).isEmpty())
 check(LearningCandidateSequence.mayRetryForReview("quality",true))
 check(!LearningCandidateSequence.mayRetryForReview("quality",false))
 for(stage in listOf("activity","continuity","target_conflict","anchor","automatic","unknown"))
  check(!LearningCandidateSequence.mayRetryForReview(stage,true))
 check(LearningCandidateSequence.rejectionPriority("quality")<LearningCandidateSequence.rejectionPriority("continuity"))
 check(LearningCandidateSequence.rejectionPriority("quality")<LearningCandidateSequence.rejectionPriority("activity"))
 check(LearningCandidateSequence.rejectionPriority("quality")<LearningCandidateSequence.rejectionPriority("unknown"))
 println("LearningCandidateSequenceTest: pass")
}
