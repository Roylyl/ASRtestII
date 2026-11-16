package org.hotwordtest

/** A suggested identity never counts as an explicit confirmation. */
internal class VoiceCandidateSelection(private val personCount:Int) {
 init { require(personCount>=0) }
 var selectedIndex:Int?=null
  private set

 fun select(index:Int):Boolean {
  selectedIndex=index.takeIf { it in 0 until personCount }
  return selectedIndex!=null
 }

 fun clear(){selectedIndex=null}
}
