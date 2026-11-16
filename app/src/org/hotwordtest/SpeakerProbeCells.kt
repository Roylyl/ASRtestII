package org.hotwordtest

/** Partition identity probes without attribution outside their evidence. */
internal object SpeakerProbeCells {
 data class Probe(val start:Int,val end:Int,val available:Boolean) {
  init { require(start>=0 && end>start) }
  val center:Int get()=start+(end-start)/2
 }
 fun mayBorrow(available:Boolean,left:String?,right:String?):Boolean = available && left!=null && left==right
 fun bounds(probes:List<Probe>):List<Pair<Int,Int>> {
  require(probes.zipWithNext().all{(a,b)->a.center<=b.center})
  // Use one shared cut for overlapping probes, constrained to audio both actually
  // cover. Short tail probes can start after the midpoint of the two centers.
  val cuts=probes.zipWithNext().map{(a,b)->
   val midpoint=a.center+(b.center-a.center)/2
   val overlapStart=maxOf(a.start,b.start)
   val overlapEnd=minOf(a.end,b.end)
   if(overlapStart<=overlapEnd)midpoint.coerceIn(overlapStart,overlapEnd) else midpoint
  }
  return probes.mapIndexed { i,p ->
   val begin=if(i==0)p.start else maxOf(p.start,cuts[i-1])
   val end=if(i==probes.lastIndex)p.end else minOf(p.end,cuts[i])
   begin to end
  }
 }
}
