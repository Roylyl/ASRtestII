package org.hotwordtest

fun main(){
 val selection=VoiceCandidateSelection(2)
 check(selection.selectedIndex==null)
 check(!selection.select(-1) && selection.selectedIndex==null)
 check(!selection.select(2) && selection.selectedIndex==null)
 check(selection.select(1) && selection.selectedIndex==1)
 check(selection.select(0) && selection.selectedIndex==0)
 selection.clear()
 check(selection.selectedIndex==null)
 check(!VoiceCandidateSelection(0).select(0))
 println("VoiceCandidateSelectionTest: pass")
}
