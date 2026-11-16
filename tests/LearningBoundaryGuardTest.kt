package org.hotwordtest

fun main() {
 val g=LearningBoundaryGuard
 fun span(a:Long,b:Long)=LearningBoundaryGuard.Span(a,b)
 fun checkParts(start:Long,end:Long,others:List<LearningBoundaryGuard.Span>,vararg expected:LearningBoundaryGuard.Span){
  check(g.retained(start,end,others,48000)==expected.toList())
 }
 // Clean long speech and a nonintersecting competing track remain unchanged.
 checkParts(1600,158400,emptyList(),span(1600,158400))
 checkParts(1600,100000,listOf(span(120000,130000)),span(1600,100000))
 // Competing tracks on each side of the previously observed constructed mix.
 checkParts(7246,67206,listOf(span(68806,140000)),span(7246,60806))
 checkParts(74185,142400,listOf(span(1600,72585)),span(80585,142400))
 // A competitor in the middle splits rather than concatenates clean intervals.
 checkParts(0,200000,listOf(span(90000,100000)),span(0,82000),span(108000,200000))
 // Multiple overlapping protections are unioned; only intervals >=3 seconds survive.
 checkParts(0,200000,listOf(span(85000,100000),span(96000,120000)),span(0,77000),span(128000,200000))
 checkParts(7246,56000,listOf(span(57000,100000)))
 // Existing unprotected data and overflows must not extend the candidate.
 checkParts(0,80000,listOf(span(0,1000)),span(9000,80000))
 checkParts(0,80000,listOf(span(Long.MAX_VALUE-1000,Long.MAX_VALUE)),span(0,80000))
 println("LearningBoundaryGuardTest: pass")
}
