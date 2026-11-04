package org.hotwordtest
fun main(){
 fun o(a:Long,b:Long,ids:List<Int>,g:String?=null,kind:String="strong",sim:Double?=0.8,status:String="experimental_segment_assignment",decision:String="match_existing")=OfflineSpeakerGrouping.Observation(a,b,ids,true,g,status,kind,decision,sim,0.3)
 val short=OfflineSpeakerGrouping.assign(listOf(o(0,2500,listOf(7),"S1"),o(3000,4000,listOf(8))))
 check(short.stableGroups.isEmpty());check(short.assignments[0].groups==listOf(null));check(short.assignments[1].groups==listOf(null));check(OfflineSpeakerGrouping.display(o(3000,4000,listOf(8)),short.assignments[1],"登记候选",false).label=="登记候选");check(OfflineSpeakerGrouping.display(o(3000,4000,listOf(8)),short.assignments[1],null,true).label=="说话人9");check(OfflineSpeakerGrouping.display(o(0,4000,listOf(1,2)),OfflineSpeakerGrouping.Assignment(listOf(null,null),listOf("unassigned","unassigned")),"不能出现在重叠",true).category=="overlap")
 val rows=listOf(o(0,2500,listOf(0),"S1"),o(3000,5500,listOf(0),"S1"),o(6000,8500,listOf(1),"S2"),o(9000,11500,listOf(1),"S2"),o(12000,14500,listOf(2),"S3"),o(15000,17500,listOf(2),"S3"),o(18000,18500,listOf(9)),o(19000,20000,listOf(0,1)),o(21000,22000,listOf(0,0)))
 val r=OfflineSpeakerGrouping.assign(rows);check(r.stableGroups==setOf("S1","S2","S3"));check(r.assignments[6].groups==listOf(null));check(r.assignments[7].groups==listOf("S1","S2"));check(r.assignments[8].groups==listOf(null,null));val overrideRows=rows.take(2)+OfflineSpeakerGrouping.Observation(22000,23000,listOf(10),true,"S1","weak_candidate","short_audio","match_existing",0.8,0.10)
 val defaultMargin=OfflineSpeakerGrouping.assign(overrideRows);val higherMargin=OfflineSpeakerGrouping.assign(overrideRows,OfflineSpeakerGrouping.Config(margin=0.20));check(defaultMargin.assignments.last().groups==listOf("S1"));check(higherMargin.assignments.last().groups==listOf(null))
 check(SpeakerDecisionConfig().strongMatch==0.45 && SpeakerDecisionConfig().weakMatch==0.40 && SpeakerDecisionConfig().newSpeakerMax==0.25 && SpeakerDecisionConfig().ambiguityMargin==0.08)
 println("PASS three speakers, short fallback, registered name, overlap, explicit margin, calibrated defaults")
}
