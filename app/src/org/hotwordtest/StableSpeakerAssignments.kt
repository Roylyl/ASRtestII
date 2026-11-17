package org.hotwordtest

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/** One task-scoped registry shared by files and live fragments. Activity tracks remain window-local. */
class StableSpeakerAssignments(
 assets:AssetManager,private val context:Context,private val modelKey:String,
 private val decisionConfig:SpeakerDecisionConfig=SpeakerDecisionConfig(),
 private val evidenceObserver:((FloatArray,JSONObject)->Unit)?=null
):AutoCloseable {
 private data class Window(val source:String,val start:Long,val end:Long,val track:String,
                           val proof:JSONObject,val voice:JSONObject,val identity:JSONObject)
 private data class ShortObservation(val window:Window,val support:SpeakerEvidenceGroups.Support,
                                    val pcm:FloatArray?,val verified:List<Window> = emptyList())
 private data class Raw(val source:String,val json:JSONObject)
 private data class Person(val key:String,val label:String,val profile:String?=null)
 private val tracker=LocalSpeakerTracker(assets,modelKey,decisionConfig=decisionConfig)
 private val analyzer=SpeechEvidence.Analyzer(context)
 private val matcher=WearerVoiceStore.Matcher(context,modelKey,loadExtractor=false)
 private val windows=linkedMapOf<String,Window>()
 private val shortWindows=linkedMapOf<String,ShortObservation>()
 private val raw=linkedMapOf<String,Raw>()
 private val observedCandidates=mutableSetOf<String>()
 private val refinementMetrics=mutableMapOf<String,JSONObject>()
 private val refinementDiagnostics=mutableMapOf<String,JSONArray>()
 private val diagnosticsDropped=mutableMapOf<String,Int>()
 private var unresolvedLearningSource:String?=null
 private var unresolvedLearning:List<SpeechEvidence.PureCandidate> = emptyList()
 private var closed=false

 /** Review-only intervals from the last successful observation; consume immediately. */
 @Synchronized fun takeUnresolvedLearningCandidates(source:String):List<SpeechEvidence.PureCandidate> {
  val out=if(unresolvedLearningSource==source)unresolvedLearning else emptyList()
  unresolvedLearningSource=null;unresolvedLearning=emptyList()
  return out
 }

 /** A supplied evidence object must have been computed from exactly this FloatArray. */
 @Synchronized fun observe(samples:FloatArray,source:String,startSample:Long=0L,
                           evidence:SpeechEvidence?=null):List<SpeechEvidence.PureCandidate> {
  unresolvedLearningSource=null;unresolvedLearning=emptyList()
  check(!closed){"说话人任务已经关闭"}
  require(samples.isNotEmpty()&&source.isNotBlank()&&startSample>=0)
  require(evidence==null || (evidence.source==source&&evidence.startSample==startSample&&evidence.sampleCount==samples.size))
  val startedNanos=System.nanoTime();var probeCalls=0;var boundaryProbeCalls=0;var tailProbeCalls=0
  val debug=JSONArray();var dropped=0
  fun trace(row:JSONObject){if(debug.length()<2048)debug.put(row) else dropped++}
  val saved=tracker.snapshot()
  val staged=linkedMapOf<String,Window>();val observedRaw=linkedMapOf<String,Raw>();val learning=mutableListOf<SpeechEvidence.PureCandidate>()
  val shortStaged=linkedMapOf<String,ShortObservation>()
  val unresolved=mutableListOf<SpeechEvidence.PureCandidate>()
  val completedCandidates=mutableSetOf<String>();val events=mutableListOf<Pair<FloatArray,JSONObject>>()
  val conflictedTracks=mutableSetOf<String>()
  tracker.evidenceObserver=if(evidenceObserver==null)null else {vector,row->events.add(vector to row)}
  try{
   var offset=0
   while(offset<samples.size){
    if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("说话人处理已取消")
    val end=if(evidence!=null)samples.size else minOf(samples.size,offset+30*16000)
    val wave=if(evidence!=null)samples else samples.copyOfRange(offset,end)
    val proof=evidence?:analyzer.analyze(wave,source,startSample+offset)
    val rawKey=source+"@"+proof.startSample+":"+proof.endSample
    val document=proof.toJson()
    observedRaw[rawKey]=Raw(source,JSONObject().put("activities",document.getJSONArray("activities"))
     .put("predicted_overlap",document.getJSONArray("predicted_overlap")).put("protected_intervals",document.getJSONArray("protected_intervals")))
    val available=SpeechEvidence.extractPureCandidates(wave,proof).flatMap{candidate->
     val occupied=(windows.values+staged.values).filter{it.source==source&&it.start<candidate.endSample&&it.end>candidate.startSample}.sortedBy{it.start}
     val parts=mutableListOf<SpeechEvidence.PureCandidate>();var cursor=candidate.startSample
     for(old in occupied){
      if(old.start>cursor)candidate.clip(cursor,minOf(old.start,candidate.endSample))?.let{parts.add(it)}
      cursor=maxOf(cursor,old.end);if(cursor>=candidate.endSample)break
     }
     if(cursor<candidate.endSample)candidate.clip(cursor,candidate.endSample)?.let{parts.add(it)}
     parts
    }
    for(candidate in available){
     val key=source+"@"+candidate.startSample+":"+candidate.endSample
     if(key in observedCandidates || key in completedCandidates)continue
     val quality=VoiceLearningStore.quality(context,candidate)
     if(!quality.accepted)continue
     val audio=candidate.samples()
     // Sliding identity windows are never independent anonymous-creation supports.
     data class Probe(val start:Int,val end:Int,val identity:JSONObject,val voice:JSONObject,val available:Boolean=true) {
      // Explicit receivers prevent the enclosing chunk's `end` from being captured.
      val center:Int get()=(this.start+this.end)/2
      val profile:String? get()=identity.optString("profile_id").takeIf{identity.optString("status")=="wearer_candidate"&&it.isNotBlank()}
     }
     val probes=mutableListOf<Probe>();val failedProbeRanges=mutableListOf<Pair<Int,Int>>();var at=0
     while(at+48000<=audio.size){
      probeCalls++
      val vector=tracker.embedding(audio.copyOfRange(at,at+48000));if(vector!=null){
       val identity=matcher.identifyEmbedding(vector,48000,true)
       val pureWindow=candidate.clip(candidate.startSample+at,candidate.startSample+at+48000)!!
       val independent=at%48000==0
       val voice=if(identity.optString("status")=="wearer_candidate"){
        if(independent)tracker.traceRegisteredPure(pureWindow,identity,vector)
        JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","registered_identity_match")
       } else if(independent)tracker.assignPure(pureWindow,vector)
       else JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","unconfirmed_overlapping_probe")
       probes.add(Probe(at,at+48000,identity,voice))
       trace(JSONObject().put("kind","identity_probe_3s").put("source",source).put("local_track",candidate.localTrack)
        .put("candidate_start_sample",candidate.startSample).put("candidate_end_sample",candidate.endSample)
        .put("start_sample",candidate.startSample+at).put("end_sample",candidate.startSample+at+48000)
        .put("independent_support",independent).put("identity",JSONObject(identity.toString()))
        .put("grouping",JSONObject(voice.toString())))
      }else {
       // A failed probe remains unknown; neighbours cannot supply its identity.
       failedProbeRanges.add(at to at+48000)
       probes.add(Probe(at,at+48000,JSONObject(),JSONObject(),false))
       trace(JSONObject().put("kind","identity_probe_3s").put("source",source).put("local_track",candidate.localTrack)
        .put("candidate_start_sample",candidate.startSample).put("candidate_end_sample",candidate.endSample)
        .put("start_sample",candidate.startSample+at).put("end_sample",candidate.startSample+at+48000)
        .put("status","embedding_unavailable"))
      }
      at+=24000
     }
     if(probes.isEmpty())continue
     // A final <1.5-second remainder is not evidence of the preceding person. Examine
     // a dedicated 1.5-second window ending at the candidate boundary instead; accept
     // it only with the stricter registered-person score and margin. It is never used
     // as an independent anonymous-group support or as a learning sample by itself.
     if(probes.last().end<audio.size){
      val tailStart=audio.size-24000
      tailProbeCalls++
      val vector=tracker.embedding(audio.copyOfRange(tailStart,audio.size))
      if(vector==null)failedProbeRanges.add(tailStart to audio.size)
      val identity=vector?.let{matcher.identifyEmbedding(it,24000,true)}
      if(identity!=null&&identity.optString("status")=="wearer_candidate"&&identity.optDouble("similarity",-1.0)>=0.65&&
       (identity.isNull("candidate_margin")||identity.optDouble("candidate_margin",-1.0)>=0.10)){
       val voice=JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","registered_tail_probe")
       probes.add(Probe(tailStart,audio.size,identity,voice))
       trace(JSONObject().put("kind","registered_tail_probe_1500ms").put("source",source).put("local_track",candidate.localTrack)
        .put("start_sample",candidate.startSample+tailStart).put("end_sample",candidate.endSample)
        .put("independent_support",false).put("identity",JSONObject(identity.toString())))
      }
     }
     // Unassigned review candidates never change grouping. A failed, conflicting,
     // known-person or malformed probe excludes its entire acoustic window, not
     // just the display cell assigned to that probe. Store-level checks still apply.
     val reviewWindows=probes.map{probe->
      val w=Window(source,candidate.startSample+probe.start,candidate.startSample+probe.end,
       candidate.localTrack,candidate.evidenceJson(),probe.voice,probe.identity)
      val margin=if(probe.identity.isNull("candidate_margin"))null else probe.identity.optDouble("candidate_margin",Double.NaN)
      val eligible=UnknownCandidateWindows.eligible(probe.available,probe.identity.optString("status"),margin,
       probe.voice.optString("speaker_status"),person(w)!=null)
      UnknownCandidateWindows.Probe(probe.start,probe.end,eligible)
     }.toMutableList()
     reviewWindows.addAll(failedProbeRanges.map{UnknownCandidateWindows.Probe(it.first,it.second,false)})
     if(unresolved.size<24)for((a,b) in UnknownCandidateWindows.ranges(reviewWindows,48000)){
      val piece=candidate.clip(candidate.startSample+a,candidate.startSample+b)?:continue
      if(unresolved.size<24)unresolved.add(piece)
     }
     // Fine probes only narrow a confirmed A/B transition; weak probes leave uncertainty.
     val cuts=mutableListOf<Pair<Int,Int>>()
     for(i in 0 until probes.size-1){
      val left=probes[i];val right=probes[i+1];val aId=left.profile;val bId=right.profile
      if(aId==null||bId==null||aId==bId)continue
      conflictedTracks.add(candidate.localTrack)
      var lo=left.center;var hi=right.center
      repeat(3){
       if(hi-lo>1600){
        val mid=(lo+hi)/2;val begin=(mid-12000).coerceIn(0,(audio.size-24000).coerceAtLeast(0))
        boundaryProbeCalls++
        val vector=tracker.embedding(audio.copyOfRange(begin,begin+24000))
        val id=vector?.let{matcher.identifyEmbedding(it,24000,true)}
        val profile=if(id!=null&&id.optString("status")=="wearer_candidate"&&id.optDouble("similarity",-1.0)>=0.65&&
         (id.isNull("candidate_margin")||id.optDouble("candidate_margin",-1.0)>=0.10))id.optString("profile_id") else null
        trace(JSONObject().put("kind","boundary_probe_1500ms").put("source",source).put("local_track",candidate.localTrack)
         .put("start_sample",candidate.startSample+begin).put("end_sample",candidate.startSample+begin+24000)
         .put("test_center_sample",candidate.startSample+mid).put("left_profile_id",aId).put("right_profile_id",bId)
         .put("lower_before_sample",candidate.startSample+lo).put("upper_before_sample",candidate.startSample+hi)
         .put("identity",id?:JSONObject.NULL))
        when(profile){aId->lo=mid;bId->hi=mid;else->Unit}
       }
      }
      cuts.add(lo to hi)
      trace(JSONObject().put("kind","boundary_uncertainty").put("source",source).put("local_track",candidate.localTrack)
       .put("start_sample",candidate.startSample+lo).put("end_sample",candidate.startSample+hi)
       .put("left_profile_id",aId).put("right_profile_id",bId))
     }
     val cells=mutableListOf<Window>()
     val bounds=SpeakerProbeCells.bounds(probes.map{SpeakerProbeCells.Probe(it.start,it.end,it.available)})
     for(i in probes.indices){
      val probe=probes[i]
      var begin=bounds[i].first
      var finish=bounds[i].second
      if(i>0){val prev=probes[i-1];if(prev.profile!=null&&probe.profile!=null&&prev.profile!=probe.profile){
       val boundary=cuts.firstOrNull{it.first>=prev.center&&it.second<=probe.center};if(boundary!=null)begin=maxOf(begin,boundary.second)
      }}
      if(i<probes.lastIndex){val next=probes[i+1];if(probe.profile!=null&&next.profile!=null&&probe.profile!=next.profile){
       val boundary=cuts.firstOrNull{it.first>=probe.center&&it.second<=next.center};if(boundary!=null)finish=minOf(finish,boundary.first)
      }}
      if(finish<=begin)continue
      val a0=candidate.startSample+begin;val b0=candidate.startSample+finish
      check(a0>=candidate.startSample&&b0<=candidate.endSample){"声纹窗口超出原始单人活动范围"}
      var w=Window(source,a0,b0,candidate.localTrack,candidate.evidenceJson(),probe.voice,probe.identity)
      // Only matching confirmed neighbors may support an overlapping non-independent probe.
      if(person(w)==null&&i>0&&i<probes.lastIndex){
       fun support(p:Probe)=Window(source,a0,b0,candidate.localTrack,candidate.evidenceJson(),p.voice,p.identity)
       val left=support(probes[i-1]);val right=support(probes[i+1]);val l=person(left);val r=person(right)
       if(SpeakerProbeCells.mayBorrow(probe.available,l?.key,r?.key))w=Window(source,a0,b0,candidate.localTrack,candidate.evidenceJson(),left.voice,left.identity)
      }
      staged[source+"|probe|"+a0+":"+b0]=w;cells.add(w)
     }
     // Keep only a quality-checked, identity-consistent interval for confirmation.
     var i=0
     while(i<cells.size){
      val first=cells[i];val id=person(first)?.key
      if(id==null){i++;continue}
      var j=i+1;var end0=first.end
      while(j<cells.size&&cells[j].start==end0&&person(cells[j])?.key==id){end0=cells[j].end;j++}
      val lower=if(first.start>candidate.startSample)first.start+1600 else first.start
      val upper=if(end0<candidate.endSample)end0-1600 else end0
      if(j-i>=2&&upper>lower&&learning.size<24){
       // Probe cells are display partitions, whereas a failed extraction invalidates
       // the probe's entire acoustic input, including its overlap with other cells.
       val eligible=mutableListOf(UnknownCandidateWindows.Probe(
        (lower-candidate.startSample).toInt(),(upper-candidate.startSample).toInt(),true))
       eligible.addAll(failedProbeRanges.map{UnknownCandidateWindows.Probe(it.first,it.second,false)})
       for((a,b) in UnknownCandidateWindows.ranges(eligible,48000)){
        val safe=candidate.clip(candidate.startSample+a,candidate.startSample+b)?:continue
        if(learning.size<24&&VoiceLearningStore.quality(context,safe).accepted)learning.add(safe)
       }
      }
      i=j
     }
     completedCandidates.add(key)
    }

    // Naming and learning have different minimum durations. Inspect each remaining
    // exclusive interval separately: a local track is not a persistent speaker identity.
    // No waveform from these shorter intervals can become a learning template.
    val activityRows=document.getJSONArray("activities")
    var shortProbeCount=0
    for(speech in proof.recognitionSpeech()){
     if(speech.localTrack in conflictedTracks)continue
     val individualRanges=mutableListOf<Pair<Long,Long>>()
     for(range in speech.intervals())for(i in 0 until activityRows.length()){
      val activity=activityRows.getJSONObject(i)
      if(activity.getString("local_track")!=speech.localTrack)continue
      val begin=maxOf(range.first,activity.getLong("start_sample"))
      val end=minOf(range.second,activity.getLong("end_sample"))
      if(end<=begin)continue
      individualRanges.add(begin to end)
      // Probe a whole short activity or the separated ends of a longer activity. A crop
      // from the same raw activity shares its original evidence key and cannot add a vote.
      val span=end-begin
      if(span<24000L)continue
      val familyStart=activity.getLong("start_sample");val familyEnd=activity.getLong("end_sample")
      val family="$familyStart:$familyEnd"
      val ranges=when {
       span==24000L->listOf(begin to end)
       span<48000L->listOf(begin to end,end-24000L to end)
       else->listOf(begin to begin+24000L,end-24000L to end)
      }
      for((left,right) in ranges){
       if(shortProbeCount>=24)break
       val shortKey="$source|short|$left:$right"
       if(shortKey in shortWindows||shortKey in shortStaged)continue
       if((windows.values+staged.values).any{w->w.source==source&&w.start<right&&w.end>left&&person(w)?.profile!=null})continue
       val a=(left-proof.startSample).toInt();val b=(right-proof.startSample).toInt()
       val audio=wave.copyOfRange(a,b)
       if(audio.count{kotlin.math.abs(it)>=0.995f}.toDouble()/audio.size>0.01)continue
       shortProbeCount++
       val vector=tracker.embedding(audio)?:continue
       val identity=matcher.identifyEmbedding(vector,audio.size,true)
       if(identity.optString("status")=="wearer_candidate")continue
       val provenance=speech.evidenceJson().put("kind","recognition_only_short_recurrence")
        .put("start_sample",left).put("end_sample",right)
        .put("source_intervals",JSONArray().put(JSONObject().put("start_sample",left).put("end_sample",right)))
       val support=SpeakerEvidenceGroups.Support(source,left,right,family,
        provenance.getLong("analysis_window_start"),provenance.getLong("analysis_window_end"),speech.localTrack,familyStart,familyEnd)
       val voice=tracker.observeShort(vector,support)
       trace(JSONObject().put("kind","short_recurrence_probe").put("source",source).put("local_track",speech.localTrack)
        .put("start_sample",left).put("end_sample",right).put("learning_allowed",false).put("grouping",voice))
       if(voice.opt("pending_key") is String||voice.opt("speaker_id") is String){
        val window=Window(source,left,right,speech.localTrack,provenance,voice,identity)
        shortStaged[shortKey]=ShortObservation(window,support,audio)
       }
      }
     }
     for(range in individualRanges.sortedBy{it.first}){
      val occupied=(windows.values+staged.values).filter{it.source==source&&it.start<range.second&&it.end>range.first}.sortedBy{it.start}
      val gaps=mutableListOf<Pair<Long,Long>>();var cursor=range.first
      for(old in occupied){
       if(old.start>cursor)gaps.add(cursor to minOf(old.start,range.second))
       cursor=maxOf(cursor,old.end);if(cursor>=range.second)break
      }
      if(cursor<range.second)gaps.add(cursor to range.second)
      for((begin,end) in gaps){
       if(end-begin<24000L)continue
       val a=(begin-proof.startSample).toInt();val b=(end-proof.startSample).toInt()
       check(a>=0&&b<=wave.size&&b>a){"短语音范围超出原始活动音频"}
       val audio=wave.copyOfRange(a,b);val vector=tracker.embedding(audio)?:continue
       val identity=matcher.identifyEmbedding(vector,audio.size,true)
       if(identity.optString("status")!="wearer_candidate" || identity.optDouble("similarity",-1.0)<0.65 ||
        (!identity.isNull("candidate_margin")&&identity.getDouble("candidate_margin")<0.10))continue
       // Another observation cannot be overwritten, even if it was not yet identified.
       if((windows.values+staged.values).any{it.source==source&&intersection(begin,end,it.start,it.end)>0})continue
       val interval=JSONObject().put("start_sample",begin).put("end_sample",end)
       val provenance=speech.evidenceJson().put("kind","recognition_only_single_exclusive_interval")
        .put("start_sample",begin).put("end_sample",end).put("source_intervals",JSONArray().put(interval))
       staged[source+"|recognition|"+begin+":"+end]=Window(source,begin,end,speech.localTrack,provenance,
        JSONObject().put("speaker_id",JSONObject.NULL).put("speaker_status","registered_recognition_only"),identity)
       trace(JSONObject().put("kind","recognition_only_single_exclusive_interval").put("source",source)
        .put("local_track",speech.localTrack).put("start_sample",begin).put("end_sample",end)
        .put("learning_allowed",false).put("identity",JSONObject(identity.toString())))
      }
     }
    }
    offset=end
   }
   val shortPlan=LinkedHashMap(shortWindows)
   shortPlan.putAll(shortStaged)
   for((key,item) in shortPlan){
    val pcm=item.pcm?:continue
    val group=person(item.window)?.key?.takeIf{it.startsWith("anonymous:")}?.substringAfter(':')?:continue
    val verified=verifyShortEdges(item,pcm,group,::trace)
    shortPlan[key]=item.copy(pcm=null,verified=verified)
   }
   // Only the recent, bounded recurrence pool needs unresolved PCM across observations.
   val outdated=shortPlan.entries.filter{it.value.pcm!=null}.dropLast(32)
   for(entry in outdated)entry.setValue(entry.value.copy(pcm=null))
   windows.putAll(staged);shortWindows.putAll(shortPlan);raw.putAll(observedRaw);observedCandidates.addAll(completedCandidates)
   val accumulated=refinementDiagnostics.getOrPut(source){JSONArray()}
   for(i in 0 until debug.length()){if(accumulated.length()<2048)accumulated.put(debug.getJSONObject(i)) else dropped++}
   diagnosticsDropped[source]=(diagnosticsDropped[source]?:0)+dropped
   refinementMetrics[source]=JSONObject().put("speaker_probe_calls",probeCalls).put("boundary_probe_calls",boundaryProbeCalls).put("tail_probe_calls",tailProbeCalls)
    .put("observe_process_ms",(System.nanoTime()-startedNanos)/1000000.0).put("timing_scope","activity_analysis_quality_and_identity_windows")
   events.forEach{(vector,row)->runCatching{evidenceObserver?.invoke(vector,row)}}
   unresolvedLearningSource=source;unresolvedLearning=unresolved.toList()
   return learning
  }catch(t:Throwable){tracker.restore(saved);throw t}
  finally{tracker.evidenceObserver=null}
 }
 private fun person(w:Window):Person? {
  val profile=w.identity.optString("profile_id").takeIf{w.identity.optString("status")=="wearer_candidate"&&it.isNotBlank()}
  if(profile!=null)return Person("profile:"+profile,w.identity.optString("display_name"),profile)
  val group=w.voice.optString("speaker_id").takeIf{it.matches(Regex("S[1-9][0-9]*"))}
   ?:tracker.resolvePending(w.voice.optString("pending_key").takeIf{it.isNotBlank()})
  return group?.let{Person("anonymous:"+it,SpeakerTranscript.groupName(it))}
 }
 private fun intersection(a:Long,b:Long,c:Long,d:Long)=(minOf(b,d)-maxOf(a,c)).coerceAtLeast(0)

 private fun verifyShortEdges(observation:ShortObservation,pcm:FloatArray,target:String,
                              trace:(JSONObject)->Unit):List<Window> {
  val window=observation.window
  // A single one-second embedding cannot establish where a change occurs. Publish
  // the overlap of consecutive passing probes and leave their outer edges unresolved.
  val probeSize=16000
  val stride=8000
  val last=pcm.size-probeSize
  if(last<0)return emptyList()
  val starts=((0..last step stride).toList()+last).distinct()
  val verified=mutableListOf<Window>()
  var previousCheck:JSONObject?=null
  var previousSupport:SpeakerEvidenceGroups.Support?=null
  for(start in starts){
   if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("短语音复核已取消")
   val support=observation.support.copy(start=window.start+start,end=window.start+start+probeSize)
   val vector=tracker.embedding(pcm.copyOfRange(start,start+probeSize))
   val check=vector?.let{tracker.verifyShortWindow(it,support,target)}
   trace(JSONObject().put("kind","short_edge_verification").put("source",window.source)
    .put("local_track",window.track).put("start_sample",support.start).put("end_sample",support.end)
    .put("target_id",target).put("verification",check?:JSONObject.NULL))
   val prior=previousSupport
   val priorCheck=previousCheck
   previousSupport=support
   previousCheck=check
   if(prior==null||priorCheck?.optBoolean("verified")!=true||check?.optBoolean("verified")!=true)continue
   val left=maxOf(support.start,verified.lastOrNull()?.end?:window.start)
   val right=minOf(support.end,prior.end)
   if(left>=right)continue
   val voice=JSONObject().put("speaker_id",target).put("speaker_status","verified_short_edge")
    .put("recognition_only",true).put("learning_allowed",false).put("short_edge_verified",true)
    .put("provenance",prior.json()).put("edge_verification",priorCheck).put("adjacent_edge_verification",check)
   verified.add(window.copy(start=left,end=right,voice=voice))
  }
  return verified
 }

 private fun outputWindows(source:String):List<Window> {
  var result=windows.values.filter{it.source==source}
  val observations=shortWindows.values.filter{it.window.source==source}
   .sortedBy{if(it.verified.isNotEmpty())0 else if(person(it.window)!=null)1 else 2}
  for(observation in observations){
   val short=observation.window
   val covered=result.filter{it.start<short.end&&short.start<it.end}
   if(covered.any{person(it)?.profile!=null||it.proof.optString("kind")=="recognition_only_short_recurrence"})continue
   // A conflict can withhold an existing anonymous attribution while recurrence is pending.
   // Otherwise a pending observation adds no output boundary of its own.
   if(person(short)==null&&covered.none{person(it)!=null})continue
   val waiting=short.copy(voice=JSONObject().put("speaker_status","short_boundary_unresolved"))
   val replacements=mutableListOf<Window>();var cursor=short.start
   for(piece in observation.verified){
    if(cursor<piece.start)replacements.add(waiting.copy(start=cursor,end=piece.start))
    replacements.add(piece);cursor=piece.end
   }
   if(cursor<short.end)replacements.add(waiting.copy(start=cursor))
   result=result.flatMap{old->
    if(old.start>=short.end||short.start>=old.end)listOf(old)
    else listOfNotNull(old.copy(end=short.start).takeIf{old.start<short.start},
     old.copy(start=short.end).takeIf{short.end<old.end})
   }+replacements
  }
  return result
 }
 private fun intervals(source:String,key:String):List<JSONObject> =
  raw.values.filter{it.source==source}.flatMap{r->val a=r.json.getJSONArray(key);(0 until a.length()).map{a.getJSONObject(it)}}
 private fun rowTracks(source:String,start:Long,end:Long):Set<String> = intervals(source,"activities")
  .filter{intersection(start,end,it.getLong("start_sample"),it.getLong("end_sample"))>0}
  .map{it.getString("local_track")}.toSet()
 private fun hasOverlap(source:String,start:Long,end:Long)=intervals(source,"predicted_overlap")
  .any{intersection(start,end,it.getLong("start_sample"),it.getLong("end_sample"))>0}
 private fun coveredUnion(start:Long,end:Long,supports:List<Window>):Long {
  val spans=supports.mapNotNull{w->val a=maxOf(start,w.start);val b=minOf(end,w.end);if(b>a)a to b else null}.sortedBy{it.first}
  var total=0L;var from=0L;var to=0L
  for(span in spans){if(span.first>to){total+=to-from;from=span.first;to=span.second}else to=maxOf(to,span.second)}
  return total+to-from
 }
 private fun stable(row:JSONObject):String?=row.optString("stable_speaker_id").takeIf{it.isNotBlank()}
 /** Keep raw evidence for audit; never collapse actual overlap into one track. */
 @Synchronized fun activityEvidence(source:String):JSONObject {
  val output=outputWindows(source)
  val activity=intervals(source,"activities").distinctBy{it.toString()}
  val overlaps=intervals(source,"predicted_overlap").distinctBy{it.toString()}
  val refined=JSONArray()
  for(row in activity){
   val start=row.getLong("start_sample");val end=row.getLong("end_sample");val track=row.getString("local_track")
   val supports=output.filter{it.track==track&&intersection(start,end,it.start,it.end)>0}
   val boundaries=(listOf(start,end)+supports.flatMap{listOf(it.start.coerceIn(start,end),it.end.coerceIn(start,end))}+
    overlaps.flatMap{listOf(it.getLong("start_sample").coerceIn(start,end),it.getLong("end_sample").coerceIn(start,end))}).distinct().sorted()
   for(i in 0 until boundaries.size-1){
    val a0=boundaries[i];val b0=boundaries[i+1];if(b0<=a0)continue
    val active=supports.filter{intersection(a0,b0,it.start,it.end)>0}
    val ids=active.mapNotNull{person(it)?.key}.distinct();val unknown=active.any{person(it)==null}
    val overlap=overlaps.any{intersection(a0,b0,it.getLong("start_sample"),it.getLong("end_sample"))>0}
    val identity=if(ids.size==1&&!unknown&&!overlap)ids.single() else null
    val virtual=if(identity!=null)"identity:"+identity else "raw:"+track+":"+a0
    refined.put(JSONObject(row.toString()).put("start_sample",a0).put("end_sample",b0)
     .put("local_track",virtual).put("raw_local_track",track).put("stable_speaker_id",identity?:JSONObject.NULL))
   }
  }
  val stableWindows=JSONArray();var windowsDropped=0
  for(w in output){
   if(stableWindows.length()>=2048){windowsDropped++;continue}
   stableWindows.put(JSONObject().put("source",w.source).put("start_sample",w.start).put("end_sample",w.end)
    .put("local_track",w.track).put("stable_speaker_id",person(w)?.key?:JSONObject.NULL)
    .put("identity",JSONObject(w.identity.toString())).put("grouping",JSONObject(w.voice.toString())))
  }
  val diagnostic=JSONObject().put("coordinate_basis","16kHz samples relative to source start; startSample offset supplied by caller")
   .put("observe_probes",refinementDiagnostics[source]?:JSONArray()).put("probes_dropped",diagnosticsDropped[source]?:0)
   .put("stable_windows",stableWindows).put("stable_windows_dropped",windowsDropped)
  return JSONObject().put("source",source).put("refinement_metrics",refinementMetrics[source]?:JSONObject.NULL).put("refinement_diagnostics",diagnostic)
   .put("activities",JSONArray().apply{activity.forEach{put(it)}})
   .put("refined_activities",refined)
   .put("predicted_overlap",JSONArray().apply{overlaps.forEach{put(it)}})
   .put("protected_intervals",JSONArray().apply{intervals(source,"protected_intervals").distinctBy{it.toString()}.forEach{put(it)}})
 }

 private fun label(row:JSONObject,person:Person,method:String,supports:List<Window>){
  row.put("speaker_display",person.label).put("stable_speaker_id",person.key)
  if(person.profile!=null){
   supports.firstOrNull{it.identity.optString("profile_id")==person.profile}?.let{
    row.put("wearer_identity",JSONObject(it.identity.toString()))
   }
  }
  row.put("speaker_identity_provenance",JSONObject().put("method",method).put("registered_profile_id",person.profile?:JSONObject.NULL)
    .put("supports",JSONArray().apply{supports.forEach{put(JSONObject().put("window_start_sample",it.start).put("window_end_sample",it.end)
     .put("activity",it.proof).put("voice",it.voice).put("identity",it.identity))}}))
 }
 /** Re-resolving the whole live transcript permits safe delayed labels without guessing a future speaker. */
 @Synchronized fun resolve(rows:JSONArray,source:String,offsetSamples:Long=0L,final:Boolean=false):JSONArray {
  check(!closed)
  val result=JSONArray(rows.toString())
  val output=outputWindows(source)
  val ranges=mutableListOf<Pair<Long,Long>>()
  val rowSupports=mutableListOf<List<Window>>()
  val persons=mutableMapOf<String,Person>()
  for(i in 0 until result.length()){
   val row=result.getJSONObject(i)
   val start=offsetSamples+row.optLong("start_sample",row.optLong("start_ms")*16)
   val end=offsetSamples+row.optLong("end_sample",row.optLong("end_ms")*16)
   ranges.add(start to end)
   row.remove("stable_speaker_id");row.remove("speaker_identity_provenance");row.remove("wearer_identity")
   row.put("speaker_display","说话人未识别")
   val matching=output.filter{intersection(start,end,it.start,it.end)>0}
   rowSupports.add(matching)
   if(end<=start)continue
   if(hasOverlap(source,start,end)){
    row.put("speaker_display","多人重叠").put("speaker_identity_provenance",JSONObject().put("method","raw_activity_overlap"))
    continue
   }
   val confirmed=matching.mapNotNull{w->person(w)?.let{it to w}}
   confirmed.forEach{persons[it.first.key]=it.first}
   val unique=confirmed.map{it.first.key}.distinct()
   val unresolved=matching.any{person(it)==null}
   val coverage=coveredUnion(start,end,confirmed.map{it.second})
   if(unique.size==1&&!unresolved&&coverage.toDouble()/(end-start)>=0.5){
    val person=persons.getValue(unique.single())
    val supports=confirmed.map{it.second}
    val method=if(supports.any{!it.voice.optBoolean("learning_allowed",true)})
     "recognition_only_short_recurrence" else "pure_activity_voice_evidence"
    label(row,person,method,supports)
   }else row.put("speaker_identity_provenance",JSONObject().put("method",when{
    unique.size>1->"conflicting_voice_evidence";unresolved->"awaiting_independent_voice_windows";else->"insufficient_pure_activity"
   }))
  }
  // A short answer waits for matching established speakers on BOTH sides. Never infer from the previous row alone.
  val confirmed=(0 until result.length()).filter{stable(result.getJSONObject(it))!=null}
  for(i in 0 until result.length()){
   val row=result.getJSONObject(i);val range=ranges[i]
   if(stable(row)!=null||range.second-range.first>=24000L||range.second<=range.first||hasOverlap(source,range.first,range.second))continue
   if(rowSupports[i].any{it.proof.optString("kind")=="recognition_only_short_recurrence"})continue
   val previous=confirmed.lastOrNull{it<i};val following=confirmed.firstOrNull{it>i}
   if(previous==null||following==null){
    row.put("speaker_identity_provenance",JSONObject().put("method",if(!final&&following==null)"waiting_for_next_confirmed_speaker" else "unresolved_short_answer"))
    continue
   }
   val left=result.getJSONObject(previous);val right=result.getJSONObject(following)
   val identity=stable(left)
   if(identity==null||identity!=stable(right))continue
   if(range.first-ranges[previous].second>48000L||ranges[following].first-range.second>48000L)continue
   if((previous+1 until following).any{j->hasOverlap(source,ranges[j].first,ranges[j].second)||
      rowSupports[j].any{w->person(w)?.key?.let{it!=identity}==true}})continue
   val tracks=rowTracks(source,range.first,range.second)
   val flanks=rowSupports[previous]+rowSupports[following]
   if(flanks.any{it.voice.optBoolean("short_edge_verified")})continue
   // Only use a track inside the original analysis window, never as a global identity.
   val comparable=flanks.filter{w->w.proof.getLong("analysis_window_start")<=range.first&&w.proof.getLong("analysis_window_end")>=range.second}
   if(tracks.isEmpty()||comparable.isEmpty()||comparable.none{it.track in tracks}||comparable.any{it.track !in tracks})continue
   label(row,persons.getValue(identity),"matching_confirmed_neighbors_same_raw_track",flanks)
  }
  return result
 }
 @Synchronized override fun close(){
  if(closed)return;closed=true
  unresolvedLearningSource=null;unresolvedLearning=emptyList()
  shortWindows.clear()
  try{matcher.close()}finally{try{tracker.close()}finally{analyzer.close()}}
 }
}
