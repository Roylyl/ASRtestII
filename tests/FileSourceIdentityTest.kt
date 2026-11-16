package org.hotwordtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun main(){
 val uri="content://files/example.zip";val name="同名.wav"
 val first=FileSourceIdentity.zipEntry(uri,3,name)
 val second=FileSourceIdentity.zipEntry(uri,7,name)
 check(first!=second)
 check(first==FileSourceIdentity.zipEntry(uri,3,name))
 check(first.contains(name)&&second.contains(name))
 check(FileSourceIdentity.singleFile("content://files/example.wav")=="content://files/example.wav")
 check(first!=FileSourceIdentity.singleFile("content://files/example.wav"))
 val legacy=uri+"#wav:"+name
 check(FileSourceIdentity.sameForDedup(first,legacy)&&FileSourceIdentity.sameForDedup(legacy,first))
 check(!FileSourceIdentity.sameForDedup(first,second))
 check(!FileSourceIdentity.sameForDedup("zip-entry:bad",legacy))
 val unicodeUri="content://files/中文:录音.zip";val nested="目录/中英.wav"
 check(FileSourceIdentity.sameForDedup(FileSourceIdentity.zipEntry(unicodeUri,2,nested),unicodeUri+"#wav:"+nested))
 check(runCatching{FileSourceIdentity.zipEntry(uri,0,name)}.isFailure)
 // ZIP permits duplicate names although ZipOutputStream's writer rejects them.
 // Write equal-length names then change both headers, keeping distinct valid WAV payloads.
 fun wav(sample:Short):ByteArray=ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN).apply{
  put("RIFF".toByteArray());putInt(38);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1)
  putInt(16000);putInt(32000);putShort(2);putShort(16);put("data".toByteArray());putInt(2);putShort(sample)
 }.array()
 val bytes=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->
  z.putNextEntry(ZipEntry("first.wav"));z.write(wav(100));z.closeEntry()
  z.putNextEntry(ZipEntry("other.wav"));z.write(wav(200));z.closeEntry()
 }}.toByteArray()
 fun rename(old:String){
  val from=old.toByteArray();val to="same1.wav".toByteArray();check(from.size==to.size);var count=0
  for(i in 0..bytes.size-from.size)if(from.indices.all{bytes[i+it]==from[it]}){to.copyInto(bytes,i);count++}
  check(count==2)
 }
 rename("first.wav");rename("other.wav")
 val identities=mutableListOf<String>();val payloads=mutableListOf<ByteArray>();var ordinal=0
 ZipInputStream(ByteArrayInputStream(bytes)).use{z->while(true){val e=z.nextEntry?:break;ordinal++
  check(e.name=="same1.wav");identities.add(FileSourceIdentity.zipEntry(uri,ordinal,e.name));payloads.add(z.readBytes());z.closeEntry()
 }}
 check(identities.size==2&&identities[0]!=identities[1]&&!payloads[0].contentEquals(payloads[1]))
 println("FileSourceIdentityTest: pass")
}
