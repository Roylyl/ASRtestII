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

 @Synchronized fun listProfiles(context:Context):List<ProfileInfo> =
  allProfiles(context).map{ProfileInfo(it.id,it.model,it.displayName,it.embeddings.keys.toSet())}

 @Synchronized fun renameById(context:Context,id:String,name:String):ProfileInfo {
  val clean=validName(name);val path=profileFile(context,id)
  val existing=readFile(path,id)?:throw IllegalArgumentException("声纹不存在")
  val json=JSONObject(AtomicFile(path).openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
  json.put("display_name",clean);writeFile(path,json)
  return ProfileInfo(id,existing.model,clean,existing.embeddings.keys.toSet())
 }

 @Synchronized fun deleteById(context:Context,id:String){
  val path=profileFile(context,id)
  require(path.exists() || File(path.path+".bak").exists()){ "声纹不存在" }
  AtomicFile(path).delete()
  require(!path.exists() && !File(path.path+".bak").exists()){ "声纹删除失败" }
 }

 fun profileName(context:Context,modelKey:String):String? {
  return try{val model=SpeakerModels.normalize(modelKey);synchronized(this){allProfiles(context).firstOrNull{it.embeddings.containsKey(model)}?.displayName}}
   catch(_:Exception){null}
 }

 /** Rename without extracting or changing the stored embedding. */
 fun rename(context:Context,modelKey:String,name:String):String {
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
   val profiles=synchronized(this){allProfiles(context)}
   if(profiles.any{it.embeddings.containsKey(model)}) "已登记"
   else if(profiles.isNotEmpty()) "已登记其他模型，请补录" else "未登记"
  } catch(_:Exception){"声纹数据不可用，请重新登记"}
 }

 /** Runs on a worker thread. VAD is a speech filter, not a single-speaker guarantee. */
 private fun extractSpeech(context:Context,samples:FloatArray):FloatArray {
  require(samples.size<=MAX_SAMPLES){"录音不能超过30秒"}
  require(samples.size>=MIN_SPEECH_SAMPLES){"录音太短，请重新录制至少3秒有效语音"}
  require(samples.all{it.isFinite()}){"录音数据无效，请重新录制"}
  val vad=VadModelFactory.create(context.assets,VadModelFactory.SILERO)
  val pieces=mutableListOf<FloatArray>();var speechCount=0
  fun drain(){while(!vad.empty()){
   val segment=vad.front();val keep=segment.samples.copyOf();vad.pop()
   if(keep.isNotEmpty()){pieces.add(keep);speechCount+=keep.size}
  }}
  try{
   val window=VadModelFactory.windowSize(VadModelFactory.SILERO);var pos=0;var speechStart=-1
   while(pos<samples.size){val n=minOf(window,samples.size-pos);val frame=FloatArray(window)
    samples.copyInto(frame,0,pos,pos+n);vad.acceptWaveform(frame);pos+=n;drain()
    if(vad.isSpeechDetected()){
     if(speechStart<0)speechStart=pos-n
     if(pos-speechStart>=20*RATE){vad.flush();drain();speechStart=pos}
    }else speechStart=-1
   }
   vad.flush();drain()
  } finally {vad.release()}
  require(speechCount>=MIN_SPEECH_SAMPLES){"检测到的语音不足3秒，请在安静环境重新朗读"}
  val speech=FloatArray(speechCount);var offset=0
  for(piece in pieces){piece.copyInto(speech,offset);offset+=piece.size}
  return speech
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
  val speech=extractSpeech(context,samples)
  val values=extractVector(context,model,speech)
  val json=JSONObject().put("version",1).put("model",model).put("sample_rate",RATE).put("display_name",cleanName)
   .put("embedding",values).put("speech_seconds",speech.size.toDouble()/RATE)
  return model to json
 }
 fun enroll(context:Context,modelKey:String,samples:FloatArray,name:String=DEFAULT_NAME):String {
  val (model,json)=prepareEnrollment(context,modelKey,samples,name)
  synchronized(this){writeFile(file(context,model),json)}
  return "声纹已登记，使用${SpeakerModels.name(model)}。识别结果为实验性判断。"
 }

 fun enrollNew(context:Context,modelKey:String,samples:FloatArray,name:String):ProfileInfo {
  val (model,json)=prepareEnrollment(context,modelKey,samples,name)
  val id=UUID.randomUUID().toString()
  synchronized(this){writeFile(profileFile(context,id),json)}
  return ProfileInfo(id,model,json.getString("display_name"))
 }
 /** Both embeddings are computed before creating a profile; raw samples never leave memory. */
 fun enrollBoth(context:Context,samples:FloatArray,name:String):ProfileInfo {
  val cleanName=validName(name);val speech=extractSpeech(context,samples)
  val vectors=JSONObject()
  for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES)) {
   try{vectors.put(model,extractVector(context,model,speech))}
   catch(e:Exception){throw IllegalStateException("${SpeakerModels.name(model)}声纹提取失败，未保存：${e.message?:"请重试"}",e)}
  }
  val id=UUID.randomUUID().toString()
  val json=JSONObject().put("version",2).put("sample_rate",RATE).put("display_name",cleanName)
   .put("embeddings",vectors).put("speech_seconds",speech.size.toDouble()/RATE)
  synchronized(this){writeFile(profileFile(context,id),json)}
  return ProfileInfo(id,SpeakerModels.CAM,cleanName,setOf(SpeakerModels.CAM,SpeakerModels.ERES))
 }
 /** Re-recording fills only missing models, preserving stored embeddings and current name. */
 fun supplementBothById(context:Context,id:String,samples:FloatArray):ProfileInfo {
  val path=profileFile(context,id)
  val original=synchronized(this){readFile(path,id)?:throw IllegalArgumentException("声纹不存在")}
  val missing=listOf(SpeakerModels.CAM,SpeakerModels.ERES).filter{!original.embeddings.containsKey(it)}
  if(missing.isEmpty())return ProfileInfo(id,original.model,original.displayName,original.embeddings.keys.toSet())
  val speech=extractSpeech(context,samples)
  val computed=JSONObject()
  for(model in missing) {
   try{computed.put(model,extractVector(context,model,speech))}
   catch(e:Exception){throw IllegalStateException("${SpeakerModels.name(model)}声纹提取失败，原声纹未改变：${e.message?:"请重试"}",e)}
  }
  return synchronized(this){
   val current=readFile(path,id)?:throw IllegalArgumentException("声纹已删除，未保存补录")
   val json=JSONObject(AtomicFile(path).openRead().bufferedReader(Charsets.UTF_8).use{it.readText()})
   val embeddings=if(json.getInt("version")==1)
    JSONObject().put(json.getString("model"),json.getJSONArray("embedding")) else json.getJSONObject("embeddings")
   for(model in missing)if(!embeddings.has(model))embeddings.put(model,computed.getJSONArray(model))
   json.put("version",2).put("embeddings",embeddings).remove("embedding");json.remove("model")
   json.put("supplement_speech_seconds",speech.size.toDouble()/RATE)
   writeFile(path,json)
   ProfileInfo(id,current.model,current.displayName,listOf(SpeakerModels.CAM,SpeakerModels.ERES).filter{embeddings.has(it)}.toSet())
  }
 }
 @Synchronized fun delete(context:Context){
  for(profile in allProfiles(context))AtomicFile(profileFile(context,profile.id)).delete()
 }

 /** Matcher loads the profile once and only compares embeddings from the same model. */
 class Matcher(context:Context,modelKey:String,loadExtractor:Boolean=true):AutoCloseable {
  private val model=SpeakerModels.normalize(modelKey)
  private val profiles=synchronized(WearerVoiceStore){allProfiles(context).filter{it.embeddings.containsKey(model)}}
  private val tracker=if(loadExtractor && profiles.isNotEmpty())LocalSpeakerTracker(context.assets,model) else null
  val ready:Boolean get()=profiles.isNotEmpty()
  fun identify(samples:FloatArray,singleSpeaker:Boolean):JSONObject {
   if(profiles.isEmpty() || !singleSpeaker || samples.size<RATE*3/2)
    return identifyEmbedding(null,samples.size,singleSpeaker)
   val vector=try{tracker?.embedding(samples)}catch(_:Exception){return base().put("status","comparison_failed")}
   return identifyEmbedding(vector,samples.size,singleSpeaker)
  }
  private fun base():JSONObject = JSONObject().put("model",SpeakerModels.name(model))
   .put("threshold_status","experimental_uncalibrated")
   .put("note","仅供测试，不是身份认证；阈值未经目标设备和场景校准")
  /** Compare an already extracted normalized vector; it must use this matcher's model. */
  fun identifyEmbedding(vector:FloatArray?,sampleCount:Int,singleSpeaker:Boolean):JSONObject {
   val out=base()
   if(profiles.isEmpty())return out.put("status","not_enrolled")
   if(!singleSpeaker)return out.put("status","ambiguous_multiple_or_unknown_speakers")
   if(sampleCount<RATE*3/2 || vector==null)return out.put("status","insufficient_audio")
   // Entries with the same display name are one output label; avoid rejecting a
   // matching person merely because they enrolled more than once under that name.
   val scores=profiles.mapNotNull{p->
     val embedding=p.embeddings[model]?:return@mapNotNull null
     if(vector.size!=embedding.size)return@mapNotNull null
     p to vector.indices.sumOf{(vector[it]*embedding[it]).toDouble()}
    }.groupBy{it.first.displayName}.values.map{matches->matches.maxByOrNull{it.second}!!}
    .sortedByDescending{it.second}
   if(scores.isEmpty())return out.put("status","dimension_mismatch")
   val best=scores[0];val similarity=best.second
   val runnerUp=scores.getOrNull(1)?.second
   val margin=runnerUp?.let{similarity-it}
   val status=when {
    similarity>=0.65 && (margin==null || margin>=0.08)->"wearer_candidate"
    similarity<=0.35->"other_candidate"
    else->"uncertain"
   }
   if(status=="wearer_candidate")out.put("display_name",best.first.displayName).put("profile_id",best.first.id)
   // This is not an identification: consumers may show it only as an uncertain
   // candidate after applying their model-specific score and ambiguity policy.
   if(status=="uncertain")out.put("nearest_candidate_name",best.first.displayName)
    .put("nearest_candidate_profile_id",best.first.id)
   return out.put("similarity",similarity).put("runner_up_similarity",runnerUp?:JSONObject.NULL)
    .put("candidate_margin",margin?:JSONObject.NULL).put("ambiguity_margin_threshold",0.08)
    .put("wearer_candidate_threshold",0.65)
    .put("other_candidate_threshold",0.35)
    .put("status",status)
  }
  override fun close(){tracker?.close()}
 }
}
