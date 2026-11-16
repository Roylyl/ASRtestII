package org.hotwordtest

fun main() {
 fun cells(vararg xs:Triple<Int,Int,Boolean>) = xs.map { SpeakerProbeCells.Probe(it.first,it.second,it.third) }.let { p -> p to SpeakerProbeCells.bounds(p) }
 val (head,h)=cells(Triple(0,3000,false),Triple(1500,4500,true),Triple(3000,6000,true))
 check(h==listOf(0 to 2250,2250 to 3750,3750 to 6000))
 check(!SpeakerProbeCells.mayBorrow(head[0].available,"A","A"))
 val (middle,m)=cells(Triple(0,3000,true),Triple(1500,4500,false),Triple(3000,6000,false),Triple(4500,7500,true))
 check(m==listOf(0 to 2250,2250 to 3750,3750 to 5250,5250 to 7500))
 check(!SpeakerProbeCells.mayBorrow(middle[1].available,"A","A"))
 check(!SpeakerProbeCells.mayBorrow(middle[2].available,"A","A"))
 val (normal,n)=cells(Triple(0,3000,true),Triple(1500,4500,true),Triple(3000,6000,true))
 check(n==h && SpeakerProbeCells.mayBorrow(normal[1].available,"A","A"))
 check(!SpeakerProbeCells.mayBorrow(true,"A","B") && !SpeakerProbeCells.mayBorrow(true,null,"A"))
 val (_,gap)=cells(Triple(0,3000,true),Triple(6000,9000,true))
 check(gap==listOf(0 to 3000,6000 to 9000))
 val (tail,t)=cells(Triple(0,3000,true),Triple(2300,3800,true))
 t.zip(tail).forEach { (cell,probe) -> check(cell.first>=probe.start && cell.second<=probe.end) }
 // A short tail can start after the midpoint between centers, despite overlapping.
 val (_,realTail)=cells(Triple(6706,54706,true),Triple(48855,72855,true))
 check(realTail==listOf(6706 to 48855,48855 to 72855))
 val (_,reverseLengths)=cells(Triple(0,1500,true),Triple(1000,4000,true))
 check(reverseLengths==listOf(0 to 1500,1500 to 4000))
 val (failedTail,ft)=cells(Triple(6706,54706,true),Triple(48855,72855,false))
 check(ft==realTail && !SpeakerProbeCells.mayBorrow(failedTail.last().available,"A","A"))
 val (failedFirst,ff)=cells(Triple(0,1500,false),Triple(1000,4000,true))
 check(ff==reverseLengths && !SpeakerProbeCells.mayBorrow(failedFirst.first().available,"A","A"))
 check(!SpeakerProbeCells.mayBorrow(true,"A","B"))
 println("SpeakerProbeCellsTest: pass")
}
