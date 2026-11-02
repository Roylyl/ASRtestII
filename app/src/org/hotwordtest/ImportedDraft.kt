package org.hotwordtest

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** Reads the UTF-8, one-turn-per-line format produced by SpeakerTranscript.line. */
internal object ImportedDraft {
 private const val MAX_BYTES=1024*1024
 private const val TIME="""(?:[1-9][0-9]{0,8}:)?[0-5][0-9]:[0-5][0-9]\.[0-9]{2}"""
 private val interval=Regex("""^\[($TIME)–($TIME)\]""")

 fun read(input:InputStream):TranscriptReview.Edited {
  val bytes=ByteArrayOutputStream()
  val buffer=ByteArray(8192)
  while(true){
   val count=input.read(buffer)
   if(count<0)break
   require(bytes.size()+count<=MAX_BYTES){"文件超过1MiB。"}
   bytes.write(buffer,0,count)
  }
  val text=try{
   Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
  }catch(e:CharacterCodingException){throw IllegalArgumentException("文件不是有效的UTF-8文本。",e)}

  val rows=mutableListOf<TranscriptReview.Row>()
  var lastStart=-1L
  val reader=text.removePrefix("\uFEFF").reader().buffered()
  reader.forEachLine { line ->
   val number=rows.size+1
   val match=interval.find(line)?:throw IllegalArgumentException("第${number}行缺少有效的起止时间。")
   val start=centiseconds(match.groupValues[1])
   val end=centiseconds(match.groupValues[2])
   require(start>=lastStart && end>=start){"第${number}行的时间顺序无效。"}
   lastStart=start
   val labelStart=match.value.length
   // The colons in the overlap marker and in the speech are part of their respective fields.
   val overlap=line.startsWith("重叠：",labelStart)
   val nameStart=labelStart+if(overlap)"重叠：".length else 0
   val separator=line.indexOf('：',nameStart)
   require(separator>nameStart && line.substring(nameStart,separator).isNotBlank()){"第${number}行缺少说话人或全角冒号。"}
   if(overlap)require(line.substring(nameStart,separator).split('、').all{it.isNotBlank()}){"第${number}行的重叠标签不完整。"}
   val speech=line.substring(separator+1)
   require(speech.isNotBlank() && line.none{(it!='\t' && Character.isISOControl(it)) || it=='\u2028' || it=='\u2029'}){
    "第${number}行正文为空或含无效控制字符。"
   }
   rows+=TranscriptReview.Row("",line.substring(0,separator+1),speech)
  }
  require(rows.isNotEmpty()){"文件中没有带时间和说话人的勘误稿。"}
  return TranscriptReview.Edited(rows,rows.size)
 }

 private fun centiseconds(value:String):Long {
  val parts=value.split(':','.')
  val hours=if(parts.size==4)parts[0].toLong() else 0L
  return hours*360000+parts[parts.size-3].toLong()*6000+parts[parts.size-2].toLong()*100+parts.last().toLong()
 }
}
