package org.hotwordtest

import kotlin.math.cos
import kotlin.math.sin

fun main(){
 fun vector(degrees:Double):FloatArray {
  val radians=Math.toRadians(degrees);return floatArrayOf(cos(radians).toFloat(),sin(radians).toFloat())
 }
 fun e(id:String,angle:Double,start:Long=0,source:String=id,strong:Boolean=true,second:Double=angle)=
  RecurringSpeakerPolicy.Evidence(id,source,start,start+48000L,
   mapOf(SpeakerModels.CAM to vector(angle),SpeakerModels.ERES to vector(second)),strong)
 fun ids(groups:List<List<RecurringSpeakerPolicy.Evidence>>)=groups.map{g->g.map{it.id}.toSet()}.toSet()
 val clean=listOf(e("a1",-2.0),e("a2",0.0),e("a3",2.0))
 check(ids(RecurringSpeakerPolicy.groups(clean))==setOf(setOf("a1","a2","a3")))
 check(RecurringSpeakerPolicy.groups(clean.take(1)).isEmpty())
 check(RecurringSpeakerPolicy.groups(clean.take(2)).isEmpty())
 check(RecurringSpeakerPolicy.groups(listOf(e("long",0.0).copy(end=480000L))).isEmpty())
 val people=clean+listOf(e("b1",88.0),e("b2",90.0),e("b3",92.0))
 check(ids(RecurringSpeakerPolicy.groups(people))==setOf(setOf("a1","a2","a3"),setOf("b1","b2","b3")))
 check(ids(RecurringSpeakerPolicy.groups(people.reversed()))==ids(RecurringSpeakerPolicy.groups(people)))

 // Multiple copies, including different file identities, are not independent support.
 val copies=listOf(e("copy1",0.0),e("copy2",0.0),e("copy3",0.0))
 val copied=RecurringSpeakerPolicy.decide(copies)
 check(copied.groups.isEmpty());check(copied.rejected.count{it.reason=="duplicate_vectors"}==2)
 val source="content://same.wav"
 check(RecurringSpeakerPolicy.groups(listOf(e("o1",-2.0,0,source),e("o2",0.0,24000,source),e("o3",2.0,48000,source))).isEmpty())
 check(RecurringSpeakerPolicy.groups(listOf(e("g1",-2.0,0,source),e("g2",0.0,63999,source),e("g3",2.0,128000,source))).isEmpty())
 check(RecurringSpeakerPolicy.groups(listOf(e("g1",-2.0,0,source),e("g2",0.0,64000,source),e("g3",2.0,128000,source))).single().size==3)
 val zip=FileSourceIdentity.zipEntry("content://archive.zip",1,"voice.wav")
 val legacy="content://archive.zip#wav:voice.wav"
 check(RecurringSpeakerPolicy.groups(listOf(e("z1",-2.0,0,zip),e("z2",0.0,0,legacy),e("z3",2.0))).isEmpty())

 // A bridge matches each pure group, but pure groups do not match each other.
 val left=listOf(e("l1",-1.0),e("l2",0.0),e("l3",1.0))
 val right=listOf(e("r1",79.0),e("r2",80.0),e("r3",81.0))
 val bridge=RecurringSpeakerPolicy.decide(left+e("bridge",40.0)+right)
 check(ids(bridge.groups)==setOf(setOf("l1","l2","l3"),setOf("r1","r2","r3")))
 check(bridge.rejected.any{it.evidence.id=="bridge"&&it.reason=="ambiguous_groups"})
 val chain=RecurringSpeakerPolicy.groups(listOf(e("chainA",0.0),e("chainB",40.0),e("chainC",80.0)))
 check(chain.isEmpty())
 val marginal=RecurringSpeakerPolicy.decide(listOf(e("m1",0.0),e("m2",2.0),e("near",44.0),e("other",91.0)))
 check(marginal.rejected.any{it.evidence.id=="near"&&it.reason=="insufficient_margin"})

 val bad=listOf(
  e("weak",1.0,strong=false),e("short",1.0).copy(end=47999),
  e("missing",1.0).copy(vectors=mapOf(SpeakerModels.CAM to vector(1.0))),
  e("nan",1.0).copy(vectors=mapOf(SpeakerModels.CAM to floatArrayOf(Float.NaN,0f),SpeakerModels.ERES to vector(1.0))),
  e("zero",1.0).copy(vectors=mapOf(SpeakerModels.CAM to floatArrayOf(0f,0f),SpeakerModels.ERES to vector(1.0))))
 check(RecurringSpeakerPolicy.decide(bad).let{it.groups.isEmpty()&&it.rejected.size==bad.size})
 check(RecurringSpeakerPolicy.groups(clean.take(2)+e("one_model_disagrees",1.0,second=90.0)).isEmpty())
 check(RecurringSpeakerPolicy.cosine(floatArrayOf(2f,0f),floatArrayOf(4f,0f))==1.0)
 check(RecurringSpeakerPolicy.cosine(floatArrayOf(1f),floatArrayOf(1f,0f))==null)
 check(RecurringSpeakerPolicy.cosine(floatArrayOf(Float.POSITIVE_INFINITY),floatArrayOf(1f))==null)
 println("RecurringSpeakerPolicyTest: pass (pure recurrence, independent sources, duplicates, overlap, dual models, bridge and margin)")
}
