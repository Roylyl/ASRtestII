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
  var best=Double.NEGATIVE_INFINITY
  var second=Double.NEGATIVE_INFINITY
  var count=0
  for(supplement in supplements){
   val score=cosine(query,supplement)?:continue
   count++
   if(java.lang.Double.compare(score,best)>0){
    second=best
    best=score
   }else if(java.lang.Double.compare(score,second)>0)second=score
  }
  // Keep the single-supplement estimate when more templates are added. Otherwise a
  // weaker second template can undo a match that the first template already made.
  val one=if(count>0)best*0.85+anchor*0.15 else anchor
  val two=if(count>=2)best*0.70+second*0.30 else anchor
  return Score(maxOf(anchor,one,two),anchor,count)
 }
}
