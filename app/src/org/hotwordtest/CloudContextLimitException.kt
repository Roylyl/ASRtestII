package org.hotwordtest
/** Only an explicit server context rejection permits automatic input splitting. */
class CloudContextLimitException:IllegalStateException("输入超过该云端模型的上下文限制")
