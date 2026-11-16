package org.hotwordtest

import org.json.JSONObject

/** Conservative retry boundaries. The rejected window and 100 ms around it never enter a retry. */
internal object AutomaticRetryPlan {
 /** Unexamined or conflicting audio removed by edge rescue cannot be treated as
  * positive identity evidence. Legacy rescues also require manual review. */
 fun edgeRescueReviewOnly(continuity:JSONObject?):Boolean = continuity?.optJSONObject("edge_rescue")!=null
 private const val MIN_SAMPLES=3L*16000L
 private const val GUARD_SAMPLES=1600L
 private const val MAX_SAMPLES=8L*16000L
 fun outsideFailure(start:Long,end:Long,failedStart:Long,failedEnd:Long):List<Pair<Long,Long>> {
  if(start<0||end<=start||failedStart<start||failedEnd>end||failedEnd<=failedStart)return emptyList()
  val leftEnd=(failedStart-GUARD_SAMPLES).coerceAtLeast(start)
  val rightStart=(failedEnd+GUARD_SAMPLES).coerceAtMost(end)
  val parts=mutableListOf<Pair<Long,Long>>()
  if(leftEnd-start>=MIN_SAMPLES)parts.add(maxOf(start,leftEnd-MAX_SAMPLES) to leftEnd)
  if(end-rightStart>=MIN_SAMPLES)parts.add(rightStart to minOf(end,rightStart+MAX_SAMPLES))
  return parts.sortedByDescending{it.second-it.first}
 }
 /** Only retry if the complete two-model parent check contains no competing identity.
  * Unavailable scores, multiple separated failures and missing windows are not evidence. */
 fun failedSpan(d:JSONObject,startSample:Long,endSample:Long):Pair<Long,Long>? {
  if(startSample<0||endSample-startSample<48000L)return null
  if(d.optString("stage")!="automatic"||d.optString("status")!="window_not_high_confidence")return null
  val models=d.optJSONArray("models")?:return null
  if(models.length()!=2)return null
  val names=mutableSetOf<String>();val failed=mutableListOf<Pair<Long,Long>>()
  for(i in 0 until models.length()){
   val model=models.optJSONObject(i)?:return null;names.add(model.optString("model"))
   val windows=model.optJSONArray("windows")?:return null
   if(windows.length()<2)return null
   var independent=0
   for(j in 0 until windows.length()){
    val w=windows.optJSONObject(j)?:return null
    val start=w.optLong("start_sample",-1);val end=w.optLong("end_sample",-1)
    val score=w.optDouble("target_score",Double.NaN);val margin=w.optDouble("margin",Double.NaN)
    val competitor=w.optDouble("other_best_score",Double.NaN)
    if(w.optBoolean("independent",false))independent++
    if(start<startSample||end>endSample||end-start!=24000L||!w.optBoolean("best_is_target",false)||
       !score.isFinite()||!competitor.isFinite()||!margin.isFinite()||margin<0.15)return null
    val status=w.optString("match_status")
    if(status=="wearer_candidate"&&score>=0.70)continue
    if(status!="uncertain"||score>=0.70)return null
    failed.add(start to end)
   }
   if(independent<2)return null
  }
  if(names!=setOf("CAM++","ERes2NetV2")||failed.isEmpty())return null
  val ordered=failed.sortedBy{it.first};var end=ordered.first().second
  for((a,b) in ordered.drop(1)){if(a>end)return null;end=maxOf(end,b)}
  return ordered.first().first to end
 }
}
