package org.hotwordtest

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Address normalization is intentionally independent of Android so it can be tested on the JVM. */
object CloudEndpoint {
 const val DEEPSEEK_HOST="api.deepseek.com"
 const val DEEPSEEK_BASE="https://$DEEPSEEK_HOST/v1"
 const val DEEPSEEK_FLASH_MODEL="deepseek-flash"

 fun normalize(address:String):String {
  val input=address.trim()
  require(input.isNotEmpty()){ "请输入云端API地址" }
  val uri=try { URI(if(Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(input)) input else "https://$input") }
          catch(_:Exception){throw IllegalArgumentException("云端API地址格式无效")}
  val scheme=uri.scheme?.lowercase()?:""
  val host=uri.host?.lowercase()?:throw IllegalArgumentException("云端API地址缺少有效主机")
  require(uri.rawUserInfo==null && uri.rawFragment==null){ "API地址不能包含账号密码或片段标识" }
  val secretNames=setOf("api_key","apikey","api-key","key","token","access_token","authorization","signature","sig")
  val queryNames=(uri.rawQuery?:"").split('&').map {
   try { java.net.URLDecoder.decode(it.substringBefore('='),"UTF-8").lowercase() }
   catch(_:Exception){throw IllegalArgumentException("API地址查询参数无效")}
  }
  require(queryNames.none{it in secretNames}){ "API Key请单独填写，不能放在API地址中" }
  require(scheme=="https"||scheme=="http"){ "API地址仅支持HTTPS" }
  val loopback=host=="localhost"||host=="127.0.0.1"||host=="::1"||host=="[::1]"
  require(scheme=="https"||loopback){ "出于密钥安全，非本机API地址必须使用HTTPS" }
  var path=(uri.rawPath?:"").trimEnd('/')
  path=when {
   path.endsWith("/chat/completions")->path
   Regex("/v[0-9]+(?:beta[0-9]*)?$",RegexOption.IGNORE_CASE).containsMatchIn(path)->"$path/chat/completions"
   else->"$path/v1/chat/completions"
  }
  return "$scheme://${uri.rawAuthority}$path"+(uri.rawQuery?.let{"?$it"}?:"")
 }
}

/** Only the encrypted API key and non-secret endpoint/model are stored in private preferences. */
class CloudSettings(context:Context) {
 data class Summary(val endpoint:String,val model:String,val hasApiKey:Boolean)
 internal class RequestConfig(val endpoint:String,val model:String,val apiKey:String)
 private val prefs=context.applicationContext.getSharedPreferences("cloud_asr_v1",Context.MODE_PRIVATE)
 private val alias="org.hotwordtest.cloud.key.v1"

 @Synchronized fun summary():Summary = Summary(prefs.getString("endpoint","")?:"",prefs.getString("model","")?:"",prefs.contains("api_key"))
 /** newApiKey=null preserves the current key; an empty string removes it. */
 @Synchronized fun save(endpoint:String,model:String,newApiKey:String?){
  val target=CloudEndpoint.normalize(endpoint)
  require(model.isNotBlank()){ "请输入云端模型名称" }
  if(newApiKey!=null)require(!newApiKey.any{it=='\r'||it=='\n'}){ "API Key不能包含换行" }
  val existing=prefs.getString("endpoint",null)
  if(prefs.contains("api_key") && existing!=null && origin(existing)!=origin(target)) {
   require(!newApiKey.isNullOrEmpty()){ "更换云端服务地址时，请重新输入该服务的API Key" }
  }
  val protected=if(!newApiKey.isNullOrEmpty())encrypt(newApiKey) else null
  val edit=prefs.edit().putString("endpoint",target).putString("model",model)
  if(newApiKey!=null){if(protected==null)edit.remove("api_key")else edit.putString("api_key",protected)}
  check(edit.commit()){ "保存云端配置失败" }
 }
 @Synchronized fun clear(){check(prefs.edit().clear().commit()){ "清除云端配置失败" }}
 @Synchronized internal fun requestConfig():RequestConfig {
  val endpoint=prefs.getString("endpoint",null)?:throw IllegalStateException("请先配置云端API地址")
  val model=prefs.getString("model",null)?.takeIf{it.isNotBlank()}?:throw IllegalStateException("请先配置云端模型")
  val stored=prefs.getString("api_key",null)?:throw IllegalStateException("请先配置API Key")
  return RequestConfig(CloudEndpoint.normalize(endpoint),model,decrypt(stored))
 }
 private fun origin(address:String):String {
  val uri=URI(CloudEndpoint.normalize(address))
  val port=if(uri.port>=0)uri.port else if(uri.scheme.equals("https",true))443 else 80
  return "${uri.scheme.lowercase()}://${uri.host.lowercase()}:$port"
 }
 private fun key(create:Boolean):SecretKey {
  val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  val existing=store.getKey(alias,null)
  if(existing is SecretKey)return existing
  check(create){ "密钥无法解密，请重新设置API Key" }
  val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
  generator.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
   .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
   .setKeySize(256).build())
  return generator.generateKey()
 }
 private fun encrypt(value:String):String = try {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true))
  val bytes=cipher.doFinal(value.toByteArray(Charsets.UTF_8))
  Base64.encodeToString(cipher.iv+bytes,Base64.NO_WRAP)
 }catch(_:Exception){throw IllegalStateException("无法安全保存API Key，请检查设备密钥存储")}
 private fun decrypt(value:String):String = try {
  val data=Base64.decode(value,Base64.NO_WRAP)
  require(data.size>=12+16)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,data,0,12))
  String(cipher.doFinal(data,12,data.size-12),Charsets.UTF_8).also{require(it.isNotEmpty())}
 }catch(_:Exception){throw IllegalStateException("无法读取API Key，请重新设置")}
}
