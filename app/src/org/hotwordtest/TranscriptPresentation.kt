package org.hotwordtest

/** Display-only numbering. Never rewrites speech, timestamps or model grouping metadata. */
internal object TranscriptPresentation {
 private val scoped=Regex("说话人([1-9][0-9]*)\\s*[（(](?:本窗口|本文件|本段)[）)]")
 private val anonymous=Regex("说话人[1-9][0-9]*")
 private fun mapLabels(label:String, transform:(String)->String):String =
  if(label.startsWith("重叠："))"重叠："+label.removePrefix("重叠：").split('、').joinToString("、",transform=transform)
  else transform(label)
 fun cleanLabel(label:String):String=mapLabels(label){item->
  scoped.matchEntire(item)?.let{"说话人"+it.groupValues[1]}?:item
 }
 class Names {
  private val numbers=linkedMapOf<String,Int>()
  fun line(value:String):String{
   if(!value.startsWith("["))return value
   val bracket=value.indexOf(']');if(bracket<0)return value
   val begin=bracket+1
   var end=value.indexOf('：',begin);if(end<0)return value
   if(value.startsWith("重叠：",begin)){end=value.indexOf('：',end+1);if(end<0)return value}
   val label=cleanLabel(value.substring(begin,end))
   // Restrict remapping to anonymous labels; keep user-registered names intact.
   val shown=mapLabels(label){item->
    if(anonymous.matches(item))"说话人"+numbers.getOrPut(item){numbers.size+1} else item
   }
   return value.substring(0,begin)+shown+value.substring(end)
  }
 }
 fun render(value:String):String{val names=Names();return value.lineSequence().joinToString("\n"){names.line(it)}}
}
