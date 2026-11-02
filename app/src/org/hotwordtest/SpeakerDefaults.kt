package org.hotwordtest

/** Shared experimental defaults; existing per-user settings remain authoritative. */
object SpeakerDefaults {
 const val NATIVE_SIMILARITY_PERCENT=50
 const val MATCH_PERCENT=45
 const val WEAK_MATCH_PERCENT=40
 const val NEW_MAX_PERCENT=30
 const val MARGIN_PERCENT=8
 const val OFFLINE_DIRECT_STRONG=0.55
 const val OFFLINE_DIRECT_WEAK=0.60
 const val STABLE_OBSERVATIONS=2
 const val STABLE_DURATION_MS=4000L
}
