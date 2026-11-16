package org.hotwordtest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A bounded local collector. Candidate audio is exactly the analyzer-owned pure interval. */
internal class VoiceLearningRun @JvmOverloads constructor(private val context:Context,
 private val enabledAtStart:Boolean=false,private val isEnabledNow:()->Boolean={false}) {
 data class Turn(val id:String,val sourceLabel:String,val time:String,val speech:String,
                 @Volatile var candidateId:String?=null,@Volatile var reason:String="等待本地质量筛选",
                 @Volatile var spanCandidateId:String?=null,@Volatile var spanReason:String?=null,
                 @Volatile var rejection:JSONObject?=null,@Volatile var reviewRescue:Boolean=false)
 private val turns=linkedMapOf<String,Turn>()
 private val executor=ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(11))
 private val savedCandidateIds=mutableSetOf<String>()
 private data class AutoEvidence(val profile:String,val source:String,val entry:JSONObject,val candidate:SpeechEvidence.PureCandidate,val saved:VoiceLearningStore.CandidateInfo)
 private val autoEligible=linkedMapOf<String,AutoEvidence>()
 private val autoIneligible=linkedMapOf<String,String>()
 private val autoStarted=mutableSetOf<String>()
 private val autoOutcomes=mutableListOf<JSONObject>()
 private val collectionRunId=UUID.randomUUID().toString()
 private val autoGuard=AutoLearningRunGuard()
 private val collectionGuard=AutoLearningRunGuard()
 private val fileWait=BoundedCandidateWait()
 @Volatile private var cancelled=false
 private val unsavedSpanKeys=mutableSetOf<String>()
 private val timedOutKeys=mutableSetOf<String>()
 private val scheduled=mutableSetOf<String>()
 // A slot is reserved while a candidate is being checked or is waiting in the
 // bounded queue. Rejected checks never consume the successful-candidate quota.
 private val reservations=mutableSetOf<String>()
 private val limit=12
 private var analyzer:SpeechEvidence.Analyzer?=null
 private val lineSpace=Regex("\\s+")
 private fun clean(s:String)=s.trim().replace(lineSpace," ")
 /** Recognition provenance is necessary but not sufficient: strict two-model initial
  * template checks are repeated on the saved WAV immediately before auto commit. */
 private data class TargetDecision(val id:String?,val reason:String)
 private fun targetDecision(entry:JSONObject,source:String,candidate:SpeechEvidence.PureCandidate,saved:VoiceLearningStore.CandidateInfo,requireMargin:Boolean=false):TargetDecision {
  fun no(reason:String)=TargetDecision(null,reason)
  if(!enabledAtStart)return no("自动学习在任务开始时未开启")
  if(saved.collectionRunId!=collectionRunId)return no("候选不是本次任务新建")
  if(saved.source!=source||candidate.source!=source)return no("候选来源与当前录音不一致")
  if(saved.startSample<candidate.startSample||saved.endSample>candidate.endSample)return no("候选区间超出原声学证据")
  if(explicitOverlap(entry,"speaker_display"))return no("说话人存在重叠或冲突")
  val stable=(entry.opt("stable_speaker_id") as? String).orEmpty()
  if(!stable.startsWith("profile:")||stable.length<=8)return no("尚未匹配已有稳定人物，继续积累独立声音片段")
  val id=stable.substringAfter("profile:")
  val identity=entry.optJSONObject("wearer_identity")?:entry.optJSONObject("voice_identity")?:return no("缺少人物识别证据")
  if(identity.optString("status")!="wearer_candidate"||identity.optString("profile_id")!=id)return no("人物归属未确认或不一致")
  val proof=entry.optJSONObject("speaker_identity_provenance")?:return no("缺少人物归属来源证据")
  if(proof.optString("method")!="pure_activity_voice_evidence"||proof.optString("registered_profile_id")!=id)return no("人物归属来源不匹配")
  val supports=proof.optJSONArray("supports")?:return no("缺少人物归属区间")
  val intervals=mutableListOf<Pair<Long,Long>>()
  for(i in 0 until supports.length()){
   val s=supports.optJSONObject(i)?:continue;val activity=s.optJSONObject("activity")?:continue
   if(activity.optString("source")!=source||activity.optString("local_track")!=candidate.localTrack||
      activity.optLong("analysis_window_start",-1)!=candidate.analysisWindowStart||
      activity.optLong("analysis_window_end",-1)!=candidate.analysisWindowEnd)continue
   val a=maxOf(saved.startSample,s.optLong("window_start_sample",-1));val b=minOf(saved.endSample,s.optLong("window_end_sample",-1))
   if(b<=a)continue
   val hit=s.optJSONObject("identity")?:return no("人物归属窗口缺少身份结果")
   if(hit.optString("status")!="wearer_candidate"||hit.optString("profile_id")!=id||
      hit.optDouble("base_similarity",-1.0)<0.70||
      (hit.isNull("candidate_margin")&&requireMargin)||
      (!hit.isNull("candidate_margin")&&hit.optDouble("candidate_margin",-1.0)<0.15))return no("人物归属窗口未达到原有校验条件")
   intervals.add(a to b)
  }
  var covered=saved.startSample
  for((a,b) in intervals.sortedBy{it.first}){if(a>covered)break;covered=maxOf(covered,b)}
  return if(covered>=saved.endSample)TargetDecision(id,"") else no("人物证据未完整覆盖候选区间或活动轨不匹配")
 }
 private fun autoTarget(entry:JSONObject,source:String,candidate:SpeechEvidence.PureCandidate,saved:VoiceLearningStore.CandidateInfo,requireMargin:Boolean=false):String? =
  targetDecision(entry,source,candidate,saved,requireMargin).id
 @Synchronized private fun pendingOutcome(id:String,reason:String){
  if(autoOutcomes.none{it.optString("candidate_id")==id})autoOutcomes.add(JSONObject().put("candidate_id",id)
   .put("stage","eligibility").put("status","not_attempted").put("reason",reason))
 }
 @Synchronized private fun publishUnattempted(reason:String,includeEligible:Boolean=true){
  for((id,why) in autoIneligible)if(id !in autoEligible)pendingOutcome(id,why)
  if(includeEligible)for(id in autoEligible.keys)if(id !in autoStarted)pendingOutcome(id,reason)
 }
 @Synchronized private fun publishInterrupted(){
  for(id in autoStarted)if(autoOutcomes.none{it.optString("candidate_id")==id})autoOutcomes.add(JSONObject()
   .put("candidate_id",id).put("stage","automatic").put("status","interrupted")
   .put("reason","自动校验已开始但未取得最终结果，请检查候选和学习库"))
 }
 private fun reserve(key:String,waitForFile:Boolean):BoundedCandidateWait.Result = fileWait.reserve(waitForFile,{cancelled}){
  synchronized(this){when{
   cancelled->BoundedCandidateWait.Result.CANCELLED
   key in scheduled->BoundedCandidateWait.Result.DUPLICATE
   savedCandidateIds.size>=limit->BoundedCandidateWait.Result.FULL
   savedCandidateIds.size+reservations.size>=limit->BoundedCandidateWait.Result.BUSY
   else->{scheduled.add(key);reservations.add(key);BoundedCandidateWait.Result.RESERVED}
  }}
 }
 @Synchronized private fun completeReservation(key:String,candidateId:String?){
  if(!reservations.remove(key))return
  if(candidateId!=null)savedCandidateIds.add(candidateId) else scheduled.remove(key)
 }
 @Synchronized private fun clearReservations(){
  scheduled.removeAll(reservations)
  reservations.clear()
 }
 @Synchronized fun find(row:TranscriptReview.Row):Turn? =
  turns.values.firstOrNull{it.sourceLabel==row.source&&it.time==row.prefix.substringBefore(']')+"]"&&clean(it.speech)==clean(row.speech)}
 private fun time(start:Long,end:Long)=SpeakerTranscript.line(start*1000/16000,end*1000/16000,"未区分","§").substringBefore(']')+"]"
 private fun explicitOverlap(value:JSONObject?,displayKey:String):Boolean = value!=null&&
  LearningOverlapGate.isExplicit(value.optString("overlap_status"),value.optString("association_status"),
   value.optJSONObject("speaker_identity_provenance")?.optString("method").orEmpty(),value.optString(displayKey),
   value.optJSONArray("local_speaker_ids")?.length()?:0)
 private fun liveMode(voice:JSONObject?,source:String):LiveLearningCandidateMode.Route {
  val provenance=voice?.optJSONObject("speaker_identity_provenance")
  val stable=(voice?.opt("stable_speaker_id") as? String).orEmpty()
  val identity=voice?.optJSONObject("identity")
  val stableValid=when{
   stable.startsWith("profile:")->stable.length>"profile:".length&&
    identity?.optString("status")=="wearer_candidate"&&identity?.optString("profile_id")==stable.substringAfter("profile:")
   stable.matches(Regex("anonymous:S[1-9][0-9]*"))->true
   else->false
  }
  return LiveLearningCandidateMode.decide(voice!=null&&voice.optString("source")==source,
   explicitOverlap(voice,"display"),stableValid,voice!=null&&voice.isNull("stable_speaker_id"),
   voice?.optString("display").orEmpty(),provenance?.optString("method").orEmpty(),
   (provenance?.optJSONArray("supports")?.length()?:0)>0)
 }

 /** Used by callers without shared activity analysis. Both local prediction and quality checks are still required. */
 fun collect(samples:FloatArray,entries:JSONArray,source:String,label:String,offset:Long=0L) {
  val local=analyzer?:SpeechEvidence.Analyzer(context).also{analyzer=it}
  var at=0
  while(at<samples.size){
   val end=minOf(samples.size,at+30*16000);val wave=samples.copyOfRange(at,end)
   val evidence=local.analyze(wave,source,at.toLong())
   collectCandidates(entries,SpeechEvidence.extractPureCandidates(wave,evidence),source,label,offset)
   at=end
  }
 }

 /** entries use source sample positions; offset changes display time only (e.g. a batch timeline). */
 fun collectCandidates(entries:JSONArray,pure:List<SpeechEvidence.PureCandidate>,
                       source:String,label:String,offsetSamples:Long=0L) {
  collectPrioritized(entries,pure,emptyList(),source,label,offsetSamples)
 }
 /** One queued task per row tries known-person evidence before review-only evidence.
  * A rejected longer interval must not suppress a shorter eligible interval. */
 @JvmOverloads fun collectPrioritized(entries:JSONArray,known:List<SpeechEvidence.PureCandidate>,
                       unresolved:List<SpeechEvidence.PureCandidate>,source:String,label:String,offsetSamples:Long=0L,waitForFile:Boolean=false) {
  for(i in 0 until entries.length()){
   val entry=entries.getJSONObject(i);val text=entry.optString("text");if(text.isBlank())continue
   val start=entry.optLong("start_sample",-1);val end=entry.optLong("end_sample",-1)
   if(start<0||end<=start)continue
   val key=source+"|"+start+"|"+end+"|"+text
   val row=synchronized(this){turns.getOrPut(key){Turn(UUID.randomUUID().toString(),label,time(offsetSamples+start,offsetSamples+end),text)}}
   // Live turns carry overlap_status/local_speaker_ids; file turns carry
   // association_status and, after resolution, raw_activity_overlap provenance.
   // Never treat a missing overlap label as evidence that speech is single-person.
   val overlap=explicitOverlap(entry,"speaker_display")
   if(overlap){if(row.candidateId==null){
    row.reason="检测到多人重叠，仅可更正显示"
    row.rejection=JSONObject().put("version",1).put("stage","activity").put("status","overlap")
     .put("reason",row.reason)
   };continue}
   if(row.candidateId!=null)continue
   fun options(items:List<SpeechEvidence.PureCandidate>)=items.asSequence()
    .filter{it.source==source}.mapNotNull{it.clip(start,end)}.toList()
   val candidates=LearningCandidateSequence.order(options(known),options(unresolved),
    {it.source+"|"+it.startSample+"|"+it.endSample},{it.endSample-it.startSample})
   if(candidates.isEmpty()){
    row.reason="没有连续至少3秒的纯净单人区间，仅可更正显示"
    row.rejection=JSONObject().put("version",1).put("stage","activity").put("status","no_contiguous_candidate")
     .put("minimum_seconds",3).put("reason",row.reason)
    continue
   }
   when(reserve(key,waitForFile)){
    BoundedCandidateWait.Result.RESERVED->{synchronized(this){timedOutKeys.remove(key)}}
    BoundedCandidateWait.Result.DUPLICATE->continue
    BoundedCandidateWait.Result.FULL->{row.reason="本次已保存12条候选，仅可更正显示";
     row.rejection=JSONObject().put("version",1).put("stage","processing").put("status","quota_full").put("reason",row.reason);continue}
    BoundedCandidateWait.Result.BUSY->{
     if(waitForFile)synchronized(this){timedOutKeys.add(key)}
     row.reason=if(waitForFile)"候选等待超时，未进行质量筛选" else "候选筛选繁忙，该片段未保存；可重新识别或补录"
     row.rejection=JSONObject().put("version",1).put("stage","processing").put("status","queue_busy").put("reason",row.reason)
     continue
    }
    BoundedCandidateWait.Result.CANCELLED->{row.reason="任务已取消，该片段未保存";return}
   }
   val identity=entry.optJSONObject("wearer_identity")?:entry.optJSONObject("voice_identity")
   val suggested=identity?.takeIf{it.optString("status")=="wearer_candidate"}?.optString("profile_id")?.takeIf{it.isNotBlank()}
   try{executor.execute{
    var candidateId:String?=null
    try{
     var rejected=""
     var allowedProfile:String?=null
     var allowedCandidate:SpeechEvidence.PureCandidate?=null
     var ineligibleReason=if(enabledAtStart)"正在积累独立声音片段，尚未建立稳定人物" else "自动学习在任务开始时未开启"
     var retryAllowed=true
     val qualityRejected=mutableListOf<LearningCandidateSequence.Item<SpeechEvidence.PureCandidate>>()
     var reviewRescue=false
     fun attempt(item:LearningCandidateSequence.Item<SpeechEvidence.PureCandidate>,remember:Boolean,reviewOnly:Boolean=false):VoiceLearningStore.CandidateInfo? {
      try{
       val title=if(reviewOnly)"质量补救片段（仅待确认）" else text
       val saved=VoiceLearningStore.collectCandidateForRun(context,item.value,title,if(item.known&&!reviewOnly)suggested else null,
       if(enabledAtStart&&!reviewOnly)collectionRunId else null,collectionGuard)?.also{saved->
       if(item.known&&!reviewOnly){val decision=targetDecision(entry,source,item.value,saved)
        allowedProfile=decision.id;ineligibleReason=decision.reason
        allowedCandidate=item.value.clip(saved.startSample,saved.endSample)
        if(allowedProfile!=null&&allowedCandidate==null)ineligibleReason="候选区间无法映射原始声学证据"
       }else if(reviewOnly)ineligibleReason="质量补救候选仅供人工确认"
       }
       // A null result may mean a duplicate, full quota or unavailable evidence.
       // It is never positive evidence that retrying a shorter interval is safe.
       if(saved==null)retryAllowed=false
       return saved
      }catch(e:VoiceLearningRejection){
       val incoming=JSONObject(e.diagnostic.toString())
       if(row.rejection==null||LearningCandidateSequence.rejectionPriority(incoming.optString("stage"))>=
        LearningCandidateSequence.rejectionPriority(row.rejection?.optString("stage").orEmpty())){
        rejected=e.message.orEmpty();row.rejection=incoming
       }
       val safe=LearningCandidateSequence.mayRetryForReview(e.diagnostic.optString("stage"),
        e.diagnostic.optBoolean("safe_for_review_quality_rescue",false))
       if(!safe)retryAllowed=false else if(remember)qualityRejected.add(item)
       return null
      }catch(e:java.util.concurrent.CancellationException){throw e}
      catch(e:IllegalArgumentException){
       retryAllowed=false
       val existing=row.rejection
       if(existing==null||LearningCandidateSequence.rejectionPriority(existing.optString("stage"))<=
        LearningCandidateSequence.rejectionPriority("processing")){
        rejected=e.message.orEmpty();row.rejection=JSONObject().put("stage","processing").put("status","rejected")
       }
       return null
      }
     }
     var c=LearningCandidateSequence.firstAccepted(candidates){item->attempt(item,true)}
     // Only a quality-only rejection may be retried for manual review. The
     // retry has no target profile or automatic-run token and cannot auto-learn.
     // The analyzer-issued clip retains exact source/track and all checks repeat.
     // Work remains on the bounded background worker, never the capture thread.
     if(c==null&&retryAllowed){
      rescue@ for(item in qualityRejected.take(2)){
       for((a,b) in LearningCandidateSequence.safeHalves(item.value.startSample,item.value.endSample,3L*16000L)){
        if(cancelled||Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("候选收集已取消")
        val clipped=item.value.clip(a,b)?:continue
        c=attempt(LearningCandidateSequence.Item(clipped,item.known),false,true)
        if(c!=null)reviewRescue=true
        if(c!=null||!retryAllowed)break@rescue
       }
      }
     }
     val selectedCandidate=c
     if(selectedCandidate!=null)row.rejection=null
     row.candidateId=selectedCandidate?.id
     row.reviewRescue=reviewRescue
     candidateId=selectedCandidate?.id
     if(!reviewRescue&&selectedCandidate!=null&&allowedProfile!=null&&allowedCandidate!=null)synchronized(this){
      autoEligible[selectedCandidate.id]=AutoEvidence(allowedProfile!!,source,JSONObject(entry.toString()),allowedCandidate!!,selectedCandidate)
      autoIneligible.remove(selectedCandidate.id)
      if(cancelled)pendingOutcome(selectedCandidate.id,"任务已取消，未开始自动校验")
     }
     else if(selectedCandidate!=null)synchronized(this){
      if(selectedCandidate.id !in autoEligible)autoIneligible[selectedCandidate.id]=if(reviewRescue)"质量补救候选仅供人工确认" else ineligibleReason
      if(cancelled)pendingOutcome(selectedCandidate.id,autoIneligible[selectedCandidate.id]?:"任务已取消")
     }
     row.reason=if(selectedCandidate==null)if(rejected.isBlank())"活动或音质未通过学习筛选，未入库" else "候选未保存："+rejected else (if(reviewRescue)"质量补救：仅待确认，未自动学习；区间：" else "待确认候选区间：")+time(offsetSamples+selectedCandidate.startSample,offsetSamples+selectedCandidate.endSample)+"，试听和学入使用同一段音频"
    }catch(e:Exception){row.reason="候选未保存："+(e.message?:"处理失败")}
    finally{completeReservation(key,candidateId)}
   }}catch(_:java.util.concurrent.RejectedExecutionException){
    completeReservation(key,null);row.reason="候选收集繁忙，该片段未保存；可重新识别录音或补录"
   }
  }
 }
 /** Review-only acoustic candidates carry no suggested profile. Explicit overlap
  * rows are rejected by the same gate as every other collection path. */
 @JvmOverloads fun collectUnresolved(entries:JSONArray,pure:List<SpeechEvidence.PureCandidate>,source:String,label:String,offsetSamples:Long=0L){
  if(pure.isEmpty())return
  collectPrioritized(sanitizeReviewRows(entries),emptyList(),pure,source,label,offsetSamples)
 }
 /** A file-only, independent review candidate may span adjacent transcript rows.
  * It is not linked to a single-row correction and never changes transcript labels. */
 fun collectFileSpanning(entries:JSONArray,known:List<SpeechEvidence.PureCandidate>,
                         unresolved:List<SpeechEvidence.PureCandidate>,source:String,label:String,offsetSamples:Long=0L){
  if(offsetSamples<0)return
  val rows=mutableListOf<SpanningCandidatePlan.Row>()
  for(i in 0 until entries.length()){
   val item=entries.getJSONObject(i);val start=item.optLong("start_sample",-1);val end=item.optLong("end_sample",-1)
   if(start<0||end<=start)return
   val method=item.optJSONObject("speaker_identity_provenance")?.optString("method").orEmpty()
   val id=(item.opt("stable_speaker_id") as? String)?.takeIf{it.isNotBlank()}
   rows.add(SpanningCandidatePlan.Row(start,end,item.optString("text"),id,
    explicitOverlap(item,"speaker_display")||method=="conflicting_voice_evidence"))
  }
  val available=LearningCandidateSequence.order(known.filter{it.source==source},unresolved.filter{it.source==source},
   {it.source+"|"+it.startSample+"|"+it.endSample},{it.endSample-it.startSample})
  fun note(pieces:List<SpanningCandidatePlan.Piece>,message:String,spanId:String?=null){synchronized(this){for(piece in pieces){
   val keyRow=source+"|"+piece.row.start+"|"+piece.row.end+"|"+piece.row.text
   turns[keyRow]?.let{if(it.candidateId==null&&(spanId!=null||it.spanCandidateId==null)){
    it.spanCandidateId=spanId;it.spanReason=message
   }}
  }}}
  for(item in available){
   val candidate=item.value
   val scope=SpanningCandidatePlan.coverage(candidate.startSample,candidate.endSample,rows)?:continue
   val key="file-span|"+source+"|"+candidate.startSample+"|"+candidate.endSample
   when(reserve(key,false)){
    BoundedCandidateWait.Result.RESERVED->{synchronized(this){timedOutKeys.remove(key)}}
    BoundedCandidateWait.Result.DUPLICATE->continue
    BoundedCandidateWait.Result.FULL->{synchronized(this){unsavedSpanKeys.add(key)};note(scope,"本次候选已达上限，跨行候选未保存");continue}
    BoundedCandidateWait.Result.BUSY->{synchronized(this){unsavedSpanKeys.add(key)};note(scope,"跨行候选筛选繁忙，未进行质量筛选");continue}
    BoundedCandidateWait.Result.CANCELLED->{note(scope,"任务已取消，跨行候选未保存");return}
   }
   try{executor.execute{
    var candidateId:String?=null
    try{
     val saved=VoiceLearningStore.collectSpanningCandidate(context,candidate,VoiceLearningStore.SpanScope(rows,offsetSamples),collectionGuard)
     candidateId=saved?.id
     if(saved!=null){
      synchronized(this){unsavedSpanKeys.remove(key)}
      synchronized(this){if(saved.id !in autoEligible)autoIneligible[saved.id]="跨行候选仅供人工确认"
       if(cancelled)pendingOutcome(saved.id,"任务已取消；跨行候选仅供人工确认")}
      val pieces=SpanningCandidatePlan.coverage(saved.startSample,saved.endSample,rows).orEmpty()
      note(pieces,"该行关联跨行学习片段；请到学习页核对完整音频，此处只更正单行显示",saved.id)
     }else{synchronized(this){unsavedSpanKeys.add(key)};note(scope,"跨行候选未保存：未通过筛选或与已有样本重复")}
    }catch(_:Exception){synchronized(this){unsavedSpanKeys.add(key)};note(scope,"跨行候选筛选失败，未保存；可重新识别或补录")}
    finally{completeReservation(key,candidateId)}
   }}catch(_:java.util.concurrent.RejectedExecutionException){
    completeReservation(key,null);synchronized(this){unsavedSpanKeys.add(key)};note(scope,"跨行候选筛选繁忙，未保存；可重新识别或补录")
   }
  }
 }
 private fun sanitizeReviewRows(entries:JSONArray):JSONArray {
  val sanitized=JSONArray()
  for(i in 0 until entries.length()){
   val copy=JSONObject(entries.getJSONObject(i).toString())
   copy.remove("wearer_identity");copy.remove("voice_identity")
   sanitized.put(copy)
  }
  return sanitized
 }
 /** The non-split live path must be genuinely unresolved, not an error or a
  * conflicting/overlapping result. Candidate identity is chosen only by the user. */
 fun collectUnresolvedClassifiedLive(entries:JSONArray,pure:List<SpeechEvidence.PureCandidate>,
                                     voice:JSONObject?,source:String,label:String){
  collectUnresolvedClassifiedLive(entries,emptyList(),pure,voice,source,label)
 }
 /** Known intervals here already passed observe's independent identity-window
  * selection. The whole row remains unresolved; no name is preselected. */
 fun collectUnresolvedClassifiedLive(entries:JSONArray,known:List<SpeechEvidence.PureCandidate>,
                                     unresolved:List<SpeechEvidence.PureCandidate>,voice:JSONObject?,source:String,label:String){
  if(voice==null||!LiveLearningCandidateMode.mayReviewSubintervals(liveMode(voice,source),known.size,unresolved.size))return
  val eligible=JSONArray()
  for(i in 0 until entries.length()){
   val row=entries.getJSONObject(i);val start=row.optLong("start_sample",-1);val end=row.optLong("end_sample",-1)
   if(start>=0&&end>start&&voice.optLong("start_sample",-1)==start&&voice.optLong("end_sample",-1)==end)eligible.put(row)
  }
  collectPrioritized(sanitizeReviewRows(eligible),known,unresolved,source,label)
 }
 /** The ordinary real-time VAD path may collect only from the actual resolver
  * output for this source and time range. A caller-provided Boolean or display
  * name is never enough to establish activity/identity evidence. */
 fun collectClassifiedLive(entries:JSONArray,pure:List<SpeechEvidence.PureCandidate>,
                           voice:JSONObject?,source:String,label:String){
  val provenance=voice?.optJSONObject("speaker_identity_provenance")
  val supports=provenance?.optJSONArray("supports")
  if(explicitOverlap(voice,"display")){
   recordUnlearnable(entries,source,label,0L,"检测到多人重叠，仅可更正显示");return
  }
  val resolved=liveMode(voice,source)==LiveLearningCandidateMode.Route.RESOLVED
  for(i in 0 until entries.length()){
   val row=entries.getJSONObject(i);val one=JSONArray().put(row)
   val start=row.optLong("start_sample",-1);val end=row.optLong("end_sample",-1)
   if(!resolved||start<0||end<=start||voice?.optLong("start_sample",-1)!=start||voice?.optLong("end_sample",-1)!=end){
    recordUnlearnable(one,source,label,0L,"说话人未明确或活动证据不足，仅可更正显示")
    continue
   }
   val eligible=pure.filter{candidate->
    val clipped=if(candidate.source==source)candidate.clip(start,end) else null
    if(clipped==null)false else{
     val intervals=mutableListOf<Pair<Long,Long>>()
     for(j in 0 until supports!!.length()){
      val support=supports.optJSONObject(j)?:continue
      val activity=support.optJSONObject("activity")?:continue
      if(activity.optString("source")!=source||activity.optString("local_track")!=clipped.localTrack||
         activity.optLong("analysis_window_start",-1)!=clipped.analysisWindowStart||
         activity.optLong("analysis_window_end",-1)!=clipped.analysisWindowEnd)continue
      val a=maxOf(clipped.startSample,support.optLong("window_start_sample",-1))
      val b=minOf(clipped.endSample,support.optLong("window_end_sample",-1))
      if(b>a)intervals.add(a to b)
     }
     var covered=clipped.startSample
     for((a,b) in intervals.sortedBy{it.first}){
      if(a>covered)break
      covered=maxOf(covered,b)
     }
     covered>=clipped.endSample
    }
   }
   if(eligible.isEmpty())recordUnlearnable(one,source,label,0L,"没有完整人物证据覆盖的纯净语音，仅可更正显示")
   else {
    // The live record keeps its display format; copy the actual resolver's
    // provenance only into the learning input for high-confidence checks.
    val enriched=JSONObject(row.toString()).put("stable_speaker_id",voice?.opt("stable_speaker_id")?:JSONObject.NULL)
     .put("speaker_identity_provenance",provenance?:JSONObject.NULL)
     .put("voice_identity",voice?.optJSONObject("identity")?:JSONObject.NULL)
    collectCandidates(JSONArray().put(enriched),eligible,source,label)
   }
  }
 }
 fun recordUnlearnable(entries:JSONArray,source:String,label:String,offsetSamples:Long=0L,reason:String="缺少原始单人活动证据，仅可更正显示"){
  for(i in 0 until entries.length()){
   val entry=entries.getJSONObject(i);val text=entry.optString("text");if(text.isBlank())continue
   val start=entry.optLong("start_sample",-1);val end=entry.optLong("end_sample",-1);if(start<0||end<=start)continue
   synchronized(this){turns.putIfAbsent(source+"|"+start+"|"+end+"|"+text,
    Turn(UUID.randomUUID().toString(),label,time(offsetSamples+start,offsetSamples+end),text,reason=reason))}
  }
 }
 private fun runAutomatic(){
  publishUnattempted(if(!enabledAtStart)"自动学习在任务开始时未开启" else "自动学习已关闭或任务已取消",false)
  if(!enabledAtStart||!autoGuard.isActive(isEnabledNow)){
   publishUnattempted(if(!enabledAtStart)"自动学习在任务开始时未开启" else "自动学习已关闭或任务已取消")
   return
  }
  val pending=synchronized(this){autoEligible.toList()}
  try{
  for((id,evidence) in pending){
   val profile=evidence.profile
   if(!autoGuard.isActive(isEnabledNow)||Thread.currentThread().isInterrupted){publishUnattempted("任务已取消或自动学习已关闭，未开始自动校验");return}
   synchronized(this){autoStarted.add(id)}
   try{
    VoiceLearningStore.confirmCandidateAutomatic(context,id,profile,collectionRunId,
     {autoGuard.isActive(isEnabledNow)},{commit->autoGuard.commit(isEnabledNow,commit)})
    synchronized(this){
     savedCandidateIds.remove(id)
     turns.values.filter{it.candidateId==id}.forEach{it.candidateId=null;it.reason="已自动学习；下次识别生效"}
     autoOutcomes.add(JSONObject().put("candidate_id",id).put("profile_id",profile).put("status","learned"))
    }
   }catch(e:Exception){
    if(e is java.util.concurrent.CancellationException)throw e
    var diagnosticSaved=false;var diagnosticSaveError:String?=null
    if(e is VoiceLearningRejection){
     try{diagnosticSaved=autoGuard.commit(isEnabledNow){
      VoiceLearningStore.recordAutomaticRejection(context,id,collectionRunId,e.diagnostic)
     }}catch(cancel:java.util.concurrent.CancellationException){throw cancel}
     catch(_:Exception){
      if(!autoGuard.isActive(isEnabledNow)||Thread.currentThread().isInterrupted)return
      diagnosticSaveError="拒绝原因未能保存"
     }
    }
    synchronized(this){autoOutcomes.add(JSONObject().put("candidate_id",id).put("profile_id",profile)
     .put("status","pending").put("reason",e.message?:"自动校验未通过")
     .put("rejection_diagnostic",if(e is VoiceLearningRejection)JSONObject(e.diagnostic.toString()) else JSONObject.NULL)
     .put("diagnostic_saved",diagnosticSaved).put("diagnostic_save_error",diagnosticSaveError?:JSONObject.NULL))}
    val rejected=(e as? VoiceLearningRejection)?.diagnostic?:continue
    val span=AutomaticRetryPlan.failedSpan(rejected,evidence.candidate.startSample,evidence.candidate.endSample)?:continue
    for((start,end) in AutomaticRetryPlan.outsideFailure(evidence.candidate.startSample,evidence.candidate.endSample,span.first,span.second)){
     if(!autoGuard.isActive(isEnabledNow)||Thread.currentThread().isInterrupted)return
     val clipped=evidence.candidate.clip(start,end)?:continue
     val proposed=evidence.saved.copy(startSample=clipped.startSample,endSample=clipped.endSample)
     if(autoTarget(evidence.entry,evidence.source,clipped,proposed,true)!=profile)continue
     var childId:String?=null
     try{
      val child=VoiceLearningStore.collectAutomaticRetry(context,clipped,profile,collectionRunId,id,collectionGuard)?:continue
      childId=child.id
      synchronized(this){savedCandidateIds.add(child.id)}
      if(autoTarget(evidence.entry,evidence.source,clipped,child,true)!=profile){
       synchronized(this){autoOutcomes.add(JSONObject().put("candidate_id",child.id).put("parent_candidate_id",id)
        .put("profile_id",profile).put("status","retry_pending").put("reason","人物证据未覆盖子段或缺少竞争对照"))}
       break
      }
      VoiceLearningStore.confirmCandidateAutomatic(context,child.id,profile,collectionRunId,
       {autoGuard.isActive(isEnabledNow)},{commit->autoGuard.commit(isEnabledNow,commit)})
      synchronized(this){savedCandidateIds.remove(child.id)
       turns.values.filter{it.candidateId==id}.forEach{it.reason="部分已自动学习，其余待确认；下次识别生效"}
       autoOutcomes.add(JSONObject().put("candidate_id",child.id).put("parent_candidate_id",id)
        .put("profile_id",profile).put("status","learned").put("mode","verified_subsegment")
        .put("start_sample",child.startSample).put("end_sample",child.endSample))}
      break
     }catch(cancel:java.util.concurrent.CancellationException){throw cancel}
     catch(failure:Exception){
      if(failure is VoiceLearningRejection&&childId!=null){
       try{autoGuard.commit(isEnabledNow){VoiceLearningStore.recordAutomaticRejection(context,childId!!,collectionRunId,failure.diagnostic)}}
       catch(cancel:java.util.concurrent.CancellationException){throw cancel}
       catch(_:Exception){}
      }
      synchronized(this){autoOutcomes.add(JSONObject().put("candidate_id",childId?:JSONObject.NULL)
       .put("parent_candidate_id",id).put("profile_id",profile).put("status","retry_pending")
       .put("reason",failure.message?:"子段校验未通过").put("start_sample",start).put("end_sample",end))}
      break
     }
    }
   }
  }
  }finally{publishUnattempted("任务已取消或自动学习已关闭，未开始自动校验");publishInterrupted()}
 }
 private fun learnRecurringSpeakers(){
  if(!enabledAtStart||cancelled||!autoGuard.isActive(isEnabledNow))return
  try{
   val outcomes=VoiceLearningStore.learnRecurring(context,collectionRunId,
    {autoGuard.isActive(isEnabledNow)},{commit->autoGuard.commit(isEnabledNow,commit)})
   synchronized(this){for(i in 0 until outcomes.length()){
    val outcome=outcomes.getJSONObject(i);val status=outcome.optString("status")
    val ids=outcome.optJSONArray("candidate_ids")?:JSONArray().apply{
     outcome.optString("candidate_id").takeIf{it.isNotBlank()}?.let{put(it)}
    }
    val affected=(0 until ids.length()).map{ids.getString(it)}.toSet()
    if(status=="discovered"||status=="learned"){
     savedCandidateIds.removeAll(affected)
     autoOutcomes.removeAll{it.optString("candidate_id") in affected&&it.optString("status")!="learned"}
     turns.values.filter{it.candidateId in affected}.forEach{row->
      row.candidateId=null
      row.reason=if(status=="discovered")"已自动建立常见说话人的声纹；下次识别生效" else "已自动补充声纹；下次识别生效"
     }
    }else if(status=="pending"){
     turns.values.filter{it.candidateId in affected}.forEach{it.reason=outcome.optString("reason","正在积累独立声音片段")}
    }
    autoOutcomes.add(outcome)
   }}
  }catch(_:java.util.concurrent.CancellationException){
   publishUnattempted("任务已取消或自动学习已关闭，未继续积累人物")
  }catch(e:Exception){synchronized(this){autoOutcomes.add(JSONObject().put("status","discovery_failed")
   .put("reason",e.message?:"自动人物积累暂未完成，候选已保留"))}}
 }
 fun finish(){
  executor.shutdown()
  try{while(!executor.awaitTermination(250,TimeUnit.MILLISECONDS)){if(Thread.currentThread().isInterrupted){cancelled=true;autoGuard.cancel();collectionGuard.cancel();executor.shutdownNow();clearReservations();return}};runAutomatic();learnRecurringSpeakers()}
  catch(_:InterruptedException){cancelled=true;autoGuard.cancel();collectionGuard.cancel();executor.shutdownNow();clearReservations();Thread.currentThread().interrupt()}
  finally{analyzer?.close();analyzer=null}
 }
 fun cancel(){cancelled=true;autoGuard.cancel();collectionGuard.cancel();executor.shutdownNow();clearReservations();publishUnattempted("任务已取消，未开始自动校验");analyzer?.close();analyzer=null}
 @Synchronized fun summary():String {
  val count=savedCandidateIds.size
  val learned=autoOutcomes.count{it.optString("status")=="learned"}
  val discovered=autoOutcomes.count{it.optString("status")=="discovered"}
  if(cancelled)return "任务已取消；已完成的候选保留，未完成候选未保存"
  val base=if(count>0)"本次保留"+count+"条声纹候选" else "本次没有剩余声纹候选"
  val reviewCount=turns.values.count{it.reviewRescue&&it.candidateId!=null}
  val withReview=if(reviewCount>0)base+"（其中"+reviewCount+"条为质量补救，仅待确认）" else base
  val withLearning=if(learned>0)withReview+"；自动学习"+learned+"条" else withReview
  val withAuto=if(discovered>0)withLearning+"；自动认识"+discovered+"位说话人" else withLearning
  val spans=if(unsavedSpanKeys.isEmpty())withAuto else withAuto+"；"+unsavedSpanKeys.size+"条跨行候选未保存"
  return if(timedOutKeys.isEmpty())spans else spans+"；其中或另有"+timedOutKeys.size+"条候选等待超时、未筛选"
 }
 fun fileWaitMillis():Long=fileWait.waitedMillis()
 @Synchronized fun autoResults():JSONArray=JSONArray().apply{autoOutcomes.forEach{put(JSONObject(it.toString()))}}
 @Synchronized fun json():JSONArray=JSONArray().also{all->turns.values.forEach{t->all.put(JSONObject().put("id",t.id).put("source_label",t.sourceLabel)
  .put("time",t.time).put("text",t.speech).put("candidate_id",t.candidateId?:JSONObject.NULL)
  .put("status",if(t.candidateId!=null)t.reason else t.spanReason?:t.reason)
  .put("review_quality_rescue",t.reviewRescue).put("rejection_diagnostic",t.rejection?:JSONObject.NULL))}}
}
