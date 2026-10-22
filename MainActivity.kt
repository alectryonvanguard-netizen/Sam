package com.nexa.ai

import android.app.Activity
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

// ---------- Theme ----------
val Cyan = Color(0xFF22D3EE); val Violet = Color(0xFF8B5CF6); val Sub = Color(0xFF8A93B8)
val Grad = Brush.horizontalGradient(listOf(Cyan, Violet))
val Bg get() = if (Store.dark) Color(0xFF070B1A) else Color(0xFFF2F5FF)
val Card get() = if (Store.dark) Color(0xFF0F1632) else Color.White
val Tx get() = if (Store.dark) Color.White else Color(0xFF0B1020)
val Line get() = if (Store.dark) Color(0xFF26305A) else Color(0xFFD5DBF2)

// ---------- State ----------
class Msg(val text: String, val mine: Boolean, val img: Bitmap? = null)
class Chat(val title: String) { val msgs = mutableStateListOf<Msg>() }

object Store {
    var screen by mutableStateOf("boot"); var tab by mutableStateOf("home")
    var user by mutableStateOf("Samuel"); var key by mutableStateOf("")
    var dark by mutableStateOf(true); var notif by mutableStateOf(true)
    var lang by mutableStateOf("English"); var logged by mutableStateOf(false)
    var floating by mutableStateOf(false); var busy by mutableStateOf(false)
    val chats = mutableStateListOf<Chat>(); var current by mutableStateOf<Chat?>(null)
    lateinit var prefs: SharedPreferences

    fun load(c: Context) {
        prefs = c.getSharedPreferences("nexa", 0)
        user = prefs.getString("user", "Samuel")!!; key = prefs.getString("key", "")!!
        dark = prefs.getBoolean("dark", true); logged = prefs.getBoolean("logged", false)
        lang = prefs.getString("lang", "English")!!
    }
    fun save() = prefs.edit().putString("user", user).putString("key", key).putBoolean("dark", dark)
        .putBoolean("logged", logged).putString("lang", lang).apply()

    fun ask(text: String, bmp: Bitmap?, scope: CoroutineScope, done: (String) -> Unit = {}) {
        val c = current ?: Chat(text.take(30)).also { chats.add(0, it); current = it }
        c.msgs.add(Msg(text, true, bmp)); busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { Gemini.text(text, bmp) }
            c.msgs.add(Msg(r, false)); busy = false; done(r)
        }
    }
}

// ---------- Gemini ----------
object Gemini {
    private fun call(model: String, body: JSONObject): JSONObject {
        val u = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${Store.key}")
        val c = u.openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val s = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return JSONObject(s)
    }
    private fun b64(b: Bitmap): String {
        val s = Bitmap.createScaledBitmap(b, 1024.coerceAtMost(b.width), (1024.coerceAtMost(b.width) * b.height / b.width), true)
        val o = ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.JPEG, 80, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }
    fun text(prompt: String, bmp: Bitmap?): String {
        if (Store.key.isBlank()) return "Add your Gemini API key in Profile → Account & Security."
        return try {
            val parts = JSONArray().put(JSONObject().put("text", prompt))
            if (bmp != null) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64(bmp))))
            val r = call("gemini-2.0-flash", JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))))
            if (r.has("error")) return "Error: " + r.getJSONObject("error").getString("message")
            r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        } catch (e: Exception) { "Error: ${e.message}" }
    }
    fun image(prompt: String): Pair<Bitmap?, String> {
        if (Store.key.isBlank()) return null to "Add your Gemini API key in Profile → Account & Security."
        return try {
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
            val r = call("gemini-2.0-flash-preview-image-generation", body)
            if (r.has("error")) return null to r.getJSONObject("error").getString("message")
            val parts = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.has("inlineData")) {
                    val bytes = Base64.decode(p.getJSONObject("inlineData").getString("data"), Base64.DEFAULT)
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) to ""
                }
            }
            null to "No image returned. Try another prompt."
        } catch (e: Exception) { null to "Error: ${e.message}" }
    }
}

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.load(this); handle(intent)
        setContent { MaterialTheme { App() } }
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }
    private fun handle(i: Intent?) {
        if (!Store.logged) return
        when (i?.getStringExtra("dest")) {
            "ask" -> { Store.screen = "main"; Store.tab = "chat" }
            "voice" -> { Store.screen = "main"; Store.tab = "voice" }
            "image" -> Store.screen = "image"
            "home" -> { Store.screen = "main"; Store.tab = "home" }
        }
        i?.removeExtra("dest")
    }
}

