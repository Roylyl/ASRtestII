package org.hotwordtest

import kotlin.math.sqrt

/** Deterministic average-linkage cosine clustering for independently sampled strong turns. */
object OfflineVoiceCluster {
 data class Sample(val index:Int,val start:Long,val end:Long,val vector:FloatArray?,val strong:Boolean)
 data class Outcome(val labels:Map<Int,String>,val strongIndices:Set<Int>,val clusterCount:Int)
 fun infer(samples:List<Sample>, threshold:Double=0.58, match:Double=0.45, margin:Double=0.08):Outcome? {
  val usable=samples.filter{it.vector!=null&&it.end>it.start&&it.vector!!.all(Float::isFinite)}
  val strong=usable.filter{it.strong&&it.end-it.start>=2000}.sortedBy{it.start}
  if(strong.size<4||strong.size>512)return null
  val dim=strong.first().vector!!.size
  if(dim==0||usable.any{it.vector!!.size!=dim})return null
  fun dot(a:FloatArray,b:FloatArray):Double {var x=0.0;for(i in a.indices)x+=a[i].toDouble()*b[i];return x}
  val similarities=Array(strong.size){DoubleArray(strong.size)}
  for(i in strong.indices)for(j in 0 until i){val x=dot(strong[i].vector!!,strong[j].vector!!);similarities[i][j]=x;similarities[j][i]=x}
  val groups=strong.indices.map{mutableListOf(it)}.toMutableList()
  while(groups.size>1){
   var best=threshold;var bi=-1;var bj=-1
   for(i in groups.indices)for(j in 0 until i){
    var sum=0.0; for(a in groups[i])for(b in groups[j])sum+=similarities[a][b]
    val sim=sum/(groups[i].size*groups[j].size)
    if(sim>best){best=sim;bi=i;bj=j}
   }
   if(bi<0)break
   groups[bj].addAll(groups[bi]);groups.removeAt(bi)
  }
  val stable=groups.filter{g->
   val chosen=mutableListOf<Sample>()
   for(x in g.map{strong[it]}.sortedBy{it.start})if(chosen.none{it.start<x.end&&x.start<it.end})chosen.add(x)
   chosen.size>=2&&chosen.sumOf{it.end-it.start}>=4000
  }.sortedBy{g->g.minOf{strong[it].start}}
  if(stable.size<2||stable.size>32)return null
  val centers=stable.map{g->
   val v=DoubleArray(dim);for(i in g)for(k in 0 until dim)v[k]+=strong[i].vector!![k]
   val norm=sqrt(v.sumOf{it*it}); if(norm<=1e-9)return null
   DoubleArray(dim){v[it]/norm}
  }
  val labels=mutableMapOf<Int,String>();val strongIndices=mutableSetOf<Int>()
  stable.forEachIndexed{g,items->items.forEach{i->labels[strong[i].index]="S${g+1}";strongIndices.add(strong[i].index)}}
  for(s in usable){
   if(labels.containsKey(s.index))continue
   val scores=centers.mapIndexed{idx,c->idx to c.indices.sumOf{c[it]*s.vector!![it]}}.sortedByDescending{it.second}
   if(scores[0].second>=match&&(scores.size==1||scores[0].second-scores[1].second>=margin))labels[s.index]="S${scores[0].first+1}"
  }
  return Outcome(labels,strongIndices,stable.size)
 }
}
