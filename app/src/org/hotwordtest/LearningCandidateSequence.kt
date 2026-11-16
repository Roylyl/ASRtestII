package org.hotwordtest

/** A bounded, known-first list for one background candidate task. */
internal object LearningCandidateSequence {
 data class Item<T>(val value:T,val known:Boolean)
 fun <T> order(known:List<T>,unknown:List<T>,key:(T)->String,length:(T)->Long):List<Item<T>> {
  fun select(items:List<T>,isKnown:Boolean)=items.sortedByDescending(length).take(24).map{Item(it,isKnown)}
  return (select(known,true)+select(unknown,false)).distinctBy{key(it.value)}
 }
 fun <T,R:Any> firstAccepted(items:List<Item<T>>,attempt:(Item<T>)->R?):R? {
  for(item in items)attempt(item)?.let{return it}
  return null
 }
 /** Retrying a rejected continuous candidate may only narrow its original interval.
  * Halves are disjoint, each meets the same minimum duration and neither crosses a
  * track boundary; callers must still re-run all quality and identity checks. */
 fun safeHalves(start:Long,end:Long,minimum:Long):List<Pair<Long,Long>> {
  if(start<0||end<=start||minimum<=0)return emptyList()
  val length=end-start
  if(length/2<minimum)return emptyList()
  val split=start+length/2
  return listOf(start to split,split to end)
 }
 /** Unknown, activity, conflict and model failures never authorize retry. */
 fun mayRetryForReview(stage:String,safeForReviewQualityRescue:Boolean):Boolean =
  stage=="quality" && safeForReviewQualityRescue
 /** Do not let a later volume/silence rejection hide stronger identity or activity evidence. */
 fun rejectionPriority(stage:String):Int=when(stage){
  "quality"->0
  "continuity","target_conflict","anchor","activity"->3
  else->2
 }
}
