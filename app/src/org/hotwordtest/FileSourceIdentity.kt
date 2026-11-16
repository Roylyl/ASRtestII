package org.hotwordtest

/** Stable within an unchanged ZIP, even when multiple entries share a name. */
internal object FileSourceIdentity {
 fun zipEntry(archiveUri:String,entryOrdinal:Int,entryName:String):String {
  require(archiveUri.isNotBlank()&&entryOrdinal>0&&entryName.isNotBlank())
  return "zip-entry:"+entryOrdinal+":"+archiveUri.length+":"+archiveUri+":"+entryName
 }
 fun singleFile(uri:String):String {require(uri.isNotBlank());return uri}
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
 fun sameForDedup(a:String,b:String):Boolean =
  a==b || legacyAlias(a)==b || legacyAlias(b)==a
}
