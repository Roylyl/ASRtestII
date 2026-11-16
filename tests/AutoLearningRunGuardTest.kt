package org.hotwordtest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun main(){
 val disabled=AutoLearningRunGuard()
 check(!disabled.isActive{false})
 check(runCatching{disabled.commit({false}){error("must not write")}}.isFailure)
 check(!disabled.isActive{throw IllegalStateException("settings unavailable")})

 val guard=AutoLearningRunGuard();val entered=CountDownLatch(1);val release=CountDownLatch(1)
 val writes=AtomicInteger();val worker=Thread{guard.commit({true}){entered.countDown();check(release.await(5,TimeUnit.SECONDS));writes.incrementAndGet()}}
 worker.start();check(entered.await(5,TimeUnit.SECONDS))
 val cancel=Thread{guard.cancel()};cancel.start();release.countDown();worker.join(5000);cancel.join(5000)
 check(!worker.isAlive&&!cancel.isAlive&&writes.get()==1)
 check(!guard.isActive{true})
 check(runCatching{guard.commit({true}){writes.incrementAndGet()}}.isFailure)
 check(writes.get()==1)
 println("AutoLearningRunGuardTest: pass")
}
