package org.hotwordtest

/** Restrict a review-only candidate to successful unassigned feature windows.
 * This is not a speaker classifier and does not establish that overlap is absent. */
internal object UnknownCandidateWindows {
 data class Probe(val start:Int,val end:Int,val eligible:Boolean) {
  init { require(start>=0 && end>start) }
 }
 fun eligible(available:Boolean,identityStatus:String,margin:Double?,speakerStatus:String,hasPerson:Boolean):Boolean =
  available && identityStatus in setOf("uncertain","other_candidate","not_enrolled") &&
   (margin==null || margin.isFinite()&&margin>=0.10) &&
   speakerStatus in setOf("pending_anonymous_confirmation","novelty_not_established","unconfirmed_overlapping_probe") && !hasPerson
 fun ranges(probes:List<Probe>,minimum:Int):List<Pair<Int,Int>> {
  require(minimum>0)
  val cuts=probes.flatMap{listOf(it.start,it.end)}.distinct().sorted()
  val out=mutableListOf<Pair<Int,Int>>()
  for(i in 0 until cuts.size-1){
   val a=cuts[i];val b=cuts[i+1];if(b<=a)continue
   val covering=probes.filter{it.start<b&&it.end>a}
   if(covering.isEmpty()||covering.any{!it.eligible})continue
   val previous=out.lastOrNull()
   if(previous!=null&&previous.second==a)out[out.lastIndex]=previous.first to b else out.add(a to b)
  }
  return out.filter{it.second-it.first>=minimum}
 }
}
