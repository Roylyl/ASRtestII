package org.hotwordtest

/** A refresh stays still; deeper or lateral pages enter from the right. */
internal object VoicePageMotionPolicy {
 enum class Motion { REFRESH, PUSH, POP }
 fun forPage(previousKey:String?,previousDepth:Int,nextKey:String,nextDepth:Int):Motion = when {
  previousKey==nextKey -> Motion.REFRESH
  nextDepth<previousDepth -> Motion.POP
  else -> Motion.PUSH
 }
}
