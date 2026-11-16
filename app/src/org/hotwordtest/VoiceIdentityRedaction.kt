package org.hotwordtest

import org.json.JSONArray
import org.json.JSONObject

/** Removes deleted identities only from explicitly selected machine diagnostics, never user text. */
internal object VoiceIdentityRedaction {
 private val names=setOf("display_name","nearest_candidate_name","profile_name")
 fun diagnostic(value:Any?,deletedId:String):Boolean = when(value){
  is JSONObject->{
   val keys=value.keys().asSequence().toList()
   // Matcher names describe the best/selected profile, not its runner-up.
   val namedProfileReferenced=listOf("profile_id","best_profile_id","nearest_candidate_profile_id","registered_profile_id","target_profile_id")
    .any{value.opt(it) is String && value.optString(it)==deletedId}
   var changed=false
   for(key in keys){
    val item=value.opt(key)
    if(key==deletedId || item is String && item==deletedId || namedProfileReferenced && key in names){value.remove(key);changed=true}
    else if(diagnostic(item,deletedId))changed=true
   }
   changed
  }
  is JSONArray->{
   var changed=false
   for(index in 0 until value.length()){
    val item=value.opt(index)
    if(item is String && item==deletedId){value.put(index,JSONObject.NULL);changed=true}
    else if(diagnostic(item,deletedId))changed=true
   }
   changed
  }
  else->false
 }
}
