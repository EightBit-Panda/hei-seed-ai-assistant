
package com.heiseed.assistant

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class OpenAIClient(
    private val apiKey: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeImage(imageFile: File): String =
        withContext(Dispatchers.IO) {

            require(imageFile.exists()) {
                "Image file does not exist"
            }

            val imageBytes = imageFile.readBytes()
            val encodedImage = Base64.encodeToString(
                imageBytes,
                Base64.NO_WRAP
            )

            val imageUrl = "data:image/jpeg;base64,$encodedImage"

            val textContent = JSONObject().apply {
                put("type", "text")
                put(
                    "text",
                    "Describe what you see in this image. " +
                            "Answer in Norwegian. Be clear and concise."
                )
            }

            val imageContent = JSONObject().apply {
                put("type", "image_url")
                put(
                    "image_url",
                    JSONObject().put("url", imageUrl)
                )
            }

            val content = JSONArray().apply {
                put(textContent)
                put(imageContent)
            }

            val message = JSONObject().apply {
                put("role", "user")
                put("content", content)
            }

            val messages = JSONArray().put(message)

            val requestJson = JSONObject().apply {
                put("model", "gpt-4.1-mini")
                put("messages", messages)
                put("max_tokens", 300)
            }

            val body = requestJson.toString()
                .toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                    ?: throw Exception("Empty response from OpenAI")

                if (!response.isSuccessful) {
                    throw Exception(
                        "OpenAI API error ${response.code}: $responseBody"
                    )
                }

                val json = JSONObject(responseBody)

                json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
        }
}
