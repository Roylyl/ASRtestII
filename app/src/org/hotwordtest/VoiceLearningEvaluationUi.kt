package org.hotwordtest

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

/** Independent local labelled trials. No audio or text is sent to a network service. */
class VoiceLearningEvaluationUi(
 private val activity:Activity,
 private val modelKey:()->String,
 private val canWork:()->Boolean,
 private val onExit:()->Unit
){
 companion object{const val REQUEST=78;const val PERMISSION=79;private const val RATE=16000}
 private val blue=Color.rgb(0,122,255)
 private val grey=Color.rgb(112,112,120)
 private val red=Color.rgb(230,57,55)
 private var dialog:Dialog?=null
 private var pageMoving=false
 private var profileId:String?=null
 private var pendingSelf:Boolean?=null
 private var pendingProfileId:String?=null
 private var state="添加本人和他人的独立验证录音，再比较学习前后结果。"
 private var output=""
 private val recording=AtomicBoolean(false)
 private val working=AtomicBoolean(false)
 @Volatile private var discard=false
 private var generation=0
 private lateinit var body:LinearLayout
 private lateinit var statusView:TextView
 private var recordButton:TextView?=null
 val isBusy:Boolean get()=recording.get()||working.get()
 private fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
 private fun text(value:String,size:Int=14,color:Int=Color.BLACK)=TextView(activity).apply{
  text=value;textSize=size.toFloat();setTextColor(color);setLineSpacing(dp(3).toFloat(),1f)
 }
 private fun round(color:Int,r:Int=14)=GradientDrawable().apply{setColor(color);cornerRadius=dp(r).toFloat()}
 private fun button(value:String,primary:Boolean=false,action:()->Unit)=text(value,16,if(primary)Color.WHITE else blue).apply{
  gravity=Gravity.CENTER;minHeight=dp(48);setPadding(dp(12),dp(9),dp(12),dp(9))
  background=round(if(primary)blue else Color.rgb(235,244,255),12)
  setTypeface(null,Typeface.BOLD);onHapticClick{action()}
 }
 private fun card()=LinearLayout(activity).apply{
  orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14));background=round(Color.WHITE)
 }
 private fun addCard(view:View){body.addView(view,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})}
 private fun requireIdle(action:()->Unit){
  if(isBusy || !canWork()){Toast.makeText(activity,"请先结束当前录音或处理任务",Toast.LENGTH_SHORT).show();return}
  action()
 }
 private fun motionEnabled():Boolean=try{
  ValueAnimator.areAnimatorsEnabled()&&
   Settings.Global.getFloat(activity.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f&&
   Settings.Global.getFloat(activity.contentResolver,Settings.Global.WINDOW_ANIMATION_SCALE,1f)>0f&&
   Settings.Global.getFloat(activity.contentResolver,Settings.Global.TRANSITION_ANIMATION_SCALE,1f)>0f
 }catch(_:Exception){ValueAnimator.areAnimatorsEnabled()}
 private fun transition(d:Dialog,enter:Boolean,finished:()->Unit){
  val window=d.window
  if(window==null||!motionEnabled()){finished();return}
  val decor=window.decorView
  val overlay=decor as? FrameLayout
  if(overlay==null){finished();return}
  val width=(decor.width.takeIf{it>0}?:activity.resources.displayMetrics.widthPixels).coerceAtLeast(1).toFloat()
  pageMoving=true
  // A transparent in-window shield consumes taps. FLAG_NOT_TOUCHABLE would
  // pass them through to the underlying Activity during the transition.
  val blocker=View(activity).apply{isClickable=true;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
  overlay.addView(blocker,FrameLayout.LayoutParams(-1,-1))
  decor.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
  if(enter){decor.translationX=width;decor.alpha=.7f}
  decor.animate().cancel()
  decor.animate().translationX(if(enter)0f else width).alpha(if(enter)1f else .7f)
   .setDuration(220).setInterpolator(DecelerateInterpolator(1.5f)).withEndAction{
    decor.translationX=0f;decor.alpha=1f
    decor.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    (blocker.parent as? ViewGroup)?.removeView(blocker);pageMoving=false
    finished()
   }.start()
 }
 fun show(){
  if(dialog?.isShowing==true)return
  if(!canWork()){Toast.makeText(activity,"请先结束当前识别任务",Toast.LENGTH_SHORT).show();return}
  val d=Dialog(activity,android.R.style.Theme_Material_Light_NoActionBar)
  dialog=d;generation++;discard=false
  d.setCancelable(false)
  d.setOnKeyListener{_,key,event->
   if(key==android.view.KeyEvent.KEYCODE_BACK){
    if(event.action==android.view.KeyEvent.ACTION_UP){activity.window.decorView.tapFeedback();close()};true
   }else false
  }
  d.setOnDismissListener{recording.set(false);discard=true;pageMoving=false;if(dialog===d){dialog=null;onExit()}}
  render();d.show()
  d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
  d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
  d.window?.statusBarColor=Color.rgb(242,242,247)
  d.window?.navigationBarColor=Color.WHITE
  transition(d,true){}
 }
 fun handleBack():Boolean {
  if(dialog?.isShowing!=true)return false
  close();return true
 }
 private fun close(){
  if(pageMoving)return
  if(working.get()&&!recording.get()){Toast.makeText(activity,"本地处理正在结束，请稍候",Toast.LENGTH_SHORT).show();return}
  recording.set(false);discard=true;generation++
  val d=dialog?:return
  transition(d,false){if(dialog===d&&d.isShowing)d.dismiss()}
 }
 fun cancel(){
  if(recording.get()){discard=true;recording.set(false);state="录音已中断，未保存验证样本"}
 }
 private fun render(){
  val d=dialog?:return
  val root=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(242,242,247))}
  val nav=FrameLayout(activity).apply{setBackgroundColor(Color.WHITE)}
  nav.addView(text("学习效果对照",18).apply{gravity=Gravity.CENTER;setTypeface(null,Typeface.BOLD)},FrameLayout.LayoutParams(-2,dp(52),Gravity.CENTER))
  val back=LinearLayout(activity).apply{
   gravity=Gravity.CENTER_VERTICAL;orientation=LinearLayout.HORIZONTAL;setPadding(dp(14),0,dp(10),0)
   contentDescription="返回";onHapticClick{close()}
  }
  back.addView(object:View(activity){
   private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=blue;style=Paint.Style.STROKE;strokeWidth=dp(2).toFloat();strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
   override fun onDraw(c:Canvas){val p=Path();p.moveTo(width*.72f,height*.2f);p.lineTo(width*.28f,height*.5f);p.lineTo(width*.72f,height*.8f);c.drawPath(p,pen)}
  },LinearLayout.LayoutParams(dp(14),dp(24)))
  back.addView(text("返回",16,blue),LinearLayout.LayoutParams(-2,-2).apply{leftMargin=dp(3)})
  nav.addView(back,FrameLayout.LayoutParams(dp(82),-1,Gravity.START));root.addView(nav,LinearLayout.LayoutParams(-1,dp(56)))
  val scroll=ScrollView(activity)
  body=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(24))}
  scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));d.setContentView(root)
  val profiles=try{WearerVoiceStore.listProfiles(activity)}catch(e:Exception){addCard(text("声纹读取失败："+e.message,14,red));return}
  if(profileId==null || profiles.none{it.id==profileId})profileId=profiles.firstOrNull()?.id
  val profile=profiles.firstOrNull{it.id==profileId}
  val intro=card()
  intro.addView(text("同一批验证样本，对比初始模板与补充模板",18).apply{setTypeface(null,Typeface.BOLD)})
  intro.addView(text("请用未参与学习的独立录音。本人样本用于统计漏认，其他人样本用于统计被误认成本人的次数。验证过程只做本地比较，样本不会补入声纹库。",13,grey).apply{setPadding(0,dp(8),0,dp(12))})
  intro.addView(button(profile?.let{"测试人物："+it.name}?:"先登记声纹"){
   requireIdle{
    if(profiles.isEmpty()){Toast.makeText(activity,"请返回声纹管理，先录制并保存一个人物",Toast.LENGTH_LONG).show();return@requireIdle}
    val labels=profiles.map{p->p.name+if(profiles.count{it.name==p.name}>1)" · "+p.id.takeLast(4) else ""}
    activity.hapticDialog().setTitle("选择已登记的人物").setItems(labels.toTypedArray()){dlg,index->
     activity.dialogTap(dlg);profileId=profiles[index].id;output="";render()
    }.setNegativeButton("取消"){dlg,_->activity.dialogTap(dlg)}.show()
   }
  })
  intro.addView(text("当前模型："+SpeakerModels.name(modelKey())+"；在设置中切换后可对同一组验证样本再次比较。",13,grey).apply{setPadding(0,dp(10),0,0)})
  addCard(intro)
  val actions=card()
  statusView=text(state,13,blue);actions.addView(statusView)
  val row=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
  recordButton=button(if(recording.get())"结束验证录音" else "录制验证样本",true){
   if(recording.get()){recording.set(false);state="正在结束录音并提取验证声纹…";statusView.text=state}
   else requireIdle{chooseLabel(false)}
  }
  row.addView(recordButton,LinearLayout.LayoutParams(0,-2,1f))
  row.addView(button("导入WAV"){requireIdle{chooseLabel(true)}},LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(10)})
  actions.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
  actions.addView(button("开始前后对照",true){requireIdle{evaluate()}},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
  addCard(actions)
  if(output.isNotBlank()){
   val result=card();result.addView(text("对照结果",18).apply{setTypeface(null,Typeface.BOLD)})
   result.addView(text(output,14).apply{setTextIsSelectable(true);setPadding(0,dp(10),0,dp(8))})
   result.addView(button("复制结果"){
    activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("声纹学习前后对照",output))
    Toast.makeText(activity,"已复制",Toast.LENGTH_SHORT).show()
   });addCard(result)
  }
  val cases=try{profile?.let{VoiceLearningEvaluation.list(activity,it.id)}?:emptyList()}catch(e:Exception){addCard(text("验证集读取失败："+e.message,14,red));return}
  val list=card();list.addView(text("验证样本（"+cases.size+"）",18).apply{setTypeface(null,Typeface.BOLD)})
  if(cases.isEmpty())list.addView(text("先各添加几条本人和其他人的单人语音。每段3～30秒，建议使用不同语句、语气、距离。",13,grey).apply{setPadding(0,dp(8),0,0)})
  for((i,item) in cases.withIndex()){
   val line=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(10),0,0)}
   line.addView(text((i+1).toString()+". "+(if(item.self)"本人" else "他人")+" · "+item.title+"\n"+if(item.status=="quality_rejected")"未通过门控："+item.reason else "有效语音"+String.format(java.util.Locale.ROOT,"%.1f",item.speechSeconds)+"秒",14),LinearLayout.LayoutParams(0,-2,1f))
   line.addView(text("删除",14,red).apply{minHeight=dp(44);gravity=Gravity.CENTER;setPadding(dp(10),0,0,0);onHapticClick{
    requireIdle{activity.hapticDialog(true).setTitle("删除这条验证样本？").setNegativeButton("取消"){dlg,_->activity.dialogTap(dlg)}
     .setPositiveButton("删除"){dlg,_->activity.dialogTap(dlg);try{VoiceLearningEvaluation.delete(activity,item.id);output="";render()}catch(e:Exception){Toast.makeText(activity,e.message,Toast.LENGTH_LONG).show()}}.show()}
   }},LinearLayout.LayoutParams(-2,-2));list.addView(line)
  };addCard(list)
 }
 private fun chooseLabel(import:Boolean){
  val id=profileId?:run{Toast.makeText(activity,"请先登记并选择人物",Toast.LENGTH_SHORT).show();return}
  val p=WearerVoiceStore.listProfiles(activity).firstOrNull{it.id==id}?:return
  activity.hapticDialog().setTitle("这段录音中的人是？").setMessage("请确认只有一个人在说话，且是独立于登记、补录和已确认学习片段的录音。")
   .setPositiveButton("本人："+p.name){dlg,_->activity.dialogTap(dlg);pendingSelf=true;pendingProfileId=id;if(import)pickFile() else requestRecord()}
   .setNeutralButton("其他人"){dlg,_->activity.dialogTap(dlg);pendingSelf=false;pendingProfileId=id;if(import)pickFile() else requestRecord()}
   .setNegativeButton("取消"){dlg,_->activity.dialogTap(dlg)}.show()
 }
 private fun pickFile(){
  val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="audio/*"}
  try{activity.startActivityForResult(intent,REQUEST)}
  catch(_:Exception){Toast.makeText(activity,"无法打开系统文件选择器",Toast.LENGTH_LONG).show()}
 }
 fun onActivityResult(request:Int,result:Int,data:Intent?):Boolean {
  if(request!=REQUEST)return false
  val uri=data?.data
  if(result!=Activity.RESULT_OK || uri==null){pendingSelf=null;pendingProfileId=null;return true}
  if(dialog?.isShowing!=true)return true
  val id=pendingProfileId?:return true;val self=pendingSelf?:return true
  var title="WAV验证样本"
  try{activity.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())title=it.getString(0)}}catch(_:Exception){}
  val source=uri.toString()
  val controls=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(6),dp(22),dp(6))}
  val start=EditText(activity).apply{hint="起始秒数";setText("0");inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;styleDialogField()}
  val length=EditText(activity).apply{hint="时长（3～30秒）";setText("10");inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;styleDialogField()}
  controls.addView(text("选择单人独立发言区间",14));controls.addView(start);controls.addView(length)
  val box=activity.hapticDialog().setTitle(title).setView(controls).setNegativeButton("取消"){dlg,_->activity.dialogTap(dlg)}.setPositiveButton("加入验证集",null).create()
  box.setOnShowListener{box.getButton(android.app.AlertDialog.BUTTON_POSITIVE).onHapticClick{
   val begin=start.text.toString().toDoubleOrNull();val duration=length.text.toString().toDoubleOrNull()
   if(begin==null||!begin.isFinite()||begin<0||duration==null||!duration.isFinite()||duration !in 3.0..30.0){length.error="请输入有效起始时间和3～30秒时长";return@onHapticClick}
   box.dismiss();addImported(uri,source,title,id,self,begin,duration)
  }};box.show();return true
 }
 private fun addImported(uri:Uri,source:String,title:String,id:String,self:Boolean,start:Double,length:Double){
  worker("正在本地检查单人语音并提取验证声纹…"){
   val samples=(activity.contentResolver.openInputStream(uri)?:error("无法打开WAV")).use{WavReader.read16k(it)}
   val begin=(start*RATE).roundToLong();val count=(length*RATE).roundToLong()
   require(begin<samples.size && begin+count<=samples.size){"所选区间超出文件时长，请缩短或调整起点"}
   val segment=samples.copyOfRange(begin.toInt(),(begin+count).toInt())
   val range=String.format(java.util.Locale.ROOT," [%.1f–%.1f秒]",start,start+length)
   val saved=VoiceLearningEvaluation.add(activity.applicationContext,id,self,source,title+range,begin,segment)
   if(saved.status=="quality_rejected")"已记录未通过门控的样本："+saved.reason else "已加入"+(if(self)"本人" else "他人")+"验证样本"
  }
 }
 private fun requestRecord(){
  if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
   activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),PERMISSION);return
  }
  startRecording()
 }
 fun onPermissionResult(granted:Boolean){
  if(dialog?.isShowing!=true)return
  if(granted && pendingProfileId!=null)requestRecord() else{state="需要麦克风权限才能录制";render()}
 }
 private fun startRecording(){
  val id=pendingProfileId?:return;val self=pendingSelf?:return
  if(!working.compareAndSet(false,true))return
  val epoch=generation;discard=false;recording.set(true);state="正在准备麦克风…";render()
  Thread{
   var mic:AudioRecord?=null
   try{
    val buffer=ShortArray(RATE*30);var used=0;var next=0L
    val minimum=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
    require(minimum>0){"设备不支持16kHz录音"}
    mic=AudioRecord(MediaRecorder.AudioSource.MIC,RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum,6400))
    check(mic.state==AudioRecord.STATE_INITIALIZED){"麦克风初始化失败"}
    mic.startRecording();check(mic.recordingState==AudioRecord.RECORDSTATE_RECORDING){"麦克风启动失败"}
    while(recording.get()&&!discard&&used<buffer.size){
     val n=mic.read(buffer,used,minOf(3200,buffer.size-used));check(n>=0){"录音读取失败"};used+=n
     val now=android.os.SystemClock.elapsedRealtime()
     if(now>=next){next=now+500;val seconds=used/RATE;activity.runOnUiThread{if(epoch==generation&&!discard){state="正在录制"+(if(self)"本人" else "他人")+"验证 · "+seconds+"秒，点击结束";statusView.text=state}}}
    }
    mic.stop();mic.release();mic=null;recording.set(false)
    require(!discard){"录音已中断，未保存"}
    val samples=FloatArray(used){buffer[it]/32768f}
    activity.runOnUiThread{if(epoch==generation){state="正在本地检查单人语音并提取声纹…";statusView.text=state}}
    val title="手机验证录音 "+java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.ROOT).format(java.util.Date())
    val saved=VoiceLearningEvaluation.add(activity.applicationContext,id,self,"evaluation-recording:"+UUID.randomUUID(),title,0,samples)
    state=if(saved.status=="quality_rejected")"已记录未通过门控的样本："+saved.reason else "已保存"+(if(self)"本人" else "他人")+"验证样本"
   }catch(t:Throwable){state=t.message?:"验证录音失败"}
   finally{
    recording.set(false);try{mic?.stop()}catch(_:Throwable){};mic?.release();working.set(false)
    activity.runOnUiThread{if(dialog?.isShowing==true&&epoch==generation)render()}
   }
  } .start()
 }
 private fun worker(hint:String,task:()->String){
  if(!working.compareAndSet(false,true))return
  val epoch=generation;discard=false;state=hint;render()
  Thread{
   try{val message=task();if(epoch==generation)state=message}catch(t:Throwable){if(epoch==generation)state=t.message?:"本地处理失败"}
   finally{working.set(false);activity.runOnUiThread{if(dialog?.isShowing==true&&epoch==generation)render()}}
  }.start()
 }
 private fun evaluate(){
  val id=profileId?:return
  worker("正在使用初始模板和补充模板对照…"){
   val result=VoiceLearningEvaluation.run(activity.applicationContext,id,modelKey(),{discard}){
    done,total->activity.runOnUiThread{if(dialog?.isShowing==true){state="本地对照 "+done+"/"+total;statusView.text=state}}
   }
   output=VoiceLearningEvaluation.report(result)
   "对照完成。验证样本未写入学习库。"
  }
 }
}
