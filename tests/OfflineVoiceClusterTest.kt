package org.hotwordtest
fun main(){
 fun s(i:Int,v:FloatArray,strong:Boolean=true,start:Long=i*3000L,end:Long=start+2500L)=OfflineVoiceCluster.Sample(i,start,end,v,strong)
 val a=floatArrayOf(1f,0f,0f);val b=floatArrayOf(0f,1f,0f);val c=floatArrayOf(0f,0f,1f)
 check(OfflineVoiceCluster.infer((0..5).map{s(it,a)})==null)
 val samples=listOf(s(0,a),s(1,b),s(2,c),s(3,a),s(4,b),s(5,c),s(6,a,false,18000,19200),s(7,floatArrayOf(.7f,.7f,0f),false,21000,22200))
 val r=OfflineVoiceCluster.infer(samples)!!
 check(r.clusterCount==3);check(r.labels[0]==r.labels[3]);check(r.labels[1]==r.labels[4]);check(r.labels[2]==r.labels[5]);check(setOf(r.labels[0],r.labels[1],r.labels[2]).size==3)
 check(r.labels[6]==r.labels[0]);check(r.labels[7]==null)
 // Same-time observations cannot by themselves establish a persistent identity.
 val overlap=listOf(s(0,a,start=0,end=2500),s(1,a,start=0,end=2500),s(2,b,start=3000,end=5500),s(3,b,start=3000,end=5500))
 check(OfflineVoiceCluster.infer(overlap)==null)
 println("one/three groups, short answers, ambiguity, independent evidence: OK")
}
