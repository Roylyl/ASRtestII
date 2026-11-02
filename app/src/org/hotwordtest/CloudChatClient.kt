package org.hotwordtest

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Blocking network call: callers must invoke it off the Android UI thread. */
class CloudChatClient internal constructor(
 private val cfg:CloudSettings.RequestConfig,
 private val makeConnection:(URL)->HttpURLConnection,
 private val connectTimeoutMs:Int,
 private val readTimeoutMs:Int
) {
 constructor(settings:CloudSettings):this(settings.requestConfig(),
  {url->url.openConnection() as HttpURLConnection},15000,120000)
 data class Result @JvmOverloads constructor(
  val text:String,val finishReason:String,val promptTokens:Int?,val completionTokens:Int?,
  val responseModel:String="",val reasoningContentPresent:Boolean=false)

 class Progress(val thinking:()->Unit,val writing:()->Unit={},val content:((String)->Unit)?=null)

 fun complete(system:String,user:String,maxTokens:Int,cancelled:()->Boolean):Result =
  complete(system,user,maxTokens,cancelled,null)

 fun complete(system:String,user:String,maxTokens:Int,cancelled:()->Boolean,progress:Progress?):Result {
  require(maxTokens in 0..32768){ "云端输出长度设置无效" }
  fun checkCancelled(){if(Thread.currentThread().isInterrupted||cancelled())throw CancellationException("云端请求已取消")}
  checkCancelled()
  val address=URL(CloudEndpoint.normalize(cfg.endpoint))
  val messages=JSONArray()
  if(system.isNotBlank())messages.put(JSONObject().put("role","system").put("content",system))
  messages.put(JSONObject().put("role","user").put("content",user))
  val official=cfg.model==CloudEndpoint.DEEPSEEK_FLASH_MODEL && address.protocol=="https" &&
   address.host.equals(CloudEndpoint.DEEPSEEK_HOST,true) && (address.port==-1 || address.port==443)
  val streamProgress=progress.takeIf{official}
  val payloadJson=JSONObject().put("model",cfg.model).put("messages",messages).put("stream",streamProgress!=null)
  if(official){
   payloadJson.put("thinking",JSONObject().put("type","enabled"))
  }
  if(maxTokens>0)payloadJson.put("max_tokens",maxTokens)
  val payload=payloadJson.toString().toByteArray(StandardCharsets.UTF_8)
  val connectionRef=AtomicReference<HttpURLConnection?>()
  val cancelledDuringIo=AtomicBoolean(false)
  val owner=Thread.currentThread()
  val watcher=Thread({
   try {
    while(!Thread.currentThread().isInterrupted){
     if(owner.isInterrupted||cancelled()){
      cancelledDuringIo.set(true); connectionRef.get()?.disconnect(); break
     }
     Thread.sleep(100)
    }
   }catch(_:InterruptedException){Thread.currentThread().interrupt()}
  },"cloud-request-cancel").apply{isDaemon=true;start()}
  var connection:HttpURLConnection?=null
  try {
   checkCancelled()
   connection=makeConnection(address)
   connectionRef.set(connection)
   if(cancelledDuringIo.get())throw CancellationException("云端请求已取消")
   connection.instanceFollowRedirects=false
   connection.requestMethod="POST"
   connection.connectTimeout=connectTimeoutMs
   connection.readTimeout=readTimeoutMs
   connection.doOutput=true
   connection.setRequestProperty("Authorization","Bearer ${cfg.apiKey}")
   connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
   connection.setRequestProperty("Accept",if(streamProgress!=null)"text/event-stream" else "application/json")
   connection.setRequestProperty("Connection","close")
   connection.setFixedLengthStreamingMode(payload.size)
   connection.outputStream.use{it.write(payload)}
   checkCancelled()
   val code=connection.responseCode
   checkCancelled()
   if(code !in 200..299){
    val contextExceeded = if(code==400 || code==413 || code==422) try {
     val error=connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
      val buffer=CharArray(8192); val n=reader.read(buffer); if(n>0)String(buffer,0,n) else ""
     }?.lowercase()?:""
     listOf("context_length_exceeded","maximum context length","maximum context window","max context","context window exceeded").any{it in error}
    } catch(_:Exception){false} else false
    checkCancelled()
    val detail=when {
     contextExceeded->"输入超过该云端模型的上下文限制，请分开导入后重试"
     else->when(code){
     in 300..399->"云端地址发生重定向，为保护API Key已拒绝，请填写最终接口地址"
     401,403->"云端认证失败，请检查API Key和模型访问权限"
     404->"云端接口不存在，请检查API地址"
     408,504->"云端请求超时"
     413->"请求内容超过云端允许的长度"
     429->"云端请求被限流或配额不足"
     in 500..599->"云端服务暂不可用（HTTP $code）"
     else->"云端请求失败（HTTP $code），请检查地址和模型配置"
     }
    }
    throw IllegalStateException(detail)
   }
   if(streamProgress!=null){
    return connection.inputStream.use{readStream(it,streamProgress,::checkCancelled)}
   }
   val raw=connection.inputStream.use{input->
    val out=ByteArrayOutputStream();val buf=ByteArray(8192)
    while(true){
     checkCancelled();val n=input.read(buf);if(n<0)break
     if(out.size().toLong()+n>8L*1024*1024)throw IllegalStateException("云端响应过大")
     out.write(buf,0,n)
    }
    out.toString("UTF-8")
   }
   checkCancelled()
   val response=try{JSONObject(raw)}catch(_:Exception){throw IllegalStateException("云端返回的JSON格式无效")}
   val choice=response.optJSONArray("choices")?.optJSONObject(0)?:throw IllegalStateException("云端响应缺少回答")
   val finish=choice.optString("finish_reason","")
   requireStop(finish)
   val message=choice.optJSONObject("message")?:throw IllegalStateException("云端响应缺少回答内容")
   val rawContent=message.opt("content")
   val text=if(rawContent is String)rawContent.trim() else ""
   if(text.isBlank())throw IllegalStateException("云端返回空回答")
   val usage=response.optJSONObject("usage")
   fun number(key:String):Int?=usage?.optInt(key,-1)?.takeIf{it>=0}
   val responseModel=(response.opt("model") as? String)?.trim().orEmpty()
   val reasoningContentPresent=(message.opt("reasoning_content") as? String)?.isNotBlank()==true
   return Result(text,finish,number("prompt_tokens"),number("completion_tokens"),responseModel,reasoningContentPresent)
  }catch(e:CancellationException){throw e}
   catch(e:IOException){
    if(cancelledDuringIo.get()||owner.isInterrupted||cancelled())throw CancellationException("云端请求已取消")
    throw IllegalStateException(if(e is java.net.SocketTimeoutException)"云端请求超时，请检查网络或重试" else "云端连接失败，请检查网络和API地址")
   }finally{
    connectionRef.set(null)
    connection?.disconnect()
    watcher.interrupt()
   }
 }

 private fun requireStop(finish:String){
  when(finish){
   "stop"->return
   "length"->throw IllegalStateException("云端回答达到长度上限，结果未完成")
   "content_filter"->throw IllegalStateException("云端服务未返回可用内容")
   else->throw IllegalStateException("云端回答未正常结束，请检查模型及服务兼容性")
  }
 }

 private fun readStream(input:InputStream,progress:Progress,checkCancelled:()->Unit):Result {
  // Bound bytes before reading lines, including malformed streams without line breaks.
  val limited=object:FilterInputStream(input){
   var bytes=0L
   override fun read(b:ByteArray,off:Int,len:Int):Int{
    val count=super.read(b,off,len)
    if(count>0)bytes+=count
    if(bytes>8L*1024*1024)throw IllegalStateException("云端响应过大")
    return count
   }
  }
  val body=StringBuilder()
  var finish=""
  var thinking=false
  var model=""
  var usage:JSONObject?=null
  limited.bufferedReader(Charsets.UTF_8).use{reader->
   while(true){
    checkCancelled()
    val line=reader.readLine()?:throw IllegalStateException("云端响应中断，结果未完成")
    if(!line.startsWith("data:"))continue
    val data=line.substring(5).trim()
    if(data=="[DONE]")break
    val chunk=try{JSONObject(data)}catch(_:Exception){throw IllegalStateException("云端返回的流式数据无效")}
    if(chunk.has("error"))throw IllegalStateException("云端报告生成失败，结果未完成")
    model=(chunk.opt("model") as? String)?:model
    chunk.optJSONObject("usage")?.let{usage=it}
    val choice=chunk.optJSONArray("choices")?.optJSONObject(0)?:throw IllegalStateException("云端响应缺少回答")
    if(finish.isNotEmpty())throw IllegalStateException("云端响应结束后仍有回答，结果未采用")
    val delta=choice.optJSONObject("delta")
    if(!thinking && (delta?.opt("reasoning_content") as? String)?.isNotEmpty()==true){
     thinking=true
     if(body.isEmpty())progress.thinking()
    }
    val part=delta?.opt("content") as? String
    if(!part.isNullOrEmpty()){
     val start=body.length
     body.append(part)
     // Catch a thought tag even if the server splits it across consecutive chunks.
     val tail=body.substring((start-8).coerceAtLeast(0))
     if(tail.contains("<think>",true)||tail.contains("</think>",true))
      throw IllegalStateException("云服务未返回完整的最终正文。")
     if(start==0)progress.writing()
     progress.content?.invoke(part)
    }
    (choice.opt("finish_reason") as? String)?.let{requireStop(it);finish=it}
   }
  }
  checkCancelled()
  requireStop(finish)
  val result=body.toString().trim()
  if(result.isEmpty())throw IllegalStateException("云端返回空回答")
  fun tokens(key:String)=usage?.optInt(key,-1)?.takeIf{it>=0}
  return Result(result,finish,tokens("prompt_tokens"),tokens("completion_tokens"),model,thinking)
 }
}
