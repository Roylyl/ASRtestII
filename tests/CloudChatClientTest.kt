package org.hotwordtest

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

fun main(){
 check(CloudEndpoint.normalize("api.example.com")=="https://api.example.com/v1/chat/completions")
 check(CloudEndpoint.normalize("https://api.example.com/compatible-mode/v1/")=="https://api.example.com/compatible-mode/v1/chat/completions")
 check(CloudEndpoint.normalize("https://api.example.com/v1/chat/completions?api-version=1").endsWith("?api-version=1"))
 check(runCatching{CloudEndpoint.normalize("http://192.168.1.1:9000")}.isFailure)
 check(runCatching{CloudEndpoint.normalize("http://127.0.0.2:9000")}.isFailure)
 check(runCatching{CloudEndpoint.normalize("https://example.com/v1?api_key=secret")}.isFailure)
 check(CloudEndpoint.normalize("http://[::1]:8090").startsWith("http://[::1]:8090"))
 val calls=AtomicInteger();val forwarded=AtomicInteger();val auth=mutableListOf<String>();val bodies=mutableListOf<String>()
 val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
 server.executor=Executors.newCachedThreadPool{r->Thread(r).apply{isDaemon=true}}
 server.createContext("/"){ exchange->
  calls.incrementAndGet();auth.add(exchange.requestHeaders.getFirst("Authorization")?:"");bodies.add(exchange.requestBody.bufferedReader().use{it.readText()})
  val path=exchange.requestURI.path
  if(path.startsWith("/redirect")){
   exchange.responseHeaders.add("Location","http://127.0.0.1:${server.address.port}/forwarded/v1/chat/completions")
   exchange.sendResponseHeaders(302,-1);exchange.close();return@createContext
  }
  if(path.startsWith("/forwarded"))forwarded.incrementAndGet()
  if(path.startsWith("/slow"))Thread.sleep(1200)
  val status=if(path.startsWith("/context"))400 else 200
  val response=when {
   status==400->"""{"error":{"code":"context_length_exceeded","message":"super-secret"}}"""
   path.startsWith("/length")->"""{"choices":[{"finish_reason":"length","message":{"content":"不完整"}}]}"""
   else->"""{"choices":[{"finish_reason":"stop","message":{"content":"测试通过"}}],"usage":{"prompt_tokens":7,"completion_tokens":3}}"""
  }.toByteArray(Charsets.UTF_8)
  try{exchange.sendResponseHeaders(status,response.size.toLong());exchange.responseBody.use{it.write(response)}}catch(_:Exception){}
 }
 server.start()
 fun client(path:String):CloudChatClient {
  val cfg=CloudSettings.RequestConfig("http://127.0.0.1:${server.address.port}/$path/v1/chat/completions","model/test","super-secret")
  return CloudChatClient(cfg,{it.openConnection() as HttpURLConnection},1000,3000)
 }
 try{
  val good=client("ok").complete("system","hello",0){false}
  check(good.text=="测试通过"&&good.promptTokens==7&&good.completionTokens==3)
  val sent=JSONObject(bodies.last());check(sent.getString("model")=="model/test"&&!sent.has("max_tokens"))
  check(auth.last()=="Bearer super-secret")
  val truncated=runCatching{client("length").complete("","hello",5){false}}.exceptionOrNull()
  check(truncated?.message?.contains("未完成")==true)
  check(JSONObject(bodies.last()).getInt("max_tokens")==5)
  val redirect=runCatching{client("redirect").complete("","hello",0){false}}.exceptionOrNull()
  check(redirect?.message?.contains("重定向")==true&&forwarded.get()==0)
  val context=runCatching{client("context").complete("","hello",0){false}}.exceptionOrNull()
  check(context?.message?.contains("上下文") == true && context.message?.contains("super-secret")==false)
  val cancel=AtomicBoolean(false)
  Thread{Thread.sleep(150);cancel.set(true)}.apply{isDaemon=true;start()}
  val cancelled=runCatching{client("slow").complete("","hello",0){cancel.get()}}.exceptionOrNull()
  check(cancelled is CancellationException)
  check(calls.get()==5)
  println("cloud endpoint, snapshot, request, limits, redirect, cancellation: OK")
 }finally{server.stop(0)}
}