// ---------- Helpers ----------
@Composable fun T(s: String, size: Int = 14, c: Color = Tx, w: FontWeight? = null, m: Modifier = Modifier, al: TextAlign? = null) =
    Text(s, m, c, size.sp, fontWeight = w, textAlign = al)

@Composable fun Logo(size: Dp) {
    val sh = RoundedCornerShape(size / 4)
    Box(Modifier.size(size).clip(sh).background(Color(0xFF0B1230)).border(2.dp, Grad, sh), Alignment.Center) {
        Text("N", style = TextStyle(brush = Grad, fontSize = (size.value * 0.6f).sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic))
    }
}
@Composable fun GBtn(t: String, m: Modifier = Modifier, on: () -> Unit) =
    Box(m.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(Grad).clickable(onClick = on), Alignment.Center) { T(t, 16, Color.White, FontWeight.SemiBold) }

@Composable fun OBtn(t: String, on: () -> Unit) =
    Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(24.dp)).clickable(onClick = on), Alignment.Center) { T(t, 14) }

@Composable fun Field(v: String, ch: (String) -> Unit, hint: String, pw: Boolean = false, trail: @Composable (() -> Unit)? = null, single: Boolean = true) =
    OutlinedTextField(v, ch, Modifier.fillMaxWidth(), placeholder = { T(hint, 14, Sub) }, singleLine = single,
        visualTransformation = if (pw) PasswordVisualTransformation() else VisualTransformation.None, trailingIcon = trail,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = Line, focusedTextColor = Tx, unfocusedTextColor = Tx, cursorColor = Cyan))

fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()

@Composable fun Wave(active: Boolean) {
    val t by rememberInfiniteTransition(label = "w").animateFloat(0f, 6.28f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "p")
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val n = 40; val w = size.width / n
        for (i in 0 until n) {
            val a = if (active) 0.25f + 0.75f * abs(sin(t + i * 0.5f)) else 0.15f
            val h = size.height * a
            drawRoundRect(Cyan, Offset(i * w + w / 4, (size.height - h) / 2), Size(w / 2, h), CornerRadius(w / 4))
        }
    }
}

// ---------- Root ----------
@Composable fun App() {
    Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        when (Store.screen) {
            "boot" -> Boot(); "welcome" -> Welcome(); "login" -> Login()
            "image" -> ImageGen(); else -> Main()
        }
    }
}

@Composable fun Boot() {
    LaunchedEffect(Unit) { delay(2000); Store.screen = if (Store.logged) "main" else "welcome" }
    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        Logo(110.dp); Spacer(Modifier.height(16.dp))
        Row { T("Nexa ", 34, w = FontWeight.Bold); Text("AI", style = TextStyle(brush = Grad, fontSize = 34.sp, fontWeight = FontWeight.Bold)) }
        T("Smarter Chats. Bigger Ideas.", 14); Spacer(Modifier.height(60.dp))
        CircularProgressIndicator(color = Cyan); Spacer(Modifier.height(10.dp)); T("Starting up...", 13, Sub)
    }
}

@Composable fun Welcome() {
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        T("Skip", 14, Sub, m = Modifier.align(Alignment.TopEnd).clickable { Store.screen = "login" }.padding(8.dp))
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Box(Modifier.size(180.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Violet.copy(.5f), Color.Transparent))), Alignment.Center) { Text("🤖", fontSize = 100.sp) }
            Spacer(Modifier.height(24.dp)); T("Meet Nexa", 26, w = FontWeight.Bold)
            Spacer(Modifier.height(8.dp)); T("Your AI assistant for everyday questions, ideas and creativity.", 15, Sub, al = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { repeat(5) { Box(Modifier.size(7.dp).clip(CircleShape).background(if (it == 0) Cyan else Line)) } }
        }
        GBtn("Get Started", Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)) { Store.screen = "login" }
    }
}

