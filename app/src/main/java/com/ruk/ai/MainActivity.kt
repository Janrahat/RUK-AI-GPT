package com.ruk.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.core.content.ContextCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val Ink = Color(0xFF101010)
private val Panel = Color(0xFF1D1D1D)
private val Gold = Color(0xFFD4AF37)
private val Paper = Color(0xFFF5F2E8)

data class ChatLine(val role: String, val text: String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var speechCallback: ((String) -> Unit)? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) readSelectedFile(uri)
    }
    private var fileCallback: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        tts = TextToSpeech(this, this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Ink, surface = Panel)) {
                RukApp(
                    onPickFile = { callback -> fileCallback = callback; pickFile.launch(arrayOf("text/*", "application/pdf", "application/json", "text/markdown", "text/csv")) },
                    onSpeak = { text -> tts?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "ruk-answer") },
                    onListen = { callback -> startListening(callback) }
                )
            }
        }
    }

    private fun readSelectedFile(uri: Uri) {
        val callback = fileCallback ?: return
        Thread {
            try {
                val type = contentResolver.getType(uri) ?: ""
                val content = if (type == "application/pdf" || uri.toString().lowercase().endsWith(".pdf")) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val doc = PDDocument.load(input)
                        try { PDFTextStripper().getText(doc).take(18000) } finally { doc.close() }
                    } ?: ""
                } else {
                    contentResolver.openInputStream(uri)?.use { input ->
                        BufferedReader(InputStreamReader(input)).readText().take(18000)
                    } ?: ""
                }
                runOnUiThread { callback(content.ifBlank { "اس فائل میں پڑھنے کے قابل متن نہیں ملا۔" }) }
            } catch (e: Exception) {
                runOnUiThread { callback("فائل نہیں پڑھی جا سکی: ${e.message ?: "نامعلوم خرابی"}") }
            }
        }.start()
    }

    private fun startListening(callback: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "اس ڈیوائس پر Speech Recognition دستیاب نہیں", Toast.LENGTH_LONG).show(); return
        }
        speechCallback = callback
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 81); return
        }
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { runOnUiThread { Toast.makeText(this@MainActivity, "آواز سننے میں مسئلہ ($error)", Toast.LENGTH_SHORT).show() } }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    speechCallback?.invoke(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "اپنا سوال بولیں")
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) { Toast.makeText(this, e.message ?: "وائس شروع نہیں ہو سکی", Toast.LENGTH_LONG).show() }
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ur", "PK") }
    override fun onDestroy() { speechRecognizer?.destroy(); tts?.shutdown(); super.onDestroy() }
}

