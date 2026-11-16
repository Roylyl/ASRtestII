package org.hotwordtest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun main(){
 val gate=BoundedCandidateWait(totalMillis=350,perItemMillis=250)
 val free=AtomicBoolean(false)
 val attempt={if(free.get()) BoundedCandidateWait.Result.RESERVED else BoundedCandidateWait.Result.BUSY}
 val started=System.nanoTime()
 check(gate.reserve(false,{false},attempt)==BoundedCandidateWait.Result.BUSY)
 check((System.nanoTime()-started)/1_000_000<200) // live recording never waits
 val result=AtomicReference<BoundedCandidateWait.Result>()
 val entered=CountDownLatch(1)
 val worker=Thread{result.set(gate.reserve(true,{false}){entered.countDown();attempt()})}
 worker.start();check(entered.await(1,TimeUnit.SECONDS));free.set(true);worker.join(1000)
 check(!worker.isAlive&&result.get()==BoundedCandidateWait.Result.RESERVED)
 free.set(false)
 check(gate.reserve(true,{false},attempt)==BoundedCandidateWait.Result.BUSY)
 val cancel=AtomicBoolean(false);val waiting=CountDownLatch(1)
 val gate2=BoundedCandidateWait(totalMillis=5000,perItemMillis=5000)
 val worker2=Thread{result.set(gate2.reserve(true,{cancel.get()}){waiting.countDown();BoundedCandidateWait.Result.BUSY})}
 worker2.start();check(waiting.await(1,TimeUnit.SECONDS));cancel.set(true);worker2.join(1000)
 check(!worker2.isAlive&&result.get()==BoundedCandidateWait.Result.CANCELLED)
 println("BoundedCandidateWaitTest: pass")
}
