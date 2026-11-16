package org.hotwordtest

/** Registered-name decisions are independent from anonymous speaker creation. */
data class RegisteredSpeakerPolicy(val acceptanceThreshold:Double=0.65,val identityMargin:Double=0.08) {
 init {
  require(acceptanceThreshold.isFinite() && acceptanceThreshold in 0.0..1.0)
  require(identityMargin.isFinite() && identityMargin in 0.0..1.0)
 }
 companion object {
  @JvmStatic fun forModel(modelKey:String):RegisteredSpeakerPolicy = when(SpeakerModels.normalize(modelKey)) {
   SpeakerModels.ERES -> RegisteredSpeakerPolicy(0.65,0.10)
   else -> RegisteredSpeakerPolicy(0.60,0.10)
  }
 }
}
