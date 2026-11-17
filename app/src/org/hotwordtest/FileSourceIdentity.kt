package org.hotwordtest

import java.security.MessageDigest

/** Recording content and its file or ZIP entry location. */
internal object FileSourceIdentity {
 private const val PCM_PREFIX="pcm-f32le-16000-v1:"
 private const val DIGEST_LENGTH=64
 private const val LOCATION_OFFSET=PCM_PREFIX.length+DIGEST_LENGTH+1
 private const val HEX="0123456789abcdef"

 fun zipEntry(archiveUri:String,entryOrdinal:Int,entryName:String):String {
  require(archiveUri.isNotBlank()&&entryOrdinal>0&&entryName.isNotBlank())
  return "zip-entry:"+entryOrdinal+":"+archiveUri.length+":"+archiveUri+":"+entryName
 }
 fun singleFile(uri:String):String {require(uri.isNotBlank());return uri}

 /** Identify the complete normalized mono recording, before choosing any segment.
  * Only bit-exact decoded PCM can be recognized across unrelated locations. */
 fun recording(location:String,samples:FloatArray,cancelled:()->Boolean={false}):String {
  require(location.isNotBlank()&&samples.isNotEmpty())
  if(cancelled())throw InterruptedException("已停止")
  // Keep the legacy location when the combined identity would exceed the source bound.
  if(location.length>8192-LOCATION_OFFSET)return location
  val digest=MessageDigest.getInstance("SHA-256")
  val block=ByteArray(16384)
  var offset=0
  for(sample in samples){
   val bits=if(sample==0f)0 else sample.toRawBits()
   block[offset++]=bits.toByte()
   block[offset++]=(bits ushr 8).toByte()
   block[offset++]=(bits ushr 16).toByte()
   block[offset++]=(bits ushr 24).toByte()
   if(offset==block.size){
    if(cancelled())throw InterruptedException("已停止")
    digest.update(block)
    offset=0
   }
  }
  if(cancelled())throw InterruptedException("已停止")
  digest.update(block,0,offset)
  return buildString {
   append(PCM_PREFIX)
   for(value in digest.digest()){
    val byte=value.toInt() and 255
    append(HEX[byte ushr 4]).append(HEX[byte and 15])
   }
   append(':').append(location)
  }
 }

 private fun fingerprint(source:String):String? {
  val end=PCM_PREFIX.length+DIGEST_LENGTH
  if(!source.startsWith(PCM_PREFIX)||source.length<=end+1||source[end]!=':')return null
  return source.substring(PCM_PREFIX.length,end).takeIf{it.all{digit->digit in HEX}}
 }
 /** Old versions identified ZIP entries only by archive URI and name. Treat a
  * legacy match as ambiguous for deduplication, never as proof of entry identity. */
 private fun legacyAlias(source:String):String? {
  if(!source.startsWith("zip-entry:"))return null
  val ordinalEnd=source.indexOf(':',10);if(ordinalEnd<0)return null
  val ordinal=source.substring(10,ordinalEnd).toIntOrNull()?:return null
  val lengthEnd=source.indexOf(':',ordinalEnd+1);if(lengthEnd<0)return null
  val length=source.substring(ordinalEnd+1,lengthEnd).toIntOrNull()?:return null
  val begin=lengthEnd+1
  if(ordinal<=0||length<=0||length>source.length-begin)return null
  val end=begin+length
  if(end>=source.length||source[end]!=':')return null
  val uri=source.substring(begin,end);val name=source.substring(end+1)
  return if(name.isBlank())null else uri+"#wav:"+name
 }
 fun sameForDedup(a:String,b:String):Boolean {
  if(a==b)return true
  val aFingerprint=fingerprint(a);val bFingerprint=fingerprint(b)
  if(aFingerprint!=null&&bFingerprint!=null)return aFingerprint==bFingerprint
  val aLocation=if(aFingerprint==null)a else a.substring(LOCATION_OFFSET)
  val bLocation=if(bFingerprint==null)b else b.substring(LOCATION_OFFSET)
  return aLocation==bLocation||legacyAlias(aLocation)==bLocation||legacyAlias(bLocation)==aLocation
 }
}
