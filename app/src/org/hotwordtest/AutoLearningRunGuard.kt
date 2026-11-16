package org.hotwordtest

/** Serializes cancellation against the short final commit, not model inference. */
internal class AutoLearningRunGuard {
 private val lock=Any()
 @Volatile private var cancelled=false
 fun cancel(){synchronized(lock){cancelled=true}}
 fun isActive(enabled:()->Boolean):Boolean = !cancelled && try{enabled()}catch(_:Exception){false}
 fun <T> commit(enabled:()->Boolean,action:()->T):T=synchronized(lock){
  require(isActive(enabled)){"自动学习已关闭或任务已取消，候选保留待确认"}
  action()
 }
}
