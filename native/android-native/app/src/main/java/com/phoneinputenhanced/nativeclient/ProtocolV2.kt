package com.phoneinputenhanced.nativeclient

import org.json.JSONObject

object ProtocolV2 {
    const val VERSION = 2
    const val PORT = 51877
    const val PATH = "/v2/ws"

    fun command(type: String, requestId: String, fields: Map<String, Any?> = emptyMap()): JSONObject {
        val json = JSONObject()
            .put("protocol", VERSION)
            .put("type", type)
            .put("requestId", requestId)
        fields.forEach { (key, value) ->
            if (value != null) json.put(key, value)
        }
        return json
    }
}
