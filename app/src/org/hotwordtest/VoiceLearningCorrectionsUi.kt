package org.hotwordtest

import android.app.Activity
import android.media.MediaPlayer
import android.widget.Toast
import java.util.Locale

internal object VoiceLearningCorrectionsUi {
 private val working=java.util.concurrent.atomic.AtomicBoolean(false)
 val isBusy:Boolean get()=working.get()
 private var stopPlayback:(()->Unit)?=null
 fun cancel(){stopPlayback?.invoke();stopPlayback=null}
 fun show(activity:Activity,rows:List<TranscriptReview.Row>,run:VoiceLearningRun?,canWork:()->Boolean,
          corrected:(Int,WearerVoiceStore.ProfileInfo)->Unit,page:Int=0){
  if(!canWork()||rows.isEmpty())return
  val begin=page*40;val shown=rows.drop(begin).take(40)
  val names=shown.map{it.prefix+it.speech.take(90)}.toTypedArray()
  val dialog=activity.hapticDialog().setTitle("更正说话人 · "+(begin+1)+"–"+(begin+shown.size)+"/"+rows.size)
   .setItems(names){d,i->activity.dialogTap(d);choose(activity,rows,begin+i,run,canWork,corrected,page)}
   .setNegativeButton("关闭"){d,_->activity.dialogTap(d)}
  if(page>0)dialog.setNeutralButton("上一页"){d,_->activity.dialogTap(d);show(activity,rows,run,canWork,corrected,page-1)}
  if(begin+shown.size<rows.size)dialog.setPositiveButton("下一页"){d,_->activity.dialogTap(d);show(activity,rows,run,canWork,corrected,page+1)}
  dialog.show()
 }
 private fun choose(activity:Activity,rows:List<TranscriptReview.Row>,index:Int,run:VoiceLearningRun?,
                    canWork:()->Boolean,corrected:(Int,WearerVoiceStore.ProfileInfo)->Unit,page:Int){
  if(!canWork())return
  val row=rows[index]
  val turn=run?.find(row)
  val profiles=try{WearerVoiceStore.listProfiles(activity)}catch(e:Exception){
   Toast.makeText(activity,e.message?:"无法读取声纹",Toast.LENGTH_LONG).show();return}
  if(profiles.isEmpty()){
   activity.hapticDialog().setTitle("先登记声纹").setMessage("请先在“声纹→声纹管理”录制并命名，之后可把这段发言归给已登记的人。")
    .setPositiveButton("知道了"){d,_->activity.dialogTap(d)}.show();return
  }
  val pending:List<VoiceLearningStore.CandidateInfo>
  val samples:List<VoiceLearningStore.SampleInfo>
  try{
   pending=VoiceLearningStore.listCandidates(activity)
   samples=profiles.flatMap{VoiceLearningStore.listSamples(activity,it.id)}
  }catch(e:Exception){Toast.makeText(activity,e.message?:"无法读取学习样本",Toast.LENGTH_LONG).show();return}
  val stillPending=turn?.candidateId?.let{id->pending.any{it.id==id}}==true
  val spanStillPending=turn?.spanCandidateId?.let{id->pending.any{it.id==id}}==true
  val learned=turn?.candidateId?.let{id->samples.firstOrNull{it.id==id}}
  // Confirmed templates no longer retain their audio and cannot be rechecked against
  // the current profile library. A text correction must not silently move a template.
  val canLearn=stillPending
  val action=if(canLearn)"选择归属并补充声纹" else "选择归属，仅更正显示"
  var player:MediaPlayer?=null
  val detail=if(turn==null)"该段没有合格学习音频。" else if(!canLearn&&learned==null&&
   (turn.spanCandidateId==null||spanStillPending))turn.spanReason?:turn.reason else turn.reason
  val options=activity.hapticDialog().setTitle(action)
   .setMessage(row.prefix+row.speech+"\n\n"+detail+
    if(canLearn)"\n选择姓名后还需确认，才会更新归属。" else if(learned!=null)"\n这条样本已学习且不再保留试听音频，此处只更正显示。若声纹归属错误，请先在声纹管理中删除补充样本，再重新识别或补录。" else "")
   .setPositiveButton("选择已登记人物"){d,_->activity.dialogTap(d)
    activity.hapticDialog().setTitle("这段是谁说的？")
     .setItems(profiles.map{p->p.name+if(profiles.count{it.name==p.name}>1)" · "+p.id.takeLast(6) else ""}.toTypedArray()){select,i->activity.dialogTap(select)
      val person=profiles[i]
      activity.hapticDialog().setTitle(if(canLearn)"更正并补充声纹？" else "更正说话人？")
       .setMessage("归属为“"+person.name+"”。"+if(canLearn)"这段合格单人语音的特征将归入该用户的声纹，初始登记保留。" else "此段只更正人物标签，未通过质量筛选的音频不加入声纹。")
       .setNegativeButton("取消"){x,_->activity.dialogTap(x)}
       .setPositiveButton("确认"){x,_->activity.dialogTap(x)
        if(!canWork()||!working.compareAndSet(false,true))return@setPositiveButton
        Thread{
         try{
          if(canLearn)VoiceLearningStore.confirmCandidate(activity,checkNotNull(turn?.candidateId),person.id)
          else require(WearerVoiceStore.listProfiles(activity).any{it.id==person.id}){"该用户已删除"}
          activity.runOnUiThread{working.set(false);if(!activity.isDestroyed){corrected(index,person);Toast.makeText(activity,if(canLearn)"已更正，声纹将在下次识别生效" else "已更正人物标签",Toast.LENGTH_LONG).show()}}
         }catch(e:Exception){activity.runOnUiThread{working.set(false);Toast.makeText(activity,e.message?:"更正失败",Toast.LENGTH_LONG).show()}}
        }.start()
       }.show()
     }.show()
   }.setNegativeButton("返回"){d,_->activity.dialogTap(d);show(activity,rows,run,canWork,corrected,page)}
  val audio=turn?.candidateId?.let{VoiceLearningStore.candidateAudio(activity,it)}
  if(audio!=null)options.setNeutralButton("试听片段",null)
  val dialog=options.create()
  dialog.setOnDismissListener{player?.release();player=null;stopPlayback=null}
  dialog.show()
  stopPlayback={dialog.dismiss()}
  if(audio!=null)dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).onHapticClick{
   try{player?.release();player=MediaPlayer().apply{setDataSource(audio.absolutePath);prepare();start()}}
   catch(_:Exception){Toast.makeText(activity,"无法播放该片段",Toast.LENGTH_SHORT).show()}
  }
 }
}