@Composable fun Login() {
    val ctx = LocalContext.current
    var signUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }; var show by remember { mutableStateOf(false) }
    fun enter(name: String) { if (name.isNotBlank()) Store.user = name.substringBefore("@").replaceFirstChar { it.uppercase() }
        Store.logged = true; Store.save(); Store.screen = "main"; Store.tab = "home" }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Logo(44.dp); Spacer(Modifier.width(10.dp)); T("Nexa AI", 22, w = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, Line, RoundedCornerShape(20.dp))) {
            listOf("Log In" to false, "Sign Up" to true).forEach { (l, v) ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(if (signUp == v) Violet.copy(.45f) else Color.Transparent).clickable { signUp = v }.padding(10.dp), Alignment.Center) { T(l, 14) }
            }
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T("Email or Username", 13); Field(email, { email = it }, "Enter your email or username")
            Spacer(Modifier.height(4.dp)); T("Password", 13)
            Field(pass, { pass = it }, "Enter your password", !show, { IconButton({ show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Sub) } })
        }
        T("Forgot password?", 12, Cyan, m = Modifier.align(Alignment.End).clickable { toast(ctx, "Password reset link sent (demo)") }.padding(6.dp))
        GBtn(if (signUp) "Create Account" else "Log In") { if (email.isBlank() || pass.isBlank()) toast(ctx, "Fill in both fields") else enter(email) }
        Spacer(Modifier.height(14.dp)); T("or", 12, Sub); Spacer(Modifier.height(14.dp))
        OBtn("G   Continue with Google") { enter("") }
        Spacer(Modifier.height(10.dp))
        OBtn("</>   Continue with GitHub") { enter("") }
        Spacer(Modifier.weight(1f))
        Row { T(if (signUp) "Already have an account? " else "Don't have an account? ", 13); T(if (signUp) "Log In" else "Sign Up", 13, Cyan, FontWeight.Bold, Modifier.clickable { signUp = !signUp }) }
    }
}

@Composable fun Main() {
    val tabs = listOf(Triple("home", "Home", Icons.Default.Home), Triple("chat", "Chat", Icons.Default.Chat), Triple("voice", "Voice", Icons.Default.Mic),
        Triple("history", "History", Icons.Default.History), Triple("profile", "Profile", Icons.Default.Person))
    Scaffold(containerColor = Bg, bottomBar = {
        NavigationBar(containerColor = Card) {
            tabs.forEach { (k, l, ic) ->
                NavigationBarItem(Store.tab == k, { Store.tab = k }, { Icon(ic, null) }, label = { Text(l, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Cyan, selectedTextColor = Cyan, indicatorColor = Line, unselectedIconColor = Sub, unselectedTextColor = Sub))
            }
        }
    }) { p -> Box(Modifier.padding(p).fillMaxSize()) {
        when (Store.tab) { "home" -> Home(); "chat" -> ChatScreen(); "voice" -> VoiceScreen(); "history" -> HistoryScreen(); else -> Profile() }
    } }
}

@Composable fun Home() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val greet = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).let { if (it < 12) "Good morning" else if (it < 18) "Good afternoon" else "Good evening" }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Menu, null, tint = Tx, modifier = Modifier.clickable { Store.tab = "history" })
            Spacer(Modifier.width(16.dp)); T("Nexa AI", 18, w = FontWeight.SemiBold); Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.clickable { toast(ctx, "Nexa Pro is coming soon") })
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(32.dp).clip(CircleShape).background(Violet).clickable { Store.tab = "profile" }, Alignment.Center) { T(Store.user.take(1), 14, Color.White) }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF1A2A6C), Color(0xFF3B1E7A)))).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T("$greet, ${Store.user}", 18, Color.White, FontWeight.Bold); T("What do you want to explore today?", 12, Color(0xFFCBD5FF)) }
            Text("🤖", fontSize = 46.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Triple("Chat", "Ask anything", Icons.Default.Chat), Triple("Voice", "Speak", Icons.Default.Mic), Triple("Image", "Upload / Create", Icons.Default.Image)).forEach { (t, s, ic) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(16.dp)).clickable {
                    when (t) { "Chat" -> Store.tab = "chat"; "Voice" -> Store.tab = "voice"; else -> Store.screen = "image" } }.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) { Icon(ic, null, tint = Cyan); T(t, 13, w = FontWeight.SemiBold); T(s, 10, Sub, al = TextAlign.Center) }
            }
        }
        Row { T("Quick Prompts", 15, w = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); T("See all >", 12, Cyan, m = Modifier.clickable { Store.tab = "history" }) }
        listOf("Explain something" to "Explain something fascinating about science in simple terms.", "Write a message" to "Help me write a friendly message to a colleague.",
            "Create an image" to "", "Help me code" to "Help me write a small Kotlin function and explain it.").forEach { (t, q) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(14.dp)).clickable {
                if (q.isEmpty()) Store.screen = "image" else { Store.current = null; Store.tab = "chat"; Store.ask(q, null, scope) } }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                T(t, 14, m = Modifier.weight(1f)); Icon(Icons.Default.KeyboardArrowRight, null, tint = Sub)
            }
        }
    }
}

