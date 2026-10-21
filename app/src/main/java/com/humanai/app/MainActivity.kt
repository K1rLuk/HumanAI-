package com.humanai.app

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

// ==========================================
// 🎨 БРЕНДИНГ
// ==========================================
val HumanOrange = Color(0xFFFF7B00)
val DarkBg = Color(0xFF0D0D0D)
val DarkSurface = Color(0xFF1A1A1A)
val LightBg = Color(0xFFF7F7F7)
val LightSurface = Color(0xFFFFFFFF)

// ==========================================
// 📦 МОДЕЛИ ДАННЫХ
// ==========================================
data class ChatMessage(val id: String = UUID.randomUUID().toString(), val content: String, val isUser: Boolean, val isImage: Boolean = false)
data class ChatSession(val id: String = UUID.randomUUID().toString(), val title: String = "Новый чат", val messages: List<ChatMessage> = emptyList())

enum class AIMode(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    FAST("Быстрый", Icons.Outlined.Bolt),
    ULTRA("Ультра-Мышление", Icons.Outlined.Psychology),
    CODE("Код", Icons.Outlined.Code)
}

// ==========================================
// 📡 МЕНЕДЖЕР МОДЕЛЕЙ (Динамический выбор)
// ==========================================
class ModelManager {
    private var allFreeModels: List<ModelInfo> = emptyList()
    private var lastFetchTime = 0L
    private val cacheDurationMs = 1000 * 60 * 60 * 6 // 6 часов

    data class ModelInfo(
        val id: String,
        val name: String,
        val contextLength: Int,
        val isReasoning: Boolean,
        val isCoding: Boolean
    )

    suspend fun refreshModels(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - lastFetchTime < cacheDurationMs && allFreeModels.isNotEmpty()) {
            return@withContext true // Используем кэш
        }
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body?.string() ?: return@withContext false
                val jsonArray = JSONObject(body).getJSONArray("data")
                val models = mutableListOf<ModelInfo>()
                
                for (i in 0 until jsonArray.length()) {
                    val model = jsonArray.getJSONObject(i)
                    val pricing = model.getJSONObject("pricing")
                    // Фильтр: бесплатные модели (prompt == "0" и completion == "0")
                    if (pricing.getString("prompt") == "0" && pricing.getString("completion") == "0") {
                        val id = model.getString("id")
                        val name = model.optString("name", id)
                        val context = model.optInt("context_length", 4096)
                        // Определяем "умные" модели по названию или описанию
                        val isReasoning = name.contains("reason", ignoreCase = true) || 
                                          name.contains("think", ignoreCase = true) || 
                                          id.contains("r1", ignoreCase = true) ||
                                          id.contains("deepseek", ignoreCase = true)
                        val isCoding = name.contains("code", ignoreCase = true) || 
                                       name.contains("coder", ignoreCase = true) ||
                                       id.contains("qwen", ignoreCase = true)
                        
                        models.add(ModelInfo(id, name, context, isReasoning, isCoding))
                    }
                }
                allFreeModels = models
                lastFetchTime = System.currentTimeMillis()
                return@withContext true
            }
        } catch (e: Exception) {
            return@withContext false
        }
    }

    fun getBestModel(mode: AIMode): String {
        if (allFreeModels.isEmpty()) {
            // Фолбэк, если список не загрузился
            return when (mode) {
                AIMode.ULTRA -> "deepseek/deepseek-r1:free"
                AIMode.CODE -> "qwen/qwen-2.5-coder-32b-instruct:free"
                else -> "meta-llama/llama-3.1-8b-instruct:free"
            }
        }

        val sorted = when (mode) {
            AIMode.ULTRA -> allFreeModels.filter { it.isReasoning }.sortedByDescending { it.contextLength }
            AIMode.CODE -> allFreeModels.filter { it.isCoding }.sortedByDescending { it.contextLength }
            AIMode.FAST -> allFreeModels.sortedByDescending { it.contextLength }
        }
        
        // Если фильтр не дал результатов, берем все бесплатные
        val candidate = sorted.firstOrNull() ?: allFreeModels.first()
        return candidate.id
    }
}

