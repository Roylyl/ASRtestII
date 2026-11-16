package org.hotwordtest

fun main(){
 fun p(a:Int,b:Int,ok:Boolean)=UnknownCandidateWindows.Probe(a,b,ok)
 fun r(vararg x:UnknownCandidateWindows.Probe)=UnknownCandidateWindows.ranges(x.toList(),3000)
 check(UnknownCandidateWindows.eligible(true,"uncertain",0.2,"unconfirmed_overlapping_probe",false))
 check(UnknownCandidateWindows.eligible(true,"not_enrolled",null,"pending_anonymous_confirmation",false))
 check(UnknownCandidateWindows.eligible(true,"other_candidate",0.2,"novelty_not_established",false))
 check(!UnknownCandidateWindows.eligible(false,"uncertain",0.2,"pending_anonymous_confirmation",false))
 check(!UnknownCandidateWindows.eligible(true,"invalid_embedding",null,"pending_anonymous_confirmation",false))
 check(!UnknownCandidateWindows.eligible(true,"uncertain",Double.NaN,"pending_anonymous_confirmation",false))
 check(!UnknownCandidateWindows.eligible(true,"uncertain",0.09,"pending_anonymous_confirmation",false))
 check(!UnknownCandidateWindows.eligible(true,"uncertain",0.2,"ambiguous_multiple",false))
 check(!UnknownCandidateWindows.eligible(true,"uncertain",0.2,"low_quality_window",false))
 check(!UnknownCandidateWindows.eligible(true,"uncertain",0.2,"pending_limit_reached",false))
 check(!UnknownCandidateWindows.eligible(true,"uncertain",0.2,"overlapping_source_evidence_not_reused",false))
 check(!UnknownCandidateWindows.eligible(true,"uncertain",0.2,"pending_anonymous_confirmation",true))
 check(!UnknownCandidateWindows.eligible(true,"wearer_candidate",0.2,"pending_anonymous_confirmation",false))
 check(r(p(0,3000,true))==listOf(0 to 3000))
 check(r(p(0,3000,true),p(1500,4500,true))==listOf(0 to 4500))
 // All samples touched by a failed/conflicting feature probe are excluded, including overlaps.
 check(r(p(0,3000,true),p(1500,4500,false),p(4500,7500,true))==listOf(4500 to 7500))
 // A successful cell may begin inside the full input of an earlier failed probe.
 // Learning starts only after that entire failed acoustic window, not at 3.85s.
 check(r(p(3850,7500,true),p(1500,4500,false))==listOf(4500 to 7500))
 check(r(p(3850,7400,true),p(1500,4500,false)).isEmpty())
 check(r(p(0,3000,false),p(1500,4500,true)).isEmpty())
 check(r(p(0,3000,true),p(1500,4500,false)).isEmpty())
 check(r(p(0,3000,true),p(4500,7500,true))==listOf(0 to 3000,4500 to 7500))
 check(r(p(0,2999,true)).isEmpty())
 check(UnknownCandidateWindows.ranges(emptyList(),3000).isEmpty())
 println("UnknownCandidateWindowsTest: pass")
}
