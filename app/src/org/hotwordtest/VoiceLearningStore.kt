package org.hotwordtest

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/** Candidate audio is local and bounded. Templates require manual confirmation or strict automatic admission. */
object VoiceLearningStore {
 private const val RATE=16000
 private const val MAX_CANDIDATES=40
 private const val MAX_TEMPLATES=24
 private val lock=Any()
 private val uuid=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
 private val snapshotLock=Any()
 private val activeSnapshots=mutableSetOf<String>()
 private val snapshotName=Regex("voice-confirm-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
 data class SnapshotCleanupResult(val deleted:Int,val failed:Int,val activeSkipped:Int)

 /** Delete a generated snapshot without following symbolic links. */
 private fun deleteSnapshotTree(file:File):Boolean {
  return try {
   if(Files.isSymbolicLink(file.toPath()))return file.delete()
   if(file.isDirectory){
    val children=file.listFiles()?:return false
    var all=true
    for(child in children)if(!deleteSnapshotTree(child))all=false
    if(!all)return false
   }
   !file.exists()||file.delete()
  }catch(_:Exception){false}
 }

 /** Retry cleanup of only this app's orphaned snapshot directories.
  * The registry and directory creation share the same lock, so a concurrent
  * confirmation cannot be mistaken for an orphan during Activity recreation. */
 fun cleanupOrphanIdentitySnapshots(context:Context):SnapshotCleanupResult=synchronized(snapshotLock){
  val cache=context.cacheDir
  val children=cache.listFiles()?:return@synchronized SnapshotCleanupResult(0,1,0)
  val canonicalCache=try{cache.canonicalFile}catch(_:Exception){return@synchronized SnapshotCleanupResult(0,1,0)}
  var deleted=0;var failed=0;var skipped=0
  for(dir in children){
   if(!snapshotName.matches(dir.name))continue
   if(dir.absolutePath in activeSnapshots){skipped++;continue}
   val safe=try{!Files.isSymbolicLink(dir.toPath())&&dir.isDirectory&&dir.canonicalFile.parentFile==canonicalCache}catch(_:Exception){false}
   if(!safe){failed++;continue}
   if(deleteSnapshotTree(dir))deleted++ else failed++
  }
  SnapshotCleanupResult(deleted,failed,skipped)
 }
 private fun active(){if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("声纹学习已取消")}
 data class Origin(val source:String,val startSample:Long,val endSample:Long)
 data class CandidateInfo(val id:String,val source:String,val title:String,val durationSeconds:Double,
                          val suggestedProfileId:String?,val createdMs:Long,val startSample:Long,val endSample:Long,
                          val spanContext:JSONObject?=null,val collectionRunId:String?=null,val autoDiagnostic:JSONObject?=null)
 internal data class SpanScope(val rows:List<SpanningCandidatePlan.Row>,val displayOffsetSamples:Long)
 data class SampleInfo(val id:String,val profileId:String,val source:String,val title:String,
                       val durationSeconds:Double,val createdMs:Long,val startSample:Long,val endSample:Long)
 data class Quality(val accepted:Boolean,val reason:String,val speechSamples:Int=0,val speechFraction:Double=0.0,
                    val rms:Double=0.0,val clipping:Double=0.0,val singleSpeaker:Boolean=false,
                    val overlapping:Boolean=false,val speech:FloatArray?=null,
                    val sourceStartSample:Long?=null,val sourceEndSample:Long?=null,val evidence:JSONObject?=null)

 private fun folder(context:Context)=File(context.filesDir,"voice-learning")
 private fun stateFile(context:Context)=AtomicFile(File(folder(context),"state.json"))
 private fun audioFile(context:Context,id:String):File {
  require(uuid.matches(id)){"学习样本编号无效"}
  return File(folder(context),"$id.wav")
 }
 private fun empty()=JSONObject().put("version",1).put("candidates",JSONArray()).put("samples",JSONArray()).put("ignored",JSONArray()).put("history",JSONArray())
 private fun cleanOrphanAudio(context:Context,state:JSONObject){
  val pending=objects(state.getJSONArray("candidates")).map{it.getString("id")}.toSet()
  folder(context).listFiles()?.filter{it.isFile}?.forEach{file->
   val name=file.name.removeSuffix(".bak").removeSuffix(".new")
   if(name.endsWith(".wav")){
    val id=name.removeSuffix(".wav")
    if(uuid.matches(id)&&id !in pending)AtomicFile(audioFile(context,id)).delete()
   }
  }
 }
 private fun load(context:Context):JSONObject {
  val file=stateFile(context)
  if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return empty().also{cleanOrphanAudio(context,it)}
  require(file.baseFile.length()<=8L*1024*1024){"声纹学习数据过大，请先清理补充样本"}
  val json=JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
  require(json.getInt("version")==1){"声纹学习数据版本不支持"}
  require(json.getJSONArray("candidates").length()<=MAX_CANDIDATES){"待确认样本过多"}
  json.getJSONArray("samples");json.getJSONArray("ignored")
  if(!json.has("history"))json.put("history",JSONArray())
  cleanOrphanAudio(context,json)
  return json
 }
 private fun save(context:Context,json:JSONObject){
  val bytes=json.toString().toByteArray(Charsets.UTF_8)
  require(bytes.size<=8*1024*1024){"声纹学习数据已满，请先清理样本"}
  val dir=folder(context);require(dir.isDirectory||dir.mkdirs()){"无法创建声纹学习目录"}
  val atomic=stateFile(context);val output=atomic.startWrite()
  try{output.write(bytes);output.flush();output.fd.sync();atomic.finishWrite(output)}
  catch(t:Throwable){atomic.failWrite(output);throw t}
 }
 private fun objects(array:JSONArray)= (0 until array.length()).map{array.getJSONObject(it)}
 private fun keep(array:JSONArray,predicate:(JSONObject)->Boolean)=JSONArray().apply{objects(array).filter(predicate).forEach{put(it)}}
 private fun origin(json:JSONObject)=Origin(json.getString("source"),json.getLong("start"),json.getLong("end"))
 private fun overlaps(a:Origin,b:Origin)=FileSourceIdentity.sameForDedup(a.source,b.source)&&a.startSample<b.endSample&&b.startSample<a.endSample
 // An ambiguous legacy ZIP name may represent several entries; removing an
 // explicit rejection for one new entry must not erase the old rejection.
 private fun exactSourceOverlap(a:Origin,b:Origin)=a.source==b.source&&a.startSample<b.endSample&&b.startSample<a.endSample
 private fun checkedOrigin(value:String,start:Long,end:Long):Origin {
  require(value.isNotBlank()&&value.length<=8192&&value.none{Character.isISOControl(it)}){"录音来源无效"}
  require(start>=0&&end>start){"学习样本时间区间无效"}
  return Origin(value,start,end)
 }
 private fun candidate(json:JSONObject)=CandidateInfo(json.getString("id"),json.getString("source"),json.getString("title"),
  json.getDouble("seconds"),json.optString("suggested").takeIf{it.isNotBlank()},json.getLong("at"),json.getLong("start"),json.getLong("end"),
  json.optJSONObject("span_context")?.let{JSONObject(it.toString())},json.optString("collection_run_id").takeIf{it.isNotBlank()},
  json.optJSONObject("auto_diagnostic")?.let{JSONObject(it.toString())})
 private fun sameSpanContext(item:JSONObject,scope:SpanScope):Boolean {
  val saved=item.optJSONObject("span_context")?:return false
  if(saved.optLong("display_offset_samples",-1)!=scope.displayOffsetSamples)return false
  val where=origin(item);val current=SpanningCandidatePlan.coverage(where.startSample,where.endSample,scope.rows)?:return false
  val rows=saved.optJSONArray("rows")?:return false
  if(rows.length()!=current.size)return false
  for(i in current.indices){
   val old=rows.optJSONObject(i)?:return false;val now=current[i]
   if(old.optLong("start_sample",-1)!=now.row.start||old.optLong("end_sample",-1)!=now.row.end||
    old.optLong("covered_start_sample",-1)!=now.start||old.optLong("covered_end_sample",-1)!=now.end||
    old.optString("text")!=now.row.text||old.optString("stable_id")!=now.row.stableId)return false
  }
  return true
 }
 private fun sample(json:JSONObject)=SampleInfo(json.getString("id"),json.getString("profile"),json.getString("source"),
  json.getString("title"),json.getDouble("seconds"),json.getLong("at"),json.getLong("start"),json.getLong("end"))
 private fun continuity(item:JSONObject):JSONObject? = item.optJSONObject("quality")?.optJSONObject("continuity")?.takeIf{
  it.optInt("version",0)>=VoiceContinuityGate.VERSION && it.optString("status") in setOf("review_required","low_confidence_review_required")
 }
 private fun currentAcousticEvidence(item:JSONObject):Boolean {
  val acoustic=item.optJSONObject("quality")?.optJSONObject("evidence")
  return (acoustic?.optInt("version",0)?:0)>=SpeechEvidence.VERSION &&
   acoustic?.optBoolean("learning_guard_applied",false)==true &&
   ((acoustic?.optLong("learning_competing_guard_samples",0)?:0)>=LearningBoundaryGuard.GUARD_SAMPLES)
 }
 private fun profileExists(context:Context,id:String)=WearerVoiceStore.hasProfile(context,id)
 /** A decision must use one immutable view of both the original and supplemental
  * templates. The application locks are always acquired in learning->profile order. */
 private data class IdentitySnapshot(val files:Map<String,ByteArray>,val samples:String) {
  fun sameAs(other:IdentitySnapshot):Boolean = samples==other.samples && files.keys==other.files.keys &&
   files.all{(name,bytes)->other.files[name]?.contentEquals(bytes)==true}
 }
 private fun identitySnapshot(context:Context,state:JSONObject):IdentitySnapshot = synchronized(WearerVoiceStore){
  // Force the normal parser and AtomicFile recovery to run before taking the snapshot.
  WearerVoiceStore.listProfiles(context)
  val captured=linkedMapOf<String,ByteArray>()
  fun add(relative:String){
   val path=File(context.filesDir,relative)
   if(path.exists()||File(path.path+".bak").exists())
    captured[relative]=AtomicFile(path).openRead().use{it.readBytes()}
  }
  for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES))add("wearer-voice-$model.json")
  val dir=File(context.filesDir,"voice-profiles")
  val files=if(!dir.exists())emptyArray() else requireNotNull(dir.listFiles()){ "声纹目录无法读取，未确认学习" }
  files.asSequence().filter{it.isFile}.map{it.name.removeSuffix(".bak").removeSuffix(".json")}
   .filter{uuid.matches(it)}.distinct().sorted().forEach{add("voice-profiles/$it.json")}
  IdentitySnapshot(captured,state.getJSONArray("samples").toString())
 }
 /** Work against a private immutable copy so concurrent profile changes cannot give the
  * two models different views. Only this generated cache directory is removed afterwards. */
 private fun <T> withIdentitySnapshot(context:Context,snapshot:IdentitySnapshot,action:(Context)->T):T {
  val dir=synchronized(snapshotLock){
   val created=File(context.cacheDir,"voice-confirm-"+UUID.randomUUID())
   require(created.mkdirs()){ "无法准备声纹校验，未确认学习" }
   activeSnapshots.add(created.absolutePath)
   created
  }
  var failure:Throwable?=null
  try{
   for((name,bytes) in snapshot.files){val file=File(dir,name);require(file.parentFile?.let{it.isDirectory||it.mkdirs()}==true);file.writeBytes(bytes)}
   val learning=File(dir,"voice-learning");require(learning.isDirectory||learning.mkdirs())
   val state=empty().put("samples",JSONArray(snapshot.samples))
   File(learning,"state.json").writeText(state.toString(),Charsets.UTF_8)
   val copy=object:ContextWrapper(context){override fun getFilesDir():File=dir}
   return action(copy)
  }catch(t:Throwable){failure=t;throw t}
  finally{
   val removed=synchronized(snapshotLock){
    val ok=deleteSnapshotTree(dir)
    activeSnapshots.remove(dir.absolutePath)
    ok
   }
   if(!removed){
    val cleanup=java.io.IOException("声纹校验临时数据清理失败，请重启应用重试")
    if(failure!=null)failure.addSuppressed(cleanup) else throw cleanup
   }
  }
 }
 /** Compute model completion against an immutable profile library and commit only if
  * neither the initial profiles nor supplemental samples changed during inference. */
 internal fun <T,R> guardedProfileUpdate(context:Context,profileId:String,
   calculate:(Context)->T,commit:(T)->R):R {
  val snapshot=synchronized(lock){
   active();require(profileExists(context,profileId)){"该用户声纹已删除"}
   identitySnapshot(context,load(context))
  }
  val result=withIdentitySnapshot(context,snapshot){frozen->active();calculate(frozen)}
  return synchronized(lock){synchronized(WearerVoiceStore){
   active();val state=load(context)
   require(snapshot.sameAs(identitySnapshot(context,state))){"声纹库在检查期间发生变化，请重新补录"}
   require(profileExists(context,profileId)){"该用户声纹已删除，未保存补录"}
   commit(result)
  }}
 }
 /** New enrollments also retain structured identity diagnostics; freeze and recheck
  * the roster so an enrollment cannot resurrect a profile deleted during inference. */
 internal fun <T,R> guardedNewProfile(context:Context,calculate:(Context)->T,commit:(T)->R):R {
  val snapshot=synchronized(lock){active();identitySnapshot(context,load(context))}
  val result=withIdentitySnapshot(context,snapshot){frozen->active();calculate(frozen)}
  return synchronized(lock){synchronized(WearerVoiceStore){
   active();require(snapshot.sameAs(identitySnapshot(context,load(context)))){"声纹库在登记期间发生变化，请重新录制"}
   commit(result)
  }}
 }
 private fun isDuplicate(state:JSONObject,origin:Origin,includeIgnored:Boolean=true,exceptCandidate:String?=null):Boolean =
  (if(includeIgnored)listOf("candidates","samples","ignored") else listOf("candidates","samples"))
   .any{key->objects(state.getJSONArray(key)).any{(key!="candidates"||it.optString("id")!=exceptCandidate)&&overlaps(origin(it),origin)}}
 /** Only the same in-memory analyzer-issued waveform may create a narrowed retry.
  * A persisted WAV or JSON record alone cannot mint an acoustic proof. */
 private fun retryParent(context:Context,state:JSONObject,parentId:String,runId:String,profileId:String,
                         child:SpeechEvidence.PureCandidate,checkAudio:Boolean):JSONObject {
  require(uuid.matches(parentId)&&uuid.matches(runId)&&uuid.matches(profileId)){"自动子段来源无效"}
  val parent=objects(state.getJSONArray("candidates")).firstOrNull{it.optString("id")==parentId}
   ?:throw IllegalArgumentException("原候选已移除，子段保留待确认")
  require(parent.optString("collection_run_id")==runId&&parent.optString("suggested")==profileId&&
   currentAcousticEvidence(parent)&&continuity(parent)!=null){"原候选证据已变化，子段保留待确认"}
  require(!AutomaticRetryPlan.edgeRescueReviewOnly(continuity(parent))){"原候选经过裁边补救，子段保留待确认"}
  val original=origin(parent)
  require(child.source==original.source&&child.startSample>=original.startSample&&child.endSample<=original.endSample&&
   (child.startSample>original.startSample||child.endSample<original.endSample)){"自动子段超出原候选"}
  val old=parent.getJSONObject("quality").getJSONObject("evidence");val fresh=child.evidenceJson()
  require(old.optString("local_track")==fresh.optString("local_track")&&
   old.optLong("analysis_window_start",-1)==fresh.optLong("analysis_window_start",-2)&&
   old.optLong("analysis_window_end",-1)==fresh.optLong("analysis_window_end",-2)){"自动子段活动轨不一致"}
  if(checkAudio){
   val file=audioFile(context,parentId);require(file.isFile){"原候选音频已丢失"}
   val audio=file.inputStream().use{WavReader.read16k(it)}
   require(audio.size.toLong()==original.endSample-original.startSample){"原候选音频长度不一致"}
   val samples=child.samples();val offset=(child.startSample-original.startSample).toInt()
   require(samples.indices.all{i->audio[offset+i]==(samples[i].coerceIn(-1f,1f)*32767).toInt().toShort().toInt()/32768f}){"子段音频与原候选不一致"}
  }
  return parent
 }
 private fun retryParentRecord(state:JSONObject,item:JSONObject,runId:String,profileId:String):JSONObject? {
  val id=item.optString("automatic_retry_parent");if(id.isBlank())return null
  require(uuid.matches(id)){"自动子段来源无效"}
  val parent=objects(state.getJSONArray("candidates")).firstOrNull{it.optString("id")==id}
   ?:throw IllegalArgumentException("原候选已移除，子段保留待确认")
  require(parent.optString("collection_run_id")==runId&&parent.optString("suggested")==profileId&&
   currentAcousticEvidence(parent)&&continuity(parent)!=null){"原候选证据已变化"}
  require(!AutomaticRetryPlan.edgeRescueReviewOnly(continuity(parent))){"原候选经过裁边补救，子段保留待确认"}
  val a=origin(parent);val b=origin(item)
  require(a.source==b.source&&b.startSample>=a.startSample&&b.endSample<=a.endSample&&b!=a){"自动子段来源范围无效"}
  val old=parent.getJSONObject("quality").getJSONObject("evidence");val fresh=item.getJSONObject("quality").getJSONObject("evidence")
  require(old.optString("local_track")==fresh.optString("local_track")&&
   old.optLong("analysis_window_start",-1)==fresh.optLong("analysis_window_start",-2)&&
   old.optLong("analysis_window_end",-1)==fresh.optLong("analysis_window_end",-2)){"自动子段活动轨不一致"}
  return parent
 }
 private fun history(state:JSONObject,event:JSONObject){
  state.put("history",JSONArray().apply{(objects(state.getJSONArray("history"))+event).takeLast(128).forEach{put(it)}})
 }
 private fun ignore(state:JSONObject,item:JSONObject){
  val list=objects(state.getJSONArray("ignored")).toMutableList()
  list.add(JSONObject().put("source",item.getString("source")).put("start",item.getLong("start")).put("end",item.getLong("end")))
  state.put("ignored",JSONArray().apply{list.takeLast(512).forEach{put(it)}})
 }
 fun listCandidates(context:Context):List<CandidateInfo> = synchronized(lock){
  val state=load(context);val samples=objects(state.getJSONArray("samples"))
  objects(state.getJSONArray("candidates")).map{item->val info=candidate(item)
   if(samples.any{overlaps(origin(it),origin(item))})info.copy(title=info.title+"（部分已学习，其余待确认）") else info
  }.sortedByDescending{it.createdMs}
 }
 fun listSamples(context:Context,profileId:String):List<SampleInfo> = synchronized(lock){objects(load(context).getJSONArray("samples")).filter{it.getString("profile")==profileId}.map(::sample).sortedByDescending{it.createdMs}}
 fun activeOrigins(context:Context,profileId:String):List<Origin> = synchronized(lock){objects(load(context).getJSONArray("samples")).filter{it.getString("profile")==profileId}.map(::origin)}
 fun activeSources(context:Context,profileId:String):Set<String> = activeOrigins(context,profileId).map{it.source}.toSet()
 fun matchesLearningSource(context:Context,profileId:String,source:String,startSample:Long,endSample:Long):Boolean =
  activeOrigins(context,profileId).any{overlaps(it,Origin(source,startSample,endSample))}
 fun candidateAudio(context:Context,id:String):File? = synchronized(lock){
  if(objects(load(context).getJSONArray("candidates")).none{it.getString("id")==id})null else audioFile(context,id).takeIf{it.isFile}
 }
 internal fun templateSnapshot(context:Context,model:String):Map<String,List<FloatArray>> = synchronized(lock){
  objects(load(context).getJSONArray("samples")).groupBy{it.getString("profile")}.mapValues{(_,items)->
   items.mapNotNull{it.getJSONObject("vectors").optJSONArray(model)?.let(::vector)}
  }
 }
 internal fun modelSupport(context:Context):Map<String,Set<String>> = synchronized(lock){
  objects(load(context).getJSONArray("samples")).groupBy{it.getString("profile")}.mapValues{(_,items)->
   items.flatMap{it.getJSONObject("vectors").keys().asSequence().toList()}.toSet()
  }
 }
 private fun vector(values:JSONArray):FloatArray {
  require(values.length() in 1..4096){"声纹学习向量无效"}
  val raw=FloatArray(values.length()){values.getDouble(it).toFloat()}
  require(raw.all{it.isFinite()}){"声纹学习向量无效"}
  val norm=sqrt(raw.sumOf{it.toDouble()*it});require(norm>1e-8){"声纹学习向量无效"}
  return FloatArray(raw.size){(raw[it]/norm).toFloat()}
 }

 /** Compatibility parameters can veto admission but never establish clean speech. */
 @Deprecated("请复用SpeechEvidence及其PureCandidate；布尔值不能建立单人证据")
 fun quality(context:Context,samples:FloatArray,singleSpeaker:Boolean?=null,overlapping:Boolean=false):Quality {
  active()
  if(samples.size<RATE*3)return Quality(false,"有效录音至少需要3秒")
  if(samples.size>RATE*30)return Quality(false,"单条补充录音不能超过30秒")
  if(samples.any{!it.isFinite()})return Quality(false,"录音采样无效")
  val evidence=SpeechEvidence.analyze(context,samples,"quality:local",0L,false)
  if(overlapping||singleSpeaker==false)return Quality(false,"调用方报告多人或重叠，未纳入学习",evidence=evidence.toJson())
  var rejected:Quality?=null
  for(candidate in SpeechEvidence.extractPureCandidates(samples,evidence).sortedByDescending{it.endSample-it.startSample}){
   val checked=quality(context,candidate);if(checked.accepted)return checked;rejected=checked
  }
  return rejected?:Quality(false,"未找到至少3秒且避开重叠和边界的连续独占语音",
   overlapping=evidence.toJson().getLong("predicted_overlap_samples")>0,evidence=evidence.toJson())
 }
 /** Only analyzer-issued candidates can use the already-checked activity path. */
 fun quality(context:Context,candidate:SpeechEvidence.PureCandidate):Quality {
  active()
  val samples=candidate.samples()
  if(samples.size<RATE*3)return Quality(false,"有效录音至少需要3秒")
  val rms=sqrt(samples.sumOf{it.toDouble()*it}/samples.size)
  val clipped=samples.count{abs(it)>=0.995f}.toDouble()/samples.size
  if(rms<0.003)return Quality(false,"音量过低，未纳入学习",rms=rms,clipping=clipped)
  if(clipped>0.01)return Quality(false,"录音削波过多，请稍远离麦克风",rms=rms,clipping=clipped)
  val vad=VadModelFactory.create(context.assets,VadModelFactory.SILERO)
  var count=0
  fun drain(){while(!vad.empty()){count+=vad.front().samples.size;vad.pop()}}
  try{
   val window=VadModelFactory.windowSize(VadModelFactory.SILERO);var pos=0
   while(pos<samples.size){active();val n=minOf(window,samples.size-pos);val frame=FloatArray(window);samples.copyInto(frame,0,pos,pos+n);vad.acceptWaveform(frame);pos+=n;drain()}
   vad.flush();drain()
  }finally{vad.release()}
  val fraction=count.toDouble()/samples.size
  if(count<RATE*3||fraction<0.55)return Quality(false,"有效人声不足或静音过多",count,fraction,rms,clipped)
  // Use exactly the candidate interval for both playback and embedding; VAD is a gate,
  // not a second edit that silently splices different audio together.
  return Quality(true,"连续独占语音质量检查通过",count,fraction,rms,clipped,true,false,samples,
   candidate.startSample,candidate.endSample,candidate.evidenceJson())
 }
 internal data class Admission(val candidate:SpeechEvidence.PureCandidate,val quality:Quality,val gate:VoiceContinuityGate.Result)
 internal data class Assessment(val admission:Admission?,val reason:String,val diagnostic:JSONObject?=null)
 private fun qualityFailure(candidate:SpeechEvidence.PureCandidate,q:Quality):Assessment {
  val data=JSONObject().put("version",1).put("stage","quality").put("status","rejected")
   .put("reason",q.reason).put("source_start_sample",candidate.startSample).put("source_end_sample",candidate.endSample)
   .put("sample_rate",RATE).put("minimum_speech_samples",RATE*3).put("minimum_speech_fraction",0.55)
   .put("minimum_rms",0.003).put("maximum_clipping_fraction",0.01)
  if(candidate.endSample-candidate.startSample>=RATE*3){data.put("rms",q.rms).put("clipping",q.clipping)}
  if(q.reason=="有效人声不足或静音过多"||q.accepted)data.put("speech_samples",q.speechSamples).put("speech_fraction",q.speechFraction)
  return Assessment(null,q.reason,data)
 }
 private fun gateFailure(stage:String,result:VoiceContinuityGate.Result,profileId:String?=null):Assessment =
  Assessment(null,result.reason,VoiceLearningDiagnostic.failure(stage,result.reason,result.evidence,profileId))
 /** Learning alone has a wider competing-track exclusion than recognition. */
 internal fun assess(context:Context,candidate:SpeechEvidence.PureCandidate,profileId:String?=null):Assessment {
  val safe=candidate.learningSafeClips().sortedByDescending{it.endSample-it.startSample}
  if(safe.isEmpty()){
   val reason="说话人交替或重叠附近缺少至少3秒的连续语音"
   return Assessment(null,reason,VoiceLearningDiagnostic.failure("activity",reason).put("safe_for_quality_rescue",false))
  }
  var reason="未找到可学习的连续语音";var diagnostic:JSONObject?=null;var onlyQuality=true
  val rejected=LearningRejectionSummary()
  for(part in safe){
   val checked=assessProtected(context,part,profileId)
   if(checked.admission!=null)return checked
   val stage=checked.diagnostic?.optString("stage").orEmpty().ifBlank{"unknown"}
   if(rejected.record(stage)){reason=checked.reason;diagnostic=checked.diagnostic}
   if(checked.diagnostic?.optString("stage")!="quality"||checked.diagnostic?.optBoolean("quality_rescue_error",false)==true)onlyQuality=false
  }
  // A quality rejection precedes continuity evaluation; it is not proof that the
  // unexamined source is free of a hidden speaker change.
  return Assessment(null,reason,(diagnostic?:VoiceLearningDiagnostic.failure("unknown",reason))
   .put("safe_for_quality_rescue",false).put("safe_for_review_quality_rescue",onlyQuality)
   .put("attempted_stage_counts",JSONObject().apply{for((stage,count) in rejected.counts())put(stage,count)}))
 }
 /** Only after a failed quality/continuity check, try one edge-only VAD crop. The resulting
  * waveform must independently pass all the original checks and never gains new audio. */
 private fun assessProtected(context:Context,candidate:SpeechEvidence.PureCandidate,profileId:String?=null):Assessment {
  val firstQuality=quality(context,candidate)
  val firstGate=if(firstQuality.accepted)VoiceContinuityGate.check(context,checkNotNull(firstQuality.speech),candidate.startSample) else null
  if(firstGate?.accepted==true){
   if(profileId!=null&&!VoiceContinuityGate.targetCompatible(firstGate.evidence,profileId)){
    val reason="该片段有与所选人物相反的声纹证据"
    return Assessment(null,reason,VoiceLearningDiagnostic.failure("target_conflict",reason,firstGate.evidence,profileId))
   }
   return Assessment(Admission(candidate,firstQuality,firstGate),firstGate.reason)
  }
  val reason=firstGate?.reason?:firstQuality.reason
  val retry=if(firstGate!=null){
   firstGate.evidence.optString("status")=="acoustic_conflict" ||
    (firstGate.evidence.optString("status")=="feature_unavailable"&&firstGate.reason.contains("短语音窗"))
  }else firstQuality.reason=="有效人声不足或静音过多"
  val firstFailure=if(firstGate!=null)gateFailure("continuity",firstGate,profileId) else qualityFailure(candidate,firstQuality)
  if(!retry)return firstFailure
  val crop=try{VoiceContinuityGate.lowSpeechTrim(context,candidate.samples())}catch(e:Exception){
   if(e is java.util.concurrent.CancellationException)throw e
   firstFailure.diagnostic?.put("quality_rescue_error",true)
   return firstFailure
  }?:return firstFailure
  val reduced=candidate.clip(candidate.startSample+crop.start,candidate.startSample+crop.end)?:return firstFailure
  val checked=quality(context,reduced);if(!checked.accepted)return firstFailure
  val gate=VoiceContinuityGate.check(context,checkNotNull(checked.speech),reduced.startSample)
  gate.evidence.put("edge_rescue",JSONObject(crop.evidence.toString())
   .put("original_start_sample",candidate.startSample).put("original_end_sample",candidate.endSample)
   .put("cropped_start_sample",reduced.startSample).put("cropped_end_sample",reduced.endSample)
   .put("first_rejection",reason).put("first_continuity",firstGate?.evidence?:JSONObject.NULL)
   .put("automatic_learning_allowed",false)
   .put("first_diagnostic",firstFailure.diagnostic?:JSONObject.NULL))
  if(!gate.accepted)return gateFailure("continuity",gate,profileId)
  if(profileId!=null&&!VoiceContinuityGate.targetCompatible(gate.evidence,profileId)){
   val conflict="裁边后仍有与所选人物相反的声纹证据"
   return Assessment(null,conflict,VoiceLearningDiagnostic.failure("target_conflict",conflict,gate.evidence,profileId))
  }
  return Assessment(Admission(reduced,checked,gate),gate.reason)
 }
 private fun embeddings(context:Context,speech:FloatArray):JSONObject {
  val out=JSONObject()
  for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES)){
   active()
   val extractor=LocalSpeakerTracker(context.assets,model)
   val value=try{extractor.embedding(speech)}finally{extractor.close()}
    ?:throw IllegalArgumentException("${SpeakerModels.name(model)}无法提取声纹，未保存学习样本")
   out.put(model,JSONArray().apply{value.forEach{put(it.toDouble())}})
  }
  return out
 }
 private fun entry(id:String,where:Origin,title:String,quality:Quality,vectors:JSONObject):JSONObject = JSONObject()
  .put("id",id).put("source",where.source).put("start",where.startSample).put("end",where.endSample)
  .put("title",title.take(256)).put("seconds",(where.endSample-where.startSample).toDouble()/RATE).put("at",System.currentTimeMillis())
  .put("vectors",vectors).put("quality",JSONObject().put("speech_fraction",quality.speechFraction).put("speech_seconds",quality.speechSamples.toDouble()/RATE).put("rms",quality.rms)
   .put("clipping",quality.clipping).put("single_speaker",quality.singleSpeaker).put("overlapping",quality.overlapping)
   .put("evidence",quality.evidence?:JSONObject.NULL))
 private fun writeAudio(context:Context,id:String,samples:FloatArray){
  val directory=folder(context);require(directory.isDirectory||directory.mkdirs()){"无法创建学习录音目录"}
  val file=AtomicFile(audioFile(context,id));val stream=file.startWrite()
  try{
   val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
   header.put("RIFF".toByteArray()).putInt(36+samples.size*2).put("WAVEfmt ".toByteArray()).putInt(16)
    .putShort(1).putShort(1).putInt(RATE).putInt(RATE*2).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples.size*2)
   stream.write(header.array());val block=ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
   for(x in samples){if(block.remaining()<2){stream.write(block.array(),0,block.position());block.clear()};block.putShort((x.coerceIn(-1f,1f)*32767).toInt().toShort())}
   if(block.position()>0)stream.write(block.array(),0,block.position());stream.flush();stream.fd.sync();file.finishWrite(stream)
  }catch(t:Throwable){file.failWrite(stream);throw t}
 }
 @Deprecated("请传入SpeechEvidence分析器生成的PureCandidate")
 fun collectCandidate(context:Context,samples:FloatArray,source:String,title:String,singleSpeaker:Boolean,overlapping:Boolean,
                      suggestedProfileId:String?=null,startSample:Long=0L,endSample:Long=startSample+samples.size,
                      allowPreviouslyIgnored:Boolean=false):CandidateInfo? {
  checkedOrigin(source,startSample,endSample)
  require(endSample-startSample==samples.size.toLong()){"录音与来源范围长度不一致"}
  if(samples.size !in RATE*3..RATE*30||!singleSpeaker||overlapping)return null
  synchronized(lock){
   val state=load(context)
   objects(state.getJSONArray("candidates")).firstOrNull{origin(it)==Origin(source,startSample,endSample)}?.let{return if(continuity(it)!=null)candidate(it) else null}
   if(state.getJSONArray("candidates").length()>=MAX_CANDIDATES||
      isDuplicate(state,Origin(source,startSample,endSample),!allowPreviouslyIgnored))return null
  }
  val evidence=SpeechEvidence.analyze(context,samples,source,startSample,false)
  for(candidate in SpeechEvidence.extractPureCandidates(samples,evidence).sortedByDescending{it.endSample-it.startSample}){
   collectCandidate(context,candidate,title,suggestedProfileId,allowPreviouslyIgnored)?.let{return it}
  }
  return null
 }
 fun collectCandidate(context:Context,candidate:SpeechEvidence.PureCandidate,title:String,
                      suggestedProfileId:String?=null,allowPreviouslyIgnored:Boolean=false):CandidateInfo? {
  return collectCandidateInternal(context,candidate,title,suggestedProfileId,allowPreviouslyIgnored,null,null,null,null)
 }
 internal fun collectCandidateForRun(context:Context,candidate:SpeechEvidence.PureCandidate,title:String,
                       suggestedProfileId:String?,collectionRunId:String?,guard:AutoLearningRunGuard):CandidateInfo? {
  if(collectionRunId!=null)require(uuid.matches(collectionRunId)){"学习任务编号无效"}
  return collectCandidateInternal(context,candidate,title,suggestedProfileId,false,null,collectionRunId,guard,null)
 }
 /** A retry may overlap only its own retained parent, never another candidate/sample. */
 internal fun collectAutomaticRetry(context:Context,candidate:SpeechEvidence.PureCandidate,profileId:String,
                                    collectionRunId:String,parentId:String,guard:AutoLearningRunGuard):CandidateInfo? =
  collectCandidateInternal(context,candidate,"自动筛选子段",profileId,false,null,collectionRunId,guard,parentId)
 /** Spanning candidates are always anonymous pending review; they never relabel rows. */
 internal fun collectSpanningCandidate(context:Context,candidate:SpeechEvidence.PureCandidate,
                                        scope:SpanScope,guard:AutoLearningRunGuard):CandidateInfo? {
  require(scope.displayOffsetSamples>=0&&scope.displayOffsetSamples<=Long.MAX_VALUE-candidate.endSample){"显示时间无效"}
  if(SpanningCandidatePlan.coverage(candidate.startSample,candidate.endSample,scope.rows)==null)return null
  return collectCandidateInternal(context,candidate,"跨行学习片段",null,false,scope,null,guard,null)
 }
 private fun collectCandidateInternal(context:Context,candidate:SpeechEvidence.PureCandidate,title:String,
                                      suggestedProfileId:String?,allowPreviouslyIgnored:Boolean,scope:SpanScope?,collectionRunId:String?,guard:AutoLearningRunGuard?,retryParentId:String?):CandidateInfo? {
  val where=checkedOrigin(candidate.source,candidate.startSample,candidate.endSample)
  var retryParentSnapshot:String?=null
   val snapshot=synchronized(lock){
   val state=load(context)
   if(retryParentId!=null){
    require(scope==null&&guard!=null&&!allowPreviouslyIgnored){"自动子段不能更改候选模式"}
    retryParentSnapshot=retryParent(context,state,retryParentId,requireNotNull(collectionRunId),requireNotNull(suggestedProfileId),candidate,true).toString()
   }
   objects(state.getJSONArray("candidates")).firstOrNull{origin(it)==where}?.let{
    if(retryParentId!=null)return null
    return if(continuity(it)!=null&&(scope==null||sameSpanContext(it,scope)))candidate(it) else null
   }
   if(state.getJSONArray("candidates").length()>=MAX_CANDIDATES||isDuplicate(state,where,!allowPreviouslyIgnored,retryParentId))return null
    identitySnapshot(context,state)
  }
  val assessment=assess(context,candidate)
  val admission=assessment.admission?:run{
   if(guard!=null&&scope==null)throw VoiceLearningRejection(assessment.diagnostic?:VoiceLearningDiagnostic.failure("unknown",assessment.reason),assessment.reason)
   return null
  }
  val selected=admission.candidate;val checked=admission.quality;val consistent=admission.gate
  val selectedWhere=checkedOrigin(selected.source,selected.startSample,selected.endSample)
  // Recompute row intersections after quality/boundary trimming. The UI must
  // describe exactly the saved audio interval, not the longer proposed range.
  val pieces=scope?.let{SpanningCandidatePlan.coverage(selectedWhere.startSample,selectedWhere.endSample,it.rows)?:return null}
  val vectors=embeddings(context,checked.speech!!);val id=UUID.randomUUID().toString()
  val storedTitle=if(selectedWhere!=where&&title.startsWith("[")&&title.contains("]"))title.substringAfter(']').trimStart() else title
  val item=entry(id,selectedWhere,storedTitle,checked,vectors).put("suggested",suggestedProfileId?:"")
  if(collectionRunId!=null)item.put("collection_run_id",collectionRunId)
  if(retryParentId!=null)item.put("automatic_retry_parent",retryParentId)
  if(scope!=null&&pieces!=null)item.put("span_context",JSONObject().put("display_offset_samples",scope.displayOffsetSamples)
   .put("rows",JSONArray().apply{pieces.forEach{piece->put(JSONObject().put("start_sample",piece.row.start)
    .put("end_sample",piece.row.end).put("text",piece.row.text).put("stable_id",piece.row.stableId?:JSONObject.NULL)
    .put("covered_start_sample",piece.start).put("covered_end_sample",piece.end))}}))
  item.getJSONObject("quality").put("continuity",consistent.evidence)
  return synchronized(lock){ synchronized(WearerVoiceStore) {
   val commit:()->CandidateInfo? = commit@{
   active()
   val state=load(context)
    require(snapshot.sameAs(identitySnapshot(context,state))){"声纹库在候选筛选期间发生变化，请重新识别"}
    if(retryParentId!=null){
     val parent=retryParent(context,state,retryParentId,requireNotNull(collectionRunId),requireNotNull(suggestedProfileId),selected,false)
     require(parent.toString()==retryParentSnapshot){"原候选在筛选期间发生变化，子段未保存"}
    }
    if(!suggestedProfileId.isNullOrBlank()&&!profileExists(context,suggestedProfileId))item.put("suggested","")
   objects(state.getJSONArray("candidates")).firstOrNull{origin(it)==selectedWhere}?.let{
    return@commit if(continuity(it)!=null&&(scope==null||sameSpanContext(it,scope)))candidate(it) else null
   }
   if(state.getJSONArray("candidates").length()>=MAX_CANDIDATES||isDuplicate(state,selectedWhere,!allowPreviouslyIgnored,retryParentId))return@commit null
   writeAudio(context,id,selected.samples())
   try{
    if(allowPreviouslyIgnored)state.put("ignored",keep(state.getJSONArray("ignored")){!exactSourceOverlap(origin(it),selectedWhere)})
    state.getJSONArray("candidates").put(item);save(context,state)
   }catch(t:Throwable){AtomicFile(audioFile(context,id)).delete();throw t}
   candidate(item)
   }
   if(guard!=null)guard.commit({true},commit) else commit()
  }}
 }
 fun confirmCandidate(context:Context,candidateId:String,profileId:String):SampleInfo =
  confirmCandidateInternal(context,candidateId,profileId,false,{true})
 /** Call only for a newly created candidate with independently verified identity provenance. */
 internal fun confirmCandidateAutomatic(context:Context,candidateId:String,profileId:String,collectionRunId:String,
                                         isEnabledNow:()->Boolean,guardedCommit:((()->SampleInfo)->SampleInfo)):SampleInfo {
  require(uuid.matches(collectionRunId)){"学习任务编号无效"}
  return confirmCandidateInternal(context,candidateId,profileId,true,isEnabledNow,collectionRunId,guardedCommit)
 }
 /** Caller serializes this short write with its cancellation guard. No PCM or embeddings are recorded. */
 internal fun recordAutomaticRejection(context:Context,candidateId:String,collectionRunId:String,diagnostic:JSONObject):Boolean = synchronized(lock){
  active();require(uuid.matches(candidateId)&&uuid.matches(collectionRunId)){"学习任务编号无效"}
  val state=load(context);val pending=objects(state.getJSONArray("candidates"))
   .firstOrNull{it.getString("id")==candidateId&&it.optString("collection_run_id")==collectionRunId}?:return@synchronized false
  val safe=JSONObject(diagnostic.toString());require(safe.toString().length<=131072){"声纹诊断过大"}
  pending.put("auto_diagnostic",safe);save(context,state);true
 }
 private fun confirmCandidateInternal(context:Context,candidateId:String,profileId:String,automatic:Boolean,isEnabledNow:()->Boolean,
                                      collectionRunId:String?=null,guardedCommit:((()->SampleInfo)->SampleInfo)?=null):SampleInfo {
  data class Prepared(val item:String,val waveform:FloatArray,val sourceStart:Long,val identity:IdentitySnapshot,val parent:String?)
  val prepared=synchronized(lock){
   active();require(profileExists(context,profileId)){"该用户声纹已删除"}
   val state=load(context)
   objects(state.getJSONArray("samples")).firstOrNull{it.getString("id")==candidateId}?.let{
    if(automatic)throw IllegalArgumentException("该候选已确认，不计入本次自动学习")
    if(it.getString("profile")==profileId)return sample(it)
    throw IllegalArgumentException("该样本的试听音频已移除，请重新选择片段或补录后更正人物")
   }
   val item=objects(state.getJSONArray("candidates")).firstOrNull{it.getString("id")==candidateId}
    ?:throw IllegalArgumentException("待确认样本已移除")
   if(automatic)require(item.optString("collection_run_id")==collectionRunId){"候选不属于本次识别任务"}
   require(objects(state.getJSONArray("samples")).none{overlaps(origin(it),origin(item))}){"该候选与已学习片段重叠，请先删除补充样本或重新选择"}
   val parent=if(automatic)retryParentRecord(state,item,requireNotNull(collectionRunId),profileId)?.toString() else null
   require(currentAcousticEvidence(item)){
    "旧版候选缺少新版边界保护，请删除此候选后重新识别或补录；原有声纹不受影响"
   }
   require(continuity(item)!=null){"旧候选缺少轨内声纹一致性检查，请删除后重新识别或补录；原有声纹不受影响"}
   if(automatic&&AutomaticRetryPlan.edgeRescueReviewOnly(continuity(item))){
    val edge=continuity(item)?.optJSONObject("edge_rescue")
    val reason="原始片段未通过完整检查，裁边后仅可人工确认"
    val diagnostic=edge?.optJSONObject("first_diagnostic")?.let{JSONObject(it.toString())}
     ?:VoiceLearningDiagnostic.failure("continuity",reason,edge?.optJSONObject("first_continuity"),profileId)
    throw VoiceLearningRejection(diagnostic,reason)
   }
   val path=audioFile(context,candidateId);require(path.isFile){"候选音频已丢失，请重新识别或补录"}
   val waveform=path.inputStream().use{WavReader.read16k(it)}
   val where=origin(item);require(waveform.size.toLong()==where.endSample-where.startSample){"候选音频与时间范围不一致，请重新识别"}
   Prepared(item.toString(),waveform,where.startSample,identitySnapshot(context,state),parent)
  }
  // No application-state lock is held while models process the candidate. Cancellation,
  // loading errors and any new identity conflict all leave the pending sample untouched.
  if(automatic)require(isEnabledNow()){"自动学习已关闭，候选保留待确认"}
  val fresh=withIdentitySnapshot(context,prepared.identity){snapshotContext->
   active()
   val result=VoiceContinuityGate.check(snapshotContext,prepared.waveform,prepared.sourceStart)
   fun reject(stage:String,reason:String,evidence:JSONObject):Nothing {
    if(automatic)throw VoiceLearningRejection(VoiceLearningDiagnostic.failure(stage,reason,evidence,profileId),reason)
    throw IllegalArgumentException(reason)
   }
   if(!result.accepted)reject("continuity","候选重新检查未通过：${result.reason}",result.evidence)
   if(automatic&&result.evidence.optString("status")!="review_required")reject("continuity","轨内一致性不足，候选保留人工确认",result.evidence)
   if(!VoiceContinuityGate.targetCompatible(result.evidence,profileId))reject("target_conflict","该片段有与所选人物相反的声纹证据，无法纳入学习",result.evidence)
   val anchor=VoiceContinuityGate.initialAnchorSupport(snapshotContext,prepared.waveform,profileId,prepared.sourceStart)
   if(!anchor.accepted)reject("anchor",anchor.reason,anchor.evidence)
   val strict=if(automatic)VoiceContinuityGate.automaticAnchorSupport(snapshotContext,prepared.waveform,profileId,prepared.sourceStart,prepared.parent!=null) else null
   if(strict!=null&&!strict.accepted)reject("automatic",strict.reason,strict.evidence)
   Triple(result.evidence,anchor.evidence,strict?.evidence)
  }
  val commit:()->SampleInfo={ synchronized(lock){ synchronized(WearerVoiceStore){
   active();if(automatic)require(isEnabledNow()){"自动学习已关闭，候选保留待确认"};val state=load(context)
   require(prepared.identity.sameAs(identitySnapshot(context,state))){"声纹库在检查期间发生变化，请重新确认"}
   require(profileExists(context,profileId)){"该用户声纹已删除"}
   val pending=state.getJSONArray("candidates")
   val item=objects(pending).firstOrNull{it.getString("id")==candidateId}?:throw IllegalArgumentException("待确认样本已移除")
   require(item.toString()==prepared.item && audioFile(context,candidateId).isFile){"待确认样本在检查期间发生变化，请重新选择"}
   require(objects(state.getJSONArray("samples")).none{overlaps(origin(it),origin(item))}){"该候选与已学习片段重叠，请先删除补充样本或重新选择"}
   val parent=if(automatic)retryParentRecord(state,item,requireNotNull(collectionRunId),profileId) else null
   require(parent?.toString()==prepared.parent){"原候选在检查期间发生变化"}
   require(objects(state.getJSONArray("samples")).count{it.getString("profile")==profileId}<MAX_TEMPLATES){"该用户已达到24条补充样本，请先删除不需要的样本"}
   val copy=JSONObject(item.toString()).put("profile",profileId).put("confirmed_at",System.currentTimeMillis())
    .put("confirmation_mode",if(automatic)"automatic" else "manual")
   copy.getJSONObject("quality").put("continuity",fresh.first).put("initial_anchor_support",fresh.second)
   if(fresh.third!=null)copy.getJSONObject("quality").put("automatic_initial_support",fresh.third)
   copy.remove("suggested")
   state.getJSONArray("samples").put(copy);state.put("candidates",keep(pending){it.getString("id")!=candidateId})
   history(state,JSONObject().put("op","add").put("sample",candidateId).put("profile",profileId).put("mode",if(automatic)"automatic" else "manual"))
   save(context,state);AtomicFile(audioFile(context,candidateId)).delete();sample(copy)
  }}}
  return if(automatic)requireNotNull(guardedCommit)(commit) else commit()
 }
 fun discardCandidate(context:Context,id:String)=synchronized(lock){
  val state=load(context);val pending=state.getJSONArray("candidates")
  val found=objects(pending).firstOrNull{it.getString("id")==id}?:return@synchronized
  // An obsolete candidate cannot be confirmed. Removing it is a migration step,
  // not a rejection of this audio; let a later recognition re-evaluate it.
  if(currentAcousticEvidence(found)&&continuity(found)!=null)ignore(state,found)
  state.put("candidates",keep(pending){it.getString("id")!=id});save(context,state);AtomicFile(audioFile(context,id)).delete()
 }
 fun supplement(context:Context,profileId:String,samples:FloatArray,source:String,title:String,
                startSample:Long=0L,endSample:Long=startSample+samples.size):SampleInfo {
  checkedOrigin(source,startSample,endSample)
  require(endSample-startSample==samples.size.toLong()){"录音与来源范围长度不一致"}
  require(samples.size in RATE*3..RATE*30){"补录须为3～30秒"}
  val snapshot=synchronized(lock){require(profileExists(context,profileId)){"该用户声纹已删除"};identitySnapshot(context,load(context))}
  val checked=withIdentitySnapshot(context,snapshot){frozenContext->
   val evidence=SpeechEvidence.analyze(frozenContext,samples,source,startSample,false)
   var lastReason="未找到至少3秒且避开重叠和边界的连续独占语音"
   var accepted:Pair<Admission,JSONObject>?=null
   for(candidate in SpeechEvidence.extractPureCandidates(samples,evidence).sortedByDescending{it.endSample-it.startSample}){
    val assessment=assess(frozenContext,candidate,profileId);lastReason=assessment.reason
    val admission=assessment.admission?:continue
    val anchor=VoiceContinuityGate.initialAnchorSupport(frozenContext,checkNotNull(admission.quality.speech),profileId,admission.candidate.startSample)
    if(!anchor.accepted){lastReason=anchor.reason;continue}
    accepted=admission to anchor.evidence;break
   }
   accepted?:throw IllegalArgumentException(lastReason)
  }
  val admission=checked.first;val chosen=admission.candidate;val verified=admission.quality
  val where=checkedOrigin(source,chosen.startSample,chosen.endSample)
  synchronized(lock){val state=load(context);require(!isDuplicate(state,where,false)){"这段录音已作为学习样本或待确认候选"}}
  val item=entry(UUID.randomUUID().toString(),where,title,verified,embeddings(context,verified.speech!!)).put("profile",profileId)
   .put("confirmed_at",System.currentTimeMillis())
  item.getJSONObject("quality").put("continuity",admission.gate.evidence).put("initial_anchor_support",checked.second)
  return synchronized(lock){ synchronized(WearerVoiceStore){
   active()
   require(profileExists(context,profileId)){"该用户声纹已删除，未保存补录"}
   val state=load(context);require(snapshot.sameAs(identitySnapshot(context,state))){"声纹库在检查期间发生变化，请重新补录"}
   require(!isDuplicate(state,where,false)){"这段录音已作为学习样本或待确认候选"}
   require(objects(state.getJSONArray("samples")).count{it.getString("profile")==profileId}<MAX_TEMPLATES){"该用户已达到24条补充样本，请先删除不需要的样本"}
   state.put("ignored",keep(state.getJSONArray("ignored")){!exactSourceOverlap(origin(it),where)})
   state.getJSONArray("samples").put(item)
   history(state,JSONObject().put("op","add").put("sample",item.getString("id")).put("profile",profileId))
   save(context,state);sample(item)
  }}
 }
 fun removeSample(context:Context,profileId:String,id:String)=synchronized(lock){
  val state=load(context);val rows=state.getJSONArray("samples")
  val found=objects(rows).firstOrNull{it.getString("profile")==profileId&&it.getString("id")==id}?:return@synchronized
  ignore(state,found);state.put("samples",keep(rows){it.getString("id")!=id})
  state.put("history",keep(state.getJSONArray("history")){it.getString("sample")!=id});save(context,state)
 }
 fun reassignSample(context:Context,sampleId:String,profileId:String):SampleInfo = synchronized(lock){
  require(profileExists(context,profileId)){"该用户声纹已删除"}
  val state=load(context);val rows=state.getJSONArray("samples")
  val current=objects(rows).firstOrNull{it.getString("id")==sampleId}?:throw IllegalArgumentException("补充样本已删除")
  if(current.getString("profile")==profileId)return@synchronized sample(current)
  throw IllegalArgumentException("该样本的试听音频已移除，请重新选择片段或补录后更正人物")
 }
 fun undoLast(context:Context,profileId:String):Boolean = synchronized(lock){
  val state=load(context);val rows=state.getJSONArray("samples")
  val events=state.getJSONArray("history")
  val event=objects(events).lastOrNull{e->e.getString("profile")==profileId && objects(rows).any{it.getString("id")==e.getString("sample")&&it.getString("profile")==profileId}}
  val found=objects(rows).lastOrNull{it.getString("profile")==profileId}?:return@synchronized false
  val id=event?.getString("sample")?:found.getString("id")
  if(event?.optString("op")=="reassign"){
   val before=event.getJSONObject("before");require(profileExists(context,before.getString("profile"))){"原声纹用户已删除，请直接删除这条补充样本"}
   state.put("samples",JSONArray().apply{objects(rows).forEach{put(if(it.getString("id")==id)before else it)}})
  }else{
   val removed=objects(rows).first{it.getString("id")==id};ignore(state,removed);state.put("samples",keep(rows){it.getString("id")!=id})
  }
  if(event!=null){val before=objects(events).toMutableList();before.removeAt(before.indexOf(event));state.put("history",JSONArray().apply{before.forEach{put(it)}})}
  save(context,state);true
 }
 /** Learning data must be removed first. Keep this lock through original-profile deletion so a
  * concurrent confirmation cannot reinsert templates between the two durable writes. Retrying
  * after either write fails is safe, including when the original file is already absent. */
 internal fun deleteProfile(context:Context,profileId:String,deleteOriginal:()->Unit)=synchronized(lock){
  val state=load(context)
  val removed=objects(state.getJSONArray("samples")).filter{it.getString("profile")==profileId}.map{it.getString("id")}.toSet()
  state.put("samples",keep(state.getJSONArray("samples")){it.getString("profile")!=profileId})
  state.put("history",keep(state.getJSONArray("history")){
   it.getString("profile")!=profileId && it.getString("sample") !in removed && it.optJSONObject("before")?.optString("profile")!=profileId
  })
  val candidates=state.getJSONArray("candidates");for(item in objects(candidates))if(item.optString("suggested")==profileId)item.put("suggested","")
   for(key in listOf("candidates","samples"))for(item in objects(state.getJSONArray(key))){
    val quality=item.optJSONObject("quality")?:continue
    for(field in listOf("continuity","initial_anchor_support","automatic_initial_support"))VoiceIdentityRedaction.diagnostic(quality.opt(field),profileId)
   }
  for(event in objects(state.getJSONArray("history"))){
   val quality=event.optJSONObject("before")?.optJSONObject("quality")?:continue
   for(field in listOf("continuity","initial_anchor_support","automatic_initial_support"))VoiceIdentityRedaction.diagnostic(quality.opt(field),profileId)
  }
  save(context,state)
  deleteOriginal()
 }
}