// ==========================================
// 🧠 ЛОГИКА ЗАПРОСА (с динамическим выбором)
// ==========================================
suspend fun getAIResponse(
    prompt: String,
    history: List<ChatMessage>,
    mode: AIMode,
    systemPrompt: String,
    prefs: android.content.SharedPreferences,
    modelManager: ModelManager
): ChatMessage = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    
    val orKey = prefs.getString("key_openrouter", "") ?: ""
    val groqKey = prefs.getString("key_groq", "") ?: ""
    val geminiKey = prefs.getString("key_gemini", "") ?: ""

    if (orKey.isBlank() && groqKey.isBlank() && geminiKey.isBlank()) {
        return@withContext ChatMessage(content = "⚠️ Нет API ключей! Зайди в Настройки и вставь хотя бы один.", isUser = false)
    }

    // 1. Обновляем список моделей (если есть ключ OpenRouter)
    if (orKey.isNotBlank()) {
        modelManager.refreshModels(orKey)
    }

    // 2. Формируем системный промпт
    var finalSystemPrompt = systemPrompt
    if (mode == AIMode.ULTRA) {
        finalSystemPrompt += "\nКРИТИЧЕСКИ ВАЖНО: Ты в режиме ULTRA REASONING. Сначала напиши размышления в теге <thinking>, проверь их на ошибки, и только потом выдай идеальный финальный ответ."
    } else if (mode == AIMode.CODE) {
        finalSystemPrompt += "\nТы — экспертный программист. Пиши только чистый, оптимизированный код. Обязательно используй markdown ``` для блоков кода."
    }

    val messagesArray = JSONArray()
    messagesArray.put(JSONObject().apply { put("role", "system"); put("content", finalSystemPrompt) })
    history.takeLast(10).forEach { msg ->
        messagesArray.put(JSONObject().apply { put("role", if (msg.isUser) "user" else "assistant"); put("content", msg.content) })
    }

    fun parseOpenAIResponse(body: String?): String? {
        return try {
            JSONObject(body ?: "").getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) { null }
    }

    var finalResponse: String? = null

    // 3. ПРОБУЕМ OPENROUTER с динамической моделью
    if (orKey.isNotBlank() && finalResponse == null) {
        try {
            val dynamicModel = modelManager.getBestModel(mode)
            val jsonBody = JSONObject().apply { 
                put("model", dynamicModel)
                put("messages", messagesArray) 
            }.toString()
            val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer $orKey").addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType())).build()
            
            client.newCall(request).execute().use { res ->
                if (res.isSuccessful) finalResponse = parseOpenAIResponse(res.body?.string())
            }
        } catch (e: Exception) { /* Идем дальше */ }
    }

    // 4. ПРОБУЕМ GROQ (если OpenRouter упал или ключа нет)
    if (groqKey.isNotBlank() && finalResponse == null) {
        try {
            val model = when (mode) {
                AIMode.ULTRA -> "deepseek-r1-distill-llama-70b"
                AIMode.CODE -> "qwen-2.5-coder-32b"
                else -> "llama-3.3-70b-versatile"
            }
            val jsonBody = JSONObject().apply { put("model", model); put("messages", messagesArray) }.toString()
            val request = Request.Builder().url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $groqKey").addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType())).build()
            
            client.newCall(request).execute().use { res ->
                if (res.isSuccessful) finalResponse = parseOpenAIResponse(res.body?.string())
            }
        } catch (e: Exception) { /* Идем дальше */ }
    }

    // 5. ПРОБУЕМ GEMINI
    if (geminiKey.isNotBlank() && finalResponse == null) {
        try {
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply { put("text", "$finalSystemPrompt\n\nUser: $prompt") }))
                }))
            }.toString()
            
            val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$geminiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType())).build()
            
            client.newCall(request).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string()
                    finalResponse = JSONObject(body ?: "").getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                }
            }
        } catch (e: Exception) { /* Всё упало */ }
    }

    // 6. Очистка от тегов <thinking> и возврат
    if (finalResponse != null) {
        val cleanText = finalResponse!!.replace(Regex("<thinking>.*?</thinking>", RegexOption.DOT_MATCHES_ALL), "").trim()
        return@withContext ChatMessage(content = if (cleanText.isEmpty()) finalResponse!! else cleanText, isUser = false)
    } else {
        return@withContext ChatMessage(content = "❌ Все API выдали ошибку. Проверь ключи в настройках или лимиты.", isUser = false)
    }
}
