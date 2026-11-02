package org.hotwordtest
import java.io.File
fun main(args:Array<String>){
 val data=File(args[0]).readLines().map{line->val p=line.split('\t');OfflineVoiceCluster.Sample(p[0].toInt(),p[1].toLong(),p[2].toLong(),p[4].split(',').map{it.toFloat()}.toFloatArray(),p[3].toBoolean())}
 val result=OfflineVoiceCluster.infer(data) ?: error("No candidate clusters")
 File(args[1]).writeText(result.labels.entries.joinToString("\n"){"${it.key}\t${it.value}\t${it.key in result.strongIndices}"})
 println("clusters=${result.clusterCount} assigned=${result.labels.size}")
}
