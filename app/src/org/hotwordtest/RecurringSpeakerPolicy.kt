package org.hotwordtest

import java.util.PriorityQueue
import kotlin.math.sqrt

/** Conservative discovery evidence, not calibrated speaker identity proof.
 * start/end and all duration constants are 16kHz sample coordinates, end exclusive.
 * Callers must freeze accepted initial templates; this policy never moves a centroid. */
internal object RecurringSpeakerPolicy {
 const val MIN_INTERVAL_SAMPLES=48000L
 const val MIN_TOTAL_SAMPLES=144000L
 const val MIN_GAP_SAMPLES=16000L
 const val MIN_INTERVAL_COUNT=3
 const val MATCH_THRESHOLD=0.70
 const val AMBIGUITY_MARGIN=0.10
 data class Evidence(val id:String,val source:String,val start:Long,val end:Long,
                     val vectors:Map<String,FloatArray>,val acousticStrong:Boolean)
 data class Rejected(val evidence:Evidence,val reason:String)
 data class Decision(val groups:List<List<Evidence>>,val rejected:List<Rejected>)
 private val models=listOf(SpeakerModels.CAM,SpeakerModels.ERES)

 fun cosine(a:FloatArray,b:FloatArray):Double? {
  if(a.isEmpty()||a.size!=b.size)return null
  var dot=0.0;var aa=0.0;var bb=0.0
  for(i in a.indices){
   if(!a[i].isFinite()||!b[i].isFinite())return null
   val x=a[i].toDouble();val y=b[i].toDouble()
   dot+=x*y;aa+=x*x;bb+=y*y
  }
  if(aa<=0.0||bb<=0.0||!aa.isFinite()||!bb.isFinite())return null
  return (dot/sqrt(aa)/sqrt(bb)).takeIf{it.isFinite()}?.coerceIn(-1.0,1.0)
 }
 private fun exactVectors(a:Evidence,b:Evidence)=models.all{a.vectors.getValue(it).contentEquals(b.vectors.getValue(it))}
 private fun separated(a:Evidence,b:Evidence):Boolean {
  if(!FileSourceIdentity.sameForDedup(a.source,b.source))return true
  // Subtraction is safe after validating nonnegative coordinates and interval order.
  return if(a.end<=b.start)b.start-a.end>=MIN_GAP_SAMPLES
   else if(b.end<=a.start)a.start-b.end>=MIN_GAP_SAMPLES else false
 }
 private data class Merge(val a:Int,val b:Int,val av:Int,val bv:Int,val score:Double)
 fun groups(evidence:List<Evidence>):List<List<Evidence>> = decide(evidence).groups

 fun decide(evidence:List<Evidence>):Decision {
  val rejected=mutableListOf<Rejected>();val accepted=mutableListOf<Evidence>()
  val ids=mutableSetOf<String>()
  // Earliest finish preserves independent short utterances when longer reimports overlap.
  val order=compareBy<Evidence>({it.source},{it.end},{it.start},{it.id})
  for(row in evidence.sortedWith(order)){
   val reason=when {
    row.id.isBlank()||row.source.isBlank()->"missing_identity"
    row.start<0||row.end<row.start||row.end-row.start<MIN_INTERVAL_SAMPLES->"short_or_invalid_interval"
    !row.acousticStrong->"acoustic_not_strong"
    models.any{model->row.vectors[model]?.let{cosine(it,it)}==null}->"invalid_dual_vectors"
    !ids.add(row.id)->"duplicate_id"
    accepted.any{exactVectors(it,row)}->"duplicate_vectors"
    accepted.any{!separated(it,row)}->"overlap_or_insufficient_gap"
    else->null
   }
   if(reason==null)accepted.add(row) else rejected.add(Rejected(row,reason))
  }
  val n=accepted.size
  if(n==0)return Decision(emptyList(),rejected)
  // Original pair scores remain immutable for the final ambiguity check.
  val pair=Array(n){DoubleArray(n){-1.0}}
  for(i in 0 until n){pair[i][i]=1.0;for(j in i+1 until n){
   val score=models.minOf{model->cosine(accepted[i].vectors.getValue(model),accepted[j].vectors.getValue(model))?:-1.0}
   pair[i][j]=score;pair[j][i]=score
  }}
  val links=Array(n){pair[it].copyOf()}
  val clusters=Array(n){mutableListOf(it)};val active=BooleanArray(n){true};val versions=IntArray(n)
  val queue=PriorityQueue<Merge>(compareByDescending<Merge>{it.score}.thenBy{it.a}.thenBy{it.b})
  fun offer(a:Int,b:Int){
   val left=minOf(a,b);val right=maxOf(a,b)
   if(links[left][right]>=MATCH_THRESHOLD)queue.add(Merge(left,right,versions[left],versions[right],links[left][right]))
  }
  for(i in 0 until n)for(j in i+1 until n)offer(i,j)
  while(queue.isNotEmpty()){
   val edge=queue.remove();val a=edge.a;val b=edge.b
   if(!active[a]||!active[b]||versions[a]!=edge.av||versions[b]!=edge.bv)continue
   clusters[a].addAll(clusters[b]);active[b]=false;versions[a]++
   for(k in 0 until n)if(active[k]&&k!=a){
    // Every pair across both clusters must pass both models, preventing chain merges.
    val score=minOf(links[a][k],links[b][k]);links[a][k]=score;links[k][a]=score;offer(a,k)
   }
  }
  val finalClusters=(0 until n).filter{active[it]}.map{clusters[it]}
  val groups=mutableListOf<List<Evidence>>()
  for(cluster in finalClusters){
   val members=mutableListOf<Evidence>()
   for(i in cluster){
    val own=cluster.filter{it!=i}.minOfOrNull{pair[i][it]}?:-1.0
    val competitor=finalClusters.filter{it!==cluster}.maxOfOrNull{other->other.minOf{pair[i][it]}}?:-1.0
    val reason=when {
     // Final complete-link clusters are incompatible. A bridge matching both stays out,
     // even if the strongest-first merge initially attached it to one of them.
     competitor>=MATCH_THRESHOLD->"ambiguous_groups"
     cluster.size>1&&own-competitor<AMBIGUITY_MARGIN->"insufficient_margin"
     else->null
    }
    if(reason==null)members.add(accepted[i]) else rejected.add(Rejected(accepted[i],reason))
   }
   // Saturating at the required duration avoids overflow for unusual long coordinates.
   val total=members.fold(0L){sum,row->minOf(MIN_TOTAL_SAMPLES,sum+minOf(MIN_TOTAL_SAMPLES,row.end-row.start))}
   if(members.size>=MIN_INTERVAL_COUNT&&total>=MIN_TOTAL_SAMPLES)groups.add(members.sortedWith(order))
   else members.forEach{rejected.add(Rejected(it,"insufficient_recurrence"))}
  }
  return Decision(groups.sortedBy{it.first().id},rejected)
 }
}
