package org.hotwordtest

/** File-only transcript context for one continuous, already-analyzed candidate. */
internal object SpanningCandidatePlan {
 data class Row(val start:Long,val end:Long,val text:String,val stableId:String?,val blocked:Boolean,
                val deferredEdge:Boolean=false)
 data class Piece(val row:Row,val start:Long,val end:Long)
 fun coverage(start:Long,end:Long,rows:List<Row>):List<Piece>? {
  if(start<0||end<=start)return null
  val selected=rows.filter{it.start<end&&it.end>start}.sortedWith(compareBy<Row>{it.start}.thenBy{it.end})
  if(selected.isEmpty()||selected.size>100||selected.any{it.start<0||it.end<=it.start||it.blocked||it.text.length>5000})return null
  val known=selected.mapIndexedNotNull{index,row->row.stableId?.takeIf{it.isNotBlank()}?.let{index to it}}
  if(known.isEmpty()||known.map{it.second}.distinct().size!=1)return null
  // An explicitly deferred edge can be evaluated acoustically for manual review;
  // it remains unknown in the transcript and cannot assign an identity to the clip.
  if(known.size!=selected.size){
   val first=known.first().first;val last=known.last().first
   if(known.size!=last-first+1)return null
   val knownSamples=known.sumOf{(index,_)->minOf(end,selected[index].end)-maxOf(start,selected[index].start)}
   if(knownSamples<48000L||end-start-knownSamples>24000L)return null
   if(selected.withIndex().any{(index,row)->index !in first..last&&!row.deferredEdge})return null
  }
  var cursor=start;val pieces=mutableListOf<Piece>()
  for(row in selected){
   val a=maxOf(start,row.start);val b=minOf(end,row.end)
   // Missing or overlapping transcript rows cannot safely establish the scope.
   if(a!=cursor||b<=a)return null
   pieces.add(Piece(row,a,b));cursor=b
  }
  if(cursor!=end||pieces.count{it.row.text.isNotBlank()}<2)return null
  if(pieces.sumOf{it.row.text.length}>12000)return null
  return pieces
 }
}
