package org.hotwordtest
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
fun main(){
 val f=File.createTempFile("wavzip-",".zip")
 try {
  ZipOutputStream(f.outputStream()).use{z->for(name in listOf("b.wav","a.wav")){z.putNextEntry(ZipEntry(name));ZeroWav(1600).use{it.copyTo(z)};z.closeEntry()}}
  ZipFile(f).use{zip->val entries=zip.entries().asSequence().filter{!it.isDirectory&&it.name.lowercase().endsWith(".wav")}.toList().sortedBy{it.name};check(entries.map{it.name}==listOf("a.wav","b.wav"));for(entry in entries){val samples=zip.getInputStream(entry).use{WavReader.read16k(it)};check(samples.size==1600)}}
  println("PASS sorted ZipFile entries / independent streaming WAV reads")
 } finally {f.delete()}
}
