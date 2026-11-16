package org.hotwordtest

fun main(){
 fun direction(from:String?,fromDepth:Int,to:String,toDepth:Int)=
  VoicePageMotionPolicy.forPage(from,fromDepth,to,toDepth)
 check(direction(null,0,"list",1)==VoicePageMotionPolicy.Motion.PUSH)
 check(direction("list",1,"detail:1",2)==VoicePageMotionPolicy.Motion.PUSH)
 check(direction("detail:1",2,"add",3)==VoicePageMotionPolicy.Motion.PUSH)
 check(direction("add",3,"detail:1",2)==VoicePageMotionPolicy.Motion.POP)
 check(direction("candidates",2,"list",1)==VoicePageMotionPolicy.Motion.POP)
 check(direction("detail:1",2,"candidates",2)==VoicePageMotionPolicy.Motion.PUSH)
 check(direction("candidates",2,"candidates",2)==VoicePageMotionPolicy.Motion.REFRESH)
 println("VoicePageMotionPolicyTest: pass")
}
