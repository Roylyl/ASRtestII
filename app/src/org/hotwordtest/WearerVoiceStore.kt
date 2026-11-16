package org.hotwordtest

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/** Locally stored speaker profiles. Raw enrollment audio is never persisted. */
object WearerVoiceStore {
 private const val RATE=16000
 private const val MAX_SAMPLES=RATE*30
 private const val MIN_SPEECH_SAMPLES=RATE*3
 private const val DEFAULT_NAME="佩戴者"
 data class ProfileInfo(val id:String,val model:String,val name:String,val models:Set<String> = setOf(model))
 internal data class Profile(val id:String,val embeddings:Map<String,FloatArray>,val displayName:String) {
  val model:String get()=embeddings.keys.first()
 }
 private fun file(context:Context,model:String)=File(context.filesDir,"wearer-voice-$model.json")
 private fun directory(context:Context)=File(context.filesDir,"voice-profiles")
 private val validId=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
 private fun profileFile(context:Context,id:String):File {
  if(id=="legacy-${SpeakerModels.CAM}")return file(context,SpeakerModels.CAM)
  if(id=="legacy-${SpeakerModels.ERES}")return file(context,SpeakerModels.ERES)
  require(validId.matches(id)){"声纹编号无效"}
  return File(directory(context),"$id.json")
 }

 private fun validName(name:String):String {
  val clean=name.trim()
  require(clean.length in 1..32){"名称请填写1～32个字符"}
  require(clean.none{Character.isISOControl(it)||it=='\u2028'||it=='\u2029'}){"名称不能包含换行或控制字符"}
  return clean
 }
 private fun writeFile(path:File,json:JSONObject){
  require(path.parentFile?.let{it.isDirectory || it.mkdirs()}==true){"无法创建声纹目录"}
  val atomic=AtomicFile(path);val out=atomic.startWrite()
  try{out.write(json.toString().toByteArray(Charsets.UTF_8));out.flush();out.fd.sync();atomic.finishWrite(out)}
  catch(t:Throwable){atomic.failWrite(out);throw t}
 }

