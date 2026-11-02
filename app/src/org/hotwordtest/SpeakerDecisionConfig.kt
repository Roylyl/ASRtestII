package org.hotwordtest

/** Anonymous speaker decisions, separate from pyannote clustering and name matching. */
data class SpeakerDecisionConfig(
 val strongMatch:Double = SpeakerDefaults.MATCH_PERCENT / 100.0,
 val weakMatch:Double = SpeakerDefaults.WEAK_MATCH_PERCENT / 100.0,
 val newSpeakerMax:Double = SpeakerDefaults.NEW_MAX_PERCENT / 100.0,
 val ambiguityMargin:Double = SpeakerDefaults.MARGIN_PERCENT / 100.0
) {
 init {
  require(listOf(strongMatch,weakMatch,newSpeakerMax,ambiguityMargin).all{it.isFinite() && it in 0.0..1.0}){"声纹决策参数需在0.00至1.00之间"}
  require(newSpeakerMax<minOf(strongMatch,weakMatch)){"新建说话人相似度上限需低于正常和短片段的已有说话人匹配门槛"}
 }
}
