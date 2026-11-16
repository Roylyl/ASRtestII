package org.hotwordtest

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Labelled held-out trials. Evaluation never writes to enrolled profiles or the learning queue. */
object VoiceLearningEvaluation {
 const val RATE=16000
 const val MAX_CASES=60
 data class CaseInfo(val id:String,val profileId:String,val self:Boolean,val source:String,
                     val title:String,val startSample:Long,val endSample:Long,val createdMs:Long,
                     val vectors:Map<String,FloatArray>,val speechSeconds:Double,
                     val status:String="accepted",val reason:String="")
 private fun location(context:Context)=File(context.filesDir,"voice-learning-evaluation.json")
 private fun read(context:Context):JSONObject {
  val f=AtomicFile(location(context))
  if(!f.baseFile.exists()&&!File(f.baseFile.path+".bak").exists())return JSONObject().put("version",1).put("cases",JSONArray())
  return JSONObject(f.openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
 }
 private fun write(context:Context,json:JSONObject){
  val atomic=AtomicFile(location(context));val out=atomic.startWrite()
  try{out.write(json.toString().toByteArray(Charsets.UTF_8));out.flush();out.fd.sync();atomic.finishWrite(out)}
  catch(t:Throwable){atomic.failWrite(out);throw t}
 }
 /** Lock order: evaluation -> learning -> profile. Durable tombstones allow startup recovery. */
 private fun pendingDeletes(data:JSONObject):List<String> {
  if(!data.has("pending_profile_deletions"))return emptyList()
  val pending=data.optJSONArray("pending_profile_deletions")?:throw IllegalStateException("待删除声纹记录损坏，原数据已保留")
  return (0 until pending.length()).map{pending.getString(it).also{id->require(id.isNotBlank()){ "待删除声纹记录无效" }}}.distinct()
 }
 private fun validateDeletionData(data:JSONObject){
  val cases=data.getJSONArray("cases")
  for(i in 0 until cases.length())require(cases.getJSONObject(i).getString("profile_id").isNotBlank()){ "验证样本人物编号无效，原数据已保留" }
  if(data.has("last_result")&&!data.isNull("last_result")){
   val result=data.optJSONObject("last_result")?:throw IllegalStateException("验证结果损坏，原数据已保留")
   require(result.optString("profile_id").isNotBlank()){ "验证结果人物编号无效，原数据已保留" }
  }
  pendingDeletes(data)
 }
 private fun noPendingDeletion(data:JSONObject,profileId:String){
  require(profileId !in pendingDeletes(data)){ "该人物的声纹删除尚未完成，请重启应用重试" }
 }
 internal fun deleteProfileAndCases(context:Context,profileId:String,deleteProfile:()->Unit)=synchronized(this){
  val data=read(context);validateDeletionData(data)
  val oldPending=pendingDeletes(data)
  if(profileId !in oldPending){
   data.put("pending_profile_deletions",JSONArray().apply{(oldPending+profileId).forEach{put(it)}})
   write(context,data)
  }
  // If interrupted, keep the durable marker and retry the idempotent deletion at startup.
  deleteProfile()
  val latest=read(context);validateDeletionData(latest)
  val cases=latest.getJSONArray("cases");val kept=JSONArray()
  for(i in 0 until cases.length())cases.getJSONObject(i).let{if(it.getString("profile_id")!=profileId)kept.put(it)}
  latest.put("cases",kept)
  // Every evaluation compares against the profile roster; deleting any profile invalidates it.
  latest.remove("last_result")
  latest.put("pending_profile_deletions",JSONArray().apply{pendingDeletes(latest).filter{it!=profileId}.forEach{put(it)}})
  write(context,latest)
 }
 fun recoverPendingProfileDeletes(context:Context){
  val ids=synchronized(this){val data=read(context);validateDeletionData(data);pendingDeletes(data)}
  for(id in ids)WearerVoiceStore.deleteById(context,id)
 } private fun parse(row:JSONObject):CaseInfo {
  val data=row.getJSONObject("vectors");val vectors=linkedMapOf<String,FloatArray>()
  for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES)){
   val values=data.optJSONArray(model)?:continue
   require(values.length() in 1..4096){"验证声纹数据无效"}
   val vector=FloatArray(values.length()){values.getDouble(it).toFloat()}
   require(vector.all{it.isFinite()}){"验证声纹数据无效"}
   vectors[model]=vector
  }
  return CaseInfo(row.getString("id"),row.getString("profile_id"),row.getBoolean("self"),
   row.getString("source"),row.getString("title"),row.getLong("start_sample"),row.getLong("end_sample"),
   row.getLong("created_ms"),vectors,row.getDouble("speech_seconds"),row.optString("status","accepted"),row.optString("reason"))
 }
 @Synchronized fun list(context:Context,profileId:String):List<CaseInfo> {
  val rows=read(context).getJSONArray("cases")
  return (0 until rows.length()).map{parse(rows.getJSONObject(it))}.filter{it.profileId==profileId}.sortedBy{it.createdMs}
 }
 @Synchronized fun delete(context:Context,id:String){
  val data=read(context);val rows=data.getJSONArray("cases");val next=JSONArray()
  for(i in 0 until rows.length())rows.getJSONObject(i).let{if(it.getString("id")!=id)next.put(it)}
  data.put("cases",next).remove("last_result");write(context,data)
 }
 private fun origins(context:Context):List<VoiceLearningStore.Origin> =
  WearerVoiceStore.listProfiles(context).flatMap{VoiceLearningStore.activeOrigins(context,it.id)}+
   VoiceLearningStore.listCandidates(context).map{VoiceLearningStore.Origin(it.source,it.startSample,it.endSample)}
 private fun overlaps(origins:List<VoiceLearningStore.Origin>,source:String,start:Long,end:Long)=
  origins.any{it.source==source && it.startSample<end && it.endSample>start}
 private fun templateState(context:Context):List<String> = WearerVoiceStore.listProfiles(context).flatMap{profile->
  listOf(profile.id+":"+profile.models.sorted().joinToString(","))+
   VoiceLearningStore.listSamples(context,profile.id).map{profile.id+":"+it.id}
 }.sorted()
 /** Both models use the same held-out audio. Only derived features are retained. */
 fun add(context:Context,profileId:String,self:Boolean,source:String,title:String,
         startSample:Long,samples:FloatArray):CaseInfo {
  require(WearerVoiceStore.listProfiles(context).any{it.id==profileId}){"目标声纹已删除，请重新选择"}
  require(samples.size in RATE*3..RATE*30){"验证段须为3～30秒，请选择单人独立发言"}
  require(startSample>=0 && source.isNotBlank()){"验证来源无效"}
  val end=startSample+samples.size
  require(!overlaps(origins(context),source,startSample,end)){
   "这段音频已进入学习候选或补充声纹，请换一段独立验证录音"
  }
  val started=android.os.SystemClock.elapsedRealtime()
  val evidence=SpeechEvidence.analyze(context,samples,source,startSample,false)
  var selected:SpeechEvidence.PureCandidate?=null
  var quality:VoiceLearningStore.Quality?=null
  var reason="未找到至少3秒且避开重叠和边界的连续独占语音"
  for(candidate in SpeechEvidence.extractPureCandidates(samples,evidence).sortedByDescending{it.endSample-it.startSample}){
   val checked=VoiceLearningStore.quality(context,candidate);reason=checked.reason
   if(checked.accepted){selected=candidate;quality=checked;break}
  }
  val speech=quality?.speech
  val vectors=JSONObject()
  if(speech!=null)for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES)){
   val vector=LocalSpeakerTracker(context.assets,model).let{tracker->
    try{tracker.embedding(speech)}finally{tracker.close()}
   }?:throw IllegalStateException(SpeakerModels.name(model)+"未提取出验证声纹")
   val array=JSONArray();vector.forEach{array.put(it.toDouble())};vectors.put(model,array)
  }
  val row=JSONObject().put("id",UUID.randomUUID().toString()).put("profile_id",profileId)
   .put("self",self).put("source",source).put("title",title.take(160))
   .put("start_sample",selected?.startSample?:startSample).put("end_sample",selected?.endSample?:end)
   .put("requested_start_sample",startSample).put("requested_end_sample",end).put("created_ms",System.currentTimeMillis())
   .put("vectors",vectors).put("speech_seconds",(quality?.speechSamples?:0).toDouble()/RATE)
   .put("status",if(speech==null)"quality_rejected" else "accepted").put("reason",reason)
   .put("activity_evidence",evidence.toJson()).put("quality_ms",android.os.SystemClock.elapsedRealtime()-started)
  return synchronized(this){
   val data=read(context);noPendingDeletion(data,profileId)
   require(WearerVoiceStore.listProfiles(context).any{it.id==profileId}){"目标声纹已删除，验证样本未保存"}
   require(!overlaps(origins(context),source,startSample,end)){
    "保存前发现该音频已进入学习候选或补充声纹，请换一段独立录音"
   }
   val rows=data.getJSONArray("cases")
   require(rows.length()<MAX_CASES){"验证集已满，请先删除旧验证样本"}
   for(i in 0 until rows.length()){
    val old=parse(rows.getJSONObject(i))
    require(old.profileId!=profileId || old.source!=source || old.startSample>=end || old.endSample<=startSample){
     "验证集已包含该来源的重叠片段，请勿重复加入"
    }
   }
   rows.put(row);data.remove("last_result");write(context,data);parse(row)
  }
 }
 /** Two immutable matcher snapshots score each trial at the same thresholds. */
 fun run(context:Context,profileId:String,modelKey:String,cancelled:()->Boolean={false},
         progress:(Int,Int)->Unit={_,_->}):JSONObject {
  val model=SpeakerModels.normalize(modelKey)
  val profile=WearerVoiceStore.listProfiles(context).firstOrNull{it.id==profileId}
   ?:throw IllegalArgumentException("目标声纹已删除")
  require(model in profile.models){"目标人物尚未登记"+SpeakerModels.name(model)}
  require(WearerVoiceStore.hasInitialTemplate(context,profileId,model)){
   "该人物在当前模型下没有初始登记模板，无法作前后对照；请切换到有初始登记的模型"
  }
  val cases=list(context,profileId)
  require(cases.isNotEmpty()){"请先添加本人和他人的独立验证样本"}
  val trials=JSONArray();var own=0;var others=0;var beforeMiss=0;var afterMiss=0;var beforeFalse=0;var afterFalse=0;var excluded=0
  var rejectedOwn=0;var rejectedOther=0
  val snapshot=templateState(context)
  val trained=origins(context)
  val started=android.os.SystemClock.elapsedRealtime()
  WearerVoiceStore.Matcher(context,model,loadExtractor=false,initialOnly=true).use{initial->
   WearerVoiceStore.Matcher(context,model,loadExtractor=false,initialOnly=false).use{learned->
    require(initial.ready && learned.ready){"该模型的登记声纹不可用"}
    require(templateState(context)==snapshot){"声纹库在读取时发生变化，请重新对照"}
    for((index,item) in cases.withIndex()){
     if(cancelled())throw InterruptedException("对照已停止")
     progress(index+1,cases.size)
     val row=JSONObject().put("id",item.id).put("title",item.title)
      .put("expected",if(item.self)"self" else "other").put("target_profile_id",profileId)
     val vector=item.vectors[model]
     if(item.status=="quality_rejected"){
      if(item.self)rejectedOwn++ else rejectedOther++
      row.put("status","quality_rejected").put("reason",item.reason)
     }else if(vector==null || overlaps(trained,item.source,item.startSample,item.endSample)){
      excluded++;row.put("status","excluded").put("reason",if(vector==null)"该模型缺少验证特征" else "该来源区间已进入学习候选或补充声纹")
     }else{
      val count=(item.speechSeconds*RATE).toInt()
      val base=initial.identifyEmbedding(vector,count,true)
      val full=learned.identifyEmbedding(vector,count,true)
      val before=base.optString("status")=="wearer_candidate" && base.optString("profile_id")==profileId
      val after=full.optString("status")=="wearer_candidate" && full.optString("profile_id")==profileId
      row.put("status","scored").put("initial",base).put("learned",full)
       .put("initial_matches_target",before).put("learned_matches_target",after)
      if(item.self){own++;if(!before)beforeMiss++;if(!after)afterMiss++}
      else{others++;if(before)beforeFalse++;if(after)afterFalse++}
     }
     trials.put(row)
    }
   }
  }
  require(templateState(context)==snapshot && origins(context).toSet()==trained.toSet()){
   "声纹库或学习候选在对照期间发生变化，结果未保存，请重新对照"
  }
  val result=JSONObject().put("status","completed").put("profile_id",profileId).put("profile_name",profile.name)
   .put("model",model).put("model_name",SpeakerModels.name(model)).put("created_ms",System.currentTimeMillis())
   .put("supplement_count",VoiceLearningStore.listSamples(context,profileId).size)
   .put("own_trials",own).put("other_trials",others).put("excluded",excluded)
   .put("reference_own_trials",cases.count{it.self}).put("reference_other_trials",cases.count{!it.self})
   .put("quality_rejected_own",rejectedOwn).put("quality_rejected_other",rejectedOther)
   .put("quality_accepted_trials",own+others)
   .put("initial_misses",beforeMiss).put("learned_misses",afterMiss)
   .put("initial_false_accepts",beforeFalse).put("learned_false_accepts",afterFalse)
   .put("process_ms",android.os.SystemClock.elapsedRealtime()-started).put("trials",trials)
   .put("note","身份分类指标仅以通过质量门控的独立样本为分母，不是端到端准确率；质量拒绝单列，不能视为识别正确。本人拒绝含未确定和匹配到其他人物；两种配置使用相同向量和阈值。来源区间进入学习则排除。")
  synchronized(this){
   val data=read(context);noPendingDeletion(data,profileId)
   require(WearerVoiceStore.listProfiles(context).any{it.id==profileId}){"目标声纹已删除，验证结果未保存"}
   require(templateState(context)==snapshot && origins(context).toSet()==trained.toSet()){
    "声纹库或学习候选在对照期间发生变化，结果未保存，请重新对照"
   }
   val current=data.getJSONArray("cases");val currentIds=(0 until current.length()).map{current.getJSONObject(it)}
    .filter{it.getString("profile_id")==profileId}.map{it.getString("id")}.toSet()
   require(currentIds==cases.map{it.id}.toSet()){"验证样本在对照期间发生变化，结果未保存，请重新对照"}
   data.put("last_result",result);write(context,data)
  }
  return result
 }
 fun report(result:JSONObject):String=buildString{
  append(result.getString("profile_name")).append(" · ").append(result.getString("model_name")).append('\n')
  append("当前补充样本").append(result.optInt("supplement_count")).append("条\n")
  append("参考验证：本人").append(result.optInt("reference_own_trials",result.optInt("own_trials")))
   .append("条，他人").append(result.optInt("reference_other_trials",result.optInt("other_trials"))).append("条\n")
  append("质量拒绝：本人").append(result.optInt("quality_rejected_own")).append("条，他人")
   .append(result.optInt("quality_rejected_other")).append("条；以下比例仅针对通过门控的独立样本。\n")
  fun rate(n:Int,total:Int)=if(total==0)"未添加验证样本" else "$n/$total（"+String.format(java.util.Locale.ROOT,"%.1f",n*100.0/total)+"%）"
  val own=result.getInt("own_trials");val other=result.getInt("other_trials")
  append("\n本人漏认\n初始模板：").append(rate(result.getInt("initial_misses"),own))
  append("\n补充后：").append(rate(result.getInt("learned_misses"),own))
  append("\n\n他人误认\n初始模板：").append(rate(result.getInt("initial_false_accepts"),other))
  append("\n补充后：").append(rate(result.getInt("learned_false_accepts"),other))
  append("\n\n排除").append(result.getInt("excluded")).append("条已参与学习或缺少特征的样本")
  append("\n耗时").append(result.getLong("process_ms")).append("毫秒\n")
  if(own==0 || other==0)append("\n请同时添加本人和他人样本，才能比较两类错误。\n")
  append("\n逐条结果\n")
  val trials=result.getJSONArray("trials")
  for(i in 0 until trials.length()){
   val row=trials.getJSONObject(i);append('\n').append(i+1).append(". ").append(row.getString("title"))
   append(" · 标注").append(if(row.getString("expected")=="self")"本人" else "他人")
   if(row.getString("status")=="excluded"){append("\n已排除：").append(row.getString("reason"));continue}
   if(row.getString("status")=="quality_rejected"){append("\n质量拒绝：").append(row.getString("reason"));continue}
   fun verdict(key:String)=if(row.getBoolean(key))"识别为本人" else "未识别为本人"
   append("\n初始：").append(verdict("initial_matches_target"))
   append(" / 补充后：").append(verdict("learned_matches_target"))
   append("\n相似度：").append(String.format(java.util.Locale.ROOT,"%.3f → %.3f",row.getJSONObject("initial").optDouble("similarity"),row.getJSONObject("learned").optDouble("similarity")))
  }
 }
}
