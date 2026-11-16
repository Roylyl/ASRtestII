package org.hotwordtest

/** An initial template remains an anchor. One unusually close supplement cannot replace it outright. */
internal object VoiceTemplateScoring {
 data class Score(val similarity:Double,val initial:Double,val supplementCount:Int)
 private fun cosine(a:FloatArray,b:FloatArray):Double? {
  if(a.size!=b.size || a.isEmpty())return null
  val value=a.indices.sumOf{a[it].toDouble()*b[it]}
  return value.takeIf{it.isFinite()}?.coerceIn(-1.0,1.0)
 }
 fun score(query:FloatArray,initial:FloatArray,supplements:List<FloatArray>):Score? {
  val anchor=cosine(query,initial)?:return null
  val scores=supplements.mapNotNull{cosine(query,it)}.sortedDescending()
  // Keep the single-supplement estimate when more templates are added. Otherwise a
  // weaker second template can undo a match that the first template already made.
  val one=if(scores.isNotEmpty())scores[0]*0.85+anchor*0.15 else anchor
  val two=if(scores.size>=2)scores[0]*0.70+scores[1]*0.30 else anchor
  return Score(maxOf(anchor,one,two),anchor,scores.size)
 }
}
