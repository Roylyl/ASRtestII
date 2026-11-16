package org.hotwordtest

import org.hotwordtest.LiveLearningCandidateMode.Route

fun main(){
 fun route(source:Boolean=true,overlap:Boolean=false,stable:Boolean=false,missing:Boolean=true,
           display:String="说话人未识别",method:String="awaiting_independent_voice_windows",supports:Boolean=false)=
  LiveLearningCandidateMode.decide(source,overlap,stable,missing,display,method,supports)
 check(route()==Route.UNRESOLVED)
 check(route(method="insufficient_pure_activity")==Route.UNRESOLVED)
 check(LiveLearningCandidateMode.mayReviewSubintervals(route(),1,0))
 check(LiveLearningCandidateMode.mayReviewSubintervals(route(),0,1))
 check(!LiveLearningCandidateMode.mayReviewSubintervals(route(),0,0))
 check(!LiveLearningCandidateMode.mayReviewSubintervals(route(overlap=true),1,1))
 check(!LiveLearningCandidateMode.mayReviewSubintervals(route(source=false),1,1))
 check(!LiveLearningCandidateMode.mayReviewSubintervals(route(method="conflicting_voice_evidence"),1,1))
 check(route(stable=true,missing=false,display="说话人1",method="pure_activity_voice_evidence",supports=true)==Route.RESOLVED)
 check(!LiveLearningCandidateMode.mayReviewSubintervals(Route.RESOLVED,1,1))
 check(route(overlap=true)==Route.BLOCKED)
 check(route(source=false)==Route.BLOCKED)
 check(route(method="raw_activity_overlap")==Route.BLOCKED)
 check(route(method="conflicting_voice_evidence")==Route.BLOCKED)
 check(route(missing=false)==Route.BLOCKED)
 check(route(display="多人重叠")==Route.BLOCKED)
 check(route(stable=true,missing=false,display="说话人1",method="pure_activity_voice_evidence",supports=false)==Route.BLOCKED)
 println("LiveLearningCandidateModeTest: pass")
}