@Composable
private fun RukApp(onPickFile: ((String) -> Unit) -> Unit, onSpeak: (String) -> Unit, onListen: ((String) -> Unit) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("ruk_settings", Activity.MODE_PRIVATE) }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "https://openrouter.ai/api/v1") ?: "https://openrouter.ai/api/v1") }
    var model by remember { mutableStateOf(prefs.getString("model", "") ?: "") }
    var imageModel by remember { mutableStateOf(prefs.getString("image_model", "") ?: "") }
    var generatedImageUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var fileText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("API key صرف اس سیشن کی میموری میں رہتی ہے؛ ایپ بند کرنے پر دوبارہ درج کریں۔") }
    val lines = remember { mutableStateListOf<ChatLine>() }
    val scope = rememberCoroutineScope()

    fun requestChat(userText: String) {
        if (apiKey.isBlank() || model.isBlank()) { status = "Settings میں API key اور model درج کریں۔"; showSettings = true; return }
        if (endpoint.isBlank() || !endpoint.startsWith("https://")) { status = "HTTPS API endpoint درج کریں۔"; showSettings = true; return }
        lines.add(ChatLine("user", userText)); prompt = ""; busy = true; status = "جواب تیار ہو رہا ہے…"
        scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) { callChat(endpoint.trim(), apiKey.trim(), model.trim(), lines.toList()) }
                lines.add(ChatLine("assistant", answer)); status = "تیار"
            } catch (e: Exception) { lines.add(ChatLine("assistant", "خرابی: ${e.message ?: "درخواست ناکام ہوئی"}")); status = "درخواست مکمل نہیں ہوئی۔ API key، model اور انٹرنیٹ چیک کریں۔" }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(Ink).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(48.dp).background(Color.White, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                Text("⚛", color = Color.Black, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("RUK AI GPT", color = Gold, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Chat • Coding • Files • Voice", color = Paper, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { showSettings = !showSettings }) { Text("Settings", color = Gold) }
        }
        if (showSettings) {
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI Provider Settings", color = Gold, fontWeight = FontWeight.Bold)
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS API base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text("Chat model ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(imageModel, { imageModel = it }, label = { Text("Image model ID (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key (session only)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Button(onClick = { prefs.edit().putString("endpoint", endpoint.trim()).putString("model", model.trim()).putString("image_model", imageModel.trim()).apply(); showSettings = false; status = "Endpoint اور model محفوظ ہوگئے؛ API key محفوظ نہیں کی گئی۔" }, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) { Text("Save endpoint/model") }
                Text("مفت model کی دستیابی provider پر منحصر ہے۔ عوامی ایپ کے لیے API key کو backend server پر رکھیں؛ فون میں key درج کرنا مکمل محفوظ طریقہ نہیں۔", color = Paper, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (generatedImageUrl.isNotBlank()) {
            AsyncImage(model = generatedImageUrl, contentDescription = "AI generated image", modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp))
            TextButton(onClick = { generatedImageUrl = "" }) { Text("Close image", color = Gold) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            if (lines.isEmpty()) item { Text("Hello! I’m RUK AI. How can I help you today?", color = Paper, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
            itemsIndexed(lines) { _, line ->
                Column(Modifier.fillMaxWidth().background(if (line.role == "user") Color(0xFF2A281E) else Panel, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text(if (line.role == "user") "You" else "RUK AI", color = Gold, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp)); Text(line.text, color = Paper)
                    if (line.role == "assistant") TextButton(onClick = { onSpeak(line.text) }) { Text("🔊 جواب سنیں", color = Gold) }
                }
            }
            if (busy) item { CircularProgressIndicator(color = Gold) }
        }
        if (fileText.isNotBlank()) {
            Text("File attached (${fileText.length} characters)", color = Gold)
            Row { TextButton(onClick = { fileText = "" }) { Text("Remove file", color = Paper) } }
        }
        Text(status, color = Gold, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Message RUK AI...") }, minLines = 2, maxLines = 5)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onPickFile { text -> fileText = text; status = "فائل تیار ہے؛ سوال کے ساتھ بھیجیں۔" } }) { Text("فائل", color = Gold) }
            OutlinedButton(onClick = { onListen { heard -> prompt = if (prompt.isBlank()) heard else "$prompt $heard" } }) { Text("🎙 Voice", color = Gold) }
            OutlinedButton(enabled = !busy && prompt.isNotBlank(), onClick = {
                if (apiKey.isBlank() || imageModel.isBlank()) { status = "Image model اور API key Settings میں درج کریں۔"; showSettings = true }
                else { busy = true; status = "تصویر بن رہی ہے…"; val imagePrompt = prompt.trim(); prompt = ""; scope.launch {
                    try { generatedImageUrl = withContext(Dispatchers.IO) { callImage(endpoint.trim(), apiKey.trim(), imageModel.trim(), imagePrompt) }; status = "تصویر تیار ہے۔" }
                    catch (e: Exception) { status = "تصویر نہیں بن سکی: ${e.message ?: "نامعلوم خرابی"}" }
                    finally { busy = false }
                } }
            }) { Text("Image", color = Gold) }
            Button(modifier = Modifier.weight(1f), enabled = !busy && prompt.isNotBlank(), onClick = {
                val text = if (fileText.isNotBlank()) "اس فائل کے متن کی بنیاد پر جواب دیں:\n${fileText.take(18000)}\n\nسوال: ${prompt.trim()}" else prompt.trim()
                requestChat(text)
            }, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) { Text("Send") }
        }
    }
}

private fun callChat(endpoint: String, key: String, model: String, lines: List<ChatLine>): String {
    val url = URL(endpoint.trimEnd('/') + "/chat/completions")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 20000; readTimeout = 60000; doOutput = true
        setRequestProperty("Authorization", "Bearer $key"); setRequestProperty("Content-Type", "application/json")
    }
    val messages = JSONArray()
    messages.put(JSONObject().put("role", "system").put("content", "You are RUK AI GPT, a helpful assistant. Reply in the user's language. For coding, explain safe and correct solutions."))
    lines.takeLast(20).forEach { messages.put(JSONObject().put("role", if (it.role == "assistant") "assistant" else "user").put("content", it.text)) }
    val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.7)
    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val response = stream.bufferedReader().use { it.readText() }
    conn.disconnect()
    if (code !in 200..299) throw IllegalStateException("HTTP $code: ${response.take(700)}")
    val json = JSONObject(response)
    return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "جواب خالی ملا۔")
}

private fun callImage(endpoint: String, key: String, model: String, prompt: String): String {
    val conn = (URL(endpoint.trimEnd('/') + "/images/generations").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 20000; readTimeout = 90000; doOutput = true
        setRequestProperty("Authorization", "Bearer $key"); setRequestProperty("Content-Type", "application/json")
    }
    val body = JSONObject().put("model", model).put("prompt", prompt).put("n", 1).put("size", "1024x1024")
    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val response = stream.bufferedReader().use { it.readText() }
    conn.disconnect()
    if (code !in 200..299) throw IllegalStateException("HTTP $code: ${response.take(700)}")
    val item = JSONObject(response).getJSONArray("data").getJSONObject(0)
    val imageUrl = item.optString("url", "")
    if (imageUrl.startsWith("https://")) return imageUrl
    throw IllegalStateException("Provider نے HTTPS image URL نہیں دیا۔ اس API کے لیے image endpoint مختلف ہو سکتا ہے۔")
}
