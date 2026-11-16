package org.hotwordtest

fun main(){
 fun row(a:Long,b:Long,text:String="语音",id:String?="A",blocked:Boolean=false)=
  SpanningCandidatePlan.Row(a,b,text,id,blocked)
 val rows=listOf(row(37600,61600,"计划创新"),row(61600,97600,"全新设计"))
 val valid=checkNotNull(SpanningCandidatePlan.coverage(49600,97600,rows))
 check(valid.size==2&&valid[0].start==49600L&&valid[0].row.start==37600L&&valid[1].end==97600L)
 check(SpanningCandidatePlan.coverage(49600,97600,listOf(rows[0],row(61601,97600)))==null)
 check(SpanningCandidatePlan.coverage(49600,97600,listOf(rows[0],row(61599,97600)))==null)
 check(SpanningCandidatePlan.coverage(49600,97600,listOf(rows[0],row(61600,97600,blocked=true)))==null)
 check(SpanningCandidatePlan.coverage(49600,97600,listOf(row(37600,61600,id="A"),row(61600,97600,id="B")))==null)
 check(SpanningCandidatePlan.coverage(49600,97600,listOf(row(37600,61600,id="A"),row(61600,97600,id="A")))!=null)
 check(SpanningCandidatePlan.coverage(49600,97600,listOf(row(37600,61600,id="A"),row(61600,97600,id=null)))==null)
 check(SpanningCandidatePlan.coverage(49600,97600,listOf(row(37600,61600,id=null),row(61600,97600,id=null)))==null)
 check(SpanningCandidatePlan.coverage(49600,97600,listOf(row(37600,61600,""),rows[1]))==null)
 // If a later acoustic trim leaves only one text row, no spanning context is stored.
 check(SpanningCandidatePlan.coverage(61600,97600,rows)==null)
 println("SpanningCandidatePlanTest: pass")
}
