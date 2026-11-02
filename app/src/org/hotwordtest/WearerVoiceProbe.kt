package org.hotwordtest

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Internal on-device regression. Profile data lives only in a newly created isolated cache directory. */
object WearerVoiceProbe {
 fun run(activity:Activity,modelDir:()->File) {
  Thread {
   val probeRoot=File(activity.getExternalFilesDir(null),"wearer-voice-probe")
   val resultFile=File(probeRoot,"result.json")
   val output=JSONObject().put("test_only",true).put("model","CAM++")
   var context:Context?=null
   var ticket:File?=null
   try {
    require(probeRoot.isDirectory || probeRoot.mkdirs()){ "无法创建测试结果目录" }
    val dir=File.createTempFile("wearer-multi-probe-",".tmp",activity.cacheDir)
    require(dir.delete() && dir.mkdir()){ "无法创建独立临时目录" }
    ticket=dir
    val ctx=object:ContextWrapper(activity.applicationContext){override fun getFilesDir():File=dir}
    context=ctx
    fun wav(name:String):FloatArray=File(probeRoot,name).inputStream().use{WavReader.read16k(it)}
    val first=wav("F01A_en_218.wav")
    val firstAgain=wav("F01A_en_341.wav")
    val second=wav("F02A_en_321.wav")
    val secondAgain=wav("F02A_en_350.wav")
    val key=SpeakerModels.CAM
    output.put("inputs","MELI的F01A与F02A片段；说话人标签来自语料，未逐段人工听音核实")
    require(WearerVoiceStore.listProfiles(ctx).isEmpty()){"测试目录不是空的"}

    // Create a version-1 pre-name profile just as the earlier released version did.
    WearerVoiceStore.enroll(ctx,key,first,"旧声纹")
    val legacyPath=File(dir,"wearer-voice-$key.json")
    val oldJson=JSONObject(legacyPath.readText(Charsets.UTF_8))
    oldJson.remove("display_name")
    legacyPath.writeText(oldJson.toString(),Charsets.UTF_8)
    val legacy=WearerVoiceStore.listProfiles(ctx).single()
    require(legacy.model==key && legacy.name=="佩戴者" && legacyPath.isFile){"旧版声纹未被保留或名称不兼容"}
    output.put("legacy_listed",true).put("legacy_default_name",legacy.name)
    WearerVoiceStore.renameById(ctx,legacy.id,"甲")
    val a=WearerVoiceStore.listProfiles(ctx).single()
    require(a.id==legacy.id && a.name=="甲"){"旧声纹改名改变了编号或未保存"}

    val b=WearerVoiceStore.enrollNew(ctx,key,second,"乙")
    val profiles=WearerVoiceStore.listProfiles(ctx)
    require(profiles.size==2 && profiles.any{it.id==a.id && it.name=="甲"} &&
     profiles.any{it.id==b.id && it.name=="乙"} && b.id!=a.id){"多条声纹未独立保存"}
    output.put("two_profiles",JSONArray().put("甲").put("乙"))
    val bFile=File(File(dir,"voice-profiles"),"${b.id}.json")
    val bBackup=File(File(dir,"voice-profiles"),"${b.id}.json.bak")
    require(bFile.renameTo(bBackup)){"无法模拟仅有备份的声纹"}
    require(WearerVoiceStore.listProfiles(ctx).any{it.id==b.id && it.name=="乙"}){"仅有备份的声纹未被恢复"}
    output.put("backup_recovered",true)
    WearerVoiceStore.Matcher(ctx,SpeakerModels.ERES).use{otherModel->
     require(!otherModel.ready){"其他模型错误读取了CAM++声纹"}
     val unmatched=otherModel.identify(firstAgain,true)
     require(!unmatched.has("display_name")){"其他模型无登记却输出了姓名"}
     output.put("model_isolation",true)
    }
    WearerVoiceStore.Matcher(ctx,key).use{m->
     require(m.ready){"匹配器未读取声纹"}
     val firstMatch=m.identify(firstAgain,true)
     require(firstMatch.optString("status")=="wearer_candidate" && firstMatch.optString("display_name")=="甲"){"同人片段未输出对应名称"}
     val secondMatch=m.identify(second,true)
     require(secondMatch.optString("status")=="wearer_candidate" && secondMatch.optString("display_name")=="乙"){"第二条声纹未独立匹配"}
     val secondIndependent=m.identify(secondAgain,true)
     val short=m.identify(FloatArray(1000),true)
     val overlap=m.identify(firstAgain,false)
     require(!short.has("display_name") && !overlap.has("display_name")){"短片段或重叠显示了确定姓名"}
     output.put("first_match",firstMatch).put("second_match",secondMatch)
      .put("second_independent",secondIndependent)
      .put("second_independent_name_correct",secondIndependent.optString("display_name")=="乙")
      .put("second_independent_false_match",secondIndependent.has("display_name") && secondIndependent.optString("display_name")!="乙")
      .put("short",short).put("overlap_or_unknown",overlap)
    }
    // Identical enrolment produces indistinguishable references; a robust matcher must not pick one.
    val duplicate=WearerVoiceStore.enrollNew(ctx,key,first,"相似人")
    WearerVoiceStore.Matcher(ctx,key).use{m->
     val ambiguous=m.identify(firstAgain,true)
     require(ambiguous.optString("status")=="uncertain" && !ambiguous.has("display_name")){"两个高度相似声纹未进入待确认"}
     output.put("ambiguous",ambiguous)
    }
    WearerVoiceStore.deleteById(ctx,duplicate.id)
    val renamed=WearerVoiceStore.renameById(ctx,a.id,"新甲")
    require(renamed.id==a.id && WearerVoiceStore.listProfiles(ctx).any{it.id==a.id && it.name=="新甲"}){"改名未持久化"}
    WearerVoiceStore.Matcher(ctx,key).use{m->
     val found=m.identify(firstAgain,true)
     require(found.optString("display_name")=="新甲"){"改名未用于匹配结果"}
     output.put("renamed_match",found)
    }

    val aligned=TimeAlignedAsr.process(ctx.assets,firstAgain,modelDir(),key,context=ctx)
    val turns=aligned.getJSONArray("speaker_turn_transcripts")
    var named=false
    for(i in 0 until turns.length()){
     val identity=turns.getJSONObject(i).optJSONObject("wearer_identity")?:continue
     if(identity.optString("status")=="wearer_candidate" && identity.optString("display_name")=="新甲")named=true
    }
    require(named){"WAV分人转写未附带正确名称"}
    output.put("alignment_status",aligned.optString("status"))
     .put("alignment_turns",turns).put("alignment_name_present",named)

    // A legacy CAM++ profile cannot be transformed into an ERes2NetV2 vector.
    // A failed short re-recording must leave its bytes and ID untouched.
    val beforeSupplement=legacyPath.readText(Charsets.UTF_8)
    val originalVector=JSONObject(beforeSupplement).getJSONArray("embedding").toString()
    var rejectedShort=false
    try{WearerVoiceStore.supplementBothById(ctx,a.id,FloatArray(1000))}
    catch(_:Exception){rejectedShort=true}
    require(rejectedShort && legacyPath.readText(Charsets.UTF_8)==beforeSupplement){"补录失败改变了已有声纹"}
    val supplemented=WearerVoiceStore.supplementBothById(ctx,a.id,first)
    val updated=JSONObject(legacyPath.readText(Charsets.UTF_8))
    require(supplemented.id==a.id && supplemented.name=="新甲" &&
     supplemented.models==setOf(SpeakerModels.CAM,SpeakerModels.ERES) &&
     updated.getJSONObject("embeddings").getJSONArray(SpeakerModels.CAM).toString()==originalVector){"补录未保留旧声纹或缺少模型"}
    output.put("supplement_reject_kept_original",true).put("supplement_preserved_id_name_cam",true)
    for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES)){
     WearerVoiceStore.Matcher(ctx,model).use{m->
      val match=m.identify(first,true)
      require(match.optString("display_name")=="新甲"){"补录后模型无法匹配原名：$model"}
      output.put("supplement_match_$model",match)
     }
    }

    WearerVoiceStore.deleteById(ctx,b.id)
    require(WearerVoiceStore.listProfiles(ctx).let{it.size==1 && it[0].id==a.id}){"删除一项影响了其他声纹"}
    val countBefore=WearerVoiceStore.listProfiles(ctx).size
    var rejectedEnroll=false
    try{WearerVoiceStore.enrollBoth(ctx,FloatArray(1000),"失败样本")}
    catch(_:Exception){rejectedEnroll=true}
    require(rejectedEnroll && WearerVoiceStore.listProfiles(ctx).size==countBefore){"登记失败产生了不完整声纹"}
    val both=WearerVoiceStore.enrollBoth(ctx,second,"双模型乙")
    require(both.models==setOf(SpeakerModels.CAM,SpeakerModels.ERES)){"一次录音未生成两种模型"}
    output.put("both_models_enrolled",true).put("failed_enroll_left_no_profile",true)
    WearerVoiceStore.renameById(ctx,both.id,"双模型新乙")
    for(model in listOf(SpeakerModels.CAM,SpeakerModels.ERES)){
     WearerVoiceStore.Matcher(ctx,model).use{m->
      val same=m.identify(second,true)
      require(same.optString("display_name")=="双模型新乙"){"双模型登记后改名未同步：$model"}
      output.put("new_profile_match_$model",same)
      output.put("new_profile_independent_$model",m.identify(secondAgain,true))
     }
    }
    WearerVoiceStore.deleteById(ctx,both.id)
    require(WearerVoiceStore.listProfiles(ctx).let{it.size==1 && it[0].id==a.id}){"删除双模型声纹影响了旧声纹"}

    // Build a normalized artificial vector with cosine 0.50 to check that an
    // uncertain best candidate is exposed only as an explicitly tentative field.
    val arr=updated.getJSONObject("embeddings").getJSONArray(SpeakerModels.CAM)
    val vec=FloatArray(arr.length()){arr.getDouble(it).toFloat()}
    val norm=kotlin.math.sqrt(vec.sumOf{it.toDouble()*it}.toFloat())
    for(i in vec.indices)vec[i]/=norm
    var axis=0
    for(i in vec.indices)if(kotlin.math.abs(vec[i])<kotlin.math.abs(vec[axis]))axis=i
    val orth=FloatArray(vec.size){i->(if(i==axis)1f else 0f)-vec[axis]*vec[i]}
    val onorm=kotlin.math.sqrt(orth.sumOf{it.toDouble()*it}.toFloat())
    val trial=FloatArray(vec.size){i->.5f*vec[i]+kotlin.math.sqrt(.75f)*orth[i]/onorm}
    WearerVoiceStore.Matcher(ctx,SpeakerModels.CAM,false).use{m->
     val candidate=m.identifyEmbedding(trial,16000*4,true)
     require(candidate.optString("status")=="uncertain" && !candidate.has("display_name") &&
      candidate.optString("nearest_candidate_name")=="新甲"){"低置信候选状态或名称错误"}
     output.put("uncertain_candidate",candidate)
    }
    WearerVoiceStore.deleteById(ctx,a.id)
    require(WearerVoiceStore.listProfiles(ctx).isEmpty()){"删除后仍显示声纹"}
    WearerVoiceStore.Matcher(ctx,key).use{m->require(!m.ready){"删除后仍可匹配"}}
    output.put("delete_independent",true).put("status","completed")
   }catch(t:Throwable){
    Log.e("WearerVoiceProbe","测试失败",t)
    output.put("status","failed").put("error",t.message?:t.javaClass.simpleName)
   }finally{
    try{context?.let{ctx->WearerVoiceStore.listProfiles(ctx).forEach{WearerVoiceStore.deleteById(ctx,it.id)}}}catch(_:Throwable){}
    try{ticket?.let{dir->File(dir,"voice-profiles").delete();dir.delete()}}catch(_:Throwable){}
    try{resultFile.writeText(output.toString(),Charsets.UTF_8)}catch(t:Throwable){Log.e("WearerVoiceProbe","无法保存结果",t)}
   }
  }.start()
 }
}
