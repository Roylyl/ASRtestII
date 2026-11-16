package org.hotwordtest

/** File workers may wait for temporary reservations, within a per-run budget. */
internal class BoundedCandidateWait(private val totalMillis:Long=120_000L,private val perItemMillis:Long=15_000L) {
 enum class Result { RESERVED, DUPLICATE, FULL, BUSY, CANCELLED }
 private var waitedNanos=0L
 @Synchronized fun waitedMillis():Long=waitedNanos/1_000_000L
 fun reserve(file:Boolean,cancelled:()->Boolean,tryReserve:()->Result):Result {
  val start=System.nanoTime()
  try {
   while(true){
    if(cancelled()||Thread.currentThread().isInterrupted)return Result.CANCELLED
    val result=tryReserve()
    if(result!=Result.BUSY||!file)return result
    val elapsed=(System.nanoTime()-start)/1_000_000L
    val remaining=minOf(perItemMillis-elapsed,totalMillis-waitedMillis()-elapsed)
    if(remaining<=0)return Result.BUSY
    try{Thread.sleep(minOf(100L,remaining))}catch(_:InterruptedException){Thread.currentThread().interrupt();return Result.CANCELLED}
   }
  } finally { if(file)synchronized(this){waitedNanos+=System.nanoTime()-start} }
 }
}
