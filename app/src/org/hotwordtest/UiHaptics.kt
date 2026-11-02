package org.hotwordtest

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText

internal fun View.tapFeedback(){
 if(isEnabled)performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

internal fun View.stepFeedback(){
 if(isEnabled)performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

internal fun View.onHapticClick(action:()->Unit){
 setOnClickListener{view->
  if(view.isEnabled){view.tapFeedback();action()}
 }
}

internal fun Activity.dialogTap(dialog:DialogInterface){
 val decor=(dialog as? Dialog)?.window?.decorView
 (decor?.takeIf{it.isAttachedToWindow}?:window.decorView).tapFeedback()
}

internal fun Activity.appDialog(destructive:Boolean=false):AlertDialog.Builder{
 val theme=if(destructive)"AppDestructiveAlert" else "AppAlert"
 return AlertDialog.Builder(this,resources.getIdentifier(theme,"style",packageName))
}

internal fun Activity.hapticDialog(destructive:Boolean=false):AlertDialog.Builder=
 appDialog(destructive).setOnCancelListener{dialog->dialogTap(dialog)}

internal fun EditText.styleDialogField(){
 val density=resources.displayMetrics.density
 fun dp(value:Int)=(value*density+0.5f).toInt()
 textSize=16f
 setTextColor(Color.rgb(28,28,30))
 setHintTextColor(Color.rgb(93,93,101))
 minHeight=dp(50)
 background=GradientDrawable().apply{setColor(Color.rgb(242,242,247));cornerRadius=dp(10).toFloat()}
 setPadding(dp(12),dp(8),dp(12),dp(8))
}
