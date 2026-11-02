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
 private var supplementId:String?=null
 private var supplementName:String?=null
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
  if(captureBusy.get()||working.get()){Toast.makeText(activity,"声纹处理正在结束，请稍候",Toast.LENGTH_SHORT).show();return}
  open=true;adding=false;banner=null;discard=false;pending=null;supplementId=null;supplementName=null;renderList()
 }
 /** Called by Activity back handling. */
 fun handleBack():Boolean {
  if(!open)return false
  if(working.get()){Toast.makeText(activity,"正在保存，请稍候",Toast.LENGTH_SHORT).show();return true}
  if(adding){leaveAdd();return true}
  close();return true
 }
 private fun close(){discard=true;recording.set(false);pending=null;supplementId=null;supplementName=null;open=false;adding=false;onExit()}
 private fun leaveAdd(){
  fun leave(){discard=true;recording.set(false);pending=null;supplementId=null;supplementName=null;adding=false;renderList()}
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
   contentDescription="返回";onHapticClick{back()}
  }
  backView.addView(BackChevron().apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(16),dp(24)))
  backView.addView(label("返回",16,blue).apply{gravity=Gravity.CENTER_VERTICAL;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(-2,-1).apply{leftMargin=dp(2)})
  nav.addView(backView,FrameLayout.LayoutParams(dp(76),dp(48),Gravity.START or Gravity.CENTER_VERTICAL).apply{leftMargin=dp(8)})
  if(plus!=null){val add=link("＋"){plus()};add.textSize=27f;add.contentDescription="添加声纹";nav.addView(add,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.END or Gravity.CENTER_VERTICAL).apply{rightMargin=dp(8)})}
  root.addView(nav,LinearLayout.LayoutParams(-1,dp(56)))
  val line=View(activity).apply{setBackgroundColor(Color.rgb(229,229,234))};root.addView(line,LinearLayout.LayoutParams(-1,dp(1)))
  val scroll=ScrollView(activity).apply{isFillViewport=true}
  val content=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(18),dp(16),dp(24))}
  scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  activity.setContentView(root)
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
  if(profiles.isEmpty()){
   val empty=card();empty.gravity=Gravity.CENTER;empty.setPadding(dp(18),dp(35),dp(18),dp(35))
   empty.addView(label("还没有保存的声纹",16,Color.DKGRAY).apply{gravity=Gravity.CENTER;setTypeface(null,Typeface.BOLD)})
   empty.addView(label("点击右上角＋，录制并命名。",13,grey).apply{gravity=Gravity.CENTER;setPadding(0,dp(8),0,0)})
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
   if(missing.isNotEmpty()){
    row.addView(label("尚不支持${missing.joinToString("、"){SpeakerModels.name(it)}}",13,grey).apply{setPadding(0,dp(10),0,dp(4))})
    row.addView(button("补录以支持两种模型"){openSupplement(profile.id,profile.name)},LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(6)})
   }
   addCard(content,row)
  }
  content.addView(label("声纹仅保存在本机。登记姓名是模型给出的候选。未匹配到姓名时，有匿名分组的片段显示“说话人1”等编号。声音过短或多人重叠时，可能无法确定归属。",13,grey).apply{setPadding(dp(3),dp(4),dp(3),0);setLineSpacing(dp(3).toFloat(),1f)})
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
  activity.hapticDialog(destructive=true).setTitle("删除声纹？").setMessage("将从本机删除“$name”的声纹。")
   .setNegativeButton("取消"){d,_->activity.dialogTap(d)}.setPositiveButton("删除"){d,_->activity.dialogTap(d)
    try{WearerVoiceStore.deleteById(activity,id);banner="已删除：$name";renderList()}
    catch(e:Exception){Toast.makeText(activity,"删除失败：${e.message?:"请重试"}",Toast.LENGTH_LONG).show()}
   }.show()
 }
 private fun openAdd(){
  if(captureBusy.get()||working.get()){Toast.makeText(activity,"请等待上一段录音结束",Toast.LENGTH_SHORT).show();return}
  adding=true;supplementId=null;supplementName=null;discard=false;pending=null;renderAdd()
 }
 private fun openSupplement(id:String,name:String){
  if(captureBusy.get()||working.get()){Toast.makeText(activity,"请等待上一段录音结束",Toast.LENGTH_SHORT).show();return}
  adding=true;supplementId=id;supplementName=name;discard=false;pending=null;renderAdd()
 }
 private fun renderAdd(){
  if(!open||!adding)return
  val content=shell(if(supplementId==null)"添加声纹" else "补录声纹",{if(working.get())Toast.makeText(activity,"正在保存，请稍候",Toast.LENGTH_SHORT).show() else leaveAdd()})
  val a=card();a.addView(label("声纹名称",15).apply{setTypeface(null,Typeface.BOLD)})
  nameInput=EditText(activity).apply{hint="输入名称，例如：小林";setSingleLine(true);inputType=InputType.TYPE_CLASS_TEXT;filters=arrayOf(InputFilter.LengthFilter(32));textSize=16f;setTextColor(Color.BLACK);setPadding(dp(4),dp(6),dp(4),dp(6))}
  if(supplementId!=null){nameInput.setText(supplementName?:"");nameInput.isEnabled=false}
  a.addView(nameInput,LinearLayout.LayoutParams(-1,dp(54)).apply{topMargin=dp(4)})
  a.addView(label("匹配到这个声音时，将在转写结果中显示名称。",13,grey).apply{setPadding(0,dp(6),0,dp(10))})
  a.addView(label(if(supplementId==null)"一次录制将生成CAM++和ERes2NetV2两种声纹，切换模型后均可使用。" else "本次补录会补齐缺失模型的声纹，已有声纹保持不变。",13,grey).apply{setLineSpacing(dp(3).toFloat(),1f)})
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
  val samples=pending?:return;val id=supplementId;val name=nameInput.text.toString().trim()
  if(name.isEmpty()||name.length>32||name.any{Character.isISOControl(it)||it=='\u2028'||it=='\u2029'}){nameInput.error="请输入1～32个字符，不能包含控制字符";nameInput.requestFocus();return}
  if(captureBusy.get()||!working.compareAndSet(false,true))return
  refresh(if(id==null)"正在生成两种模型的声纹，请稍候…" else "正在生成缺失模型的声纹，请稍候…")
  Thread{
   try{
    val saved=if(id==null)WearerVoiceStore.enrollBoth(activity.applicationContext,samples,name)
     else WearerVoiceStore.supplementBothById(activity.applicationContext,id,samples)
    pending=null;working.set(false)
    activity.runOnUiThread{if(open&&adding){adding=false;supplementId=null;supplementName=null;banner="已保存：${saved.name}，支持两种模型";renderList()}}
   }catch(e:Throwable){working.set(false);activity.runOnUiThread{if(open&&adding)refresh("保存失败：${e.message?:e.javaClass.simpleName}")}}
  }.start()
 }
 /** When the Activity leaves foreground, stop capture and discard unsaved audio. */
 fun cancel(){
  if(!open)return
  discard=true;recording.set(false);pending=null
  if(adding&&!working.get())activity.runOnUiThread{if(open&&adding)refresh("录音已中断，未保存")}
 }
}
