package org.hotwordtest

object SpeakerModels {
 const val CAM = "campplus"
 const val ERES = "eres2netv2"
 fun normalize(key:String?):String = if(key==ERES) ERES else CAM
 fun name(key:String?):String = if(normalize(key)==ERES) "ERes2NetV2" else "CAM++"
 fun asset(key:String?):String = if(normalize(key)==ERES) "models/eres2netv2.onnx" else "models/campplus.onnx"
}
