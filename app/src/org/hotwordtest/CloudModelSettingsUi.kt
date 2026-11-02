package org.hotwordtest

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.content.DialogInterface
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

@Suppress("DEPRECATION")
class CloudModelSettingsUi:DialogFragment(){
 companion object{
  private const val TAG="cloud_model_settings_editor"
  private val blue=Color.rgb(0,122,255)
  private val grey=Color.rgb(93,93,101)
  private fun Context.dp(n:Int)=(resources.displayMetrics.density*n+0.5f).toInt()
  private fun rounded(context:Context,color:Int,radius:Int)=GradientDrawable().apply{
   setColor(color);cornerRadius=context.dp(radius).toFloat()
  }
  private fun label(context:Context,value:String,size:Int,color:Int)=TextView(context).apply{
   text=value;textSize=size.toFloat();setTextColor(color)
  }
  fun open(host:Activity){
   val manager=host.fragmentManager
   if(host.isFinishing||host.isDestroyed||manager.isStateSaved||manager.findFragmentByTag(TAG)!=null)return
   manager.beginTransaction().add(CloudModelSettingsUi(),TAG).commitNow()
  }
  fun addSettings(host:Activity,group:LinearLayout){
   val saved=CloudSettings(host).summary()
   val configured=saved.endpoint.isNotBlank()&&saved.model.isNotBlank()&&saved.hasApiKey
   val line=LinearLayout(host).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,host.dp(9),0,host.dp(4))}
   val badge=label(host,if(configured)"已配置" else "未配置",13,
    if(configured)Color.rgb(22,111,64) else Color.rgb(107,76,25)).apply{
    setTypeface(null,Typeface.BOLD)
    setPadding(host.dp(9),host.dp(3),host.dp(9),host.dp(3))
    background=rounded(host,if(configured)Color.rgb(231,247,236) else Color.rgb(251,241,219),8)
   }
   line.addView(badge)
   if(saved.model.isNotBlank())line.addView(label(host,saved.model,14,grey).apply{
    setPadding(host.dp(9),0,0,0);setSingleLine(true);ellipsize=android.text.TextUtils.TruncateAt.END
   },LinearLayout.LayoutParams(0,-2,1f))
   group.addView(line)
   group.addView(label(host,"从测试页手动开始勘误和摘要时，应用会将转写文字发给你配置的云端服务。",13,grey).apply{
    setPadding(0,host.dp(4),0,host.dp(10));setLineSpacing(host.dp(2).toFloat(),1f)
   })
   group.addView(label(host,if(configured)"编辑云端模型配置" else "配置云端模型",16,blue).apply{
    gravity=Gravity.CENTER;minHeight=host.dp(50);setTypeface(null,Typeface.BOLD)
    background=rounded(host,Color.rgb(235,244,255),12);isFocusable=true
    onHapticClick{open(host)}
   })
  }
 }

 private lateinit var endpointField:EditText
 private lateinit var keyField:EditText
 private lateinit var modelField:EditText
 private lateinit var errorLabel:TextView
 private lateinit var settings:CloudSettings

 override fun onCreateDialog(savedInstanceState:Bundle?):Dialog{
  val host=checkNotNull(activity)
  settings=CloudSettings(host)
  val saved=settings.summary()
  val useDefaults=saved.endpoint.isEmpty() && saved.model.isEmpty() && !saved.hasApiKey
  val content=LinearLayout(host).apply{
   orientation=LinearLayout.VERTICAL;setPadding(host.dp(22),host.dp(4),host.dp(22),host.dp(4))
   isFocusableInTouchMode=true
  }
  fun field(title:String,hintText:String,type:Int,next:Int):EditText{
   val caption=label(host,title,14,Color.BLACK).apply{setTypeface(null,Typeface.BOLD);setPadding(0,host.dp(14),0,host.dp(5))}
   content.addView(caption)
   val field=EditText(host).apply{
    id=View.generateViewId();isSaveEnabled=false;setSingleLine(true);inputType=type;imeOptions=next
    hint=hintText;styleDialogField()
   }
   caption.labelFor=field.id
   content.addView(field,LinearLayout.LayoutParams(-1,host.dp(50)))
   return field
  }
  endpointField=field("服务地址",CloudEndpoint.DEEPSEEK_BASE,
   InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,EditorInfo.IME_ACTION_NEXT)
  endpointField.setText(savedInstanceState?.getString("draft_endpoint")?:if(useDefaults)CloudEndpoint.DEEPSEEK_BASE else saved.endpoint)
  keyField=field("API Key",if(saved.hasApiKey)"已保存，留空沿用" else "输入服务提供方的密钥",
   InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,EditorInfo.IME_ACTION_NEXT).apply{
   transformationMethod=PasswordTransformationMethod.getInstance()
   importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
  }
  if(saved.hasApiKey)content.addView(label(host,"输入新密钥即可替换已保存的API Key。",12,grey).apply{setPadding(0,host.dp(4),0,0)})
  modelField=field("模型名称","输入服务提供方的模型名称",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
   EditorInfo.IME_ACTION_DONE)
  modelField.setText(savedInstanceState?.getString("draft_model")?:if(useDefaults)CloudEndpoint.DEEPSEEK_FLASH_MODEL else saved.model)
  errorLabel=label(host,"",13,Color.rgb(183,38,31)).apply{
   visibility=View.GONE;setPadding(0,host.dp(8),0,0);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
  }
  content.addView(errorLabel)
  val scroll=ScrollView(host).apply{addView(content)}
  return host.appDialog().setTitle("云端模型配置").setView(scroll)
   .setNegativeButton("取消"){dialog,_->host.dialogTap(dialog)}.setPositiveButton("保存",null).create()
 }

 override fun onStart(){
  super.onStart()
  val editor=dialog as? AlertDialog?:return
  editor.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
  editor.getButton(AlertDialog.BUTTON_POSITIVE).onHapticClick{saveSettings()}
 }

 private fun invalid(message:String,field:EditText?=null){
  errorLabel.text=message;errorLabel.visibility=View.VISIBLE
  field?.requestFocus()
 }
 private fun saveSettings(){
  val endpoint=endpointField.text.toString().trim()
  val model=modelField.text.toString().trim()
  val key=keyField.text.toString()
  try{CloudEndpoint.normalize(endpoint)}catch(e:IllegalArgumentException){invalid(e.message?:"请检查服务地址",endpointField);return}
  if(model.isBlank()){invalid("请输入模型名称",modelField);return}
  if(key.isBlank()&&!settings.summary().hasApiKey){invalid("请输入API Key",keyField);return}
  try{
   settings.save(endpoint,model,key.takeUnless{it.isBlank()})
  }catch(e:IllegalArgumentException){invalid(e.message?:"请检查配置内容");return}
   catch(e:IllegalStateException){invalid(e.message?:"保存配置失败，请重试");return}
  val host=activity?:return
  keyField.text.clear()
  dismiss()
  (host as? MainActivity)?.cloudModelSettingsSaved()
  Toast.makeText(host,"云端模型配置已保存",Toast.LENGTH_SHORT).show()
 }

 override fun onSaveInstanceState(outState:Bundle){
  super.onSaveInstanceState(outState)
  if(::endpointField.isInitialized)outState.putString("draft_endpoint",endpointField.text.toString())
  if(::modelField.isInitialized)outState.putString("draft_model",modelField.text.toString())
 }
 override fun onCancel(dialog:DialogInterface){
  activity?.dialogTap(dialog)
  super.onCancel(dialog)
 }
 override fun onDestroyView(){
  if(::keyField.isInitialized)keyField.text.clear()
  super.onDestroyView()
 }
}
