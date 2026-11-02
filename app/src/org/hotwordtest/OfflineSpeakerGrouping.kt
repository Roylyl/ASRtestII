package org.hotwordtest

/** Conservative post-grouping of one file; source diarization IDs and timestamps remain untouched. */
object OfflineSpeakerGrouping {
 data class Observation(val startMs:Long,val endMs:Long,val rawIds:List<Int>,val textPresent:Boolean,
                        val group:String?,val status:String,val kind:String,val decision:String,
                        val similarity:Double?,val margin:Double?)
 data class Assignment(val groups:List<String?>,val reasons:List<String>)
 data class Result(val assignments:List<Assignment>,val stableGroups:Set<String>)
 private val validGroup=Regex("S[1-9][0-9]*")
 data class Config(val strong:Double=SpeakerDefaults.OFFLINE_DIRECT_STRONG,val weak:Double=SpeakerDefaults.OFFLINE_DIRECT_WEAK,val margin:Double=SpeakerDefaults.MARGIN_PERCENT/100.0)
 private fun direct(o:Observation,config:Config):String? {
  val g=o.group ?: return null
  if(!validGroup.matches(g)||!o.textPresent||o.rawIds.size!=1||o.endMs<=o.startMs)return null
  if(o.status !in setOf("experimental_segment_assignment","weak_candidate"))return null
  val strong=o.kind=="strong" && o.status=="experimental_segment_assignment"
  val weak=o.status=="weak_candidate"
  if(!strong&&!weak)return null
  if(o.similarity==null){if(!strong||o.decision !in setOf("initial_group","new_group"))return null}
  else if(!o.similarity.isFinite()||o.similarity < (if(strong)config.strong else config.weak))return null
  if(o.margin!=null&&(!o.margin.isFinite()||o.margin<config.margin))return null
  return g
 }
 data class Display(val label:String,val category:String)
 /** Names and raw window IDs remain candidates, never verified identities. */
 fun display(o:Observation,a:Assignment,registeredName:String?,windowLocal:Boolean):Display {
  val assigned=a.groups.mapNotNull{it}
  fun name(g:String)="说话人"+g.removePrefix("S")
  if(o.rawIds.size>1){
   if(windowLocal)return Display("重叠："+o.rawIds.distinct().joinToString("、"){"说话人${it+1}（本窗口）"},"overlap")
   val known=assigned.distinct().map(::name);return Display(if(known.isEmpty())"多人重叠，身份待确认" else "重叠："+(known+if(a.groups.any{it==null})listOf("另有未归组声音") else emptyList()).joinToString("、"),"overlap")
  }
  if(o.rawIds.size==1 && !registeredName.isNullOrBlank())return Display(registeredName,"registered_candidate")
  if(o.rawIds.size==1 && assigned.size==1)return Display(name(assigned.single()),"grouped")
  if(o.rawIds.size==1 && windowLocal)return Display("说话人${o.rawIds.single()+1}（本窗口）","window_local")
  return Display("说话人待确认","ungrouped")
 }
 fun assign(input:List<Observation>,config:Config=Config()):Result {
  // A group is eligible only after independent strong, non-overlapping observations.
  val evidence=linkedMapOf<String,MutableList<Observation>>()
  for(o in input){val g=direct(o,config)?:continue;if(o.kind=="strong"&&o.endMs-o.startMs>=2000)evidence.getOrPut(g){mutableListOf()}.add(o)}
  val stable=evidence.filterValues { items ->
   val chosen=mutableListOf<Observation>()
   for(item in items.sortedBy{it.startMs})if(chosen.none{it.startMs<item.endMs&&item.startMs<it.endMs})chosen.add(item)
   chosen.size>=SpeakerDefaults.STABLE_OBSERVATIONS && chosen.sumOf{minOf(8000L,it.endMs-it.startMs)}>=SpeakerDefaults.STABLE_DURATION_MS
  }.keys
  // Raw tracks may combine people; allow inheritance only with repeated, consistent strong evidence.
  val votes=linkedMapOf<Int,MutableMap<String,MutableList<Observation>>>()
  for(o in input){val g=direct(o,config)?:continue;if(g !in stable||o.kind!="strong")continue;votes.getOrPut(o.rawIds.single()){linkedMapOf()}.getOrPut(g){mutableListOf()}.add(o)}
  val rawMap=mutableMapOf<Int,String>()
  for((id,groups) in votes){
   val ranked=groups.entries.sortedByDescending{it.value.size};val top=ranked.first();val all=ranked.sumOf{it.value.size}
   val duration=ranked.sumOf{e->e.value.sumOf{minOf(8000L,it.endMs-it.startMs)}}
   val topDuration=top.value.sumOf{minOf(8000L,it.endMs-it.startMs)}
   if(top.value.size>=SpeakerDefaults.STABLE_OBSERVATIONS&&top.value.size*5>=all*4&&topDuration>=SpeakerDefaults.STABLE_DURATION_MS&&topDuration*5>=duration*4)rawMap[id]=top.key
  }
  val assignments=input.map { o ->
   val directGroup=direct(o,config)?.takeIf{it in stable}
   val groups=o.rawIds.mapIndexed { index,id ->
    if(o.rawIds.size==1&&index==0&&directGroup!=null)directGroup else rawMap[id]
   }.toMutableList()
   val reasons=o.rawIds.mapIndexed { index,id ->
    if(groups[index]==null)"unassigned" else if(o.rawIds.size==1&&directGroup!=null)"direct_voice" else "track_evidence"
   }.toMutableList()
   // Overlapping sources must never be rendered as one person.
   val duplicate=groups.filterNotNull().groupingBy{it}.eachCount().filterValues{it>1}.keys
   for(i in groups.indices)if(groups[i] in duplicate){groups[i]=null;reasons[i]="overlap_conflict"}
   Assignment(groups,reasons)
  }
  return Result(assignments,stable.toSet())
 }
}