@Composable fun ChatScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }; var pic by remember { mutableStateOf<Bitmap?>(null) }; var menu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? ->
        u?.let { pic = ctx.contentResolver.openInputStream(it)?.use { s -> BitmapFactory.decodeStream(s) } } }
    val msgs = Store.current?.msgs ?: emptyList<Msg>()
    val ls = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) ls.animateScrollToItem(msgs.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ArrowBack, null, tint = Tx, modifier = Modifier.clickable { Store.tab = "home" })
            Spacer(Modifier.width(12.dp)); Logo(34.dp); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) { T("Nexa AI", 15, w = FontWeight.SemiBold); T(if (Store.busy) "Typing..." else "Online", 11, Color(0xFF34D399)) }
            Box { Icon(Icons.Default.MoreVert, null, tint = Tx, modifier = Modifier.clickable { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("New chat") }, { Store.current = null; menu = false })
                    DropdownMenuItem({ Text("Delete this chat") }, { Store.current?.let { Store.chats.remove(it) }; Store.current = null; menu = false })
                } }
        }
        if (msgs.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { T("Ask me anything 👋", 16, Sub) }
        else LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = ls, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(msgs) { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(16.dp)).background(if (m.mine) Violet else Card).padding(12.dp)) {
                        m.img?.let { Image(it.asImageBitmap(), null, Modifier.heightIn(max = 180.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit); Spacer(Modifier.height(6.dp)) }
                        T(m.text, 14, if (m.mine) Color.White else Tx)
                    }
                }
            }
            if (Store.busy) item { CircularProgressIndicator(color = Cyan, modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }
        }
        pic?.let { Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(it.asImageBitmap(), null, Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            IconButton({ pic = null }) { Icon(Icons.Default.Close, null, tint = Sub) } } }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(input, { input = it }, "Type a message...", trail = { IconButton({ picker.launch("image/*") }) { Icon(Icons.Default.AttachFile, null, tint = Sub) } }) }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(46.dp).clip(CircleShape).background(Grad).clickable {
                if (input.isNotBlank() && !Store.busy) { Store.ask(input.trim(), pic, scope); input = ""; pic = null } }, Alignment.Center) { Icon(Icons.Default.Send, null, tint = Color.White) }
        }
    }
}

