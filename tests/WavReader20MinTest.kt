package org.hotwordtest
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
class ZeroWav(private val frames:Int, private val actualBytes:Int=frames*2, private val advertised:Int=frames*2):InputStream(){
 private val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply{
  put("RIFF".toByteArray());putInt(36+advertised);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(16000);putInt(32000);putShort(2);putShort(16);put("data".toByteArray());putInt(advertised)
 }.array()
 private var at=0
 override fun read():Int {if(at<header.size)return header[at++].toInt() and 255;if(at<header.size+actualBytes){at++;return 0};return -1}
 override fun read(b:ByteArray,off:Int,len:Int):Int {if(len==0)return 0;var n=0;while(n<len){val v=read();if(v<0)break;b[off+n]=v.toByte();n++};return if(n==0)-1 else n}
}
fun main(){
 val twenty=16000*1200;val result=WavReader.read16k(ZeroWav(twenty));check(result.size==twenty&&result.first()==0f&&result.last()==0f)
 try{WavReader.read16k(ZeroWav(twenty+1));error("overlong accepted")}catch(e:IllegalArgumentException){check(e.message!!.contains("20分钟"))}
 try{WavReader.read16k(ZeroWav(1,actualBytes=0,advertised=52_428_800));error("oversized accepted")}catch(e:java.io.IOException){check(e.message!!.contains("50MiB"))}
 try{WavReader.read16k(ZeroWav(16000,actualBytes=100));error("truncated accepted")}catch(e:java.io.EOFException){}
 println("PASS 20-minute mono PCM16 / 20min+sample rejected / 50MiB cap / truncated WAV")
}
