package org.hotwordtest

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView

/** The original Activity view stays underneath the voice pages until the stack closes. */
internal class VoicePageNavigator(private val activity:Activity) {
 private var host:FrameLayout?=null
 private var base:View?=null
 private var shown:View?=null
 private var key:String?=null
 private var depth=0
 private var animator:AnimatorSet?=null
 private var shield:View?=null
 val transitioning:Boolean get()=animator!=null

 private fun motionEnabled():Boolean = try {
  ValueAnimator.areAnimatorsEnabled() &&
   Settings.Global.getFloat(activity.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f &&
   Settings.Global.getFloat(activity.contentResolver,Settings.Global.TRANSITION_ANIMATION_SCALE,1f)>0f
 } catch(_:Exception){ValueAnimator.areAnimatorsEnabled()}

 private fun attached(container:FrameLayout)=
  (activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)===container)

 private fun ensureHost():FrameLayout {
  host?.takeIf{attached(it)}?.let{return it}
  finishMotion()
  val content=activity.findViewById<ViewGroup>(android.R.id.content)
  val original=checkNotNull(content.getChildAt(0)){"主页面尚未准备完成"}
  content.removeView(original)
  val container=FrameLayout(activity)
  container.addView(original,FrameLayout.LayoutParams(-1,-1))
  content.addView(container,ViewGroup.LayoutParams(-1,-1))
  host=container;base=original;shown=original;key=null;depth=0
  return container
 }

 private fun reset(view:View){view.translationX=0f;view.alpha=1f}
 private fun finishMotion(){animator?.end()}
 private fun firstScroll(view:View):ScrollView? {
  if(view is ScrollView)return view
  if(view is ViewGroup)for(i in 0 until view.childCount){
   firstScroll(view.getChildAt(i))?.let{return it}
  }
  return null
 }
 private fun animate(from:View,to:View,push:Boolean,done:()->Unit) {
  val container=host?:run{done();return}
  val width=(container.width.takeIf{it>0}?:activity.window.decorView.width.takeIf{it>0}
   ?:activity.resources.displayMetrics.widthPixels).coerceAtLeast(1).toFloat()
  if(!motionEnabled()){done();return}
  val blocker=View(activity).apply{isClickable=true;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
  shield=blocker;container.addView(blocker,FrameLayout.LayoutParams(-1,-1))
  to.visibility=View.VISIBLE;to.translationX=if(push)width else -width*.20f;to.alpha=.7f
  val set=AnimatorSet();var ended=false
  set.playTogether(
   ObjectAnimator.ofFloat(to,View.TRANSLATION_X,to.translationX,0f),
   ObjectAnimator.ofFloat(to,View.ALPHA,.7f,1f),
   ObjectAnimator.ofFloat(from,View.TRANSLATION_X,0f,if(push)-width*.20f else width),
   ObjectAnimator.ofFloat(from,View.ALPHA,1f,.7f)
  )
  set.duration=220;set.interpolator=DecelerateInterpolator(1.5f)
  set.addListener(object:AnimatorListenerAdapter(){
   override fun onAnimationEnd(animation:Animator){
    if(ended)return;ended=true
    reset(from);reset(to);shield?.let{(it.parent as? ViewGroup)?.removeView(it)};shield=null;animator=null
    done()
   }
  })
  animator=set;set.start()
 }

 /** Same-page content refreshes do not animate or interrupt the current task. */
 fun show(view:View,pageKey:String,pageDepth:Int){
  require(pageDepth>0)
  finishMotion()
  val container=ensureHost();val previous=checkNotNull(shown)
  val previousKey=key;val previousDepth=depth
  container.addView(view,FrameLayout.LayoutParams(-1,-1));view.bringToFront()
  shown=view;key=pageKey;depth=pageDepth
  previous.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
  view.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
  val finish={
   if(previous===base)previous.visibility=View.GONE else container.removeView(previous)
   view.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
  }
  when(VoicePageMotionPolicy.forPage(previousKey,previousDepth,pageKey,pageDepth)){
   VoicePageMotionPolicy.Motion.REFRESH->finish()
   VoicePageMotionPolicy.Motion.PUSH->animate(previous,view,true,finish)
   VoicePageMotionPolicy.Motion.POP->animate(previous,view,false,finish)
  }
 }

 /** Call only after the caller has handled unsaved recording and work-in-progress. */
 fun close(onClosed:()->Unit){
  finishMotion()
  val container=host;val from=shown;val target=base
  if(container==null||from==null||target==null||!attached(container)||from===target){
   host=null;base=null;shown=null;key=null;depth=0;onClosed();return
  }
  val previousScroll=firstScroll(target)?.scrollY
  target.visibility=View.VISIBLE;target.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
  from.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
  animate(from,target,false){
   target.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
   host=null;base=null;shown=null;key=null;depth=0
   onClosed()
   if(previousScroll!=null&&!activity.isFinishing&&!activity.isDestroyed){
    val content=activity.findViewById<ViewGroup>(android.R.id.content)
    val newRoot=content?.getChildAt(0)
    newRoot?.post{if(host==null&&content.getChildAt(0)===newRoot)firstScroll(newRoot)?.scrollTo(0,previousScroll)}
   }
  }
 }

 /** Finish the visual change before a lifecycle cancellation; no recording is touched. */
 fun settle(){finishMotion()}
}
