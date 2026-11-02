package org.hotwordtest

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Incremental VAD for 16 kHz mono PCM. Single-threaded; callbacks run on caller thread.
 * A segment is emitted after the VAD closes it, not at speech onset. Caller must keep
 * microphone capture off this worker and bound its queue. Sample offsets are absolute
 * within this instance, end-exclusive. No output here claims word/speaker timestamps.
 */
class VadLiveSegmenter(assets: AssetManager, vadKey:String=VadModelFactory.SILERO,
                       private val contextSamples:Int=0,
                       private val onSegment: (Segment) -> Unit) : AutoCloseable {
 companion object {
  const val SAMPLE_RATE = 16000
  const val WINDOW = 512
  const val PRE_ROLL = 2400
  const val POST_ROLL = 1600
  const val MAX_SPEECH_SECONDS = 20f
  // Bounds raw PCM history while retaining 20s maximum speech plus VAD end latency.
  const val HISTORY_SAMPLES = 32 * SAMPLE_RATE
 }
 data class Segment(val index:Int, val startSample:Long, val endSample:Long,
                    val vadStartSample:Long, val vadEndSample:Long,
                    val samples:FloatArray, val overlapClipped:Boolean,
                    val contextStartSample:Long=startSample,
                    val contextAudio:FloatArray=samples)
 private val window=VadModelFactory.windowSize(vadKey)
 private val vad=VadModelFactory.create(assets,vadKey)
 private val history=FloatArray(HISTORY_SAMPLES)
 private val frame=FloatArray(window)
 private var buffered=0
 private var total=0L
 private var lastEnd=0L
 private var activeStart=-1L
 private var count=0
 private var finished=false
 private var closed=false

 /** Feed contiguous samples; never call from multiple threads. */
 fun accept(samples:FloatArray) {
  check(!finished && !closed){"VAD流已结束"}
  require(total + buffered + samples.size < Int.MAX_VALUE-window) {"单次录音超过VAD时间轴上限"}
  var pos=0
  while(pos<samples.size){
   val n=minOf(window-buffered,samples.size-pos)
   samples.copyInto(frame,buffered,pos,pos+n);buffered+=n;pos+=n
   if(buffered==window){processFrame(window);buffered=0}
  }
 }
 private fun processFrame(realCount:Int){
  for(i in 0 until realCount){history[(total % HISTORY_SAMPLES).toInt()]=frame[i];total++}
  // Padded trailing zeros are fed to VAD only, never counted as source audio.
  vad.acceptWaveform(frame.copyOf());drain()
  if(vad.isSpeechDetected()){
   if(activeStart<0)activeStart=total
   if(total-activeStart>=MAX_SPEECH_SECONDS*SAMPLE_RATE){vad.flush();drain();activeStart=-1L}
  }else activeStart=-1L
 }
 private fun drain(){
  while(!vad.empty()){
   val v=vad.front();val rawStart=v.start.toLong().coerceIn(0,total)
   val rawEnd=(v.start.toLong()+v.samples.size).coerceIn(rawStart,total)
   vad.pop()
   if(rawEnd<=rawStart)continue
   val earliest=maxOf(0L,total-HISTORY_SAMPLES)
   val wantedStart=maxOf(0L,rawStart-PRE_ROLL)
   // Never pretend buffered-away audio was processed.
   check(wantedStart>=earliest || wantedStart<=lastEnd){"VAD音频缓冲不足，不能保证片段完整"}
   val start=maxOf(wantedStart,lastEnd,earliest)
   val end=minOf(total,rawEnd+POST_ROLL)
   if(end<=start)continue
   val chunk=FloatArray((end-start).toInt()) {i->history[((start+i)%HISTORY_SAMPLES).toInt()]}
   val contextStart=maxOf(earliest,start-contextSamples.coerceIn(0,5*SAMPLE_RATE))
   val contextEnd=if(contextSamples>0)total else end
   val context=if(contextSamples>0)FloatArray((contextEnd-contextStart).toInt()){i->history[((contextStart+i)%HISTORY_SAMPLES).toInt()]} else chunk
   val segment=Segment(count++,start,end,rawStart,rawEnd,chunk,start>rawStart,contextStart,context)
   lastEnd=end
   onSegment(segment)
  }
 }
 /** Flushes the final non-silent segment. Must be called before close on normal stop. */
 fun finish(){
  check(!closed){"VAD已释放"};if(finished)return
  if(buffered>0){frame.fill(0f,buffered,window);processFrame(buffered);buffered=0}
  vad.flush();drain();finished=true
 }
 fun inputSamples():Long=total+buffered
 override fun close(){if(!closed){closed=true;vad.release()}}
}
