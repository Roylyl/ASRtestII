package org.hotwordtest

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.util.zip.ZipInputStream
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.*
import android.view.*
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity:Activity(){
 private val blue=Color.rgb(0,122,255); private val bg=Color.rgb(242,242,247)
 private val groups get() = WordPacks.groups
 private val prefs by lazy { getSharedPreferences("hotword_test",0) }
 private val cloudSettings by lazy { CloudSettings(applicationContext) }
 private val updateUi by lazy { UpdateUi(this) }
 private val active=AtomicBoolean(false); @Volatile private var destroyed=false; @Volatile private var diarizationRunning=false
 private var page=0; private var status="准备就绪"; private var transcript:CharSequence="点击“开始手机录音”，或导入WAV/ZIP。"; @Volatile private var startRequested=false
 private var instantPreview=""
 private lateinit var instantView:TextView
 private var polishText="完成转写后，可使用设置中的云端模型勘误。"
 private var summaryText="勘误完成后在此显示摘要。"
 private var polishPhase=0
 private var completedEdit:TranscriptReview.Edited?=null
 private var editTarget:Pair<String,String>?=null
 private var importedDraft=false
 private var readingDraft=false
 @Volatile private var polishRunning=false
 @Volatile private var recognitionBusy=false
 @Volatile private var reviewRows:List<TranscriptReview.Row> = emptyList()
 private var polishWorker:Thread?=null

 private var recognitionSource="手机麦克风或导入文件"
 private var reviewNote=""
 private fun sourceStatus(value:String)=(if(recognitionSource.isEmpty())value else "来源：$recognitionSource · $value")+
  if(reviewNote.isEmpty())"" else " · $reviewNote"
 private fun beginRecognition(source:String){
  recognitionSource=source
  reviewNote=""
  recognitionBusy=true
  reviewRows=emptyList()
  completedEdit=null
  importedDraft=false
  editTarget=null
  polishText="等待本次转写完成"
  summaryText="勘误完成后在此显示摘要。"
 }
 private fun idleForReview() = !recognitionBusy && !active.get() && !startRequested &&
  !diarizationRunning && !polishRunning && !readingDraft && !destroyed
 private fun hasReviewMaterial() = reviewRows.isNotEmpty() || importedDraft
 private fun reusableEdit(target:Pair<String,String>)=completedEdit?.takeIf{importedDraft || editTarget==target}

 private fun cloudReady(settings:CloudSettings.Summary)=settings.hasApiKey &&
  settings.endpoint.isNotBlank() && settings.model.isNotBlank()

 private fun offerCloudSettings(){
  hapticDialog().setTitle("请先接入云端模型")
   .setMessage("请到设置填写服务地址、API Key和模型名称。保存后回到测试页，"+if(importedDraft)"点击“重新生成摘要”。" else "再开始勘误并总结。")
   .setNegativeButton("稍后"){dialog,_->dialogTap(dialog)}
   .setPositiveButton("去设置"){dialog,_->
    dialogTap(dialog)
    page=3
    render()
    openCloudModelSettings()
   }.show()
 }

 // Old releases could copy the bundled weights here when the asset could not be memory mapped.
 private fun clearRetiredModelCache(){
  val folder=File(noBackupFilesDir,"qwen")
  if(!folder.isDirectory)return
  folder.listFiles()?.filter{it.isFile&&(it.extension in setOf("gguf","part","tmp"))}?.forEach{it.delete()}
  folder.delete()
 }

 private fun updatePolishUi(change:()->Unit){
  runOnUiThread{if(!destroyed){change();refreshPolishUi()}}
 }

 private fun displayRows(rows:List<TranscriptReview.Row>):List<TranscriptReview.Row>{
  val names=TranscriptPresentation.Names()
  return rows.map{it.copy(prefix=names.line(it.prefix))}
 }
 private fun startPolish(){
  if(!idleForReview())return
  val settings=cloudSettings.summary()
  if(!cloudReady(settings)){offerCloudSettings();return}
  if(!hasReviewMaterial())return
  val rows=displayRows(reviewRows)
  val target=settings.endpoint to settings.model
  val previous=reusableEdit(target)
  polishRunning=true
  polishPhase=if(previous==null)1 else 2
  if(previous==null)polishText="正在请求云端模型勘误…"
  summaryText=if(previous==null)"等待转写勘误完成。" else "正在用已有勘误稿重新生成摘要…"
  refreshPolishUi()
  polishWorker=Thread{
   val cancelled={destroyed || Thread.currentThread().isInterrupted}
   var correction=previous
   try{
    val client=CloudChatClient(cloudSettings)
    var correctionPart=""
    val correctionProgress=CloudChatClient.Progress(
     thinking={val hint="${correctionPart}模型正在思考勘误内容…";updatePolishUi{polishText=hint}},
     writing={val hint="${correctionPart}正在接收校对结果…";updatePolishUi{polishText=hint}}
    )
    val correct=TranscriptReview.Completion {system,user,stop->
     android.util.Log.i("CloudReview","task=correction request chars="+user.length)
     val response=client.complete(system,user,0,stop,correctionProgress)
     android.util.Log.i("CloudReview","task=correction completed reasoning="+response.reasoningContentPresent+" prompt_tokens="+response.promptTokens)
     response.text
    }
    val edited=previous?:TranscriptReview.correct(correct,rows,cancelled){_,total->
     correctionPart=if(total==1)"" else "全文超出上下文，分块处理 · "
     val hint="${correctionPart}正在结合全文勘误…"
     updatePolishUi{polishText=hint}
    }
    correction=edited
    updatePolishUi{
     polishText=TranscriptReview.render(edited.rows)
     reviewNote=edited.warning
     if(page==0&&::statusView.isInitialized)statusView.text=sourceStatus(status)
     polishPhase=2
     summaryText="正在根据勘误稿生成内容摘要…"
    }
    val partial=StringBuilder()
    var nextPreview=0L
    val progress=CloudChatClient.Progress(
     thinking={updatePolishUi{summaryText="模型正在结合全部内容深度思考总体摘要…"}},
     content={delta->
      partial.append(delta)
      val now=android.os.SystemClock.elapsedRealtime()
      if(now>=nextPreview){nextPreview=now+180;val preview=partial.toString();updatePolishUi{summaryText=preview}}
     }
    )
    val summarize=TranscriptReview.Completion {system,user,stop->
     partial.setLength(0)
     android.util.Log.i("CloudReview","task=summary request chars="+user.length)
     val response=client.complete(system,user,0,stop,progress)
     android.util.Log.i("CloudReview","task=summary completed reasoning="+response.reasoningContentPresent+" prompt_tokens="+response.promptTokens)
     response.text
    }
    val extract=TranscriptReview.Completion {system,user,stop->
     android.util.Log.i("CloudReview","task=summary_internal request chars="+user.length)
     client.complete(system,user,0,stop,CloudChatClient.Progress(thinking={})).text
    }
    val summary=TranscriptReview.summarize(summarize,edited.rows,cancelled,{hint->updatePolishUi{summaryText=hint}},extract)
    updatePolishUi{summaryText=summary;completedEdit=edited;editTarget=target}
   }catch(e:Throwable){
    val completed=correction
    updatePolishUi{
     if(completed==null){polishText="勘误未完成：${e.message?:e.javaClass.simpleName}";summaryText="等待勘误完成。"}
     else{
      completedEdit=completed;editTarget=target
      summaryText="摘要未完成：${e.message?:e.javaClass.simpleName}。再次点击可重试摘要。"
     }
    }
   }finally{updatePolishUi{polishRunning=false;polishPhase=0;polishWorker=null}}
  }.apply{start()}
 }
 private val voiceEnrollment by lazy { VoiceEnrollmentDialog(this,{selectedSpeakerModel()},{idleForReview()},{if(!destroyed&&(page==2||page==3))render()}) }
 private lateinit var content:LinearLayout; private lateinit var statusView:TextView; private lateinit var resultView:TextView
 private lateinit var ramView:TextView
 private var ramText="RAM占用：— MB\n本机：— / — MB"
 private var ramMonitor:java.util.concurrent.ScheduledExecutorService?=null
 override fun onStart(){
  super.onStart()
  val worker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
  val manager=getSystemService(android.app.ActivityManager::class.java)
  val info=android.app.ActivityManager.MemoryInfo()
  ramMonitor=worker
  worker.scheduleWithFixedDelay({
   val pssKiB=android.os.Debug.getPss()
   manager.getMemoryInfo(info)
   fun mb(bytes:Long)=(bytes.coerceAtLeast(0)+500000L)/1000000L
   val appMb=if(pssKiB>0)mb(pssKiB*1024).toString() else "—"
   val reading="RAM占用：$appMb MB\n本机：${mb(info.totalMem-info.availMem)} / ${mb(info.totalMem)} MB"
   runOnUiThread{
    if(ramMonitor===worker && !destroyed){
     ramText=reading
     if(::ramView.isInitialized && ramView.text!=reading)ramView.text=reading
    }
   }
  },0,2,java.util.concurrent.TimeUnit.SECONDS)
 }
 override fun onResume(){super.onResume();updateUi.resume()}
 override fun onPause(){updateUi.pause();super.onPause()}
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
 private fun speakerThreshold():Double=prefs.getInt("speaker_group_threshold",SpeakerDefaults.NATIVE_SIMILARITY_PERCENT).coerceIn(0,100)/100.0
 private fun speakerMatchThreshold():Double=prefs.getInt("speaker_match_threshold",SpeakerDefaults.MATCH_PERCENT).coerceIn(0,100)/100.0
 private fun speakerNewMaxSimilarity():Double=prefs.getInt("speaker_new_max_similarity",SpeakerDefaults.NEW_MAX_PERCENT).coerceIn(0,100)/100.0
 private fun speakerMinMargin():Double=prefs.getInt("speaker_min_margin",SpeakerDefaults.MARGIN_PERCENT).coerceIn(0,100)/100.0
 private fun speakerDecisionConfig():SpeakerDecisionConfig=SpeakerDecisionConfig(
  strongMatch=speakerMatchThreshold(),weakMatch=SpeakerDefaults.WEAK_MATCH_PERCENT/100.0,
  newSpeakerMax=speakerNewMaxSimilarity(),ambiguityMargin=speakerMinMargin())
 private fun speakerDecisionJson(config:SpeakerDecisionConfig):org.json.JSONObject=org.json.JSONObject()
  .put("strong_match",config.strongMatch).put("weak_match",config.weakMatch)
  .put("new_speaker_max_similarity",config.newSpeakerMax).put("ambiguity_margin",config.ambiguityMargin)
  .put("status","experimental_uncalibrated")
 // Initialize missing defaults only; never overwrite values from older installations.
 private fun initializeSpeakerPipelineDefaults(){
  if(prefs.getBoolean("speaker_pipeline_defaults_1_10",false))return
  val edit=prefs.edit()
  if(!prefs.contains("file_vad"))edit.putBoolean("file_vad",true)
  if(!prefs.contains("speaker_enabled"))edit.putBoolean("speaker_enabled",true)
  if(!prefs.contains("live_speaker_turns"))edit.putBoolean("live_speaker_turns",true)
  if(!prefs.contains("vad_model"))edit.putString("vad_model",VadModelFactory.SILERO)
  if(!prefs.contains("speaker_model"))edit.putString("speaker_model",SpeakerModels.CAM)
  if(!prefs.contains("speaker_group_threshold"))edit.putInt("speaker_group_threshold",SpeakerDefaults.NATIVE_SIMILARITY_PERCENT)
  val saved=edit.putBoolean("speaker_pipeline_defaults_1_10",true).commit()
  if(!saved)android.util.Log.e("HotwordTest","默认声纹配置保存失败")
 }
 private fun text(s:String,size:Int=15,color:Int=Color.DKGRAY):TextView = TextView(this).apply { text=s; textSize=size.toFloat();setTextColor(color) }
 private fun round(color:Int,r:Int=18)=GradientDrawable().apply {setColor(color);cornerRadius=dp(r).toFloat()}
 private fun styleSwitch(s:Switch){s.minHeight=dp(48)}
 private inner class AppleSwitch:Switch(this){
  private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
  private val tapSlop=ViewConfiguration.get(context).scaledTouchSlop
  private var downX=0f;private var downY=0f
  init{minHeight=dp(48);background=null}
  private fun insideSwitch(x:Float,y:Float):Boolean{val h=dp(31).toFloat();val left=width-dp(51).toFloat();val right=width.toFloat();val top=(height-h)/2f;val r=h/2f;if(x<left||x>right||y<top||y>top+h)return false;val cx=x.coerceIn(left+r,right-r);val dx=x-cx;val dy=y-(top+r);return dx*dx+dy*dy<=r*r}
  override fun onDraw(c:Canvas){
   val h=dp(31).toFloat();val w=dp(51).toFloat();val right=width.toFloat();val left=right-w;val top=(height-h)/2f
   pen.color=if(isChecked)Color.rgb(52,199,89) else Color.rgb(229,229,234);pen.style=Paint.Style.FILL;c.drawRoundRect(left,top,right,top+h,h/2,h/2,pen)
   val r=dp(13).toFloat();val x=if(isChecked)right-dp(15) else left+dp(15);val y=top+h/2
   pen.color=Color.WHITE;c.drawCircle(x,y,r,pen)
   pen.color=currentTextColor;pen.typeface=typeface;pen.textSize=textSize;c.drawText(text.toString(),paddingLeft.toFloat(),height/2f-(pen.ascent()+pen.descent())/2f,pen)
  }
  override fun onTouchEvent(e:MotionEvent):Boolean{
   if(!isEnabled)return false
   when(e.actionMasked){
    MotionEvent.ACTION_DOWN->{
     if(!insideSwitch(e.x,e.y))return false
     downX=e.x;downY=e.y;isPressed=true
    }
    MotionEvent.ACTION_MOVE->if(!insideSwitch(e.x,e.y)||kotlin.math.abs(e.x-downX)>tapSlop||kotlin.math.abs(e.y-downY)>tapSlop)isPressed=false
    MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL->isPressed=false
    MotionEvent.ACTION_UP->{
     val click=isPressed&&insideSwitch(e.x,e.y)&&kotlin.math.abs(e.x-downX)<=tapSlop&&kotlin.math.abs(e.y-downY)<=tapSlop
     isPressed=false
     if(click)performClick()
    }
   }
   return true
  }
  override fun performClick():Boolean{
   if(!isEnabled)return false
   tapFeedback()
   return super.performClick()
  }
 }
 private inner class WeightSlider:SeekBar(this){
  private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
  init{max=3;thumbOffset=0;minimumHeight=dp(48);background=null;elevation=0f;stateListAnimator=null}
  override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);setPadding(w/8,0,w/8,0)}
  override fun onDraw(canvas:Canvas){
   val left=paddingLeft.toFloat();val right=(width-paddingRight).toFloat();val cy=height/2f;val radius=dp(15).toFloat();val start=left-radius;val end=right+radius
   paint.style=Paint.Style.FILL;paint.color=Color.rgb(233,233,235);canvas.drawRoundRect(start,cy-radius,end,cy+radius,radius,radius,paint)
   val x=left+(right-left)*progress/3f
   paint.color=Color.rgb(59,130,246);canvas.save();val clip=Path().apply{addRoundRect(RectF(start,cy-radius,end,cy+radius),radius,radius,Path.Direction.CW)};canvas.clipPath(clip);canvas.drawRect(start,cy-radius,x,cy+radius,paint);canvas.restore()
   for(i in 0..3){paint.color=if(i<=progress)Color.rgb(166,203,255) else Color.rgb(180,180,185);canvas.drawCircle(left+(right-left)*i/3f,cy,dp(2).toFloat(),paint)}
   paint.color=Color.WHITE;canvas.drawCircle(x,cy,dp(15).toFloat(),paint)
  }
 }
 private inner class ThresholdSlider:SeekBar(this){
  private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
  init{max=100;thumbOffset=0;minimumHeight=dp(48);background=null;elevation=0f;stateListAnimator=null;setPadding(dp(16),0,dp(16),0)}
  override fun onDraw(c:Canvas){
   val left=paddingLeft.toFloat();val right=(width-paddingRight).toFloat();val y=height/2f;val r=dp(3).toFloat();val x=left+(right-left)*progress/100f
   paint.color=Color.rgb(229,229,234);c.drawRoundRect(left,y-r,right,y+r,r,r,paint)
   paint.color=blue;c.drawRoundRect(left,y-r,x.coerceAtLeast(left),y+r,r,r,paint)
   paint.color=Color.WHITE;c.drawCircle(x,y,dp(13).toFloat(),paint)
   paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1).toFloat();paint.color=Color.rgb(213,213,218);c.drawCircle(x,y,dp(13).toFloat(),paint);paint.style=Paint.Style.FILL
  }
 }
 private fun card():LinearLayout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;background=round(Color.WHITE);setPadding(dp(18),dp(16),dp(18),dp(16));layoutParams=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(12)} }
 private fun button(label:String,primary:Boolean=false,action:()->Unit):TextView=text(label,16,if(primary) Color.WHITE else blue).apply { gravity=Gravity.CENTER;minHeight=dp(50);setPadding(dp(12),dp(12),dp(12),dp(12));background=round(if(primary) blue else Color.rgb(235,244,255),12);setTypeface(null,Typeface.BOLD);onHapticClick{action()} }
 private fun tabIcon(drawable:String,selected:Boolean):ImageView{
  // This standalone APK build links resources without generating an R class.
  val name=if(selected)drawable+"_selected" else drawable
  val iconId=resources.getIdentifier(name,"drawable",packageName)
  return ImageView(this).apply{
   setImageResource(iconId)
   imageTintList=android.content.res.ColorStateList.valueOf(if(selected)blue else Color.rgb(116,116,124))
   scaleType=ImageView.ScaleType.FIT_CENTER
   importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
  }
 }
 override fun onCreate(b:Bundle?){super.onCreate(b);clearRetiredModelCache();initializeSpeakerPipelineDefaults();try{FsmnVad.configure(this)}catch(e:Throwable){android.util.Log.e("HotwordTest","FSMN VAD初始化失败",e);status="FSMN VAD初始化失败：${e.message?:e.javaClass.simpleName}"};window.statusBarColor=bg;window.navigationBarColor=Color.WHITE;window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;render();if(intent.getBooleanExtra("probe",false)) runProbe();if(intent.getBooleanExtra("batchProbe",false)) startBatch(Uri.fromFile(File(getExternalFilesDir(null),"batch-probe.zip")),"batch-probe.zip",true);if(intent.getBooleanExtra("liveReplayProbe",false))runLiveReplayProbe();if(intent.getBooleanExtra("speakerProbe",false))runSpeakerProbe(this);if(intent.getBooleanExtra("diarizationProbe",false))DiarizationTest.runProbe(this);if(intent.getBooleanExtra("vadComparisonProbe",false))VadComparisonProbe.run(this);if(intent.getBooleanExtra("alignmentProbe",false))TimeAlignedAsr.runProbe(this){ensureModels()};if(intent.getBooleanExtra("hourComparisonProbe",false))HourComparisonProbe.start(this,intent.getStringExtra("hourRunId")?:"",intent.getStringExtra("hourVad")?:"",intent.getStringExtra("hourSpeaker")?:"",{ensureModels()},{setStatus(it)});if(intent.getBooleanExtra("optimizedSpeakerProbe",false))OptimizedSpeakerProbe.start(this){setStatus(it)};if(intent.getBooleanExtra("liveSpeakerProbe",false))LiveSpeakerProbe.run(this){ensureModels()};if(intent.getBooleanExtra("longProbe",false))LongAudioProbe.start(this,intent.getStringExtra("longRunId")?:"",{ensureModels()},{setStatus(it)})}
 private fun render(){
  val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(bg)}
  val header=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(14),dp(20),dp(8))}
  val titleRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
  titleRow.addView(text("ASRtestII",25,Color.BLACK).apply{setTypeface(null,Typeface.BOLD)},LinearLayout.LayoutParams(0,-2,1f))
  ramView=text(ramText,12,Color.DKGRAY).apply{gravity=Gravity.END;maxLines=2;typeface=Typeface.MONOSPACE}
  titleRow.addView(ramView);header.addView(titleRow)
  header.addView(text("X-ASR1920ms · 本地离线识别",12,blue));root.addView(header)
  val scroller=ScrollView(this);content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))};scroller.addView(content);root.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
  when(page){0->showTest();1->showWords();2->showVoiceSettings();else->showSettings()}
  val divider=View(this).apply{setBackgroundColor(Color.rgb(221,221,225))};root.addView(divider,LinearLayout.LayoutParams(-1,dp(1)))
  val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(dp(12),dp(3),dp(12),dp(3));setBackgroundColor(Color.WHITE)}
  val tabs=listOf("测试" to "nav_mic","热词" to "nav_tag","声纹" to "nav_voice","设置" to "nav_settings")
  tabs.forEachIndexed { i,(name,drawable)->
   val selected=page==i
   val item=LinearLayout(this).apply{
    orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
    contentDescription=name;isSelected=selected;isFocusable=true
    onHapticClick{page=i;render()}
   }
   item.addView(tabIcon(drawable,selected),LinearLayout.LayoutParams(dp(28),dp(28)))
   item.addView(text(name,11,if(selected)blue else Color.rgb(116,116,124)).apply{
    gravity=Gravity.CENTER;setPadding(0,dp(3),0,0)
    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
   })
   nav.addView(item,LinearLayout.LayoutParams(0,-1,1f))
  }
  root.addView(nav,LinearLayout.LayoutParams(-1,dp(64)));setContentView(root)
 }
 private lateinit var polishResultView:TextView
 private lateinit var summaryResultView:TextView
 private lateinit var polishAction:TextView
 private lateinit var draftAction:TextView
 private lateinit var recordAction:TextView
 private lateinit var importAction:TextView

 private fun addResultBox(group:LinearLayout,value:String,height:Int,followTail:Boolean):TextView {
  val box=ScrollView(this).apply{isNestedScrollingEnabled=true}
  val output=text(value,17,Color.BLACK).apply{
   setTextIsSelectable(true)
   setPadding(0,dp(10),0,dp(10))
   setLineSpacing(dp(2).toFloat(),1f)
  }
  box.addView(output)
  group.addView(box,LinearLayout.LayoutParams(-1,dp(height)))
  if(followTail){
   var follow=transcript is StringBuilder
   box.viewTreeObserver.addOnGlobalLayoutListener{
    if(follow){box.scrollTo(0,output.height);follow=false}
   }
   output.addTextChangedListener(object:android.text.TextWatcher{
   override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){
    follow=!box.canScrollVertically(1)&&output.selectionStart==output.selectionEnd
   }
   override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}
   override fun afterTextChanged(s:android.text.Editable?){}
  })
  }
  return output
 }
 private var expandedKind:String?=null
 private var expandedOutput:TextView?=null
 private fun expandedValue(kind:String):String=when(kind){
  "识别结果"->resultView.text.toString()
  "转写勘误"->if(::polishResultView.isInitialized)polishResultView.text.toString() else polishText
  else->summaryText
 }
 private fun refreshExpanded(){
  val view=expandedOutput?:return
  val value=expandedValue(expandedKind?:return)
  if(view.text.toString()!=value)view.text=value
 }
 private fun openExpanded(kind:String){
  val output=text(expandedValue(kind),17,Color.BLACK).apply{
   setTextIsSelectable(true);setPadding(dp(20),dp(8),dp(20),dp(16));setLineSpacing(dp(5).toFloat(),1f)
  }
  val scroll=ScrollView(this).apply{addView(output);isFillViewport=true}
  val host=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(scroll,LinearLayout.LayoutParams(-1,dp((resources.configuration.screenHeightDp*0.68f).toInt())))}
  val dialog=hapticDialog().setTitle(kind).setView(host)
   .setNeutralButton("复制全部"){d,_->dialogTap(d)
    val value=expandedValue(kind)
    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(kind,value))
    Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show()
   }.setPositiveButton("关闭"){d,_->dialogTap(d)}.create()
  expandedKind=kind;expandedOutput=output
  dialog.setOnDismissListener{expandedOutput=null;expandedKind=null}
  dialog.show()
 }
 private fun resultHeading(title:String):LinearLayout=LinearLayout(this).apply{
  orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
  addView(text(title,18,Color.BLACK).apply{setTypeface(null,Typeface.BOLD)},LinearLayout.LayoutParams(0,-2,1f))
  addView(text("展开",14,blue).apply{
   minWidth=dp(48);minHeight=dp(44);gravity=Gravity.CENTER;contentDescription="展开$title";onHapticClick{openExpanded(title)}
  },LinearLayout.LayoutParams(-2,-2))
 }
 private fun showTest(){
  val screenHeight=resources.configuration.screenHeightDp
  val recognition=card()
  recognition.addView(resultHeading("识别结果"))
  statusView=text(sourceStatus(status),13,blue).apply{setPadding(0,dp(4),0,0)}
  recognition.addView(statusView)
  instantView=text(instantPreview,16,Color.DKGRAY).apply{visibility=if(instantPreview.isEmpty())View.GONE else View.VISIBLE}
  recognition.addView(instantView)
  resultView=addResultBox(recognition,TranscriptPresentation.render(transcript.toString()),(screenHeight/4).coerceIn(112,176),true)
  val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
  recordAction=button(if(active.get()||startRequested)"结束识别" else "开始手机录音",true){
   if(active.get()||startRequested)stop() else requestStart()
  }
  importAction=button("导入WAV/ZIP"){selectFile()}
  actions.addView(recordAction,LinearLayout.LayoutParams(0,-2,1f))
  actions.addView(importAction,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(10)})
  recognition.addView(actions)
  content.addView(recognition)

  val language=card()
  language.addView(text("云端勘误与摘要",18,Color.BLACK).apply{setTypeface(null,Typeface.BOLD)})
  language.addView(text("点击开始后，会将转写文字和说话人标签发送到你设置的服务。",13,Color.DKGRAY))
  draftAction=text("打开已有勘误稿",14,blue).apply{
   gravity=Gravity.CENTER_VERTICAL;minHeight=dp(44);isFocusable=true
   onHapticClick{selectDraft()}
  }
  language.addView(draftAction)
  fun stage(title:String,value:String):TextView{
   val heading=resultHeading(title).apply{setPadding(0,dp(8),0,0)}
   language.addView(heading)
   val panel=LinearLayout(this).apply{
    orientation=LinearLayout.VERTICAL
    background=round(bg,10)
    setPadding(dp(10),0,dp(10),0)
   }
   language.addView(panel)
   return addResultBox(panel,value,(screenHeight/7).coerceIn(78,112),false)
  }
  polishResultView=stage("转写勘误",polishText)
  summaryResultView=stage("内容摘要",summaryText)
  polishAction=button("开始勘误并总结",true){startPolish()}
  language.addView(polishAction,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
  content.addView(language)
  refreshPolishUi()
 }
 private fun refreshPolishUi(){
  if(destroyed||page!=0||!::polishAction.isInitialized)return
  val visibleText=if(polishText=="等待本次转写完成" && !recognitionBusy){
   if(reviewRows.isEmpty())"本次没有可勘误的完整转写。" else "转写已完成，可以开始勘误。"
  }else polishText
  if(polishResultView.text.toString()!=visibleText)polishResultView.text=visibleText
  if(summaryResultView.text.toString()!=summaryText)summaryResultView.text=summaryText
  val settings=cloudSettings.summary()
  val reusable=reusableEdit(settings.endpoint to settings.model)!=null
  polishAction.text=when(polishPhase){
   1->"正在勘误…"
   2->"正在总结…"
   else->if(reusable)"重新生成摘要" else "开始勘误并总结"
  }
  val recognizing=active.get()||startRequested||diarizationRunning
  recordAction.text=if(recognizing)if(recognitionSource=="手机麦克风")"结束手机录音" else "停止识别" else "开始手机录音"
  draftAction.text=if(readingDraft)"正在读取勘误稿…" else "打开已有勘误稿"
  draftAction.isEnabled=idleForReview()
  recordAction.isEnabled=!readingDraft&&!polishRunning&&(!recognitionBusy||recognizing)
  importAction.isEnabled=!readingDraft&&!polishRunning&&!recognizing&&!recognitionBusy
  polishAction.isEnabled=idleForReview()&&(hasReviewMaterial()||!cloudReady(settings))
  listOf(recordAction,importAction,polishAction,draftAction).forEach{it.alpha=if(it.isEnabled)1f else 0.45f}
  refreshExpanded()
 }
 private fun decodeSpeakerTurn(engine:OnlineRecognizer,hotwords:String?,samples:FloatArray):String {
  val stream=engine.createStream(hotwords?:"")
  try{var p=0;while(p<samples.size){val end=minOf(p+800,samples.size);stream.acceptWaveform(samples.copyOfRange(p,end),16000);p=end;while(engine.isReady(stream))engine.decode(stream)}
   stream.acceptWaveform(FloatArray(2580*16),16000);stream.inputFinished();while(engine.isReady(stream))engine.decode(stream)
   return engine.getResult(stream).text
  }finally{stream.release()}
 }
 private fun describeSpeakerTurns(seg:VadLiveSegmenter.Segment,out:LiveSpeakerTurnSegmenter.Outcome,original:String,
                                  engine:OnlineRecognizer,hotwords:String?,speaker:LiveSpeakerLabeler?):org.json.JSONArray {
  val items=mutableListOf<Pair<LiveSpeakerTurnSegmenter.Turn,String>>()
  for(turn in out.turns){
   val text=if(out.turns.size==1)original else if(turn.audio.size<1600)"" else decodeSpeakerTurn(engine,hotwords,turn.audio)
   items.add(turn to text)
  }
  val local=linkedMapOf<Int,String>();val globalByLocal=mutableMapOf<Int,String>();val conflicted=mutableSetOf<Pair<Int,Int>>()
  for(id in out.turns.flatMap{it.localIds}.distinct().sorted()){
   val clean=items.filter{it.first.localIds==listOf(id)&&it.second.isNotBlank()}.map{it.first.audio}
   val size=clean.sumOf{it.size};var label="说话人未识别"
   if(size>=16000&&speaker!=null){
    val audio=FloatArray(size);var pos=0;for(part in clean){part.copyInto(audio,pos);pos+=part.size}
    val prior=speaker.snapshotAnonymousGroups()
    val voice=try{speaker.classify(audio,true)}catch(_:Exception){speaker.restoreAnonymousGroups(prior);null}
    val sp=voice?.optJSONObject("speaker")
    if(voice?.optJSONObject("identity")?.optString("status")=="wearer_candidate")label=voice.optString("display").ifBlank{label}
    if(sp!=null&&!sp.isNull("speaker_id")){
     val group=sp.getString("speaker_id")
     val collision=globalByLocal.filter{(other,g)->g==group&&out.turns.any{it.localIds.contains(id)&&it.localIds.contains(other)}}.keys
     if(collision.isNotEmpty()){
      speaker.restoreAnonymousGroups(prior)
      collision.forEach{other->conflicted.add(minOf(id,other) to maxOf(id,other))}
     }else{
      globalByLocal[id]=group;label=voice.optString("display").ifBlank{SpeakerTranscript.groupName(group)}
     }
    }
   }
   local[id]=label
  }
  val rows=org.json.JSONArray()
  for((turn,text) in items){
   val names=turn.localIds.map{local[it]?:"说话人未识别"}
   val collision=turn.localIds.indices.any{i->turn.localIds.indices.any{j->i<j&&conflicted.contains(minOf(turn.localIds[i],turn.localIds[j]) to maxOf(turn.localIds[i],turn.localIds[j]))}}
   val display=when(names.size){0->"说话人未识别";1->names[0];else->if(collision||names.distinct().size<names.size)"多人重叠，归属未区分" else "重叠："+names.joinToString("、"){if(it=="说话人未识别")"另有未归组声音" else it}}
   val ids=org.json.JSONArray();turn.localIds.forEach{ids.put(it)}
   rows.put(org.json.JSONObject().put("start_sample",turn.start).put("end_sample",turn.end)
    .put("start_ms",turn.start*1000/16000).put("end_ms",turn.end*1000/16000)
    .put("local_speaker_ids",ids).put("speaker_display",display).put("localized_speaker_display",true).put("text",text)
    .put("overlap_status",if(names.size>1)"predicted_overlap" else "not_confirmed")
    .put("identity_status",if(names.size>1)"ambiguous_overlap" else "unverified"))
  }
  return rows
 }
 private fun showWords(){
  val custom=card();val toggle=AppleSwitch().apply{text="自定义热词";textSize=17f;setTextColor(Color.BLACK);setTypeface(null,Typeface.BOLD);styleSwitch(this);isChecked=prefs.getBoolean("customEnabled",false);setOnCheckedChangeListener{_,checked->prefs.edit().putBoolean("customEnabled",checked).apply();invalidate()}};custom.addView(toggle,LinearLayout.LayoutParams(-1,dp(48)))
  custom.addView(text("每行一个中文、英文或中英混合词，仅保存在本机",13,Color.GRAY).apply{setPadding(0,dp(6),0,dp(8))})
  val edit=EditText(this).apply{setText(prefs.getString("custom",""));hint="例如：联系人姓名、常用地点";minLines=3;gravity=Gravity.TOP;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;background=round(Color.rgb(242,242,247),12);setPadding(dp(12),dp(12),dp(12),dp(12))};custom.addView(edit)
  custom.addView(button("保存自定义热词"){try{val lines=edit.text.toString().lines().map{it.trim()}.filter{it.isNotEmpty()};hotwordString(lines.map{it to 1f});prefs.edit().putString("custom",edit.text.toString()).apply();Toast.makeText(this,"已保存，下次识别时生效",Toast.LENGTH_SHORT).show()}catch(e:Exception){Toast.makeText(this,e.message?:"热词格式无效",Toast.LENGTH_LONG).show()}},LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(10)})
  val customLabels=listOf("默认","轻度","中度","高");val customStrength=text("权重：${customLabels[prefs.getInt("weight_custom",0).coerceIn(0,3)]}",13,Color.DKGRAY).apply{setPadding(0,dp(16),0,0)};custom.addView(customStrength)
  val customSlider=WeightSlider().apply{progress=prefs.getInt("weight_custom",0).coerceIn(0,3);contentDescription="自定义热词权重";setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,value:Int,fromUser:Boolean){customStrength.text="权重：${customLabels[value]}";invalidate();if(fromUser){prefs.edit().putInt("weight_custom",value).apply();s?.stepFeedback()}};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}})};custom.addView(customSlider,LinearLayout.LayoutParams(-1,dp(48)))
  val customMarks=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};customLabels.forEach{label->customMarks.addView(text(label,12,Color.GRAY).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))};custom.addView(customMarks);content.addView(custom)
  content.addView(text("领域词包",20,Color.BLACK).apply{setTypeface(null,Typeface.BOLD);setPadding(dp(4),dp(10),0,dp(6))})
  content.addView(text("可多选；点击词条查看详情，下次识别时生效",13,Color.GRAY).apply{setPadding(dp(4),0,0,dp(14))})
  val chosen=prefs.getStringSet("selected",emptySet())!!.toMutableSet()
  groups.forEach{(name,words)->val c=card();val list=words.split(',');val cb=AppleSwitch().apply{text=name;textSize=16f;setTextColor(Color.BLACK);styleSwitch(this);isChecked=chosen.contains(name);setOnCheckedChangeListener{_,checked->if(checked)chosen.add(name) else chosen.remove(name);prefs.edit().putStringSet("selected",chosen.toSet()).apply();invalidate()}};c.addView(cb,LinearLayout.LayoutParams(-1,dp(48)))
   var expanded=false;val preview=text("${list.size}个词 · ${list.take(3).joinToString("、")}  查看全部",13,Color.GRAY).apply{setPadding(0,dp(9),0,dp(2))};preview.onHapticClick{expanded=!expanded;preview.text=if(expanded)list.joinToString("、")+"  收起" else "${list.size}个词 · ${list.take(3).joinToString("、")}  查看全部"};c.addView(preview)
   val labels=listOf("默认","轻度","中度","高");val strength=text("权重：${labels[prefs.getInt("weight_"+name,0).coerceIn(0,3)]}",13,Color.DKGRAY).apply{setPadding(0,dp(12),0,0)};c.addView(strength)
   val slider=WeightSlider().apply{progress=prefs.getInt("weight_"+name,0).coerceIn(0,3);contentDescription="$name 热词权重";setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,value:Int,fromUser:Boolean){strength.text="权重：${labels[value]}";invalidate();if(fromUser){prefs.edit().putInt("weight_"+name,value).apply();s?.stepFeedback()}};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}})};c.addView(slider,LinearLayout.LayoutParams(-1,dp(48)))
   val marks=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};labels.forEach{label->marks.addView(text(label,12,Color.GRAY).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))};c.addView(marks);content.addView(c)}
 }
 private fun showVoiceSettings(){
  data class Setting(val key:String,val title:String,val description:String,val defaultValue:Int,
                     var saved:Int,var draft:Int,var valid:Boolean=true,var syncing:Boolean=false,
                     var field:EditText?=null,var slider:ThresholdSlider?=null,
                     var current:TextView?=null,var state:TextView?=null)
  val settings=mutableListOf<Setting>()
  val intro=card()
  intro.addView(text("说话人判断",19,Color.BLACK).apply{setTypeface(null,Typeface.BOLD)})
  intro.addView(text("分别调整已有说话人匹配、新说话人创建和多人区分。修改后点击页面下方“保存设置”，下次任务生效。",13,Color.GRAY).apply{setPadding(0,dp(10),0,0);setLineSpacing(dp(3).toFloat(),1f)})
  content.addView(intro)
  fun format(value:Int)=String.format(java.util.Locale.ROOT,"%.2f",value/100.0)
  fun refreshState(item:Setting){
   val state=item.state?:return
   state.text=if(!item.valid)"输入无效 · 当前生效${format(item.saved)}" else if(item.draft==item.saved)"已保存 · 当前生效${format(item.saved)}" else "未保存 · 当前生效${format(item.saved)}"
   state.setTextColor(if(item.valid&&item.draft==item.saved)Color.GRAY else blue)
  }
  fun parseInput(field:EditText):Int? {
   val raw=field.text.toString().trim()
   if(!raw.matches(Regex("^(?:0(?:\\.\\d{1,2})?|1(?:\\.0{1,2})?|\\.\\d{1,2})$")))return null
   return raw.toDoubleOrNull()?.let{kotlin.math.round(it*100).toInt()}
  }
  fun display(item:Setting,value:Int,haptic:View?=null){
   val v=value.coerceIn(0,100);item.draft=v;item.valid=true;item.syncing=true
   item.slider!!.progress=v;item.field!!.setText(format(v));item.syncing=false
   item.current!!.text=format(v);item.field!!.error=null;refreshState(item)
   haptic?.stepFeedback()
  }
  fun addSetting(key:String,title:String,description:String,defaultValue:Int){
   val saved=prefs.getInt(key,defaultValue).coerceIn(0,100)
   val item=Setting(key,title,description,defaultValue,saved,saved);settings.add(item)
   val c=card()
   c.addView(text(title,17,Color.BLACK).apply{setTypeface(null,Typeface.BOLD)})
   c.addView(text(description,13,Color.GRAY).apply{setPadding(0,dp(8),0,0);setLineSpacing(dp(2).toFloat(),1f)})
   val current=text("",24,Color.BLACK).apply{gravity=Gravity.CENTER;setTypeface(null,Typeface.BOLD);setPadding(0,dp(8),0,0)}
   item.current=current;c.addView(current)
   val slider=ThresholdSlider().apply{progress=saved;contentDescription=title}
   item.slider=slider;c.addView(slider,LinearLayout.LayoutParams(-1,dp(48)))
   val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(8),0,0)}
   val field=EditText(this).apply{
    gravity=Gravity.CENTER;setSingleLine(true);textSize=17f
    inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
    background=round(bg,10);setPadding(dp(5),0,dp(5),0);contentDescription="输入${title}，范围0到1"
   }
   item.field=field
   row.addView(button("−"){display(item,item.draft-1)},LinearLayout.LayoutParams(dp(52),dp(48)))
   row.addView(field,LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(10);rightMargin=dp(10)})
   row.addView(button("+"){display(item,item.draft+1)},LinearLayout.LayoutParams(dp(52),dp(48)))
   c.addView(row)
   val state=text("",13,Color.GRAY).apply{gravity=Gravity.CENTER;setPadding(0,dp(6),0,dp(2))}
   item.state=state;c.addView(state)
   field.addTextChangedListener(object:android.text.TextWatcher{
    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
     if(item.syncing)return
     val value=parseInput(field);item.valid=value!=null
     if(value!=null){item.draft=value;slider.progress=value;current.text=format(value);field.error=null}
     refreshState(item)
    }
    override fun afterTextChanged(s:android.text.Editable?){}
   })
   slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
    override fun onProgressChanged(s:SeekBar?,value:Int,fromUser:Boolean){if(fromUser)display(item,value,s)}
    override fun onStartTrackingTouch(s:SeekBar?){}
    override fun onStopTrackingTouch(s:SeekBar?){}
   })
   display(item,saved);content.addView(c)
  }
  addSetting("speaker_match_threshold","已有说话人匹配下限",
   "声纹相似度达到此值时可匹配已有说话人。已有多组时，还要比较最接近的两组得分。",SpeakerDefaults.MATCH_PERCENT)
  addSetting("speaker_new_max_similarity","新建说话人相似度上限",
   "声纹相似度低于此值时才允许新建匿名说话人。数值越低，新建越严格。",SpeakerDefaults.NEW_MAX_PERCENT)
  addSetting("speaker_min_margin","多个说话人最小分差",
   "已有多个候选组时，最佳与次佳相似度至少相差此值；也用于离线最终归组筛选。",SpeakerDefaults.MARGIN_PERCENT)
  addSetting("speaker_group_threshold","说话人切分聚类阈值",
   "仅控制说话人活动切分使用的离线聚类，与上面的声纹归组规则独立。",SpeakerDefaults.NATIVE_SIMILARITY_PERCENT)
  val controls=card()
  controls.addView(text("范围0.00—1.00，步长0.01。新建上限必须低于已有匹配下限和短片段固定下限0.40；无法满足判断条件的片段保留未确定状态。",12,Color.GRAY).apply{setLineSpacing(dp(3).toFloat(),1f)})
  controls.addView(button("保存设置",true){
   val invalid=settings.firstOrNull{!it.valid}
   val match=settings.first{it.key=="speaker_match_threshold"}
   val newMax=settings.first{it.key=="speaker_new_max_similarity"}
   if(invalid!=null){invalid.field?.error="请输入0.00至1.00，最多两位小数";Toast.makeText(this,"请检查输入",Toast.LENGTH_SHORT).show()}
   else if(newMax.draft>=minOf(match.draft,40)){newMax.field?.error="新建上限必须低于已有匹配下限和0.40";Toast.makeText(this,"新建上限必须低于已有匹配下限和0.40",Toast.LENGTH_LONG).show()}
   else {
    val edit=prefs.edit();settings.forEach{edit.putInt(it.key,it.draft)}
    if(edit.commit()){
     settings.forEach{it.saved=it.draft;display(it,it.saved);it.field?.clearFocus()}
     Toast.makeText(this,"已保存，下次任务生效",Toast.LENGTH_SHORT).show()
    }else Toast.makeText(this,"保存失败，请重试",Toast.LENGTH_LONG).show()
   }
  },LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(12)})
  controls.addView(text("CAM++和ERes2NetV2的匿名声纹短片段、低电平匹配下限均为0.40；细分模式中预测重叠时不新建或更新声纹组。普通WAV或ZIP分段未检测多人重叠，整段可能混人。已登记姓名使用独立门槛；当前仅在CAM++上验证，以上数值仍需场景校准。",12,Color.GRAY).apply{setPadding(0,dp(12),0,0);setLineSpacing(dp(3).toFloat(),1f)})
  controls.addView(text("离线整文件显示还会进行保守筛选：强片段相似度至少0.55、弱片段至少0.60，并要求至少两段独立强片段、累计至少4秒后才使用稳定编号。分差采用上方设置；证据不足显示待确认。",12,Color.GRAY).apply{setPadding(0,dp(10),0,0);setLineSpacing(dp(3).toFloat(),1f)})
  controls.addView(text("当前VAD：${VadModelFactory.name(selectedVadModel())} · 声纹模型：${SpeakerModels.name(selectedSpeakerModel())}",12,Color.GRAY).apply{setPadding(0,dp(10),0,0)})
  content.addView(controls)
  val profiles=card();profiles.addView(text("已保存的声纹",18,Color.BLACK).apply{setTypeface(null,Typeface.BOLD)})
  profiles.addView(text("管理录制的声纹和显示名称",13,Color.GRAY).apply{setPadding(0,dp(8),0,dp(8))})
  profiles.addView(button("声纹管理"){voiceEnrollment.show()});content.addView(profiles)
 }
 fun openCloudModelSettings(){CloudModelSettingsUi.open(this)}
 internal fun cloudModelSettingsSaved(){if(!destroyed)render()}

 private fun showSettings(){
  fun section(title:String):LinearLayout{
   val group=card()
   group.addView(text(title,18,Color.BLACK).apply{setTypeface(null,Typeface.BOLD)})
   content.addView(group)
   return group
  }
  fun note(group:LinearLayout,value:String):TextView{
   val label=text(value,13,Color.DKGRAY).apply{setPadding(0,dp(6),0,dp(2));setLineSpacing(dp(3).toFloat(),1f)}
   group.addView(label)
   return label
  }
  fun action(group:LinearLayout,title:String,open:()->Unit){
   group.addView(button(title,action=open),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
  }
  fun toggle(group:LinearLayout,title:String,key:String,defaultValue:Boolean=true){
   group.addView(AppleSwitch().apply{
    text=title;textSize=15f;setTextColor(Color.BLACK)
    isChecked=prefs.getBoolean(key,defaultValue)
    setOnCheckedChangeListener{_,value->prefs.edit().putBoolean(key,value).apply();invalidate()}
   },LinearLayout.LayoutParams(-1,dp(48)))
  }
  fun modelInfo(group:LinearLayout,value:String){
   val link=text("展开模型说明",14,blue).apply{gravity=Gravity.CENTER_VERTICAL;minHeight=dp(48);isFocusable=true}
   group.addView(link)
   val details=note(group,value).apply{visibility=View.GONE}
   link.onHapticClick{
    val open=details.visibility!=View.VISIBLE
    details.visibility=if(open)View.VISIBLE else View.GONE
    link.text=if(open)"收起模型说明" else "展开模型说明"
   }
  }

  val vad=section("语音活动检测")
  toggle(vad,"使用VAD分段","file_vad")
  note(vad,"开启后按停顿输出语音片段和时间。设置在下次识别时生效。")
  action(vad,"VAD模型：${VadModelFactory.name(selectedVadModel())}"){chooseVadModel()}
  modelInfo(vad,"Silero VAD（0.61MiB）和TEN VAD（0.32MiB）支持录音与文件。两者的阈值为0.5，最短语音为0.2秒，结束静音为0.6秒。\n\nFSMN VAD（1.64MiB）为实验模型，使用单独的分段规则，仅支持60秒以内的离线文件。")

  val speaker=section("说话人与声纹")
  toggle(speaker,"本地声纹区分（实验）","speaker_enabled")
  note(speaker,"需开启VAD。匿名编号显示为“说话人1”“说话人2”等。编号按本次识别结果的首次出现顺序排列。登记姓名也可能误判。")
  toggle(speaker,"细分连续说话人（实验）","live_speaker_turns")
  note(speaker,"需同时开启VAD和声纹区分。连续发言会按人细分，处理耗时可能增加。")
  action(speaker,"声纹模型：${SpeakerModels.name(selectedSpeakerModel())}"){chooseSpeakerModel()}
  action(speaker,"声纹阈值与登记"){page=2;render()}
  modelInfo(speaker,"CAM++（26.97MiB）和ERes2NetV2（68.13MiB）提取声纹特征。说话人切分使用pyannote（5.72MiB）检测活动区间。\n\n短于或等于10秒的离线切分仅输出窗口内编号。预测重叠时难以区分每句话的归属。声纹阈值需要结合录音场景校准。")

  CloudModelSettingsUi.addSettings(this,section("云端模型"))

  val asr=section("语音识别")
  note(asr,"X-ASR1920ms在本机运行，输入为16kHz单声道音频。支持录音、16位PCM WAV和ZIP中的WAV文件。录音期间请保持应用在前台。")
  modelInfo(asr,"内置Zipformer2流式Transducer及词表，共约586.2MiB。1920ms为分块配置，显示延迟随设备和分段而变。\n\n未启用有效热词时使用greedy_search，启用时使用modified_beam_search。")
  updateUi.addTo(section("软件更新"))
 }
 private fun setStatus(v:String){status=v;runOnUiThread{if(!destroyed&&page==0&&::statusView.isInitialized)statusView.text=sourceStatus(v)}}
 private fun setResult(v:String){runOnUiThread{transcript=v;if(!destroyed&&page==0&&::resultView.isInitialized)resultView.text=TranscriptPresentation.render(v);refreshExpanded()}}
 private fun showInstantPreview(value:String){
  if(instantPreview==value)return
  instantPreview=value
  if(!destroyed&&page==0&&::instantView.isInitialized){
   instantView.text=value;instantView.visibility=if(value.isEmpty())View.GONE else View.VISIBLE
  }
 }
 private fun requestStart(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),31);return};start()}
 override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==31){if(g.isNotEmpty()&&g[0]==PackageManager.PERMISSION_GRANTED)start() else {status="需要麦克风权限";render()}} else if(r==VoiceEnrollmentDialog.PERMISSION_REQUEST){voiceEnrollment.onPermissionResult(g.isNotEmpty()&&g[0]==PackageManager.PERMISSION_GRANTED)}}
 override fun onPostCreate(savedInstanceState:Bundle?){
  super.onPostCreate(savedInstanceState)
  if(android.os.Build.VERSION.SDK_INT>=33){
   onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){handleAppBack()}
  }
 }
 @Suppress("DEPRECATION")
 private fun handleAppBack(){
  window.decorView.tapFeedback()
  if(!voiceEnrollment.handleBack())super.onBackPressed()
 }
 @Deprecated("Android legacy back callback")
 override fun onBackPressed(){handleAppBack()}
 private fun stop(){startRequested=false;if(diarizationRunning){setStatus("正在结束，等待当前处理完成…");return};active.set(false);setStatus("正在结束并刷新尾句…")}
 override fun onStop(){ramMonitor?.shutdownNow();ramMonitor=null;super.onStop();voiceEnrollment.cancel();if(active.get() || startRequested)stop()}
 override fun onDestroy(){updateUi.close();voiceEnrollment.cancel();destroyed=true;polishWorker?.interrupt();active.set(false);startRequested=false;super.onDestroy()}
 private fun ensureModels():File{val dir=File(filesDir,"model-1920");dir.mkdirs();for(n in listOf("encoder-1920ms.onnx","decoder-1920ms.onnx","joiner-1920ms.onnx","tokens.txt","bpe.vocab")){val file=File(dir,n);if(!file.exists()||file.length()==0L){val tmp=File(dir,"$n.tmp");assets.open("models/1920/$n").use{input->tmp.outputStream().use{input.copyTo(it)}};check(tmp.renameTo(file)){"模型文件准备失败"}}};return dir}
 private fun words():List<Pair<String,Float>>{val out=linkedMapOf<String,Float>();val selected=prefs.getStringSet("selected",emptySet())!!;fun add(w:String,score:Float){if(w.isNotEmpty())out[w]=maxOf(out[w]?:0f,score)};groups.forEach{(k,v)->if(k in selected){val score=listOf(1f,2f,3f,4f)[prefs.getInt("weight_"+k,0).coerceIn(0,3)];v.split(',').forEach{add(it.trim(),score)}}};if(prefs.getBoolean("customEnabled",false)){val score=listOf(1f,2f,3f,4f)[prefs.getInt("weight_custom",0).coerceIn(0,3)];prefs.getString("custom","")!!.lines().map{it.trim()}.forEach{add(it,score)}};return out.map{it.key to it.value}}
 private fun hotwordString(terms:List<Pair<String,Float>>):String {
  val tokens=assets.open("models/1920/tokens.txt").bufferedReader().use{it.readLines()}.mapNotNull{it.substringBeforeLast(' ',"").takeIf{x->x.isNotEmpty()}}.toHashSet()
  return terms.map{ (word,score) ->
   require(word.length<=48 && !word.contains(':') && !word.contains('/') && !word.contains('#')) {"词语格式无效：$word"}
   val pieces=mutableListOf<String>();val latin=StringBuilder()
   fun flushLatin(){if(latin.isNotEmpty()){pieces.add(latin.toString());latin.clear()}}
   for(c in word){
    when {
     c in 'A'..'Z'||c in 'a'..'z' -> latin.append(c)
     c in '0'..'9' -> {require(c.toString() in tokens || "▁$c" in tokens){"模型词表不支持数字“$c”，请改用可识别的词语：$word"};latin.append(c)}
     c=='-'||c=='\'' -> {require(c.toString() in tokens || "▁$c" in tokens){"模型词表不支持符号“$c”：$word"};latin.append(c)}
     c.isWhitespace() -> flushLatin()
     c in '\u4e00'..'\u9fff' -> {flushLatin();require("▁$c" in tokens){"词语“$word”包含词表不支持的字“$c”"};pieces.add(c.toString())}
     else -> throw IllegalArgumentException("词语“$word”包含不支持的字符“$c”")
    }
   }
   flushLatin();require(pieces.isNotEmpty()){ "热词不能为空" };pieces.joinToString(" ")+" :"+score.toString()
  }.joinToString("/")
 }
 private fun selectedVadModel():String = VadModelFactory.normalize(prefs.getString("vad_model",VadModelFactory.SILERO))
 private fun chooseVadModel(){
  if(active.get()||startRequested){Toast.makeText(this,"请先结束当前任务",Toast.LENGTH_SHORT).show();return}
  val keys=arrayOf(VadModelFactory.SILERO,VadModelFactory.TEN,VadModelFactory.FSMN)
  val names=arrayOf("Silero VAD","TEN VAD","FSMN VAD（实验，离线最长60秒）")
  hapticDialog().setTitle("选择VAD模型").setSingleChoiceItems(names,keys.indexOf(selectedVadModel()).coerceAtLeast(0)){dialog,which->dialogTap(dialog);prefs.edit().putString("vad_model",keys[which]).apply();dialog.dismiss();render()}.setNegativeButton("取消"){dialog,_->dialogTap(dialog)}.show()
 }
 private fun selectAlignedFile(){
  val pick=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="audio/*";putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("audio/wav","audio/x-wav","audio/wave","application/octet-stream"))}
  try{startActivityForResult(pick,44)}catch(e:Exception){Toast.makeText(this,"无法打开文件选择器",Toast.LENGTH_SHORT).show()}
 }
 private fun startAligned(uri:Uri){
  if(!idleForReview())return
  beginRecognition("文件导入")
  val vadKey=selectedVadModel();val speakerKey=selectedSpeakerModel();val threshold=speakerThreshold();val decisionConfig=speakerDecisionConfig();val terms=words()
  diarizationRunning=true;startRequested=true;active.set(true);page=0;status="正在准备本地转写与说话人切分…";transcript="";render()
  Thread{
   try{
    val samples=(if(uri.scheme=="file")File(uri.path!!).inputStream() else contentResolver.openInputStream(uri)?:error("无法打开所选文件")).use{WavReader.read16k(it)}
    if(!startRequested)throw InterruptedException("已停止")
    val dir=ensureModels();val hot=if(terms.isEmpty())"" else hotwordString(terms)
    setStatus("正在本地识别并切分说话人…")
    val outcome=TimeAlignedAsr.process(assets,samples,dir,speakerKey,hot,vadKey,cancelled={!startRequested},context=this@MainActivity,speakerThreshold=threshold,decisionConfig=decisionConfig)
    outcome.put("speaker_decision_config",speakerDecisionJson(decisionConfig))
    if(!startRequested)throw InterruptedException("已停止")
    val runs=File(getExternalFilesDir(null),"runs");runs.mkdirs()
    val result=TranscriptPresentation.render(SpeakerTranscript.aligned(outcome))
    outcome.put("transcript_text",result).put("transcript_scope","speaker_turns_only")
    val runName="aligned-${System.currentTimeMillis()}"
    File(runs,"$runName.json").writeText(outcome.toString())
    File(runs,"$runName.txt").writeText(result)
    reviewRows=TranscriptReview.fromResult(outcome)
    setResult(result);setStatus("说话人分段转写完成 · ${outcome.getDouble("total_ms").toLong()}毫秒")
   }catch(e:Throwable){setStatus(if(e is InterruptedException)"已停止" else "转写与说话人关联失败：${e.message?:e.javaClass.simpleName}");android.util.Log.e("HotwordTest","alignment failed",e)}
   finally{diarizationRunning=false;active.set(false);startRequested=false;runOnUiThread{recognitionBusy=false;if(!destroyed)render()}}
  }.start()
 }
 private fun selectedSpeakerModel():String = SpeakerModels.normalize(prefs.getString("speaker_model",SpeakerModels.CAM))
 private fun chooseSpeakerModel(){
  if(active.get()||startRequested){Toast.makeText(this,"请先结束当前任务",Toast.LENGTH_SHORT).show();return}
  val names=arrayOf("CAM++","ERes2NetV2");val keys=arrayOf(SpeakerModels.CAM,SpeakerModels.ERES);val chosen=keys.indexOf(selectedSpeakerModel()).coerceAtLeast(0)
  hapticDialog().setTitle("选择声纹模型").setSingleChoiceItems(names,chosen){dialog,which->dialogTap(dialog);prefs.edit().putString("speaker_model",keys[which]).apply();dialog.dismiss();render()}.setNegativeButton("取消"){dialog,_->dialogTap(dialog)}.show()
 }
 private fun selectDiarizationFile(){
  val pick=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="audio/*";putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("audio/wav","audio/x-wav","audio/wave","application/octet-stream"))}
  try{startActivityForResult(pick,43)}catch(e:Exception){Toast.makeText(this,"无法打开文件选择器",Toast.LENGTH_SHORT).show()}
 }
 private fun startDiarization(uri:Uri){
  if(!idleForReview())return
  val model=selectedSpeakerModel();diarizationRunning=true;startRequested=true;active.set(true);page=0;status="加载说话人切分模型…";transcript="";render()
  Thread{
   try{
    val samples=(if(uri.scheme=="file")File(uri.path!!).inputStream() else contentResolver.openInputStream(uri)?:error("无法打开所选文件")).use{WavReader.read16k(it)}
    if(!startRequested)throw InterruptedException("已停止")
    val outcome=DiarizationTest.processOne(assets,samples,model,-1)
    val dir=File(getExternalFilesDir(null),"runs");dir.mkdirs();File(dir,"diarization-${System.currentTimeMillis()}.json").writeText(outcome.toString())
    if(!startRequested){setStatus("已停止");return@Thread}
    val segments=outcome.getJSONArray("segments");val out=StringBuilder()
    val singleWindow=outcome.getString("processing_mode")=="single_window_segmentation_only"
    out.append(if(singleWindow)"不超过10秒：仅切分，未执行声纹聚类；编号为窗口内局部编号。\n\n" else "多窗口处理：已配置${SpeakerModels.name(model)}，是否实际进入声纹聚类取决于音频检测结果；编号为匿名编号。\n\n")
    if(segments.length()==0)out.append("未检测到说话人\n")
    for(i in 0 until segments.length()){val a=segments.getJSONObject(i);val start=String.format(java.util.Locale.ROOT,"%.2f",a.getDouble("start_seconds"));val end=String.format(java.util.Locale.ROOT,"%.2f",a.getDouble("end_seconds"));out.append("[$start—${end}秒]说话人${a.getInt("speaker_id")+1}\n")}
    setResult(out.toString());setStatus("本地切分完成 · ${if(singleWindow)"单窗口" else "多窗口"} · ${outcome.getDouble("process_ms").toLong()}毫秒")
   }catch(e:Throwable){setStatus(if(e is InterruptedException)"已停止" else "说话人切分失败：${e.message?:e.javaClass.simpleName}")}
   finally{diarizationRunning=false;active.set(false);startRequested=false;runOnUiThread{if(!destroyed)render()}}
  }.start()
 }
 private fun selectFile(){
  val pick=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type="*/*";putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("audio/wav","audio/x-wav","audio/wave","application/zip","application/x-zip-compressed","application/octet-stream"))}
  try{startActivityForResult(pick,42)}catch(e:Exception){Toast.makeText(this,"无法打开文件选择器",Toast.LENGTH_SHORT).show()}
 }
 private fun selectDraft(){
  if(!idleForReview())return
  val pick=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="text/plain"}
  try{startActivityForResult(pick,45)}catch(_:Exception){Toast.makeText(this,"无法打开文件选择器",Toast.LENGTH_SHORT).show()}
 }
 private fun readDraft(uri:Uri){
  if(!idleForReview())return
  readingDraft=true
  refreshPolishUi()
  Thread{
   val parsed=runCatching{
    val stream=contentResolver.openInputStream(uri)?:error("无法读取所选文件。")
    stream.use{ImportedDraft.read(it)}
   }
   runOnUiThread{
    readingDraft=false
    if(destroyed||isFinishing)return@runOnUiThread
    refreshPolishUi()
    val draft=parsed.getOrElse{
     hapticDialog().setTitle("无法打开勘误稿").setMessage(it.message?:"读取失败。")
      .setPositiveButton("知道了"){dialog,_->dialogTap(dialog)}.show()
     return@runOnUiThread
    }
    hapticDialog().setTitle("打开已有勘误稿")
     .setMessage("已读到${draft.rows.size}条发言。打开后将替换测试页当前记录，保留稿中的时间和说话人。点击“重新生成摘要”时才会发送到已配置的云端服务。")
     .setNegativeButton("取消"){dialog,_->dialogTap(dialog)}
     .setPositiveButton("打开"){dialog,_->
      dialogTap(dialog)
      if(idleForReview())loadDraft(draft)
     }.show()
   }
  }.start()
 }
 private fun loadDraft(draft:TranscriptReview.Edited){
  reviewRows=emptyList()
  val shownDraft=draft.copy(rows=displayRows(draft.rows))
  completedEdit=shownDraft
  importedDraft=true
  editTarget=null
  recognitionSource="已导入勘误稿"
  reviewNote=""
  setStatus("共${draft.rows.size}条发言")
  showInstantPreview("")
  setResult("本次打开的是已有勘误稿，原始识别结果未载入。")
  polishText=TranscriptReview.render(shownDraft.rows)
  summaryText="点击“重新生成摘要”后，将使用这份勘误稿生成摘要。"
  if(page!=0){page=0;render()}else refreshPolishUi()
  if(!cloudReady(cloudSettings.summary()))offerCloudSettings()
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
  super.onActivityResult(requestCode,resultCode,data)
  if(resultCode!=Activity.RESULT_OK)return
  val uri=data?.data?:return
  when(requestCode){
   45->readDraft(uri)
   44->startAligned(uri)
   43->startDiarization(uri)
   42->{
    var name=uri.lastPathSegment?:"录音.wav"
    try{contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())name=it.getString(0)}}catch(_:Exception){}
    startBatch(uri,name,false)
   }
  }
 }
 private fun copyLimited(input:java.io.InputStream,out:java.io.OutputStream,limit:Long):Long {
  val buffer=ByteArray(32768);var total=0L
  while(true){if(!startRequested)throw InterruptedException("已停止");val n=input.read(buffer);if(n<0)break;if(n==0)continue;total+=n;require(total<=limit){"文件超过大小限制"};out.write(buffer,0,n)};return total
 }
 private fun batchLine(start:Long,end:Long,offset:Long,label:String,speech:String):String=
  SpeakerTranscript.line((offset+start)*1000/16000,(offset+end)*1000/16000,label,speech)

 private fun batchTranscript(record:org.json.JSONObject,offset:Long):String=
  TranscriptReview.render(TranscriptReview.transcriptRows(record,record.optString("file"),offset))

 // Worker callbacks share one pending UI update. Only new speaker lines are appended.
 private inner class FileProgress(private val file:String,private val offset:Long,previous:String):TimeAlignedAsr.Progress {
  private val ui=android.os.Handler(android.os.Looper.getMainLooper())
  private val names=TranscriptPresentation.Names().apply{previous.lineSequence().forEach{line(it)}}
  private var scheduled=false
  private var nextUpdate=0L
  private var phase=""
  private var preview=""
  private val lines=StringBuilder()
  private val draw=Runnable { drawPending() }

  private fun schedule(){
   if(!scheduled){scheduled=true;ui.postAtTime(draw,nextUpdate)}
  }
  @Synchronized private fun drawPending(){
   scheduled=false;nextUpdate=android.os.SystemClock.uptimeMillis()+300
   if(destroyed||!startRequested)return
   setStatus("$file · $phase")
   showInstantPreview(preview)
   if(lines.isNotEmpty()){
    val draft=transcript as? StringBuilder?:StringBuilder(transcript).also{transcript=it}
    if(draft.isNotEmpty())lines.insert(0,'\n')
    draft.append(lines)
    if(page==0&&::resultView.isInitialized)resultView.append(lines.lineSequence().joinToString("\n"){names.line(it)})
    else lines.lineSequence().forEach{names.line(it)}
    lines.setLength(0)
    refreshExpanded()
   }
  }
  @Synchronized override fun onVad(index:Int,text:String){
   phase=if(index==0)"正在检测语音，等待首句文字" else "正在初步识别第${index}个语音段"
   if(text.isNotBlank())preview="即时预览（说话人待切分）：${text.trim()}"
   schedule()
  }
  @Synchronized override fun onDiarization(done:Int,total:Int){
   phase=if(total>0)"正在切分说话人 · 已处理${done}/${total}块" else "正在切分说话人，等待分段结果"
   schedule()
  }
  @Synchronized override fun onTurn(done:Int,total:Int,turn:org.json.JSONObject?){
   preview=""
   phase="正在分段转写 · 已识别${done}/${total}段 · 说话人标签暂定"
   if(turn!=null&&turn.optString("text").isNotBlank()){
    val line=batchLine(turn.getLong("start_sample"),turn.getLong("end_sample"),offset,SpeakerTranscript.turnLabel(turn),turn.getString("text"))
    if(lines.isNotEmpty())lines.append('\n')
    lines.append(line)
   }
   schedule()
  }
  @Synchronized override fun onGrouping(){
   phase="正在核对声纹分组，分段文字已识别，标签暂定"
   schedule()
  }
  @Synchronized fun finish(){
   ui.removeCallbacks(draw)
   runOnUiThread{showInstantPreview("")}
  }
 }

 private fun startBatch(uri:Uri,name:String,probe:Boolean){
  if(!idleForReview())return;beginRecognition("文件导入");val useVad=if(probe&&intent.hasExtra("probeVad"))intent.getBooleanExtra("probeVad",false) else prefs.getBoolean("file_vad",true);val useSpeaker=useVad && if(probe&&intent.hasExtra("probeSpeaker"))intent.getBooleanExtra("probeSpeaker",false) else prefs.getBoolean("speaker_enabled",true);val speakerKey=if(probe&&intent.hasExtra("probeSpeakerModel"))SpeakerModels.normalize(intent.getStringExtra("probeSpeakerModel")) else selectedSpeakerModel();val vadKey=if(probe&&intent.hasExtra("probeVadModel"))VadModelFactory.normalize(intent.getStringExtra("probeVadModel")) else selectedVadModel();val threshold=if(probe)LocalSpeakerTracker.EXPERIMENTAL_THRESHOLD else speakerThreshold();val decisionConfig=if(probe)SpeakerDecisionConfig() else speakerDecisionConfig();startRequested=true;active.set(true);page=0;status="正在读取文件…";transcript="";render();val terms=if(probe&&intent.getBooleanExtra("probeNoHotwords",false))emptyList() else words()
  val useTurns=useSpeaker && if(probe)intent.getBooleanExtra("probeSpeakerTurns",false) else prefs.getBoolean("live_speaker_turns",true)
  val untimedFiles=org.json.JSONArray()
  Thread {var engine:OnlineRecognizer?=null;var speaker:LocalSpeakerTracker?=null;var aligned:TimeAlignedAsr.Session?=null;val tmp=File(cacheDir,"import-"+System.nanoTime());tmp.mkdirs();val results=org.json.JSONArray();val output=StringBuilder();var message=""
   try {
    val inputFile=File(tmp,"source");(if(uri.scheme=="file")File(uri.path!!).inputStream() else contentResolver.openInputStream(uri)?:error("无法打开所选文件")).use{ input-> inputFile.outputStream().use { copyLimited(input,it,512L*1024*1024) } }
    val files=mutableListOf<Pair<String,File>>()
    if(name.lowercase().endsWith(".zip")) {
     var expanded=0L;var count=0
     ZipInputStream(inputFile.inputStream().buffered()).use { zip->
      while(true){if(!startRequested)throw InterruptedException("已停止");val entry=zip.nextEntry?:break;count++;require(count<=1000){"ZIP条目超过1000个"}
       val entryName=entry.name.replace('\\','/');val parts=entryName.split('/');require(!entryName.startsWith('/')&&!entryName.contains(':')&&parts.none{it==".."||it=="."}&&!entryName.contains('\u0000')){"ZIP包含不安全路径"}
       if(!entry.isDirectory && entryName.lowercase().endsWith(".wav")){
        val target=File(tmp,"item-${count}.wav");val bytes=target.outputStream().use { copyLimited(zip,it,minOf(50L*1024*1024,1024L*1024*1024-expanded)) };expanded+=bytes;files.add(entryName to target)
       } else if(!entry.isDirectory) {expanded+=copyLimited(zip,object:java.io.OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,off:Int,len:Int){}},minOf(50L*1024*1024,1024L*1024*1024-expanded))}
       require(expanded<=1024L*1024*1024){"ZIP解压超过1024MiB"};zip.closeEntry()
      }
     }
    } else {require(name.lowercase().endsWith(".wav")){"请选择WAV或ZIP"};require(inputFile.length()<=50L*1024*1024){"单个WAV超过50MiB"};files.add(name to inputFile)}
    require(files.isNotEmpty()){ "文件中没有WAV" };files.sortBy{it.first}
    val model=ensureModels();if(!startRequested)throw InterruptedException("已停止");val hot=if(terms.isNotEmpty())hotwordString(terms) else ""
    setStatus("加载模型，准备识别${files.size}条音频…")
    if(useTurns)aligned=TimeAlignedAsr.Session(assets,model,speakerKey,hot,vadKey,context=this@MainActivity,speakerThreshold=threshold,decisionConfig=decisionConfig)
    else{
     engine=OnlineRecognizer(config=OnlineRecognizerConfig(featConfig=FeatureConfig(sampleRate=16000,featureDim=80),modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig(encoder=File(model,"encoder-1920ms.onnx").path,decoder=File(model,"decoder-1920ms.onnx").path,joiner=File(model,"joiner-1920ms.onnx").path),tokens=File(model,"tokens.txt").path,numThreads=2,provider="cpu",modelType="zipformer2",modelingUnit=if(hot.isEmpty())"" else "bpe",bpeVocab=if(hot.isEmpty())"" else File(model,"bpe.vocab").path),enableEndpoint=false,decodingMethod=if(hot.isEmpty())"greedy_search" else "modified_beam_search",maxActivePaths=4,hotwordsScore=4.0f))
     if(useSpeaker)speaker=LocalSpeakerTracker(assets,speakerKey,threshold,decisionConfig)
    }
    var nextStart=0L
    var silentFiles=0
    var firstFailure=""
    files.forEachIndexed { index,(label,file)->
     if(!startRequested)throw InterruptedException("已停止")
     val progressLabel="第${index+1}条录音，共${files.size}条"
     setStatus("正在识别$progressLabel")
     val offset=nextStart
     val fileSegments=org.json.JSONArray()
     val completedLines=mutableListOf<String>()
     var sampleCount:Int?=null
     var stream:OnlineStream?=null
     var cancelled:InterruptedException?=null
     val progress=if(useTurns&&!probe)FileProgress(progressLabel,offset,output.toString()) else null
     val record=try{
      val samples=file.inputStream().use{WavReader.read16k(it)}
      sampleCount=samples.size
      nextStart+=samples.size
      if(!startRequested)throw InterruptedException("已停止")
      if(useTurns){
       aligned!!.process(samples,cancelled={!startRequested},progress=progress).put("mode","speaker_turns")
        .put("transcript_scope","speaker_turns_only").put("speaker_decision_config",speakerDecisionJson(decisionConfig))
      }else if(useVad){
       val recognizer=checkNotNull(engine)
       val previousFiles=output.toString()
       fun preview(partial:String=""){
        setResult(listOf(previousFiles,completedLines.joinToString("\n"),partial).filter{it.isNotBlank()}.joinToString("\n"))
       }
       val outcome=VadFilePipeline(assets,vadKey).process(samples,recognizer,hot,cancelled={!startRequested},
        onPartial={_,start,end,current->
         preview(batchLine(start.toLong(),end.toLong(),offset,if(useSpeaker)"待确认" else "未区分",current))
        },onFinal={segment->
         val row=segment.json()
         speaker?.let{tracker->
          val group=if(segment.text.isBlank())org.json.JSONObject().put("speaker_id",org.json.JSONObject.NULL)
           .put("speaker_status","empty_asr_not_grouped").put("threshold",org.json.JSONObject.NULL)
          else tracker.assignConservative(samples.copyOfRange(segment.vadStartSample,segment.vadEndSample))
          row.put("speaker_id",group.opt("speaker_id")).put("speaker_status",group.optString("speaker_status"))
           .put("speaker_similarity",group.opt("similarity_to_previous")).put("speaker_threshold",group.opt("threshold"))
         }
         fileSegments.put(row)
         if(segment.text.isNotBlank()){
          val id=SpeakerTranscript.segmentLabel(row).ifBlank{"未区分"}
          completedLines.add(batchLine(segment.startSample.toLong(),segment.endSample.toLong(),offset,id,segment.text))
         }
         preview()
        })
       val data=outcome.json().put("segments",fileSegments).put("mode","vad_segments")
        .put("decoding",if(hot.isEmpty())"greedy_search" else "modified_beam_search")
       if(useSpeaker)data.put("speaker_decision_config",speakerDecisionJson(decisionConfig))
        .put("speaker_status","experimental_segment_assignment").put("speaker_model",SpeakerModels.name(speakerKey))
        .put("speaker_threshold_status","uncalibrated")
       data
      }else{
       val recognizer=checkNotNull(engine)
       val current=recognizer.createStream(hot);stream=current
       var off=0
       while(off<samples.size){
        if(!startRequested)throw InterruptedException("已停止")
        val end=minOf(off+800,samples.size);current.acceptWaveform(samples.copyOfRange(off,end),16000)
        off=end;while(recognizer.isReady(current))recognizer.decode(current)
       }
       current.acceptWaveform(FloatArray(2580*16),16000);current.inputFinished()
       while(recognizer.isReady(current))recognizer.decode(current)
       org.json.JSONObject().put("status","completed").put("mode","whole_file").put("text",recognizer.getResult(current).text)
        .put("start_ms",0).put("end_ms",samples.size*1000L/16000)
        .put("time_source","整个文件输入区间，非语音边界或词级时间")
        .put("speaker_id",org.json.JSONObject.NULL).put("word_timestamps",org.json.JSONObject.NULL).put("confidence",org.json.JSONObject.NULL)
      }
     }catch(e:Exception){
      if(e is InterruptedException)cancelled=e else if(firstFailure.isEmpty())firstFailure="$label：${e.message?:"未知错误"}"
      org.json.JSONObject().put("status",if(cancelled==null)"failed" else "interrupted")
       .put("error",e.message?:"未知错误").put("segments",fileSegments)
     }finally{progress?.finish();stream?.release()}

     record.put("file",label).put("file_order",index+1)
     val count=sampleCount
     if(count==null){
      untimedFiles.put(label)
      record.put("input_samples",org.json.JSONObject.NULL).put("merged_offset_ms",org.json.JSONObject.NULL)
     }else{
      record.put("input_samples",count).put("duration_ms",count*1000L/16000).put("merged_offset_samples",offset).put("merged_offset_ms",offset*1000/16000)
     }
     val fileText=batchTranscript(record,0L)
     record.put("transcript_text",fileText)
     results.put(record)
     if(fileText.isNotEmpty()){
      if(output.isNotEmpty())output.append('\n')
      output.append(if(offset==0L)fileText else batchTranscript(record,offset))
     }else if(record.optString("status")=="completed")silentFiles++
     setResult(output.toString())
     cancelled?.let{throw it}
    }
    val successful=(0 until results.length()).count{results.getJSONObject(it).optString("status")=="completed"}
    message=buildString{
     append("识别完成：${successful}/${files.size}条成功")
     if(silentFiles>0)append("，${silentFiles}条未识别到文字")
     if(firstFailure.isNotEmpty())append("。失败：$firstFailure")
     if(untimedFiles.length()>0)append("。时间仅累计可读取的WAV，${untimedFiles.length()}个文件的时长未知，未计时")
    }
    if(startRequested && successful==files.size){
     reviewRows=(0 until results.length()).flatMap{i->val record=results.getJSONObject(i);TranscriptReview.fromResult(record,record.optString("file"))}
    } 
   }catch(e:Throwable){message=if(e is InterruptedException)"已停止" else "导入失败：${e.message}";android.util.Log.e("HotwordTest","batch failed",e)}
   finally {
    try{aligned?.close()}catch(_:Exception){};try{speaker?.close()}catch(_:Exception){};try{engine?.release()}catch(_:Exception){}
    tmp.listFiles()?.forEach{it.delete()};tmp.delete()
    startRequested=false;active.set(false);setResult(output.toString());setStatus(message)
    val used=org.json.JSONArray()
    terms.forEach{(word,score)->used.put(org.json.JSONObject().put("word",word).put("score",score.toDouble()))}
    val shownOutput=TranscriptPresentation.render(output.toString())
    val payload=org.json.JSONObject().put("status",message)
     .put("mode",if(useTurns)"speaker_turns" else if(useVad)"vad_segments" else "whole_file")
     .put("transcript_text",shownOutput)
     .put("transcript_scope",if(useTurns)"speaker_turns_only" else if(useVad)"vad_segments" else "whole_file")
     .put("merged_time_basis","顺序拼接可读取WAV的16kHz采样时长，单文件记录内为相对时间")
     .put("time_excluded_files",untimedFiles)
     .put("decoding",if(terms.isEmpty())"greedy_search" else "modified_beam_search")
     .put("hotwords",used).put("results",results)
    try{
     if(probe)File(getExternalFilesDir(null),"batch-result.json").writeText(payload.toString())
     else{
      val dir=File(getExternalFilesDir(null),"runs");dir.mkdirs();val runName="transcript-${System.currentTimeMillis()}"
      File(dir,"$runName.json").writeText(payload.toString());File(dir,"$runName.txt").writeText(shownOutput)
     }
    }catch(e:Exception){android.util.Log.e("HotwordTest","保存结果失败",e);setStatus("$message；结果保存失败：${e.message}")}
    runOnUiThread{recognitionBusy=false;if(!destroyed)render()}
   }
  }.start()
 }
 private fun runLiveReplayProbe(){
  Thread{var e:OnlineRecognizer?=null;val out=File(getExternalFilesDir(null),"live-replay-result.json")
   try{val dir=ensureModels();val samples=File(getExternalFilesDir(null),"probe.wav").inputStream().use{WavReader.read16k(it)};e=OnlineRecognizer(config=OnlineRecognizerConfig(featConfig=FeatureConfig(sampleRate=16000,featureDim=80),modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig(encoder=File(dir,"encoder-1920ms.onnx").path,decoder=File(dir,"decoder-1920ms.onnx").path,joiner=File(dir,"joiner-1920ms.onnx").path),tokens=File(dir,"tokens.txt").path,numThreads=2,provider="cpu",modelType="zipformer2"),enableEndpoint=false,decodingMethod="greedy_search"));val items=org.json.JSONArray();val vadKey=VadModelFactory.normalize(intent.getStringExtra("probeVadModel"));val v=VadLiveSegmenter(assets,vadKey){seg->val s=e.createStream();try{var p=0;while(p<seg.samples.size){val end=minOf(p+800,seg.samples.size);s.acceptWaveform(seg.samples.copyOfRange(p,end),16000);p=end;while(e.isReady(s))e.decode(s)};s.acceptWaveform(FloatArray(2580*16),16000);s.inputFinished();while(e.isReady(s))e.decode(s);items.put(org.json.JSONObject().put("start_sample",seg.startSample).put("end_sample",seg.endSample).put("text",e.getResult(s).text))}finally{s.release()}};try{var p=0;while(p<samples.size){val end=minOf(p+800,samples.size);v.accept(samples.copyOfRange(p,end));p=end};v.finish();out.writeText(org.json.JSONObject().put("status","completed").put("input_samples",v.inputSamples()).put("vad_model",VadModelFactory.name(vadKey)).put("segments",items).toString())}finally{v.close()}}
   catch(t:Throwable){out.writeText(org.json.JSONObject().put("status","failed").put("error",t.message?:t.javaClass.simpleName).toString())}finally{e?.release()}
  }.start()
 }
 private fun runProbe(){
  Thread {
   var engine:OnlineRecognizer?=null;var stream:OnlineStream?=null
   val output=File(getExternalFilesDir(null),"probe-result.json")
   try {
    val dir=ensureModels();val file=File(getExternalFilesDir(null),"probe.wav")
    val data=file.readBytes();val b=java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    require(data.size>44 && String(data,0,4)=="RIFF" && String(data,8,4)=="WAVE") {"要求PCM WAV"}
    var offset=12;var payload=0;var length=0;var formatOk=false
    while(offset+8<=data.size){val tag=String(data,offset,4);val size=b.getInt(offset+4);require(size>=0 && offset+8L+size<=data.size);if(tag=="fmt "){require(size>=16);formatOk=b.getShort(offset+8).toInt()==1 && b.getShort(offset+10).toInt()==1 && b.getInt(offset+12)==16000 && b.getShort(offset+22).toInt()==16};if(tag=="data"){payload=offset+8;length=size;break};offset+=8+size+(size%2)}
    require(formatOk && length>0 && length%2==0){"要求16kHz单声道PCM16 WAV"}
    val term=intent.getStringExtra("probeWord")?:"";val hot=if(term.isBlank())"" else hotwordString(listOf(term to 4f));val begin=System.nanoTime()
    engine=OnlineRecognizer(config=OnlineRecognizerConfig(featConfig=FeatureConfig(sampleRate=16000,featureDim=80),modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig(encoder=File(dir,"encoder-1920ms.onnx").path,decoder=File(dir,"decoder-1920ms.onnx").path,joiner=File(dir,"joiner-1920ms.onnx").path),tokens=File(dir,"tokens.txt").path,numThreads=2,provider="cpu",modelType="zipformer2",modelingUnit="bpe",bpeVocab=File(dir,"bpe.vocab").path),enableEndpoint=false,decodingMethod="modified_beam_search",maxActivePaths=4,hotwordsScore=4.0f))
    stream=engine.createStream(hot);var sample=0;val total=length/2
    while(sample<total){val n=minOf(800,total-sample);stream.acceptWaveform(FloatArray(n){b.getShort(payload+2*(sample+it))/32768f},16000);sample+=n;while(engine.isReady(stream))engine.decode(stream)}
    stream.acceptWaveform(FloatArray(2580*16),16000);stream.inputFinished();while(engine.isReady(stream))engine.decode(stream)
    val result=org.json.JSONObject().put("status","completed").put("text",engine.getResult(stream).text).put("term",term).put("encoded",hot).put("decode","modified_beam_search").put("seconds",(System.nanoTime()-begin)/1e9)
    output.writeText(result.toString());setStatus("测试完成")
   }catch(e:Throwable){output.writeText(org.json.JSONObject().put("status","failed").put("error",e.message?:e.javaClass.simpleName).toString());android.util.Log.e("HotwordTest","probe failed",e);setStatus("测试失败：${e.message}")}
   finally{try{stream?.release()}catch(_:Exception){};try{engine?.release()}catch(_:Exception){}}
  }.start()
 }
 private fun start(){if(!idleForReview())return;val useVad=prefs.getBoolean("file_vad",true);val speakerEnabled=useVad&&prefs.getBoolean("speaker_enabled",true);val speakerKey=selectedSpeakerModel();val vadKey=selectedVadModel();val threshold=speakerThreshold();val decisionConfig=speakerDecisionConfig();val turnMode=useVad&&speakerEnabled&&prefs.getBoolean("live_speaker_turns",true);if(useVad && vadKey==VadModelFactory.FSMN){setStatus("FSMN仅支持60秒以内的离线文件，请切换Silero或TEN后录音");Toast.makeText(this,"FSMN不支持实时录音",Toast.LENGTH_LONG).show();return};beginRecognition("手机麦克风");startRequested=true;status="正在准备模型…";transcript="";render();val terms=words()
  Thread{var recorder:AudioRecord?=null;var engine:OnlineRecognizer?=null;var stream:OnlineStream?=null;var liveSpeaker:LiveSpeakerLabeler?=null;var liveSpeakerError:String?=null;var turnSplitter:LiveSpeakerTurnSegmenter?=null
   try{val dir=ensureModels();if(!startRequested)return@Thread;val h=if(terms.isNotEmpty())hotwordString(terms) else null
    setStatus(if(h==null)"正在加载模型（无热词）…" else "正在加载模型和${terms.size}个热词…")
    engine=OnlineRecognizer(config=OnlineRecognizerConfig(featConfig=FeatureConfig(sampleRate=16000,featureDim=80),modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig(encoder=File(dir,"encoder-1920ms.onnx").path,decoder=File(dir,"decoder-1920ms.onnx").path,joiner=File(dir,"joiner-1920ms.onnx").path),tokens=File(dir,"tokens.txt").path,numThreads=2,provider="cpu",modelType="zipformer2",modelingUnit=if(h==null)"" else "bpe",bpeVocab=if(h==null)"" else File(dir,"bpe.vocab").path),enableEndpoint=false,decodingMethod=if(h==null)"greedy_search" else "modified_beam_search",maxActivePaths=4,hotwordsScore=4.0f))
    stream=engine.createStream(h?:"");if(!startRequested)return@Thread
    if(speakerEnabled){
     setStatus("正在加载声纹模型…")
     try{liveSpeaker=LiveSpeakerLabeler(assets,applicationContext,speakerKey,threshold,decisionConfig)}
     catch(e:Exception){liveSpeakerError=e.message?:"声纹模型加载失败";android.util.Log.e("HotwordTest","实时声纹初始化失败，继续转写",e)}
     if(!startRequested)return@Thread
    }
    if(turnMode){setStatus("正在加载说话人切分模型…");turnSplitter=LiveSpeakerTurnSegmenter(assets,speakerKey,threshold);if(!startRequested)return@Thread}
    val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);check(min>0){"设备不支持16kHz单声道录音"};recorder=AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,6400));check(recorder.state==AudioRecord.STATE_INITIALIZED){"麦克风初始化失败"};recorder.startRecording();active.set(true);setStatus("正在录音，请说话 · ${if(h==null)"无热词" else "热词${terms.size}个"}");setResult("")
    val queue=java.util.concurrent.ArrayBlockingQueue<FloatArray>(if(turnMode)1200 else 400)
    val overflow=AtomicBoolean(false)
    val captureError=java.util.concurrent.atomic.AtomicReference<String?>(null)
    val captureDevice=recorder
    val reader=Thread {
      try {
       val buffer=ShortArray(800)
       while(active.get() && startRequested){
        val n=captureDevice.read(buffer,0,buffer.size)
        if(n<0){captureError.set("录音读取失败：$n");break}
        if(n==0)continue
        if(!queue.offer(FloatArray(n){buffer[it]/32768f})) {overflow.set(true);active.set(false);break}
       }
      } catch(e:Throwable){captureError.set(e.message?:"录音读取异常");active.set(false)}
    }
    fun saveLive(payload:org.json.JSONObject,body:String){
     val runs=File(getExternalFilesDir(null),"runs");runs.mkdirs()
     val name="live-${System.currentTimeMillis()}"
     val shown=TranscriptPresentation.render(body)
     File(runs,"$name.json").writeText(payload.put("transcript_text",shown).toString())
     File(runs,"$name.txt").writeText(shown)
    }
    reader.start();var previous=""
    if(useVad){
      val segments=org.json.JSONArray();val displayedTurns=org.json.JSONArray();val finished=StringBuilder();var observed=0L;val speaker=liveSpeaker;var splitBacklogCount=0;var splitErrorCount=0
     val segmenter=VadLiveSegmenter(assets,vadKey,if(turnSplitter!=null)5*16000 else 0){seg->
      val local=engine.createStream(h?:"")
       try{
        var p=0;var prev=""
        val startMs=seg.startSample*1000/16000;val endMs=seg.endSample*1000/16000
        while(p<seg.samples.size){
         val end=minOf(p+800,seg.samples.size);local.acceptWaveform(seg.samples.copyOfRange(p,end),16000);p=end
         while(engine.isReady(local))engine.decode(local)
         val text=engine.getResult(local).text
         if(text!=prev){
          prev=text
          if(!turnMode)setResult(finished.toString()+SpeakerTranscript.line(startMs,endMs,"未区分",text))
         }
        }
       local.acceptWaveform(FloatArray(2580*16),16000);local.inputFinished();while(engine.isReady(local))engine.decode(local);val t=engine.getResult(local).text
       val splitBacklog=turnSplitter!=null && t.isNotBlank() && queue.size>=200
       var splitFailed=splitBacklog
       if(splitBacklog){splitBacklogCount++;setStatus("处理积压：本段未完成说话人切分")}
       if(turnSplitter!=null&&t.isNotBlank()&&!splitBacklog){
        val splitStart=System.nanoTime();val previousGroups=speaker?.snapshotAnonymousGroups()
        try{
         val split=turnSplitter!!.split(seg)
         val turns=describeSpeakerTurns(seg,split,t,engine,h,speaker)
         val record=org.json.JSONObject().put("index",seg.index).put("start_sample",seg.startSample).put("end_sample",seg.endSample)
          .put("start_ms",seg.startSample*1000/16000).put("end_ms",seg.endSample*1000/16000)
          .put("vad_start_sample",seg.vadStartSample).put("vad_end_sample",seg.vadEndSample)
          .put("text",t).put("original_vad_text",t).put("speaker_turns",turns)
          .put("speaker_status","speaker_activity_split_experimental").put("identity_status","unverified")
          .put("context_start_sample",split.contextStart).put("context_end_sample",split.contextEnd)
          .put("diarization_ms",split.inferenceMs).put("speaker_turns_total_ms",(System.nanoTime()-splitStart)/1000000.0)
          .put("audio_queue_blocks",queue.size).put("word_timestamps",org.json.JSONObject.NULL)
         segments.put(record)
         for(i in 0 until turns.length())displayedTurns.put(turns.getJSONObject(i))
         val lines=SpeakerTranscript.live(turns)
         if(lines.isNotBlank())finished.append(lines).append("\n")
         setResult(finished.toString())
         return@VadLiveSegmenter
        }catch(e:Exception){splitFailed=true;splitErrorCount++;try{if(previousGroups!=null)speaker?.restoreAnonymousGroups(previousGroups)}catch(restore:Exception){throw IllegalStateException("声纹状态恢复失败",restore)};android.util.Log.e("HotwordTest","细分失败，原VAD结果仅保留在诊断记录",e)}
       }
       // Split mode never displays a whole-VAD fallback as a speaker turn.
       // Retain diagnostics and explicitly report incomplete intervals instead.
       if(turnMode){
        segments.put(org.json.JSONObject().put("index",seg.index)
         .put("start_ms",seg.startSample*1000/16000).put("end_ms",seg.endSample*1000/16000)
         .put("original_vad_text",t).put("speaker_turns",org.json.JSONArray())
         .put("speaker_status",if(splitBacklog)"speaker_turn_skipped_backlog" else if(splitFailed)"speaker_turn_split_failed" else "empty_asr")
         .put("display_status","no_split_transcript"))
         if(splitFailed && !splitBacklog)setStatus("本段说话人切分失败")
        setResult(finished.toString());return@VadLiveSegmenter
       }
       val voiceStart=System.nanoTime()
       // Empty ASR output is not proof of music or silence: do not let an
       // untranscribed segment create or change anonymous voice groups.
       val voice=if(splitFailed) org.json.JSONObject()
        .put("speaker",org.json.JSONObject().put("speaker_id",org.json.JSONObject.NULL).put("speaker_status",if(splitBacklog)"speaker_turn_skipped_backlog_not_grouped" else "speaker_turn_split_failed_not_grouped"))
        .put("identity",org.json.JSONObject().put("status","not_evaluated_split_failed"))
        .put("display","说话人未识别")
        .put("voice_gate",if(splitBacklog)"处理积压，暂时跳过细分；保留原始转写，不归组" else "说话人细分失败，保留原始转写且不把整段混合音频归组")
       else if(speaker!=null && t.isBlank()) org.json.JSONObject()
        .put("speaker",org.json.JSONObject().put("speaker_id",org.json.JSONObject.NULL).put("speaker_status","empty_asr_not_grouped"))
        .put("identity",org.json.JSONObject().put("status","not_evaluated_empty_asr"))
        .put("display","无文字")
        .put("voice_gate","ASR结果为空，未进行声纹归组；不表示没有语音或一定是音乐")
       else try{speaker?.classify(seg.samples)}catch(e:Exception){android.util.Log.e("HotwordTest","实时声纹识别失败，继续转写",e);null}
       val voiceMs=(System.nanoTime()-voiceStart)/1000000.0
       val sp=voice?.optJSONObject("speaker")
       val identity=voice?.optJSONObject("identity")
       val display=if(!speakerEnabled)"" else voice?.optString("display")?:"待确认"
        val record=org.json.JSONObject().put("index",seg.index).put("start_sample",seg.startSample).put("end_sample",seg.endSample).put("start_ms",seg.startSample*1000/16000).put("end_ms",seg.endSample*1000/16000).put("vad_start_sample",seg.vadStartSample).put("vad_end_sample",seg.vadEndSample).put("overlap_clipped",seg.overlapClipped).put("text",t).put("speaker_id",sp?.opt("speaker_id")?:org.json.JSONObject.NULL).put("speaker_status",sp?.optString("speaker_status")?:if(speakerEnabled)"comparison_failed" else "not_implemented").put("speaker_similarity",sp?.opt("similarity_to_previous")?:org.json.JSONObject.NULL).put("speaker_threshold",sp?.opt("threshold")?:org.json.JSONObject.NULL).put("speaker_display",if(display.isEmpty())org.json.JSONObject.NULL else display).put("voice_identity",identity?:org.json.JSONObject.NULL).put("voice_gate",voice?.opt("voice_gate")?:org.json.JSONObject.NULL).put("single_speaker_status","not_verified").put("overlap_status","not_checked").put("voice_process_ms",voiceMs).put("audio_queue_blocks",queue.size).put("word_timestamps",org.json.JSONObject.NULL).put("confidence",org.json.JSONObject.NULL)
        segments.put(record)
        val label=SpeakerTranscript.segmentLabel(record).ifBlank{"未区分"}
        val line=SpeakerTranscript.line(startMs,endMs,label,t)
        if(line.isNotEmpty())finished.append(line).append('\n')
        setResult(finished.toString().trimEnd('\n'))
      }finally{local.release()}
     }
     try{setStatus(if(liveSpeakerError!=null)"声纹不可用，继续转写；语音停顿后提交片段" else "VAD已开启：语音停顿后提交片段");while(reader.isAlive||queue.isNotEmpty()){val samples=queue.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS)?:continue;segmenter.accept(samples)};reader.join();captureError.get()?.let{throw IllegalStateException(it)};segmenter.finish();observed=segmenter.inputSamples()}finally{segmenter.close()}
     recorder.stop();val payload=org.json.JSONObject().put("status",if(overflow.get())"queue_overflow" else "completed").put("mode","live_vad_segments").put("speaker_split_backlog_skipped",splitBacklogCount).put("speaker_split_failures",splitErrorCount).put("vad_model",VadModelFactory.name(vadKey)).put("sample_rate",16000).put("input_samples",observed).put("segments",segments).put("speaker_status",if(speaker!=null)"experimental_segment_assignment" else if(speakerEnabled)"initialization_failed" else "not_implemented").put("speaker_error",liveSpeakerError?:org.json.JSONObject.NULL).put("speaker_model",if(speakerEnabled)SpeakerModels.name(speakerKey) else org.json.JSONObject.NULL).put("speaker_threshold_status",if(speaker==null)org.json.JSONObject.NULL else "uncalibrated").put("speaker_clustering_threshold",if(turnSplitter==null)org.json.JSONObject.NULL else threshold).put("identity_status",if(!speakerEnabled)"not_implemented" else if(speaker==null)"initialization_failed" else "experimental_vad_segment_candidate").put("single_speaker_status",if(speakerEnabled)"not_verified" else "not_applicable").put("overlap_status",if(turnSplitter!=null)"predicted_per_turn" else if(speakerEnabled)"not_checked" else "not_applicable").put("word_timestamp_status","not_implemented").put("time_source","本次录音采样偏移；片段边界非词级时间").put("partial_scope","VAD关闭片段后的识别中间结果")
      val finalText=finished.toString().trimEnd('\n')
      payload.put("speaker_decision_config",speakerDecisionJson(decisionConfig))
       .put("transcript_scope",if(turnMode)"speaker_turns_only" else "vad_segments")
      if(turnMode)payload.put("speaker_turn_transcripts",displayedTurns)
      saveLive(payload,finalText)
      if(!overflow.get() && splitBacklogCount==0 && splitErrorCount==0)reviewRows=TranscriptReview.fromResult(payload)
      setResult(finalText)
      val details=mutableListOf<String>()
      if(overflow.get())details.add("处理积压，缓冲区已满，录音提前结束")
      if(splitBacklogCount>0||splitErrorCount>0)details.add("${splitBacklogCount}段因积压未细分，${splitErrorCount}段细分失败")
      if(liveSpeakerError!=null)details.add("声纹模型不可用")
      if(finalText.isEmpty())details.add("未识别到可显示的文字")
      setStatus((listOf(if(details.isEmpty())"识别完成" else "识别结束")+details).joinToString(" · "))
      return@Thread
    }
    var inputSamples=0L
    while(reader.isAlive || queue.isNotEmpty()){
      val samples=queue.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
      inputSamples+=samples.size
      stream.acceptWaveform(samples,16000)
      while(engine.isReady(stream))engine.decode(stream)
      val result=engine.getResult(stream).text
      val partial=SpeakerTranscript.line(0L,inputSamples*1000/16000,"未区分",result)
      if(partial!=previous){previous=partial;setResult(partial)}
    }
    reader.join()
    captureError.get()?.let { throw IllegalStateException(it) }
    recorder.stop();stream.acceptWaveform(FloatArray((1920+660)*16),16000);stream.inputFinished()
    while(engine.isReady(stream))engine.decode(stream)
    val speech=engine.getResult(stream).text
    val endMs=inputSamples*1000/16000
    val finalText=SpeakerTranscript.line(0L,endMs,"未区分",speech)
    val payload=org.json.JSONObject().put("status",if(overflow.get())"queue_overflow" else "completed")
     .put("mode","live_whole_recording").put("transcript_scope","whole_recording").put("text",speech)
     .put("start_ms",0).put("end_ms",endMs).put("sample_rate",16000).put("input_samples",inputSamples)
     .put("time_source","本次实际录音输入区间，非词级时间").put("speaker_id",org.json.JSONObject.NULL)
     .put("word_timestamps",org.json.JSONObject.NULL).put("confidence",org.json.JSONObject.NULL)
    setResult(finalText)
    saveLive(payload,finalText)
    if(!overflow.get() && speech.isNotBlank()){
     val prefix=SpeakerTranscript.line(0L,endMs,"未区分","§").removeSuffix("§")
     reviewRows=listOf(TranscriptReview.Row("",prefix,speech))
    }
    setStatus(when{
     overflow.get()->"识别处理跟不上录音，缓冲区已满，录音提前结束；结果可能不完整"
     finalText.isEmpty()->"未识别到文字"
     else->"识别完成"
    })
   }catch(e:Throwable){reviewRows=emptyList();setStatus("识别失败：${e.message?:e.javaClass.simpleName}");android.util.Log.e("HotwordTest","识别失败",e)}finally{try{turnSplitter?.close()}catch(_:Exception){};try{liveSpeaker?.close()}catch(_:Exception){};try{recorder?.release()}catch(_:Exception){};try{stream?.release()}catch(_:Exception){};try{engine?.release()}catch(_:Exception){};active.set(false);startRequested=false;runOnUiThread{recognitionBusy=false;if(!destroyed)render()}}
  }.start()
 }
}