@Composable fun VoiceScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Tap to speak") }; var reply by remember { mutableStateOf("") }; var active by remember { mutableStateOf(false) }
    val tts = remember { TextToSpeech(ctx) { } }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown() } }
    val tag = when (Store.lang) { "Español" -> "es-ES"; "Français" -> "fr-FR"; else -> "en-US" }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
        if (said == null) { status = "Tap to speak"; active = false } else {
            status = "Thinking..."; active = true
            Store.ask(said, null, scope) { ans -> reply = ans; status = "Tap to speak"; tts.language = Locale.forLanguageTag(tag); tts.speak(ans, TextToSpeech.QUEUE_FLUSH, null, "n") }
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        T("Voice Chat", 18, w = FontWeight.SemiBold); Spacer(Modifier.height(50.dp))
        Box(Modifier.size(190.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Violet.copy(.4f), Color.Transparent))).clickable {
            try { status = "Listening..."; active = true
                launcher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag))
            } catch (e: Exception) { toast(ctx, "Speech recognition not available"); status = "Tap to speak"; active = false } }, Alignment.Center) {
            Box(Modifier.size(120.dp).clip(CircleShape).border(3.dp, Grad, CircleShape), Alignment.Center) { Icon(Icons.Default.Mic, null, tint = Cyan, modifier = Modifier.size(54.dp)) }
        }
        Spacer(Modifier.height(24.dp)); T(status, 17, w = FontWeight.SemiBold); T("Ask anything, get instant answers.", 12, Cyan)
        Spacer(Modifier.height(16.dp)); Wave(active || Store.busy)
        if (reply.isNotEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp).clip(RoundedCornerShape(14.dp)).background(Card).verticalScroll(rememberScrollState()).padding(12.dp)) { T(reply, 14) } else Spacer(Modifier.weight(1f))
        Box(Modifier.width(150.dp).height(46.dp).clip(RoundedCornerShape(23.dp)).background(Color(0xFFEF4444)).clickable { tts.stop(); active = false; status = "Tap to speak"; reply = ""; Store.tab = "home" }, Alignment.Center) { T("End", 16, Color.White, FontWeight.SemiBold) }
    }
}

@Composable fun ImageGen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }; var style by remember { mutableStateOf("Realistic") }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }; var loading by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ArrowBack, null, tint = Tx, modifier = Modifier.clickable { Store.screen = "main" })
            Spacer(Modifier.width(14.dp)); T("Create Image", 18, w = FontWeight.SemiBold); Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Download, null, tint = Tx, modifier = Modifier.clickable { bmp?.let { saveImg(ctx, it) } ?: toast(ctx, "Generate an image first") })
        }
        Field(prompt, { prompt = it }, "A futuristic city at night with neon lights", single = false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Realistic", "Anime", "Artistic").forEach { s ->
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(if (style == s) Violet.copy(.5f) else Card).border(1.dp, Line, RoundedCornerShape(20.dp)).clickable { style = s }.padding(horizontal = 16.dp, vertical = 8.dp)) { T(s, 13) }
            }
        }
        GBtn(if (loading) "Generating..." else "Generate") {
            if (prompt.isBlank()) toast(ctx, "Describe the image first") else if (!loading) {
                loading = true
                scope.launch { val (b, err) = withContext(Dispatchers.IO) { Gemini.image("$prompt, $style style") }
                    loading = false; if (b != null) bmp = b else toast(ctx, err) }
            }
        }
        Box(Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(16.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(16.dp)), Alignment.Center) {
            if (loading) CircularProgressIndicator(color = Cyan)
            else bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: T("Your image will appear here", 13, Sub)
        }
    }
}
fun saveImg(ctx: Context, b: Bitmap) {
    val v = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "nexa_${System.currentTimeMillis()}.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NexaAI") }
    ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)?.let { u -> ctx.contentResolver.openOutputStream(u)?.use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    toast(ctx, "Saved to Pictures/NexaAI")
}

@Composable fun HistoryScreen() {
    var q by remember { mutableStateOf("") }; var menu by remember { mutableStateOf(false) }
    val list = Store.chats.filter { q.isBlank() || it.title.contains(q, true) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Menu, null, tint = Tx, modifier = Modifier.clickable { Store.tab = "home" }); Spacer(Modifier.width(14.dp)); T("Chat History", 18, w = FontWeight.SemiBold); Spacer(Modifier.weight(1f))
            Box { Icon(Icons.Default.MoreVert, null, tint = Tx, modifier = Modifier.clickable { menu = true })
                DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("Clear all") }, { Store.chats.clear(); Store.current = null; menu = false }) } }
        }
        Field(q, { q = it }, "Search chats...", trail = { Icon(Icons.Default.Search, null, tint = Sub) })
        if (list.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { T("No chats yet", 14, Sub) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list) { c ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).clickable { Store.current = c; Store.tab = "chat" }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(Grad), Alignment.Center) { Icon(Icons.Default.Chat, null, tint = Color.White) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { T(c.title, 14, w = FontWeight.SemiBold); T(c.msgs.lastOrNull()?.text?.take(40) ?: "", 12, Sub) }
                    IconButton({ Store.chats.remove(c); if (Store.current == c) Store.current = null }) { Icon(Icons.Default.Delete, null, tint = Sub) }
                }
            }
        }
    }
}

