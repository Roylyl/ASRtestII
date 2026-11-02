package org.hotwordtest
import java.io.File
fun main(args:Array<String>){
 val input=File(args[0]).readLines().map{line->val a=line.split('\t');OfflineSpeakerGrouping.Observation(a[0].toLong(),a[1].toLong(),a[2].split(',').filter{it.isNotEmpty()}.map{it.toInt()},a[3].toBoolean(),a[4].ifEmpty{null},a[5],a[6],a[7],a[8].toDoubleOrNull(),a[9].toDoubleOrNull())}
 val r=OfflineSpeakerGrouping.assign(input)
 File(args[1]).writeText(input.indices.joinToString("\n"){i->val x=input[i];val groups=r.assignments[i].groups;listOf(x.startMs,x.endMs,x.rawIds.joinToString(","),groups.joinToString(","){it?:"?"}).joinToString("\t")})
 println("stableGroups=${r.stableGroups}")
}
