package org.hotwordtest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

/** Full-screen voice-profile pages. Raw enrollment audio is kept in memory only. */
class VoiceEnrollmentDialog(
 private val activity:Activity,
 private val modelKey:()->String,
 private val canRecord:()->Boolean,
 private val onExit:()->Unit
){
 companion object { const val PERMISSION_REQUEST=32; private const val RATE=16000; private const val MAX_SECONDS=30 }
 private val blue=Color.rgb(0,122,255)
 private val background=Color.rgb(242,242,247)
 private val grey=Color.rgb(112,112,120)
 private val red=Color.rgb(230,57,55)
 private val recording=AtomicBoolean(false)
 private val captureBusy=AtomicBoolean(false)
 private val working=AtomicBoolean(false)
 @Volatile private var discard=false
 @Volatile private var pending:FloatArray?=null
 private var recordingSource=""
 private var supplementId:String?=null
 private var supplementName:String?=null
 private var appendLearning=false
 private var detailId:String?=null
 private var candidatesMode=false
 private var candidatesDirect=false
 private val pageNavigator=VoicePageNavigator(activity)
 private var player:MediaPlayer?=null
 private var playingCandidate:String?=null
 private var playbackButton:TextView?=null
 private var open=false
 private var adding=false
 private var banner:String?=null
 private lateinit var nameInput:EditText
 private lateinit var stateView:TextView
 private lateinit var recordView:TextView
 private lateinit var saveView:TextView
 private fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
 private fun label(s:String,size:Int,color:Int=Color.BLACK)=TextView(activity).apply{text=s;textSize=size.toFloat();setTextColor(color)}
 private fun round(color:Int,r:Int=14)=GradientDrawable().apply{setColor(color);cornerRadius=dp(r).toFloat()}
 private fun button(title:String,primary:Boolean=false,action:()->Unit)=label(title,16,if(primary)Color.WHITE else blue).apply{
  gravity=Gravity.CENTER;minHeight=dp(50);setPadding(dp(12),dp(8),dp(12),dp(8));setTypeface(null,Typeface.BOLD)
  background=round(if(primary)blue else Color.rgb(235,244,255),12);onHapticClick{action()}
 }
 private fun link(title:String,color:Int=blue,action:()->Unit)=label(title,15,color).apply{
  gravity=Gravity.CENTER;minHeight=dp(44);setPadding(dp(10),0,dp(10),0);onHapticClick{action()}
 }
 private inner class BackChevron:View(activity){
  private val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{
   color=blue;style=Paint.Style.STROKE;strokeWidth=resources.displayMetrics.density*2f
   strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
  }
  private val path=Path()
  override fun onDraw(canvas:Canvas){
   super.onDraw(canvas)
   path.reset();path.moveTo(width*.72f,height*.2f);path.lineTo(width*.28f,height*.5f);path.lineTo(width*.72f,height*.8f)
   canvas.drawPath(path,stroke)
  }
 }
 private fun card():LinearLayout=LinearLayout(activity).apply{
  orientation=LinearLayout.VERTICAL;background=round(Color.WHITE);setPadding(dp(16),dp(14),dp(16),dp(14))
 }
 private fun addCard(parent:LinearLayout,child:View,space:Int=12){parent.addView(child,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(space)})}

 fun show(){
  if(open)return
  if(!canRecord()){Toast.makeText(activity,"请先结束当前录音和识别任务",Toast.LENGTH_SHORT).show();return}
  if(captureBusy.get()||working.get()){Toast.makeText(activity,"声纹处理正在结束，请稍候",Toast.LENGTH_SHORT).show();return}
  open=true;adding=false;banner=null;discard=false;pending=null;supplementId=null;supplementName=null;appendLearning=false;detailId=null;candidatesMode=false;candidatesDirect=false;renderList()
 }
 fun showCandidates(){
  if(!canRecord()){Toast.makeText(activity,"请先结束当前录音和识别任务",Toast.LENGTH_SHORT).show();return}
  if(captureBusy.get()||working.get()){Toast.makeText(activity,"请等待声纹处理结束",Toast.LENGTH_SHORT).show();return}
  if(open&&adding){Toast.makeText(activity,"请先保存声纹或返回列表",Toast.LENGTH_SHORT).show();return}
  candidatesDirect=!open
  if(!open){open=true;adding=false;discard=false;pending=null}
  stopPlayback();candidatesMode=true;detailId=null;renderCandidates()
 }
 /** Called by Activity back handling. */
 fun handleBack():Boolean {
  if(pageNavigator.transitioning)return true
  if(!open)return false
  if(working.get()){Toast.makeText(activity,"正在保存，请稍候",Toast.LENGTH_SHORT).show();return true}
  if(adding){leaveAdd();return true}
  if(candidatesMode){returnFromCandidates();return true}
  if(detailId!=null){stopPlayback();detailId=null;renderList();return true}
  close();return true
 }
 private fun close(){
  if(pageNavigator.transitioning)return
  stopPlayback();discard=true;recording.set(false);pending=null;supplementId=null;supplementName=null
  open=false;adding=false;detailId=null;candidatesMode=false;candidatesDirect=false;pageNavigator.close(onExit)
 }
 private fun returnFromCandidates(){
  stopPlayback()
  if(candidatesDirect){close()}else{candidatesMode=false;renderList()}
 }
 private fun leaveAdd(){
  fun leave(){discard=true;recording.set(false);pending=null;supplementId=null;supplementName=null;appendLearning=false;adding=false;renderCurrent()}
  if(recording.get()||captureBusy.get()||pending!=null){
   activity.hapticDialog(destructive=true).setTitle("放弃未保存的录音？").setMessage("当前录音尚未保存，返回后需要重新录制。")
    .setNegativeButton("继续编辑"){dialog,_->activity.dialogTap(dialog)}.setPositiveButton("放弃"){dialog,_->activity.dialogTap(dialog);leave()}.show()
  }else leave()
 }
 private fun shell(title:String,back:()->Unit,plus:(()->Unit)?=null):LinearLayout {
  val root=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(this@VoiceEnrollmentDialog.background)}
  val nav=FrameLayout(activity).apply{setBackgroundColor(Color.WHITE)}
  nav.addView(label(title,18).apply{gravity=Gravity.CENTER;setTypeface(null,Typeface.BOLD)},FrameLayout.LayoutParams(-2,dp(48),Gravity.CENTER))
  val backView=LinearLayout(activity).apply{
   orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(6),0,dp(8),0)
   contentDescription="返回";onHapticClick{if(!pageNavigator.transitioning)back()}
  }
  backView.addView(BackChevron().apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(16),dp(24)))
  backView.addView(label("返回",16,blue).apply{gravity=Gravity.CENTER_VERTICAL;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(-2,-1).apply{leftMargin=dp(2)})
  nav.addView(backView,FrameLayout.LayoutParams(dp(76),dp(48),Gravity.START or Gravity.CENTER_VERTICAL).apply{leftMargin=dp(8)})
  if(plus!=null){val add=link("＋"){if(!pageNavigator.transitioning)plus()};add.textSize=27f;add.contentDescription="添加声纹";nav.addView(add,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.END or Gravity.CENTER_VERTICAL).apply{rightMargin=dp(8)})}
  root.addView(nav,LinearLayout.LayoutParams(-1,dp(56)))
  val line=View(activity).apply{setBackgroundColor(Color.rgb(229,229,234))};root.addView(line,LinearLayout.LayoutParams(-1,dp(1)))
  val scroll=ScrollView(activity).apply{isFillViewport=true}
  val content=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(18),dp(16),dp(24))}
  scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  val pageKey=when{adding->"add";candidatesMode->"candidates";detailId!=null->"detail:"+detailId;else->"list"}
  val pageDepth=when{adding&&detailId!=null->3;candidatesMode&&candidatesDirect->1;adding||candidatesMode||detailId!=null->2;else->1}
  pageNavigator.show(root,pageKey,pageDepth)
  return content
 }
 private fun renderList(){
  if(!open)return
  val content=shell("声纹管理",{close()},{openAdd()})
  banner?.let {msg-> addCard(content,label(msg,14,Color.rgb(32,130,77)).apply{setPadding(dp(3),0,0,dp(2))},4)}
  content.addView(label("已保存的声纹",21).apply{setTypeface(null,Typeface.BOLD);setPadding(dp(3),0,0,dp(6))})
  content.addView(label("当前识别模型：${SpeakerModels.name(modelKey())}。新声纹一次录制即可用于两种模型。",13,grey).apply{setPadding(dp(3),0,dp(3),dp(16));setLineSpacing(dp(3).toFloat(),1f)})
  val profiles=try{WearerVoiceStore.listProfiles(activity)}catch(e:Exception){
   addCard(content,card().apply{addView(label("读取失败：${e.message?:"声纹数据不可用"}",14,red))});return
  }
  val candidates=try{VoiceLearningStore.listCandidates(activity)}catch(_:Exception){emptyList()}
  addCard(content,button("待确认学习片段 · ${candidates.size}条"){showCandidates()})
  if(profiles.isEmpty()){
   val empty=card();empty.gravity=Gravity.CENTER;empty.setPadding(dp(18),dp(35),dp(18),dp(35))
   empty.addView(label("等待自动认识说话人",16,Color.DKGRAY).apply{gravity=Gravity.CENTER;setTypeface(null,Typeface.BOLD)})
   empty.addView(label("开启自动学习后正常录音即可，无需先登记。也可点击＋手动添加。",13,grey).apply{gravity=Gravity.CENTER;setPadding(0,dp(8),0,0)})
   addCard(content,empty)
  }else profiles.forEach{profile->
   val row=card();val top=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
   val info=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
   info.addView(label(profile.name,17).apply{setTypeface(null,Typeface.BOLD)})
   val supported=listOf(SpeakerModels.CAM,SpeakerModels.ERES).filter{it in profile.models}
   val missing=listOf(SpeakerModels.CAM,SpeakerModels.ERES).filter{it !in profile.models}
   info.addView(label("${supported.joinToString("、"){SpeakerModels.name(it)}} · 已保存",13,grey).apply{setPadding(0,dp(5),0,0)})
   top.addView(info,LinearLayout.LayoutParams(0,-2,1f))
   top.addView(link("重命名"){rename(profile.id,profile.name)},LinearLayout.LayoutParams(-2,dp(44)))
   top.addView(link("删除",red){delete(profile.id,profile.name)},LinearLayout.LayoutParams(-2,dp(44)))
   row.addView(top)
   val samples=try{VoiceLearningStore.listSamples(activity,profile.id).size}catch(_:Exception){0}
   row.addView(button("模板与学习 · 初始＋${samples}条补充"){detailId=profile.id;renderDetail(profile.id)},LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(10)})
   if(missing.isNotEmpty()){
    row.addView(label("尚不支持${missing.joinToString("、"){SpeakerModels.name(it)}}",13,grey).apply{setPadding(0,dp(10),0,dp(4))})
    row.addView(button("补录以支持两种模型"){openSupplement(profile.id,profile.name)},LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(6)})
   }
   addCard(content,row)
  }
  content.addView(label("声纹仅保存在本机。登记姓名是模型给出的候选。未匹配到姓名时，有匿名分组的片段显示“说话人1”等编号。声音过短或多人重叠时，可能无法确定归属。",13,grey).apply{setPadding(dp(3),dp(4),dp(3),0);setLineSpacing(dp(3).toFloat(),1f)})
 }
 private fun renderCurrent(){
  if(candidatesMode)renderCandidates() else detailId?.let{renderDetail(it)}?:renderList()
 }
 private fun date(value:Long)=java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.CHINA).format(java.util.Date(value))
 private fun seconds(value:Double)=String.format(java.util.Locale.ROOT,"%.1f秒",value)
 private fun readyToChange():Boolean{
  if(captureBusy.get()||working.get()||!canRecord()){
   Toast.makeText(activity,"请先结束当前录音和识别任务",Toast.LENGTH_SHORT).show();return false
  }
  return true
 }
 private fun renderDetail(id:String){
  if(!open)return
  stopPlayback();detailId=id;candidatesMode=false
  val profile=try{WearerVoiceStore.listProfiles(activity).firstOrNull{it.id==id}}catch(e:Exception){Toast.makeText(activity,e.message?:"声纹读取失败",Toast.LENGTH_LONG).show();null}
  if(profile==null){detailId=null;renderList();return}
  val content=shell("模板与学习",{detailId=null;renderList()})
  banner?.let{addCard(content,label(it,14,Color.rgb(32,130,77)),4)}
  content.addView(label(profile.name,22).apply{setTypeface(null,Typeface.BOLD);setPadding(dp(3),0,0,dp(14))})
  addCard(content,card().apply{
   addView(label("初始声纹模板",17).apply{setTypeface(null,Typeface.BOLD)})
   addView(label(profile.models.joinToString("、"){SpeakerModels.name(it)}+" · 保留",13,grey).apply{setPadding(0,dp(7),0,0)})
   addView(label("自动认识或手动添加的人物均使用稳定ID。补充、重命名和撤销学习不会另建人物，初始模板持续参与匹配。",13,grey).apply{setPadding(0,dp(8),0,0);setLineSpacing(dp(3).toFloat(),1f)})
  })
  addCard(content,button("补录同一个人的声音",true){if(readyToChange())openAppend(id,profile.name)})
  addCard(content,button("撤销最近一次学习"){
   if(readyToChange())activity.hapticDialog(destructive=true).setTitle("撤销学习？")
    .setMessage("撤销“${profile.name}”最近一次追加或归属变更，已删除样本保持删除。")
    .setNegativeButton("取消"){d,_->activity.dialogTap(d)}
    .setPositiveButton("撤销"){d,_->activity.dialogTap(d);try{
     val changed=VoiceLearningStore.undoLast(activity,id)
     banner=if(changed)"已撤销最近一次学习" else "没有可撤销的学习记录";renderDetail(id)
    }catch(e:Exception){Toast.makeText(activity,e.message?:"撤销失败",Toast.LENGTH_LONG).show()}}.show()
  })
  val samples=try{VoiceLearningStore.listSamples(activity,id)}catch(e:Exception){
   addCard(content,label(e.message?:"补充模板读取失败",14,red));return
  }
  content.addView(label("补充模板 · ${samples.size}条",17).apply{setTypeface(null,Typeface.BOLD);setPadding(dp(3),dp(8),0,dp(12))})
  if(samples.isEmpty())addCard(content,card().apply{addView(label("还没有补充模板",15,grey));addView(label("可以手动补录，或从待确认学习片段中选择本人发言。",13,grey).apply{setPadding(0,dp(8),0,0)})})
  samples.forEach{sample->
   val row=card();row.addView(label(sample.title.ifBlank{"补充模板"},16).apply{setTypeface(null,Typeface.BOLD);maxLines=3})
   row.addView(label("${date(sample.createdMs)} · ${seconds(sample.durationSeconds)}",13,grey).apply{setPadding(0,dp(6),0,dp(6))})
   row.addView(link("删除这条补充模板",red){
    if(readyToChange())activity.hapticDialog(destructive=true).setTitle("删除补充模板？")
     .setMessage("删除这条补充样本的声纹特征，初始登记模板保留。")
     .setNegativeButton("取消"){d,_->activity.dialogTap(d)}
     .setPositiveButton("删除"){d,_->activity.dialogTap(d);try{VoiceLearningStore.removeSample(activity,id,sample.id);banner="已删除补充模板";renderDetail(id)}catch(e:Exception){Toast.makeText(activity,e.message?:"删除失败",Toast.LENGTH_LONG).show()}}.show()
   })
   addCard(content,row)
  }
  addCard(content,button("查看待确认学习片段"){showCandidates()})
 }
 private fun stopPlayback(){
  val previous=player;player=null;playingCandidate=null
  playbackButton?.text="试听";playbackButton=null
  try{previous?.stop()}catch(_:Exception){};previous?.release()
 }
 private fun playCandidate(id:String,control:TextView){
  if(playingCandidate==id){stopPlayback();return}
  stopPlayback()
  val audio=VoiceLearningStore.candidateAudio(activity,id)
  if(audio==null){Toast.makeText(activity,"这条候选的试听音频已不可用",Toast.LENGTH_SHORT).show();return}
  val next=MediaPlayer();player=next;playingCandidate=id;playbackButton=control;control.text="停止试听"
  try{
   next.setDataSource(audio.absolutePath)
   next.setOnPreparedListener{if(player===it&&open&&candidatesMode)it.start() else it.release()}
   next.setOnCompletionListener{if(player===it)stopPlayback()}
   next.setOnErrorListener{_,_,_->if(player===next){stopPlayback();Toast.makeText(activity,"无法试听这条候选",Toast.LENGTH_SHORT).show()};true}
   next.prepareAsync()
  }catch(_:Exception){stopPlayback();Toast.makeText(activity,"无法试听这条候选",Toast.LENGTH_SHORT).show()}
 }
 private fun confirmCandidate(candidate:VoiceLearningStore.CandidateInfo){
  if(!readyToChange())return
  stopPlayback()
  val profiles=try{WearerVoiceStore.listProfiles(activity)}catch(e:Exception){Toast.makeText(activity,e.message?:"声纹读取失败",Toast.LENGTH_LONG).show();return}
  if(profiles.isEmpty()){Toast.makeText(activity,"请先添加并命名一条声纹",Toast.LENGTH_LONG).show();return}
  val duplicateNames=profiles.groupingBy{it.name}.eachCount()
  val names=profiles.mapIndexed{index,p->
   val name=if((duplicateNames[p.name]?:0)>1)"${p.name} · 记录${index+1}" else p.name
   if(p.id==candidate.suggestedProfileId)"$name · 系统建议" else name
  }.toTypedArray()
  val selection=VoiceCandidateSelection(profiles.size)
  val dialog=activity.hapticDialog().setTitle("请选择这段是谁的声音")
   .setSingleChoiceItems(names,-1){d,which->
    activity.dialogTap(d)
    (d as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=selection.select(which)
   }
   .setNegativeButton("取消"){d,_->activity.dialogTap(d);selection.clear()}
   .setPositiveButton("确认并追加",null).create()
  dialog.setOnShowListener{
   dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply{
    isEnabled=false
    onHapticClick{
     val selected=selection.selectedIndex ?: return@onHapticClick
     if(!readyToChange()||!working.compareAndSet(false,true))return@onHapticClick
     val target=profiles[selected];dialog.dismiss()
     if(candidate.spanContext!=null){
      working.set(false)
      val count=candidate.spanContext.optJSONArray("rows")?.length()?:0
      activity.hapticDialog().setTitle("确认完整跨行片段")
       .setMessage("请确认${spanTime(candidate)}的完整音频均为“${target.name}”的声音。此片段涉及${count}行转写；确认只追加声纹，不修改这些行的人物标签。")
       .setNegativeButton("取消"){d,_->activity.dialogTap(d)}
       .setPositiveButton("确认整段并追加"){d,_->activity.dialogTap(d)
        if(readyToChange()&&working.compareAndSet(false,true))appendCandidate(candidate,target)
       }.show()
     }else appendCandidate(candidate,target)
    }
   }
  }
  dialog.show()
 }
 private fun appendCandidate(candidate:VoiceLearningStore.CandidateInfo,target:WearerVoiceStore.ProfileInfo){
     banner="正在核对声纹，请稍候…";renderCandidates()
     Thread{
      try{
       VoiceLearningStore.confirmCandidate(activity.applicationContext,candidate.id,target.id)
       activity.runOnUiThread{working.set(false);if(!activity.isDestroyed){
        banner="已追加到${target.name}";if(open&&candidatesMode)renderCandidates()
        else Toast.makeText(activity,banner,Toast.LENGTH_LONG).show()
       }}
      }catch(e:Throwable){activity.runOnUiThread{working.set(false);if(!activity.isDestroyed){
       banner=null;if(open&&candidatesMode)renderCandidates();Toast.makeText(activity,e.message?:"确认失败",Toast.LENGTH_LONG).show()
      }}}
     }.start()
 }
 private fun sampleMs(samples:Long)=(samples/16000)*1000+(samples%16000)*1000/16000
 private fun spanTime(candidate:VoiceLearningStore.CandidateInfo):String {
  val offset=candidate.spanContext?.optLong("display_offset_samples",0L)?:0L
  return SpeakerTranscript.line(sampleMs(offset+candidate.startSample),sampleMs(offset+candidate.endSample),"","§").substringBefore(']')+"]"
 }
 private fun autoDiagnosticText(candidate:VoiceLearningStore.CandidateInfo):String? {
  val d=candidate.autoDiagnostic?:return null
  val reason=d.optString("reason").ifBlank{"未通过自动校验"}
  val result=StringBuilder("自动学习未通过：").append(reason)
  val models=d.optJSONArray("models")?:return result.toString()
  var failure:org.json.JSONObject?=null;var modelName=""
  for(i in 0 until models.length()){
   val model=models.optJSONObject(i)?:continue;val windows=model.optJSONArray("windows")?:continue
   for(j in 0 until windows.length()){
    val row=windows.optJSONObject(j)?:continue
    if(row.optString("match_status")!="wearer_candidate"||row.opt("best_is_target")==false){failure=row;modelName=model.optString("model");break}
   }
   if(failure!=null)break
  }
  if(failure!=null){
   val row=failure;val start=row.optLong("start_sample",-1);val end=row.optLong("end_sample",-1)
   result.append(if(d.optString("stage")=="automatic")"\n首个失败窗口：" else "\n检查窗口：").append(modelName)
   if(start>=0&&end>start)result.append(" · ").append(String.format(java.util.Locale.ROOT,"%.2f–%.2f秒",start/16000.0,end/16000.0))
   if(!row.isNull("target_score"))result.append("\n目标相似度：").append(String.format(java.util.Locale.ROOT,"%.3f",row.optDouble("target_score")))
   if(!d.isNull("minimum_similarity"))result.append(" · 门槛：").append(String.format(java.util.Locale.ROOT,"%.2f",d.optDouble("minimum_similarity")))
   if(!row.isNull("other_best_score"))result.append("\n其他已登记人物最高分：").append(String.format(java.util.Locale.ROOT,"%.3f",row.optDouble("other_best_score")))
   if(!row.isNull("margin"))result.append(" · 分差：").append(String.format(java.util.Locale.ROOT,"%.3f",row.optDouble("margin")))
  }
  return result.append(if(failure!=null&&d.optString("stage")=="automatic")"\n仅记录首个失败窗口，分数不是身份概率。该候选仍需人工确认。" else "\n该候选仍需人工确认；分数不是身份概率。").toString()
 }
 private fun renderCandidates(){
  if(!open)return
  stopPlayback();candidatesMode=true;detailId=null
  val content=shell("待确认学习片段",{returnFromCandidates()})
  banner?.let{addCard(content,label(it,14,Color.rgb(32,130,77)),4)}
  content.addView(label("先试听，再确认是谁",21).apply{setTypeface(null,Typeface.BOLD);setPadding(dp(3),0,0,dp(8))})
  content.addView(label("候选已通过模型活动和质量筛选，仍请试听核对是否为同一人。待确认片段尚未参与声纹匹配，确认归属后才追加到该用户的补充模板。",13,grey).apply{setPadding(dp(3),0,dp(3),dp(16));setLineSpacing(dp(3).toFloat(),1f)})
  val candidates=try{VoiceLearningStore.listCandidates(activity)}catch(e:Exception){addCard(content,label(e.message?:"候选读取失败",14,red));return}
  if(candidates.isEmpty())addCard(content,card().apply{
   addView(label("暂无待确认片段",17).apply{setTypeface(null,Typeface.BOLD)})
   addView(label("完成本地录音或文件识别后，在这里查看收集到的单人语音。过短、过轻、重叠或质量不足的片段不会直接成为补充模板。",13,grey).apply{setPadding(0,dp(8),0,0);setLineSpacing(dp(3).toFloat(),1f)})
  })
  candidates.forEach{candidate->
   val row=card();row.addView(label(candidate.title.ifBlank{"待确认语音"},16).apply{setTypeface(null,Typeface.BOLD);maxLines=5})
   row.addView(label("${date(candidate.createdMs)} · ${seconds(candidate.durationSeconds)} · 待确认",13,grey).apply{setPadding(0,dp(8),0,dp(8))})
   autoDiagnosticText(candidate)?.let{message->row.addView(label(message,13,grey).apply{setPadding(0,0,0,dp(10));setLineSpacing(dp(3).toFloat(),1f)})}
   candidate.spanContext?.let{span->
    val rows=span.optJSONArray("rows")?:org.json.JSONArray()
    row.addView(label("实际音频：${spanTime(candidate)} · 涉及${rows.length()}行",14).apply{setPadding(0,0,0,dp(6))})
    for(i in 0 until rows.length()){
     val entry=rows.getJSONObject(i);val partial=entry.getLong("covered_start_sample")!=entry.getLong("start_sample")||entry.getLong("covered_end_sample")!=entry.getLong("end_sample")
     val content=entry.optString("text").ifBlank{"无识别文字"}
     row.addView(label("第${i+1}行"+(if(partial)"（部分音频）" else "")+"："+content,13,grey).apply{setPadding(0,0,0,dp(4))})
    }
    row.addView(label("这些行被模型预测为同一人，仍可能有误。请试听完整区间；确认归属只追加声纹，不会修改原转写的人物标签。",13,grey).apply{setPadding(0,dp(4),0,dp(8))})
   }
   val actions=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
   lateinit var listen:TextView
   listen=button("试听"){playCandidate(candidate.id,listen)}
   actions.addView(listen,LinearLayout.LayoutParams(0,-2,1f))
   actions.addView(button("确认归属",true){confirmCandidate(candidate)},LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(10)})
   row.addView(actions)
   row.addView(link("丢弃",red){if(readyToChange()){
    stopPlayback();activity.hapticDialog(destructive=true).setTitle("丢弃这条候选？").setMessage("这条语音及其待确认特征会从学习候选中移除。")
     .setNegativeButton("取消"){d,_->activity.dialogTap(d)}
     .setPositiveButton("丢弃"){d,_->activity.dialogTap(d);try{VoiceLearningStore.discardCandidate(activity,candidate.id);banner="已丢弃候选";renderCandidates()}catch(e:Exception){Toast.makeText(activity,e.message?:"丢弃失败",Toast.LENGTH_LONG).show()}}.show()
   }},LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(4)})
   addCard(content,row)
  }
 }
 private fun rename(id:String,old:String){
  val input=EditText(activity).apply{setSingleLine(true);inputType=InputType.TYPE_CLASS_TEXT;filters=arrayOf(InputFilter.LengthFilter(32));setText(old);setSelection(text.length);styleDialogField()}
  val content=FrameLayout(activity).apply{
   setPadding(dp(22),dp(8),dp(22),dp(4));addView(input,FrameLayout.LayoutParams(-1,-2))
  }
  val dialog=activity.hapticDialog().setTitle("重命名声纹").setView(content).setNegativeButton("取消"){d,_->activity.dialogTap(d)}.setPositiveButton("保存",null).create()
  dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).onHapticClick{
   try{val saved=WearerVoiceStore.renameById(activity,id,input.text.toString());banner="已保存名称：${saved.name}";dialog.dismiss();renderList()}
   catch(e:Exception){input.error=e.message?:"名称无效"}
  }};dialog.show()
 }
 private fun delete(id:String,name:String){
  activity.hapticDialog(destructive=true).setTitle("删除声纹？").setMessage("将删除“$name”的初始声纹、补充模板和其验证样本。待确认片段、原始录音和已生成的转写仍会保留，可另行删除。")
   .setNegativeButton("取消"){d,_->activity.dialogTap(d)}.setPositiveButton("删除"){d,_->activity.dialogTap(d)
    try{WearerVoiceStore.deleteById(activity,id);banner="已删除：$name";renderList()}
    catch(e:Exception){Toast.makeText(activity,"删除未完成：${e.message?:"请重试"}；请重启应用检查后重试",Toast.LENGTH_LONG).show()}
   }.show()
 }
 private fun openAdd(){
  if(captureBusy.get()||working.get()){Toast.makeText(activity,"请等待上一段录音结束",Toast.LENGTH_SHORT).show();return}
  stopPlayback();adding=true;supplementId=null;supplementName=null;appendLearning=false;discard=false;pending=null;renderAdd()
 }
 private fun openSupplement(id:String,name:String){
  if(captureBusy.get()||working.get()){Toast.makeText(activity,"请等待上一段录音结束",Toast.LENGTH_SHORT).show();return}
  stopPlayback();adding=true;supplementId=id;supplementName=name;appendLearning=false;discard=false;pending=null;renderAdd()
 }
 private fun openAppend(id:String,name:String){
  if(captureBusy.get()||working.get()){Toast.makeText(activity,"请等待上一段录音结束",Toast.LENGTH_SHORT).show();return}
  stopPlayback();adding=true;supplementId=id;supplementName=name;appendLearning=true;discard=false;pending=null;renderAdd()
 }
 private fun renderAdd(){
  if(!open||!adding)return
  val content=shell(if(supplementId==null)"添加声纹" else if(appendLearning)"补充声纹模板" else "补齐模型声纹",{if(working.get())Toast.makeText(activity,"正在保存，请稍候",Toast.LENGTH_SHORT).show() else leaveAdd()})
  val a=card();a.addView(label("声纹名称",15).apply{setTypeface(null,Typeface.BOLD)})
  nameInput=EditText(activity).apply{hint="输入名称，例如：小林";setSingleLine(true);inputType=InputType.TYPE_CLASS_TEXT;filters=arrayOf(InputFilter.LengthFilter(32));textSize=16f;setTextColor(Color.BLACK);setPadding(dp(4),dp(6),dp(4),dp(6))}
  if(supplementId!=null){nameInput.setText(supplementName?:"");nameInput.isEnabled=false}
  a.addView(nameInput,LinearLayout.LayoutParams(-1,dp(54)).apply{topMargin=dp(4)})
  a.addView(label("匹配到这个声音时，将在转写结果中显示名称。",13,grey).apply{setPadding(0,dp(6),0,dp(10))})
  a.addView(label(when{supplementId==null->"一次录制将生成CAM++和ERes2NetV2两种声纹，切换模型后均可使用。";appendLearning->"本次录音作为同一用户的新模板，初始登记模板保留。可以用不同的自然语气、音量或距离补录。";else->"本次补录会补齐缺失模型的声纹，已有声纹保持不变。"},13,grey).apply{setLineSpacing(dp(3).toFloat(),1f)})
  if(supplementId!=null)a.addView(label("请让这条声纹对应的人重新朗读一次；成功保存前原声纹保持不变。",13,grey).apply{setPadding(0,dp(8),0,0);setLineSpacing(dp(3).toFloat(),1f)})
  addCard(content,a)
  val b=card();b.addView(label("录制声纹",17).apply{setTypeface(null,Typeface.BOLD)})
  b.addView(label("请在安静环境中，让待登记的人自然朗读10～20秒。录音只暂存在本机内存。",14,grey).apply{setPadding(0,dp(8),0,dp(14));setLineSpacing(dp(3).toFloat(),1f)})
  b.addView(label("朗读示例",14).apply{setTypeface(null,Typeface.BOLD)})
  b.addView(label("今天我正在进行本地声纹登记。请按照正常说话的速度，清楚地读完这段文字。希望设备能够识别我的声音，并与身边其他人的声音区分。接下来我会在不同环境中使用语音转写，记录工作、交流和日常生活中的内容。",16).apply{setPadding(0,dp(8),0,dp(14));setLineSpacing(dp(4).toFloat(),1f)})
  stateView=label("未录制 · 最长30秒",13,grey);b.addView(stateView)
  recordView=button("开始录制",true){if(recording.get())stopRecording() else requestRecord()};b.addView(recordView,LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(14)})
  saveView=button("保存声纹"){save()};b.addView(saveView,LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(10)})
  addCard(content,b);refresh("未录制 · 最长30秒")
 }
 private fun refresh(message:String){
  if(!open||!adding)return
  stateView.text=message
  recordView.text=if(recording.get())"结束录制" else if(pending==null)"开始录制" else "重新录制"
  val enableRecord=!working.get()&&(!captureBusy.get()||recording.get());recordView.isEnabled=enableRecord;recordView.alpha=if(enableRecord)1f else .45f
  val ready=pending!=null&&!captureBusy.get()&&!working.get();saveView.isEnabled=ready;saveView.alpha=if(ready)1f else .45f
 }
 private fun requestRecord(){
  if(working.get()||captureBusy.get())return
  if(!canRecord()){refresh("请先结束当前识别任务");return}
  if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),PERMISSION_REQUEST);return}
  startRecording()
 }
 fun onPermissionResult(granted:Boolean){if(!open||!adding)return;if(granted)requestRecord() else refresh("需要麦克风权限才能录制")}
 private fun startRecording(){
  if(!captureBusy.compareAndSet(false,true))return
  recordingSource="manual:"+java.util.UUID.randomUUID().toString()
  recording.set(true);discard=false;pending=null;refresh("正在准备麦克风，请稍候…")
  Thread{
   var recorder:AudioRecord?=null;var error:String?=null;val all=ShortArray(RATE*MAX_SECONDS);var count=0
   try{
    if(!recording.get()||discard)return@Thread
    val min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
    check(min>0){"设备不支持16kHz单声道录音"}
    recorder=AudioRecord(MediaRecorder.AudioSource.MIC,RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,6400))
    check(recorder.state==AudioRecord.STATE_INITIALIZED){"麦克风初始化失败"}
    if(!recording.get()||discard)return@Thread
    recorder.startRecording();check(recorder.recordingState==AudioRecord.RECORDSTATE_RECORDING){"麦克风无法开始录音"}
    activity.runOnUiThread{if(open&&adding&&recording.get()&&!discard)refresh("正在录音，请朗读示例；读完后点击结束录制。")}
    while(recording.get()&&!discard&&count<all.size){val n=recorder.read(all,count,minOf(3200,all.size-count));check(n>=0){"录音读取失败：$n"};count+=n}
   }catch(e:Throwable){error=e.message?:"录音失败"}
   finally{try{recorder?.stop()}catch(_:Throwable){};recorder?.release();recording.set(false);captureBusy.set(false)}
   if(!discard&&error==null&&count>0){pending=FloatArray(count){all[it]/32768f}}
   activity.runOnUiThread{if(open&&adding&&!discard){if(error!=null)refresh("录音失败：$error") else if(count==0)refresh("没有采集到音频，请重新录制") else refresh("已录制${count/RATE}秒 · 尚未保存，请点击保存声纹")}}
  }.start()
 }
 private fun stopRecording(){recording.set(false);refresh("正在结束录音…")}
 private fun save(){
  val samples=pending?:return;val id=supplementId;val name=nameInput.text.toString().trim();val source=recordingSource
  if(name.isEmpty()||name.length>32||name.any{Character.isISOControl(it)||it=='\u2028'||it=='\u2029'}){nameInput.error="请输入1～32个字符，不能包含控制字符";nameInput.requestFocus();return}
  if(captureBusy.get()||!working.compareAndSet(false,true))return
  val append=appendLearning
  refresh(if(id==null||append)"正在生成两种模型的声纹，请稍候…" else "正在生成缺失模型的声纹，请稍候…")
  Thread{
   try{
    val saved=if(id==null)WearerVoiceStore.enrollBoth(activity.applicationContext,samples,name)
     else if(append){VoiceLearningStore.supplement(activity.applicationContext,id,samples,source,"手动补录");WearerVoiceStore.listProfiles(activity).first{it.id==id}}
     else WearerVoiceStore.supplementBothById(activity.applicationContext,id,samples)
    pending=null;working.set(false)
    activity.runOnUiThread{if(open&&adding){adding=false;supplementId=null;supplementName=null;appendLearning=false;banner=if(append)"已为${saved.name}追加补充模板" else "已保存：${saved.name}，支持两种模型";renderCurrent()}}
   }catch(e:Throwable){working.set(false);activity.runOnUiThread{if(open&&adding)refresh("保存失败：${e.message?:e.javaClass.simpleName}")}}
  }.start()
 }
 /** When the Activity leaves foreground, stop capture and discard unsaved audio. */
 fun cancel(){
  if(!open)return
  pageNavigator.settle()
  stopPlayback()
  discard=true;recording.set(false);pending=null
  if(adding&&!working.get())activity.runOnUiThread{if(open&&adding)refresh("录音已中断，未保存")}
 }
}
