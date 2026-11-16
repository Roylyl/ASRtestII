package org.hotwordtest

/** File-only transcript context for one continuous, already-analyzed candidate. */
internal object SpanningCandidatePlan {
 data class Row(val start:Long,val end:Long,val text:String,val stableId:String?,val blocked:Boolean)
 data class Piece(val row:Row,val start:Long,val end:Long)
 fun coverage(start:Long,end:Long,rows:List<Row>):List<Piece>? {
  if(start<0||end<=start)return null
  val selected=rows.filter{it.start<end&&it.end>start}.sortedWith(compareBy<Row>{it.start}.thenBy{it.end})
  if(selected.isEmpty()||selected.size>100||selected.any{it.start<0||it.end<=it.start||it.blocked||it.text.length>5000})return null
  // Never infer that an unknown row belongs to a neighboring known person.
  val identities=selected.map{it.stableId?.takeIf{id->id.isNotBlank()}?:return null}
  if(identities.distinct().size!=1)return null
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
