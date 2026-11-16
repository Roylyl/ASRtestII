package org.hotwordtest

import kotlin.math.abs

fun main() {
 fun close(actual:Double, expected:Double) = check(abs(actual-expected)<1e-6) { "$actual != $expected" }
 fun vector(first:Float):FloatArray = floatArrayOf(first,kotlin.math.sqrt(1f-first*first))
 val query=floatArrayOf(1f,0f)
 val initial=vector(0.60f)
 val first=vector(0.80f)
 val weakSecond=vector(0.40f)
 val empty=VoiceTemplateScoring.score(query,initial,emptyList())!!
 close(empty.similarity,0.60);check(empty.supplementCount==0)
 val single=VoiceTemplateScoring.score(query,initial,listOf(first))!!
 close(single.similarity,0.77);check(single.supplementCount==1)
 val two=VoiceTemplateScoring.score(query,initial,listOf(first,weakSecond))!!
 close(two.similarity,single.similarity);check(two.supplementCount==2)
 val strong=VoiceTemplateScoring.score(query,initial,listOf(vector(0.90f),first))!!
 close(strong.similarity,0.87)
 val afterDelete=VoiceTemplateScoring.score(query,initial,listOf(first))!!
 close(afterDelete.similarity,single.similarity);check(afterDelete.similarity<strong.similarity)
 val order=VoiceTemplateScoring.score(query,initial,listOf(weakSecond,first))!!
 close(order.similarity,two.similarity)
 check(VoiceTemplateScoring.score(floatArrayOf(),floatArrayOf(),emptyList())==null)
 check(VoiceTemplateScoring.score(query,floatArrayOf(1f),emptyList())==null)
 check(VoiceTemplateScoring.score(query,floatArrayOf(Float.NaN,0f),emptyList())==null)
 check(VoiceTemplateScoring.score(query,floatArrayOf(Float.POSITIVE_INFINITY,0f),emptyList())==null)
 val invalid=VoiceTemplateScoring.score(query,initial,listOf(first,floatArrayOf(Float.NaN,0f),floatArrayOf(1f)))!!
 close(invalid.similarity,single.similarity);check(invalid.supplementCount==1)
 println("VoiceTemplateScoringTest: pass")
}
