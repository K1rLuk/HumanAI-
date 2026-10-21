package com.humanai.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.UUID

// ==========================================
// 🎨 БРЕНДИНГ И ЦВЕТА (M3 Expressive)
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

enum class AIModel(val id: String, val displayName: String) {
    GPT("openai", "ChatGPT (Free)"),
    MISTRAL("mistral", "Mistral AI"),
    LLAMA("llama", "Llama 3 (Meta)")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember { mutableIntStateOf(0) }
            val isDark = when(themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            val colorScheme = if (isDark) darkColorScheme(primary = HumanOrange, background = DarkBg, surface = DarkSurface, onSurface = Color.White) else lightColorScheme(primary = HumanOrange, background = LightBg, surface = LightSurface, onSurface = Color.Black)
            
            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(isDark, onThemeChange = { themeMode = (themeMode + 1) % 3 })
                }
            }
        }
    }
}

// ==========================================
// 🖥️ ГЛАВНЫЙ ЭКРАН
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(isDark: Boolean, onThemeChange: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("HumanAI_Prefs", Context.MODE_PRIVATE) }
    val gson = remember { Gson() }

    var sessions by remember {
        mutableStateOf(
            try {
                val json = prefs.getString("sessions", null)
                if (json != null) gson.fromJson(json, object : TypeToken<List<ChatSession>>() {}.type) else listOf(ChatSession(title = "Приветствие"))
            } catch (e: Exception) { listOf(ChatSession(title = "Приветствие")) }
        )
    }
    var currentSessionIndex by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(sessions) {
        prefs.edit().putString("sessions", gson.toJson(sessions)).apply()
    }

    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(AIModel.GPT) }
    var isThinkingMode by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val client = remember { OkHttpClient() }

    val currentSession = sessions.getOrNull(currentSessionIndex) ?: ChatSession()

    LaunchedEffect(currentSession.messages.size) {
        if (currentSession.messages.isNotEmpty()) listState.animateScrollToItem(currentSession.messages.size - 1)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("История чатов", modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Button(onClick = { sessions = listOf(ChatSession()) + sessions; currentSessionIndex = 0; scope.launch { drawerState.close() } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = HumanOrange)) {
                    Icon(Icons.Default.Add, null); Spacer(modifier = Modifier.width(8.dp)); Text("Новый чат")
                }
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn {
                    items(sessions.size) { index ->
                        NavigationDrawerItem(
                            label = { Text(sessions[index].title, maxLines = 1) },
                            selected = index == currentSessionIndex,
                            onClick = { currentSessionIndex = index; scope.launch { drawerState.close() } },
                            modifier = Modifier.padding(horizontal = 8.dp),
                            colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = HumanOrange.copy(alpha = 0.2f))
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, null) } },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(HumanOrange), contentAlignment = Alignment.Center) { Text("H", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column { Text("HumanAI", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text(selectedModel.displayName, fontSize = 12.sp, color = HumanOrange) }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showModelMenu = true }) { Icon(Icons.Default.Memory, contentDescription = "Models", tint = HumanOrange) }
                        DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                            AIModel.values().forEach { model ->
                                DropdownMenuItem(text = { Text(model.displayName) }, onClick = { selectedModel = model; showModelMenu = false }, leadingIcon = { if (selectedModel == model) Icon(Icons.Default.Check, null, tint = HumanOrange) })
                            }
                        }
                        IconButton(onClick = onThemeChange) { Icon(if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = "Theme") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            bottomBar = {
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val scale by animateFloatAsState(if (isThinkingMode) 1f else 0.9f, label = "think")
                        FilterChip(
                            selected = isThinkingMode, onClick = { isThinkingMode = !isThinkingMode },
                            label = { Text("Deep Reasoning", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Outlined.Psychology, null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.scale(scale),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = HumanOrange.copy(alpha = 0.2f), selectedLabelColor = HumanOrange)
                        )
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("Спроси HumanAI...") }, shape = RoundedCornerShape(24.dp), maxLines = 5,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HumanOrange, unfocusedBorderColor = Color.Transparent, focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val buttonScale by animateFloatAsState(targetValue = if (inputText.isNotBlank()) 1f else 0.8f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "sendBtn")
                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank() && !isLoading) {
                                    val prompt = inputText; inputText = ""; isLoading = true
                                    val updatedMessages = currentSession.messages + ChatMessage(content = prompt, isUser = true)
                                    val updatedTitle = if (currentSession.messages.isEmpty()) prompt.take(20) else currentSession.title
                                    sessions = sessions.toMutableList().also { it[currentSessionIndex] = currentSession.copy(messages = updatedMessages, title = updatedTitle) }
                                    scope.launch {
                                        val response = processSmartPrompt(prompt, updatedMessages, isThinkingMode, client)
                                        sessions = sessions.toMutableList().also { it[currentSessionIndex] = currentSession.copy(messages = updatedMessages + response) }
                                        isLoading = false
                                    }
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier.scale(buttonScale).size(50.dp).clip(CircleShape).background(if (inputText.isNotBlank()) HumanOrange else MaterialTheme.colorScheme.surface)
                        ) {
                            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = HumanOrange, strokeWidth = 2.dp) else Icon(Icons.Default.ArrowUpward, contentDescription = "Send", tint = if (inputText.isNotBlank()) Color.White else Color.Gray)
                        }
                    }
                }
            }
        ) { padding ->
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (currentSession.messages.isEmpty()) {
                    item {
                        Column(modifier = Modifier.fillParentMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.AutoAwesome, null, tint = HumanOrange, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("HumanAI готов к работе", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                            Text("Включи Deep Reasoning для сложных задач", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                items(currentSession.messages, key = { it.id }) { msg ->
                    AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 })) {
                        MessageBubble(msg, clipboardManager)
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(msg: ChatMessage, clipboardManager: androidx.compose.ui.platform.ClipboardManager) {
    val isUser = msg.isUser
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) {
            Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(HumanOrange), contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.widthIn(max = 320.dp), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Box(modifier = Modifier.clip(RoundedCornerShape(16.dp, 16.dp, if (isUser) 0.dp else 16.dp, if (isUser) 16.dp else 0.dp)).background(if (isUser) HumanOrange else MaterialTheme.colorScheme.surface).padding(12.dp)) {
                if (msg.isImage) {
                    AsyncImage(model = msg.content, contentDescription = "AI", modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
                } else {
                    val isCode = msg.content.contains("```")
                    Text(text = msg.content.replace("```", ""), color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 22.sp, fontFamily = if (isCode) FontFamily.Monospace else FontFamily.Default)
                }
            }
            if (!isUser && !msg.isImage && msg.content.isNotBlank()) {
                Row(modifier = Modifier.padding(top = 4.dp).clickable { clipboardManager.setText(AnnotatedString(msg.content)) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(12.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Копировать", fontSize = 11.sp, color = Color.Gray)
                }
            }
        }
    }
}

// ==========================================
// 🧠 УМНАЯ ЛОГИКА (Auto-Fallback + Deep Reasoning)
// ==========================================
suspend fun processSmartPrompt(prompt: String, history: List<ChatMessage>, thinkingMode: Boolean, client: OkHttpClient): ChatMessage = withContext(Dispatchers.IO) {
    if (prompt.startsWith("/img ")) {
        val imgPrompt = prompt.replace("/img ", "").trim()
        return@withContext ChatMessage(content = "https://image.pollinations.ai/prompt/${URLEncoder.encode(imgPrompt, "UTF-8")}?nologo=true", isUser = false, isImage = true)
    }

    val contextHistory = history.takeLast(15).joinToString("\n") { if (it.isUser) "User: ${it.content}" else "AI: ${it.content}" }
    val systemInstruction = if (thinkingMode) """
        Ты — HumanAI. Сначала напиши свои рассуждения в теге <thought>, проверь их на ошибки. Исправь ошибки. Только потом напиши финальный ответ.
    """.trimIndent() else "Отвечай кратко, по делу."

    val finalPrompt = "$systemInstruction\n\nИстория:\n$contextHistory\nUser: $prompt\nAI:"
    val encodedPrompt = URLEncoder.encode(finalPrompt, "UTF-8")

    // 🔄 Авто-перебор моделей (Smart Fallback)
    val modelsToTry = listOf("openai", "mistral", "llama")
    
    for (modelId in modelsToTry) {
        try {
            val request = Request.Builder().url("https://text.pollinations.ai/$encodedPrompt?model=$modelId").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    var text = response.body?.string() ?: return@withContext ChatMessage(content = "Пустой ответ", isUser = false)
                    text = text.replace(Regex("<thought>.*?</thought>", RegexOption.DOT_MATCHES_ALL), "").trim()
                    return@withContext ChatMessage(content = text, isUser = false)
                }
            }
        } catch (e: Exception) { continue }
    }
    return@withContext ChatMessage(content = "Все бесплатные модели сейчас перегружены. Попробуй позже.", isUser = false)
}
