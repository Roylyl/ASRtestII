package org.hotwordtest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor

/** Window-local predicted activity, not a count or proof of real people. Construction is restricted
 * to the analyzer; consumers never supply a Boolean or a JSON claim of clean speech. */
class SpeechEvidence private constructor(
 private val input:FloatArray,private val snapshot:FloatArray,
 val source:String,val startSample:Long,
 private val raw:List<Activity>,private val exclusive:List<Exclusive>,
 private val overlap:List<Span>,private val protected:List<Span>,private val diagnostics:JSONArray
) {
 val sampleCount:Int get()=snapshot.size
 val endSample:Long get()=startSample+sampleCount
 private data class Span(val start:Long,val end:Long)
 private data class Activity(val start:Long,val end:Long,val track:String,val windowStart:Long,val windowEnd:Long)
 private data class Exclusive(val start:Long,val end:Long,val activity:Activity)

 /** Recognition may pool clean fragments of one window-local track. This type cannot be
  * passed to learning storage; original intervals remain separate for output attribution. */
 class RecognitionSpeech private constructor(private val owner:SpeechEvidence,private val parts:List<Exclusive>) {
  val source:String get()=owner.source
  val localTrack:String get()=parts.first().activity.track
  fun intervals():List<Pair<Long,Long>> = parts.map{it.start to it.end}
  fun samples():FloatArray {
   val out=FloatArray(parts.sumOf{(it.end-it.start).toInt()});var at=0
   for(part in parts){val a=(part.start-owner.startSample).toInt();val b=(part.end-owner.startSample).toInt();owner.snapshot.copyInto(out,at,a,b);at+=b-a}
   return out
  }
  fun evidenceJson():JSONObject=JSONObject().put("version",VERSION).put("source",source)
   .put("kind","recognition_only_same_window_exclusive_fragments").put("local_track",localTrack)
   .put("analysis_window_start",parts.first().activity.windowStart).put("analysis_window_end",parts.first().activity.windowEnd)
   .put("learning_allowed",false).put("source_intervals",JSONArray().apply{parts.forEach{put(JSONObject().put("start_sample",it.start).put("end_sample",it.end))}})
  companion object {
   internal fun fromOwner(owner:SpeechEvidence):List<RecognitionSpeech> = owner.exclusive.groupBy{it.activity.track}.values
    .filter{parts->parts.sumOf{it.end-it.start}>=24000L}.map{RecognitionSpeech(owner,it.sortedBy{p->p.start})}
  }
 }
 fun recognitionSpeech():List<RecognitionSpeech> = RecognitionSpeech.fromOwner(this)

 class PureCandidate private constructor(private val owner:SpeechEvidence,private val interval:Exclusive,
                                         private val learningGuardApplied:Boolean=false) {
  val source:String get()=owner.source
  val startSample:Long get()=interval.start
  val endSample:Long get()=interval.end
  val localTrack:String get()=interval.activity.track
  val analysisWindowStart:Long get()=interval.activity.windowStart
  val analysisWindowEnd:Long get()=interval.activity.windowEnd
  val windowId:String get()=source+"@"+analysisWindowStart
  /** Intersect a transcript turn with this already-protected interval. This never expands the
   * analyzed range, changes its source, or admits audio from a different waveform. */
  fun clip(start:Long,end:Long):PureCandidate? {
   if(start<0L||end<=start)return null
   val a=maxOf(startSample,start);val b=minOf(endSample,end)
   if(b-a<MIN_SAMPLES)return null
   return PureCandidate(owner,interval.copy(start=a,end=b),learningGuardApplied)
  }
  /** Retain only continuous audio away from competing local tracks, for templates only. */
  internal fun learningSafeClips():List<PureCandidate> {
   val competitors=owner.raw.asSequence().filter{it.track!=localTrack&&it.windowStart==analysisWindowStart&&it.windowEnd==analysisWindowEnd}
    .map{LearningBoundaryGuard.Span(it.start,it.end)}.toList()
   return LearningBoundaryGuard.retained(startSample,endSample,competitors,MIN_SAMPLES)
    .map{PureCandidate(owner,interval.copy(start=it.start,end=it.end),true)}
  }
  /** Callers receive a copy; store/extractors can never be handed a replacement waveform. */
  fun samples():FloatArray=owner.snapshot.copyOfRange((startSample-owner.startSample).toInt(),(endSample-owner.startSample).toInt())
  fun evidenceJson():JSONObject=JSONObject().put("version",VERSION).put("kind","predicted_exclusive_local_activity")
   .put("source",source).put("start_sample",startSample).put("end_sample",endSample)
   .put("local_track",localTrack).put("window_id",windowId).put("analysis_window_start",analysisWindowStart)
   .put("analysis_window_end",analysisWindowEnd).put("boundary_guard_samples",GUARD)
   .put("learning_competing_guard_samples",if(learningGuardApplied)LearningBoundaryGuard.GUARD_SAMPLES else JSONObject.NULL)
   .put("learning_guard_applied",learningGuardApplied)
   .put("min_duration_on",0).put("min_duration_off",0).put("local_track_is_person_identity",false)
   .put("silence_bridge_max_samples",RATE).put("silence_bridge_requires_no_competing_activity",true)
  companion object {
   private val token=Any()
   internal fun create(owner:SpeechEvidence,start:Long,end:Long,track:String,proof:Any):PureCandidate {
    require(proof===token){"单人活动证据来源无效"}
    val interval=owner.exclusive.firstOrNull{it.start<=start&&it.end>=end&&it.activity.track==track}
     ?:throw IllegalArgumentException("该区间不是分析器生成的独占语音")
    require(end-start>=MIN_SAMPLES&&end-start<=MAX_CANDIDATE_SAMPLES)
    return PureCandidate(owner,Exclusive(start,end,interval.activity))
   }
   internal fun fromOwner(owner:SpeechEvidence):List<PureCandidate> {
    val out=mutableListOf<PureCandidate>()
    for(part in owner.exclusive){
     var at=part.start
     while(part.end-at>=MIN_SAMPLES){
      val end=minOf(part.end,at+MAX_CANDIDATE_SAMPLES)
      out.add(create(owner,at,end,part.activity.track,token));at=end
     }
    }
    return out
   }
  }
 }

 fun toJson():JSONObject {
  fun spans(rows:List<Span>)=JSONArray().apply{rows.forEach{put(JSONObject().put("start_sample",it.start).put("end_sample",it.end))}}
  return JSONObject().put("version",VERSION).put("source",source).put("start_sample",startSample).put("end_sample",endSample)
   .put("sample_rate",RATE).put("evidence_type","native_unsmoothed_hard_activity").put("frame_probabilities_available",false)
   .put("boundary_guard_samples",GUARD).put("local_track_is_person_identity",false)
   .put("activities",JSONArray().apply{raw.forEach{a->put(JSONObject().put("start_sample",a.start).put("end_sample",a.end)
    .put("local_track",a.track).put("window_id",source+"@"+a.windowStart).put("analysis_window_start",a.windowStart).put("analysis_window_end",a.windowEnd))}})
   .put("predicted_overlap",spans(overlap)).put("protected_intervals",spans(protected))
   .put("predicted_overlap_samples",overlap.sumOf{it.end-it.start})
   .put("exclusive_intervals",JSONArray().apply{exclusive.forEach{a->put(JSONObject().put("start_sample",a.start).put("end_sample",a.end).put("local_track",a.activity.track))}})
   .put("pure_candidates",JSONArray().apply{PureCandidate.fromOwner(this@SpeechEvidence).forEach{put(it.evidenceJson())}})
   .put("windows",JSONArray(diagnostics.toString()))
 }

 /** Reuse model sessions across 30-second batches. The analyzer stores only one private waveform
  * snapshot per returned batch. A full recording must be tiled rather than copied for each turn. */
 class Analyzer(context:Context,private val includeLegacy:Boolean=false):AutoCloseable {
  private val session=DiarizationTest.ActivitySession(context.assets,includeLegacy)
  private var closed=false
  @Synchronized fun analyze(samples:FloatArray,source:String,startSample:Long=0L,includeLegacy:Boolean=this.includeLegacy):SpeechEvidence {
   check(!closed){"活动分析器已经关闭"}
   require(includeLegacy==this.includeLegacy){"请在创建分析器时指定是否保留旧版诊断"}
   require(samples.isNotEmpty()&&samples.size<=RATE*30){"活动分析每批必须为30秒以内"}
   require(source.isNotBlank()&&source.length<=8192&&source.none{Character.isISOControl(it)}&&startSample>=0)
   require(startSample<=Long.MAX_VALUE-samples.size){"活动来源范围无效"}
   require(samples.all{it.isFinite()}){"录音采样无效"}
   val wave=samples.copyOf();val activities=mutableListOf<Activity>();val overlaps=mutableListOf<Span>()
   val pure=mutableListOf<Exclusive>();val guards=mutableListOf<Span>();val diagnostics=JSONArray()
   var localStart=0
   while(localStart<wave.size){
    interrupted();val end=minOf(wave.size,localStart+WINDOW);val absolute=startSample+localStart;val absoluteEnd=startSample+end
    val out=session.process(wave.copyOfRange(localStart,end));val native=out.getJSONObject("unsmoothed")
    val rows=readActivities(native,absolute,absoluteEnd)
    val rawOverlap=overlapping(rows);overlaps.addAll(rawOverlap)
    val boundary=mutableListOf<Span>()
    // Ordinary silence inside the same local track is not a speaker change. Protect true
    // competing activity, track changes and model-window edges, not every word boundary.
    boundary.add(Span(absolute,absolute+GUARD));boundary.add(Span(absoluteEnd-GUARD,absoluteEnd))
    for(left in rows)for(right in rows){
     if(left.track!=right.track && right.start>=left.end && right.start-left.end<=RATE){
      boundary.add(Span(left.end-GUARD,left.end+GUARD))
      boundary.add(Span(right.start-GUARD,right.start+GUARD))
     }
    }
    for(span in rawOverlap)boundary.add(Span(span.start-GUARD,span.end+GUARD))
    val protection=union(boundary.map{Span(it.start.coerceAtLeast(absolute),it.end.coerceAtMost(absoluteEnd))}.filter{it.end>it.start})
    guards.addAll(protection)
    val firstPure=pure.size
    val events=rows.flatMap{listOf(it.start,it.end)}.distinct().sorted()
    for(i in 0 until events.size-1){
     val a=events[i];val b=events[i+1];if(b<=a)continue
     val active=rows.filter{it.start<b&&it.end>a}.distinctBy{it.track}
     if(active.size!=1)continue
     for(part in subtract(Span(a,b),protection)){
      val last=pure.lastOrNull()
      if(last!=null&&last.end==part.start&&last.activity.track==active[0].track){pure[pure.lastIndex]=last.copy(end=part.end)}
      else pure.add(Exclusive(part.start,part.end,active[0]))
     }
    }
    val pieces=pure.subList(firstPure,pure.size).toList();pure.subList(firstPure,pure.size).clear()
    for(piece in pieces){
     val previous=pure.lastOrNull()
     val sameWindow=previous?.activity?.windowStart==piece.activity.windowStart
     val gap=if(previous==null)Long.MAX_VALUE else piece.start-previous.end
     val foreign=previous!=null&&rows.any{it.track!=piece.activity.track&&it.start<piece.start&&it.end>previous.end}
     val protectedGap=previous!=null&&protection.any{it.start<piece.start&&it.end>previous.end}
     if(previous!=null&&sameWindow&&previous.activity.track==piece.activity.track&&gap in 0..RATE.toLong()&&!foreign&&!protectedGap)
      pure[pure.lastIndex]=previous.copy(end=piece.end)
     else pure.add(piece)
    }
    activities.addAll(rows)
    val report=JSONObject().put("window_id",source+"@"+absolute).put("start_sample",absolute).put("end_sample",absoluteEnd)
     .put("unsmoothed",native).put("unsmoothed_overlap_samples",rawOverlap.sumOf{it.end-it.start})
     .put("unsmoothed_unique_local_tracks",rows.map{it.track}.distinct().size)
     .put("unsmoothed_has_overlap_over_50ms",hasPairOverlapOver(rows,RATE/20L))
    if(out.has("legacy")){
     val old=out.getJSONObject("legacy");val oldRows=readActivities(old,absolute,absoluteEnd);val oldOverlap=overlapping(oldRows)
     val localTracks=oldRows.map{it.track}.distinct().size;val legacyMixed=hasPairOverlapOver(oldRows,RATE/20L)
     report.put("legacy",old).put("legacy_overlap_samples",oldOverlap.sumOf{it.end-it.start})
      .put("legacy_unique_local_tracks",localTracks).put("legacy_has_overlap_over_50ms",legacyMixed)
      .put("legacy_gate_reject",localTracks!=1||legacyMixed)
    }
    diagnostics.put(report);localStart=end
   }
   return SpeechEvidence(samples,wave,source,startSample,activities.toList(),pure.toList(),union(overlaps),union(guards),diagnostics)
  }
  @Synchronized override fun close(){if(!closed){closed=true;session.close()}}
 }

 companion object {
  const val VERSION=4
  const val RATE=16000
  const val GUARD=1600L
  private const val WINDOW=160000
  private const val MIN_SAMPLES=48000L
  private const val MAX_CANDIDATE_SAMPLES=320000L
  private fun interrupted(){if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("活动分析已取消")}
  @JvmStatic fun analyze(context:Context,samples:FloatArray,source:String,startSample:Long=0L,includeLegacy:Boolean=false):SpeechEvidence =
   Analyzer(context,includeLegacy).use{it.analyze(samples,source,startSample,includeLegacy)}
  @JvmStatic fun extractPureCandidates(samples:FloatArray,evidence:SpeechEvidence):List<PureCandidate> {
   require(samples===evidence.input){"录音与活动证据不是同一批数据"}
   return PureCandidate.fromOwner(evidence)
  }
  /** Display/ASR may bridge silence; acoustic overlap evidence remains unmodified. */
  @JvmStatic fun displayActivities(evidence:JSONObject):JSONArray {
   val input=evidence.getJSONArray("activities")
   val rows=(0 until input.length()).map{JSONObject(input.getJSONObject(it).toString())}.sortedBy{it.getLong("start_sample")}
   val merged=mutableListOf<JSONObject>()
   for(row in rows){
    val last=merged.lastOrNull();val a=row.getLong("start_sample");val b=last?.getLong("end_sample")?:-1
    val track=row.getString("local_track")
    val competing=rows.any{it.getString("local_track")!=track&&it.getLong("start_sample")<a&&it.getLong("end_sample")>b}
    if(last!=null&&last.getString("local_track")==track&&a-b in 0..8000L&&!competing)
     last.put("end_sample",maxOf(last.getLong("end_sample"),row.getLong("end_sample")))
    else merged.add(row)
   }
   return JSONArray().apply{merged.forEach{put(it)}}
  }
  private fun readActivities(out:JSONObject,offset:Long,limit:Long):List<Activity> {
   val segments=out.getJSONArray("segments");val rows=mutableListOf<Activity>()
   for(i in 0 until segments.length()){
    val row=segments.getJSONObject(i);val start=row.getDouble("start_seconds");val end=row.getDouble("end_seconds")
    require(start.isFinite()&&end.isFinite()){"活动时间无效"}
    val a=(offset+ceil(start*RATE).toLong()).coerceIn(offset,limit)
    val b=(offset+floor(end*RATE).toLong()).coerceIn(offset,limit)
    if(b>a)rows.add(Activity(a,b,"$offset:"+row.getInt("speaker_id"),offset,limit))
   }
   return rows
  }
  private fun overlapping(rows:List<Activity>):List<Span> {
   val out=mutableListOf<Span>()
   for(i in rows.indices)for(j in i+1 until rows.size){
    if(rows[i].track==rows[j].track)continue
    val a=maxOf(rows[i].start,rows[j].start);val b=minOf(rows[i].end,rows[j].end)
    if(b>a)out.add(Span(a,b))
   }
   return union(out)
  }
  private fun hasPairOverlapOver(rows:List<Activity>,threshold:Long):Boolean {
   for(i in rows.indices)for(j in i+1 until rows.size)if(rows[i].track!=rows[j].track&&
    minOf(rows[i].end,rows[j].end)-maxOf(rows[i].start,rows[j].start)>threshold)return true
   return false
  }
  private fun union(rows:List<Span>):List<Span> {
   val out=mutableListOf<Span>()
   for(row in rows.sortedBy{it.start}){
    val last=out.lastOrNull()
    if(last!=null&&last.end>=row.start)out[out.lastIndex]=Span(last.start,maxOf(last.end,row.end)) else out.add(row)
   }
   return out
  }
  private fun subtract(span:Span,removed:List<Span>):List<Span> {
   val out=mutableListOf<Span>();var at=span.start
   for(bad in removed){if(bad.end<=at)continue;if(bad.start>=span.end)break
    if(bad.start>at)out.add(Span(at,minOf(span.end,bad.start)));at=maxOf(at,bad.end);if(at>=span.end)break}
   if(at<span.end)out.add(Span(at,span.end));return out
  }
 }
}