 private fun readFile(path:File,id:String):Profile? {
  val atomic=AtomicFile(path)
  if(!path.exists()&&!File(path.path+".bak").exists())return null
  val json=JSONObject(atomic.openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
  val version=json.getInt("version")
  require(version==1||version==2){"声纹版本不支持，请重新登记"}
  val data=if(version==1) JSONObject().put(json.getString("model"),json.getJSONArray("embedding"))
           else json.getJSONObject("embeddings")
  val keys=data.keys().asSequence().toList()
  require(keys.isNotEmpty() && keys.all{it==SpeakerModels.CAM||it==SpeakerModels.ERES}){"声纹模型无效，请重新登记"}
  val embeddings=linkedMapOf<String,FloatArray>()
  for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES))if(data.has(model)) {
   val values=data.getJSONArray(model);require(values.length() in 1..4096){"声纹数据无效，请重新登记"}
   val raw=FloatArray(values.length()){values.getDouble(it).toFloat()}
   require(raw.all{it.isFinite()}){"声纹数据无效，请重新登记"}
   val norm=sqrt(raw.sumOf{it.toDouble()*it}.toFloat());require(norm>1e-8f){"声纹数据无效，请重新登记"}
   embeddings[model]=FloatArray(raw.size){raw[it]/norm}
  }
  // Existing version-1 profiles predate display names and keep their embedding.
  val name=try{validName(json.optString("display_name",DEFAULT_NAME))}catch(_:Exception){DEFAULT_NAME}
  return Profile(id,embeddings,name)
 }

 private fun read(context:Context,modelKey:String):Profile? {
  val model=SpeakerModels.normalize(modelKey)
  return readFile(file(context,model),"legacy-$model")
 }

 private fun allProfiles(context:Context):List<Profile>{
  val out=mutableListOf<Profile>()
  for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES)){
   try{read(context,model)?.let{out.add(it)}}catch(e:Exception){
    throw IllegalStateException("旧声纹读取失败，数据已保留：$model",e)
   }
  }
  val profileDir=directory(context)
  val files=if(!profileDir.exists()) emptyArray() else
   requireNotNull(profileDir.listFiles()){ "声纹目录无法读取，原数据未删除" }
  val ids=files.filter{it.isFile}.map{it.name.removeSuffix(".bak").removeSuffix(".json")}
   .filter{validId.matches(it)}.distinct().sorted()
  for(id in ids){
   val path=profileFile(context,id)
   try{readFile(path,id)?.let{out.add(it)}}catch(e:Exception){
    throw IllegalStateException("声纹读取失败，数据已保留：$id",e)
   }
  }
  return out
 }

 fun listProfiles(context:Context):List<ProfileInfo> {
  val profiles=synchronized(this){allProfiles(context)}
  val learnedModels=VoiceLearningStore.modelSupport(context)
  return profiles.map{
   ProfileInfo(it.id,it.model,it.displayName,it.embeddings.keys.toSet()+learnedModels[it.id].orEmpty())
  }
 }
 @Synchronized internal fun hasProfile(context:Context,id:String)=readFile(profileFile(context,id),id)!=null
 @Synchronized fun hasInitialTemplate(context:Context,id:String,modelKey:String):Boolean =
  readFile(profileFile(context,id),id)?.embeddings?.containsKey(SpeakerModels.normalize(modelKey))==true

 @Synchronized fun renameById(context:Context,id:String,name:String):ProfileInfo {
  val clean=validName(name);val path=profileFile(context,id)
  val existing=readFile(path,id)?:throw IllegalArgumentException("声纹不存在")
  val json=JSONObject(AtomicFile(path).openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
  json.put("display_name",clean);writeFile(path,json)
  return ProfileInfo(id,existing.model,clean,existing.embeddings.keys.toSet())
 }

 fun deleteById(context:Context,id:String){
  // Validate the ID before a durable deletion marker is written.
  val path=profileFile(context,id)
  VoiceLearningEvaluation.deleteProfileAndCases(context,id){
   VoiceLearningStore.deleteProfile(context,id){
    synchronized(this){
      // Other profiles may retain historical matching diagnostics that mention this ID.
      // Keep their names and embeddings intact; only scrub structured diagnostics. The
      // enclosing durable deletion marker makes every atomic write safe to retry.
      for(other in allProfiles(context))if(other.id!=id){
       val otherPath=profileFile(context,other.id)
       val json=JSONObject(AtomicFile(otherPath).openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
       var changed=false
       for(field in listOf("continuity","supplement_continuity","supplement_identity_verification"))
        if(VoiceIdentityRedaction.diagnostic(json.opt(field),id))changed=true
       if(changed)writeFile(otherPath,json)
      }
     AtomicFile(path).delete()
     require(!path.exists() && !File(path.path+".bak").exists()){ "声纹删除失败，请重试" }
    }
   }
  }
 }

 fun profileName(context:Context,modelKey:String):String? {
  return try{val model=SpeakerModels.normalize(modelKey);listProfiles(context).firstOrNull{model in it.models}?.name}
   catch(_:Exception){null}
 }

 /** Rename without extracting or changing the stored embedding. */
 @Synchronized fun rename(context:Context,modelKey:String,name:String):String {
  val model=SpeakerModels.normalize(modelKey);val clean=validName(name)
  require(read(context,model)?.embeddings?.containsKey(model)==true){"当前模型尚未登记声纹"}
  val atomic=AtomicFile(file(context,model))
  val json=JSONObject(atomic.openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
  json.put("display_name",clean)
  writeFile(file(context,model),json)
  return clean
 }

 fun status(context:Context,modelKey:String):String {
  return try {
   val model=SpeakerModels.normalize(modelKey)
   val profiles=listProfiles(context)
   if(profiles.any{model in it.models}) "已登记"
   else if(profiles.isNotEmpty()) "已登记其他模型，请补录" else "未登记"
  } catch(_:Exception){"声纹数据不可用，请重新登记"}
 }

 /** Runs on a worker thread. VAD is a speech filter, not a single-speaker guarantee. */
 private data class EnrollmentSpeech(val wave:FloatArray,val continuity:JSONObject)
 private fun extractSpeech(context:Context,samples:FloatArray):EnrollmentSpeech {
  require(samples.size in MIN_SPEECH_SAMPLES..MAX_SAMPLES){"请录制3～30秒有效语音"}
  val evidence=SpeechEvidence.analyze(context,samples,"enrollment:"+UUID.randomUUID(),0L,false)
  val candidates=SpeechEvidence.extractPureCandidates(samples,evidence).sortedByDescending{it.endSample-it.startSample}
  var reason="未找到至少3秒连续、避开重叠的清晰人声，请在安静环境重新录制"
  for(candidate in candidates){
   val assessment=VoiceLearningStore.assess(context,candidate);reason=assessment.reason
   assessment.admission?.let{return EnrollmentSpeech(checkNotNull(it.quality.speech),it.gate.evidence)}
  }
  throw IllegalArgumentException(reason)
 }
 private fun extractVector(context:Context,model:String,speech:FloatArray):JSONArray {
  val tracker=LocalSpeakerTracker(context.assets,model)
  val vector=try{tracker.embedding(speech)}finally{tracker.close()}
   ?:throw IllegalArgumentException("${SpeakerModels.name(model)}无法提取有效声纹，请在安静环境重新朗读")
  val values=JSONArray();vector.forEach{values.put(it.toDouble())}
  return values
 }
 private fun prepareEnrollment(context:Context,modelKey:String,samples:FloatArray,name:String):Pair<String,JSONObject> {
  val cleanName=validName(name)
  val model=SpeakerModels.normalize(modelKey)
  val selected=extractSpeech(context,samples);val speech=selected.wave
  val values=extractVector(context,model,speech)
  val json=JSONObject().put("version",1).put("model",model).put("sample_rate",RATE).put("display_name",cleanName)
   .put("embedding",values).put("speech_seconds",speech.size.toDouble()/RATE).put("continuity",selected.continuity)
  return model to json
 }
 fun enroll(context:Context,modelKey:String,samples:FloatArray,name:String=DEFAULT_NAME):String {
  return VoiceLearningStore.guardedNewProfile(context,{frozen->prepareEnrollment(frozen,modelKey,samples,name)}){prepared->
   val (model,json)=prepared;writeFile(file(context,model),json)
   "声纹已登记，使用${SpeakerModels.name(model)}。识别结果为实验性判断。"
  }
 }

 fun enrollNew(context:Context,modelKey:String,samples:FloatArray,name:String):ProfileInfo {
  return VoiceLearningStore.guardedNewProfile(context,{frozen->prepareEnrollment(frozen,modelKey,samples,name)}){prepared->
   val (model,json)=prepared;val id=UUID.randomUUID().toString();writeFile(profileFile(context,id),json)
   ProfileInfo(id,model,json.getString("display_name"))
  }
 }
 /** Both embeddings are computed before creating a profile; raw samples never leave memory. */
 fun enrollBoth(context:Context,samples:FloatArray,name:String):ProfileInfo {
  return VoiceLearningStore.guardedNewProfile(context,{frozen->
   val cleanName=validName(name);val selected=extractSpeech(frozen,samples);val speech=selected.wave
   val vectors=JSONObject()
   for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES)) {
    try{vectors.put(model,extractVector(frozen,model,speech))}
    catch(e:Exception){if(e is java.util.concurrent.CancellationException)throw e
     throw IllegalStateException("${SpeakerModels.name(model)}声纹提取失败，未保存：${e.message?:"请重试"}",e)}
   }
   JSONObject().put("version",2).put("sample_rate",RATE).put("display_name",cleanName)
    .put("embeddings",vectors).put("speech_seconds",speech.size.toDouble()/RATE).put("continuity",selected.continuity)
  }){json->
   val id=UUID.randomUUID().toString();writeFile(profileFile(context,id),json)
   ProfileInfo(id,SpeakerModels.CAM,json.getString("display_name"),setOf(SpeakerModels.CAM,SpeakerModels.ERES))
  }
 }
 /** Model completion must be supported by the existing initial template: the two
  * model embedding spaces are different and a new model vector cannot verify itself. */
 private fun verifyModelCompletion(context:Context,id:String,model:String,selected:EnrollmentSpeech):JSONObject {
  val models=selected.continuity.optJSONObject("models")?:throw IllegalArgumentException("缺少声纹一致性证据，请重新补录")
  for(key in listOf(SpeakerModels.CAM,SpeakerModels.ERES)){
   val rows=models.optJSONObject(key)?.optJSONArray("windows")?:continue
   for(i in 0 until rows.length()){
    val row=rows.getJSONObject(i)
    require(!(row.optBoolean("independent")&&row.optString("registered_status")=="wearer_candidate"&&
      row.optString("registered_profile_id")!=id)){"补录中出现其他已登记人物的证据，请重新录制"}
   }
  }
  val tracker=LocalSpeakerTracker(context.assets,model)
  val matcher=Matcher(context,model,loadExtractor=false,initialOnly=true)
  val checks=JSONArray();var at=0
  try{
   while(at+24000<=selected.wave.size){
    if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("声纹学习已取消")
    val vector=tracker.embedding(selected.wave.copyOfRange(at,at+24000))
    val match=matcher.identifyEmbedding(vector,24000,true)
    require(match.optString("status")=="wearer_candidate"&&match.optString("profile_id")==id){
     "无法用原登记声纹确认补录为同一人，请在安静环境重新录制"
    }
    checks.put(JSONObject().put("start_sample",at).put("end_sample",at+24000)
     .put("similarity",match.opt("similarity")?:JSONObject.NULL)
     .put("margin",match.opt("candidate_margin")?:JSONObject.NULL))
    at+=24000
   }
  }finally{try{matcher.close()}finally{tracker.close()}}
  require(checks.length()>=2){"补录中可核验的语音不足，请重新录制"}
  return JSONObject().put("method","independent_windows_initial_template_only")
   .put("model",model).put("profile_id",id).put("windows",checks)
   .put("note","试验性声纹相似度，不是身份认证；低置信度时拒绝补录")
 }

 /** Re-recording fills only missing models, preserving stored embeddings and current name. */
 fun supplementBothById(context:Context,id:String,samples:FloatArray):ProfileInfo {
  data class Prepared(val originalModel:String,val missing:List<String>,val computed:JSONObject,
                      val seconds:Double,val continuity:JSONObject,val verification:JSONObject)
  return VoiceLearningStore.guardedProfileUpdate(context,id,{frozen->
   val original=readFile(profileFile(frozen,id),id)?:throw IllegalArgumentException("声纹不存在")
   val missing=listOf(SpeakerModels.CAM,SpeakerModels.ERES).filter{!original.embeddings.containsKey(it)}
   if(missing.isEmpty())Prepared(original.model,missing,JSONObject(),0.0,JSONObject(),JSONObject())
   else{
    val existing=listOf(SpeakerModels.CAM,SpeakerModels.ERES).firstOrNull{original.embeddings.containsKey(it)}
     ?:throw IllegalArgumentException("原声纹没有可用于核验的模型，请重新登记")
    val selected=extractSpeech(frozen,samples);val speech=selected.wave
    require(VoiceContinuityGate.targetCompatible(selected.continuity,id)){"该片段有与所选人物相反的声纹证据，未保存补录"}
    val verification=verifyModelCompletion(frozen,id,existing,selected)
    val computed=JSONObject()
    for(model in missing){
     try{computed.put(model,extractVector(frozen,model,speech))}
     catch(e:Exception){if(e is java.util.concurrent.CancellationException)throw e
      throw IllegalStateException("${SpeakerModels.name(model)}声纹提取失败，原声纹未改变：${e.message?:"请重试"}",e)}
    }
    Prepared(original.model,missing,computed,speech.size.toDouble()/RATE,selected.continuity,verification)
   }
  },{prepared->
   val path=profileFile(context,id)
   val current=readFile(path,id)?:throw IllegalArgumentException("声纹已删除，未保存补录")
   if(prepared.missing.isEmpty())ProfileInfo(id,current.model,current.displayName,current.embeddings.keys.toSet())
   else{
    val json=JSONObject(AtomicFile(path).openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
    val embeddings=if(json.getInt("version")==1)
     JSONObject().put(json.getString("model"),json.getJSONArray("embedding")) else json.getJSONObject("embeddings")
    for(model in prepared.missing)if(!embeddings.has(model))embeddings.put(model,prepared.computed.getJSONArray(model))
    json.put("version",2).put("embeddings",embeddings).remove("embedding");json.remove("model")
    json.put("supplement_speech_seconds",prepared.seconds).put("supplement_continuity",prepared.continuity)
     .put("supplement_identity_verification",prepared.verification)
    writeFile(path,json)
    ProfileInfo(id,current.model,current.displayName,listOf(SpeakerModels.CAM,SpeakerModels.ERES).filter{embeddings.has(it)}.toSet())
   }
  })
 }
 fun delete(context:Context){
  val ids=synchronized(this){allProfiles(context).map{it.id}}
  for(id in ids)deleteById(context,id)
 }

 /** A task uses one consistent profile/template snapshot; subsequent tasks see confirmed changes. */
 class Matcher @JvmOverloads constructor(context:Context,modelKey:String,loadExtractor:Boolean=true,
    private val initialOnly:Boolean=false,
    private val acceptanceThreshold:Double=RegisteredSpeakerPolicy.forModel(modelKey).acceptanceThreshold,
    private val identityMargin:Double=RegisteredSpeakerPolicy.forModel(modelKey).identityMargin):AutoCloseable {
  init { require(acceptanceThreshold.isFinite() && acceptanceThreshold in 0.0..1.0 && identityMargin.isFinite() && identityMargin in 0.0..1.0) }
  private val model=SpeakerModels.normalize(modelKey)
  private val originals=synchronized(WearerVoiceStore){allProfiles(context)}
  private val supplements=if(initialOnly)emptyMap() else VoiceLearningStore.templateSnapshot(context,model)
  private val profiles=originals.filter{it.embeddings.containsKey(model)||(!initialOnly&&!supplements[it.id].isNullOrEmpty())}
  private val tracker=if(loadExtractor && profiles.isNotEmpty())LocalSpeakerTracker(context.assets,model) else null
  val ready:Boolean get()=profiles.isNotEmpty()
  fun identify(samples:FloatArray,singleSpeaker:Boolean):JSONObject {
   if(profiles.isEmpty() || !singleSpeaker || samples.size<RATE*3/2)
    return identifyEmbedding(null,samples.size,singleSpeaker)
   val vector=try{tracker?.embedding(samples)}catch(_:Exception){return base().put("status","comparison_failed")}
   return identifyEmbedding(vector,samples.size,singleSpeaker)
  }
  private fun base():JSONObject = JSONObject().put("model",SpeakerModels.name(model))
   .put("threshold_status","experimental_uncalibrated").put("initial_only",initialOnly)
   .put("note","仅供测试，不是身份认证；阈值未经目标设备和场景校准")
  /** Every person has a stable profile ID. Equal display names never collapse identities. */
  fun identifyEmbedding(vector:FloatArray?,sampleCount:Int,singleSpeaker:Boolean):JSONObject {
   val out=base()
   if(profiles.isEmpty())return out.put("status","not_enrolled")
   if(!singleSpeaker)return out.put("status","ambiguous_multiple_or_unknown_speakers")
   if(sampleCount<RATE*3/2 || vector==null)return out.put("status","insufficient_audio")
   if(vector.isEmpty()||vector.any{!it.isFinite()})return out.put("status","invalid_embedding")
   val norm=sqrt(vector.sumOf{it.toDouble()*it})
   if(norm<=1e-8)return out.put("status","invalid_embedding")
   val query=FloatArray(vector.size){(vector[it]/norm).toFloat()}
   data class Match(val profile:Profile,val score:VoiceTemplateScoring.Score,val hasInitial:Boolean)
   val scores=profiles.mapNotNull{p->
    val learned=supplements[p.id].orEmpty()
    val original=p.embeddings[model]
    val anchor=original?:learned.firstOrNull()?:return@mapNotNull null
    VoiceTemplateScoring.score(query,anchor,learned).let{score->
     if(score==null)null else Match(p,score,original!=null)
    }
   }.sortedByDescending{it.score.similarity}
   if(scores.isEmpty())return out.put("status","dimension_mismatch")
   val best=scores[0];val similarity=best.score.similarity
   val runnerUp=scores.getOrNull(1)?.score?.similarity
   val margin=runnerUp?.let{similarity-it}
   val status=when {
    similarity>=acceptanceThreshold && (margin==null || margin>=identityMargin)->"wearer_candidate"
    similarity<=0.35->"other_candidate"
    else->"uncertain"
   }
   if(status=="wearer_candidate")out.put("display_name",best.profile.displayName).put("profile_id",best.profile.id)
   if(status=="uncertain")out.put("nearest_candidate_name",best.profile.displayName)
    .put("nearest_candidate_profile_id",best.profile.id)
   return out.put("similarity",similarity)
    .put("base_similarity",if(best.hasInitial)best.score.initial else JSONObject.NULL)
    .put("supplement_count",best.score.supplementCount)
    .put("best_profile_id",best.profile.id)
    .put("runner_up_profile_id",scores.getOrNull(1)?.profile?.id?:JSONObject.NULL)
    .put("runner_up_similarity",runnerUp?:JSONObject.NULL)
    .put("candidate_margin",margin?:JSONObject.NULL).put("ambiguity_margin_threshold",identityMargin)
    .put("wearer_candidate_threshold",acceptanceThreshold).put("other_candidate_threshold",0.35).put("status",status)
  }
  override fun close(){tracker?.close()}
 }
}
