package org.hotwordtest

import android.content.res.AssetManager
import org.json.JSONObject
import kotlin.math.roundToLong

/** Experimental speaker-activity splitting for one closed VAD segment.
 * Local pyannote IDs only refer to this context.  They are never treated as a
 * global identity unless a non-overlapping voice sample was actually matched.
 */
class LiveSpeakerTurnSegmenter(assets:AssetManager,modelKey:String,threshold:Double):AutoCloseable {
 data class Turn(val start:Long,val end:Long,val localIds:List<Int>,val audio:FloatArray)
 data class Outcome(val turns:List<Turn>,val inferenceMs:Double,val contextStart:Long,val contextEnd:Long)
 private data class Span(val start:Long,val end:Long,val id:Int)
 private val diarizer=DiarizationTest.Session(assets,modelKey,-1,threshold)
 fun split(segment:VadLiveSegmenter.Segment):Outcome {
  val started=System.nanoTime();val result=diarizer.process(segment.contextAudio)
  val list=result.getJSONArray("segments");val spans=mutableListOf<Span>()
  for(i in 0 until list.length()) {
   val row=list.getJSONObject(i)
   val a=(segment.contextStartSample+(row.getDouble("start_seconds")*16000).roundToLong()).coerceIn(segment.startSample,segment.endSample)
   val b=(segment.contextStartSample+(row.getDouble("end_seconds")*16000).roundToLong()).coerceIn(a,segment.endSample)
   if(b>a)spans.add(Span(a,b,row.getInt("speaker_id")))
  }
  val bounds=(listOf(segment.startSample,segment.endSample)+spans.flatMap{listOf(it.start,it.end)}).distinct().sorted()
  val turns=mutableListOf<Turn>()
  for(i in 0 until bounds.size-1) {
   val a=bounds[i];val b=bounds[i+1];if(b<=a)continue
   val ids=spans.filter{it.start<b&&it.end>a}.map{it.id}.distinct().sorted()
   val last=turns.lastOrNull()
   if(last!=null&&last.end==a&&last.localIds==ids){
    val merged=FloatArray((b-last.start).toInt());segment.samples.copyInto(merged,0,(last.start-segment.startSample).toInt(),(b-segment.startSample).toInt())
    turns[turns.lastIndex]=Turn(last.start,b,ids,merged)
   }else{
    val a0=(a-segment.startSample).toInt();val b0=(b-segment.startSample).toInt()
    turns.add(Turn(a,b,ids,segment.samples.copyOfRange(a0,b0)))
   }
  }
  return Outcome(turns,(System.nanoTime()-started)/1000000.0,segment.contextStartSample,segment.contextStartSample+segment.contextAudio.size)
 }
 override fun close(){diarizer.close()}
}
