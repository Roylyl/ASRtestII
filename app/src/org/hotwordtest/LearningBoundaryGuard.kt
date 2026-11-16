package org.hotwordtest

/** Extra exclusion for template audio only. A missing competing track cannot be detected here. */
internal object LearningBoundaryGuard {
 const val GUARD_SAMPLES=8000L
 data class Span(val start:Long,val end:Long)

 fun retained(start:Long,end:Long,competitors:List<Span>,minimum:Long):List<Span> {
  require(start>=0&&end>start&&minimum>0)
  val protected=competitors.asSequence().filter{it.end>it.start}.map{
   val a=if(it.start<GUARD_SAMPLES)0 else it.start-GUARD_SAMPLES
   val b=if(it.end>Long.MAX_VALUE-GUARD_SAMPLES)Long.MAX_VALUE else it.end+GUARD_SAMPLES
   Span(a,b)
  }.filter{it.start<end&&it.end>start}.sortedBy{it.start}.toList()
  val result=mutableListOf<Span>();var cursor=start
  for(bad in protected){
   val cut=bad.start.coerceIn(start,end)
   if(cut>cursor&&cut-cursor>=minimum)result.add(Span(cursor,cut))
   cursor=maxOf(cursor,bad.end.coerceAtMost(end))
   if(cursor>=end)break
  }
  if(end-cursor>=minimum)result.add(Span(cursor,end))
  return result
 }
}
