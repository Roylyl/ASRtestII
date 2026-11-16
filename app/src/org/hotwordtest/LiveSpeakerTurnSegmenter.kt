package org.hotwordtest

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject

/** The same unsmoothed activity evidence drives transcription turns and learning candidates. */
class LiveSpeakerTurnSegmenter(assets:AssetManager,modelKey:String,threshold:Double,
                               private val context:Context?=null):AutoCloseable {
 data class Turn(val start:Long,val end:Long,val localIds:List<Int>,val audio:FloatArray)
 data class Outcome(val turns:List<Turn>,val inferenceMs:Double,val contextStart:Long,val contextEnd:Long,val evidence:SpeechEvidence)
 private data class Span(val start:Long,val end:Long,val id:Int)
 private var analyzer:SpeechEvidence.Analyzer?=null
 @JvmOverloads fun split(segment:VadLiveSegmenter.Segment,evidence:SpeechEvidence?=null,source:String="live",activityOverride:JSONObject?=null):Outcome {
  val started=System.nanoTime()
  val proof=evidence?:run{
   val local=analyzer?:SpeechEvidence.Analyzer(checkNotNull(context){"缺少原始活动分析上下文"}).also{analyzer=it}
   local.analyze(segment.contextAudio,source,segment.contextStartSample)
  }
  require(proof.startSample==segment.contextStartSample&&proof.sampleCount==segment.contextAudio.size)
  // Also validates waveform identity, preventing an unrelated waveform from claiming this activity.
  SpeechEvidence.extractPureCandidates(segment.contextAudio,proof)
  // Refined activity supplies turn boundaries, never replacement acoustic proof.
  require(activityOverride==null || activityOverride.optString("source")==source){"活动数据来源不匹配"}
  val refined=activityOverride?.optJSONArray("refined_activities")
  val list=refined?:SpeechEvidence.displayActivities(proof.toJson())
  val trackIds=linkedMapOf<String,Int>();val spans=mutableListOf<Span>()
  for(i in 0 until list.length()){
   val row=list.getJSONObject(i);val rawTrack=row.getString("local_track")
   val identity=if(refined!=null&&!row.isNull("stable_speaker_id"))row.optString("stable_speaker_id","") else ""
   val track=if(refined!=null)rawTrack+"\u0000"+identity else rawTrack
   val a=row.getLong("start_sample").coerceIn(segment.startSample,segment.endSample)
   val b=row.getLong("end_sample").coerceIn(a,segment.endSample)
   if(b>a)spans.add(Span(a,b,trackIds.getOrPut(track){trackIds.size}))
  }
  val bounds=(listOf(segment.startSample,segment.endSample)+spans.flatMap{listOf(it.start,it.end)}).distinct().sorted()
  val turns=mutableListOf<Turn>()
  for(i in 0 until bounds.size-1){
   val a=bounds[i];val b=bounds[i+1];if(b<=a)continue
   val ids=spans.filter{it.start<b&&it.end>a}.map{it.id}.distinct().sorted()
   val previous=turns.lastOrNull()
   val start=if(previous!=null&&previous.end==a&&previous.localIds==ids)previous.start else a
   val audio=segment.samples.copyOfRange((start-segment.startSample).toInt(),(b-segment.startSample).toInt())
   if(start<a)turns[turns.lastIndex]=Turn(start,b,ids,audio) else turns.add(Turn(a,b,ids,audio))
  }
  return Outcome(turns,(System.nanoTime()-started)/1000000.0,segment.contextStartSample,segment.contextStartSample+segment.contextAudio.size,proof)
 }
 override fun close(){analyzer?.close();analyzer=null}
}
