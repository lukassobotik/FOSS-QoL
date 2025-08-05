package dev.lukassobotik.fossqol.utils

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

fun formatDataToBase64(url: String, snippets: List<String>): String {
    val json = JSONObject().apply {
        put("url", url)
        put("snippets", JSONArray(snippets))
    }

    val jsonString = json.toString()
    return Base64.encodeToString(jsonString.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}