@Composable fun Profile() {
    val ctx = LocalContext.current; var dlg by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(Store.user) }; var key by remember { mutableStateOf(Store.key) }
    fun row(ic: androidx.compose.ui.graphics.vector.ImageVector, t: String, trail: String = "", on: () -> Unit, extra: @Composable () -> Unit = {}) =
        @Composable { Row(Modifier.fillMaxWidth().clickable(onClick = on).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ic, null, tint = Sub); Spacer(Modifier.width(14.dp)); T(t, 14, m = Modifier.weight(1f)); T(trail, 12, Sub); extra()
            Spacer(Modifier.width(4.dp)); Icon(Icons.Default.KeyboardArrowRight, null, tint = Sub) } }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Violet), Alignment.Center) { T(Store.user.take(1), 26, Color.White, FontWeight.Bold) }
            Spacer(Modifier.width(14.dp))
            Column { T(Store.user, 18, w = FontWeight.Bold); T("@${Store.user.lowercase()}", 12, Sub)
                Box(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(Violet.copy(.4f)).clickable { toast(ctx, "You are on the Free Plan") }.padding(horizontal = 10.dp, vertical = 3.dp)) { T("Free Plan", 11) } }
        }
        Spacer(Modifier.height(10.dp))
        row(Icons.Default.Lock, "Account & Security", on = { dlg = "account" })()
        row(Icons.Default.Palette, "Appearance", if (Store.dark) "Dark Mode" else "Light Mode", { Store.dark = !Store.dark; Store.save() })()
        row(Icons.Default.Notifications, "Notifications", if (Store.notif) "On" else "Off", { Store.notif = !Store.notif })()
        row(Icons.Default.Language, "Language", Store.lang, { Store.lang = when (Store.lang) { "English" -> "Español"; "Español" -> "Français"; else -> "English" }; Store.save() })()
        row(Icons.Default.Settings, "Chat Settings", on = { dlg = "chat" })()
        row(Icons.Default.Shield, "Privacy & Data", on = { dlg = "privacy" })()
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.BubbleChart, null, tint = Sub); Spacer(Modifier.width(14.dp)); T("Floating Assistant", 14, m = Modifier.weight(1f))
            Switch(Store.floating, { on ->
                if (on) { if (!Settings.canDrawOverlays(ctx)) { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))); toast(ctx, "Allow overlay, then switch on again") }
                    else { ctx.startService(Intent(ctx, FloatingService::class.java)); Store.floating = true } }
                else { ctx.stopService(Intent(ctx, FloatingService::class.java)); Store.floating = false } })
        }
        row(Icons.Default.Info, "About Nexa AI", "v1.0.0", { dlg = "about" })()
        row(Icons.Default.Logout, "Log out", on = { Store.logged = false; Store.save(); Store.current = null; Store.screen = "login" })()
    }
    if (dlg.isNotEmpty()) AlertDialog(onDismissRequest = { dlg = "" },
        title = { Text(when (dlg) { "account" -> "Account & Security"; "chat" -> "Chat Settings"; "privacy" -> "Privacy & Data"; else -> "About Nexa AI" }) },
        text = { when (dlg) {
            "account" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Field(name, { name = it }, "Display name"); Field(key, { key = it }, "Gemini API key", pw = true) }
            "chat" -> Text("Delete every conversation stored in this app.")
            "privacy" -> Text("Chats stay on this device. Messages are sent to the Gemini API only to get answers. Clear all local data?")
            else -> Text("Nexa AI v1.0.0\nSmarter Chats. Bigger Ideas.\nBuilt with Kotlin + Jetpack Compose.") } },
        confirmButton = { TextButton({
            when (dlg) { "account" -> { Store.user = name.ifBlank { "Samuel" }; Store.key = key.trim(); Store.save() }
                "chat" -> { Store.chats.clear(); Store.current = null }
                "privacy" -> { Store.chats.clear(); Store.current = null; Store.key = ""; Store.save() } }
            dlg = "" }) { Text(if (dlg == "about") "OK" else if (dlg == "account") "Save" else "Confirm") } },
        dismissButton = { if (dlg != "about") TextButton({ dlg = "" }) { Text("Cancel") } })
}
