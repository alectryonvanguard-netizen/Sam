package com.nexa.ai

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

// ================= THEME (gradients only) =================
val Cyan = Color(0xFF22D3EE); val Blue = Color(0xFF3B82F6); val Violet = Color(0xFF8B5CF6)
val Pink = Color(0xFFEC4899); val Sub = Color(0xFF8A93B8)
val Grad = Brush.horizontalGradient(listOf(Cyan, Blue, Violet))
val Tx get() = if (Store.dark) Color.White else Color(0xFF0B1020)
val BgBrush get() = if (Store.dark) Brush.verticalGradient(listOf(Color(0xFF040612), Color(0xFF0A1030), Color(0xFF160B38)))
    else Brush.verticalGradient(listOf(Color(0xFFE8F1FF), Color(0xFFF1ECFF)))

fun Modifier.glass(r: Dp = 16.dp): Modifier {
    val s = RoundedCornerShape(r)
    val fill = if (Store.dark) Brush.linearGradient(listOf(Color(0xCC121C4A), Color(0xCC1E1459)))
        else Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFEDE8FF)))
    return this.clip(s).background(fill, s).border(1.dp, Brush.linearGradient(listOf(Cyan.copy(.55f), Violet.copy(.55f))), s)
}

// ================= STATE =================
class Msg(val text: String, val mine: Boolean, val img: Bitmap? = null)
class Chat(val title: String) { val msgs = mutableStateListOf<Msg>() }

object Store {
    var screen by mutableStateOf("boot"); var tab by mutableStateOf("home")
    var user by mutableStateOf("Samuel"); var dark by mutableStateOf(true); var notif by mutableStateOf(false)
    var lang by mutableStateOf("English"); var logged by mutableStateOf(false); var floating by mutableStateOf(false)
    var busy by mutableStateOf(false); var autoListen = false; var foreground = false
    var avatar by mutableStateOf<Bitmap?>(null)
    val chats = mutableStateListOf<Chat>(); var current by mutableStateOf<Chat?>(null)
    lateinit var prefs: SharedPreferences

    fun load(c: Context) {
        prefs = c.getSharedPreferences("nexa", 0)
        user = prefs.getString("user", "Samuel")!!; dark = prefs.getBoolean("dark", true)
        logged = prefs.getBoolean("logged", false); lang = prefs.getString("lang", "English")!!
        notif = prefs.getBoolean("notif", false)
        File(c.filesDir, "avatar.png").takeIf { it.exists() }?.let { avatar = BitmapFactory.decodeFile(it.path) }
    }
    fun save() = prefs.edit().putString("user", user).putBoolean("dark", dark).putBoolean("logged", logged)
        .putString("lang", lang).putBoolean("notif", notif).apply()
    fun setAvatar(c: Context, b: Bitmap) {
        val s = Bitmap.createScaledBitmap(b, 400, 400 * b.height / b.width, true)
        avatar = s
        File(c.filesDir, "avatar.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    fun ask(ctx: Context, text: String, bmp: Bitmap?, scope: CoroutineScope, done: (String) -> Unit = {}) {
        val c = current ?: Chat(text.take(30)).also { chats.add(0, it); current = it }
        c.msgs.add(Msg(text, true, bmp)); busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { Gemini.text(text, bmp) }
            c.msgs.add(Msg(r, false)); busy = false
            if (!foreground) notify(ctx, "Nexa AI", r)
            done(r)
        }
    }
}

fun notify(ctx: Context, title: String, text: String) {
    if (!Store.notif) return
    if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
    val n = Notification.Builder(ctx, "nexa").setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title)
        .setContentText(text.take(100)).setStyle(Notification.BigTextStyle().bigText(text.take(300)))
        .setContentIntent(pi).setAutoCancel(true).build()
    ctx.getSystemService(NotificationManager::class.java).notify(System.currentTimeMillis().toInt(), n)
}

// ================= GEMINI (key comes from the GitHub secret at build time) =================
object Gemini {
    private const val NOKEY = "Nexa isn't connected yet. Add the GEMINI_API_KEY secret in your GitHub repo and rebuild."
    private fun call(model: String, body: JSONObject): JSONObject {
        val u = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${BuildConfig.GEMINI_KEY}")
        val c = u.openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val s = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return JSONObject(s)
    }
    private fun b64(b: Bitmap): String {
        val w = 1024.coerceAtMost(b.width)
        val s = Bitmap.createScaledBitmap(b, w, w * b.height / b.width, true)
        val o = ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.JPEG, 80, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }
    fun text(prompt: String, bmp: Bitmap?): String {
        if (BuildConfig.GEMINI_KEY.isBlank()) return NOKEY
        return try {
            val parts = JSONArray().put(JSONObject().put("text", prompt))
            if (bmp != null) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64(bmp))))
            val r = call("gemini-2.0-flash", JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))))
            if (r.has("error")) return "Error: " + r.getJSONObject("error").getString("message")
            r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        } catch (e: Exception) { "Error: ${e.message}" }
    }
    fun image(prompt: String): Pair<Bitmap?, String> {
        if (BuildConfig.GEMINI_KEY.isBlank()) return null to NOKEY
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

// ================= ACTIVITY =================
class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.load(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("nexa", "Nexa AI", NotificationManager.IMPORTANCE_DEFAULT))
        handle(intent)
        setContent { MaterialTheme { App() } }
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }
    override fun onResume() { super.onResume(); Store.foreground = true }
    override fun onPause() { super.onPause(); Store.foreground = false }
    private fun handle(i: Intent?) {
        if (!Store.logged) return
        when (i?.getStringExtra("dest")) {
            "ask" -> { Store.screen = "main"; Store.tab = "chat" }
            "voice" -> { Store.screen = "main"; Store.tab = "voice"; Store.autoListen = true }
            "image" -> Store.screen = "image"
            "home" -> { Store.screen = "main"; Store.tab = "home" }
        }
        i?.removeExtra("dest")
    }
}

// ================= HELPERS =================
fun photoPerm() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable fun rememberPerm(perm: String?, onResult: (Boolean) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
    return { if (perm == null || ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) onResult(true) else l.launch(perm) }
}

@Composable fun T(s: String, size: Int = 14, c: Color = Tx, w: FontWeight? = null, m: Modifier = Modifier, al: TextAlign? = null) =
    Text(s, m, c, size.sp, fontWeight = w, textAlign = al)
@Composable fun GText(s: String, size: Int, w: FontWeight = FontWeight.Bold) =
    Text(s, style = TextStyle(brush = Grad, fontSize = size.sp, fontWeight = w))
fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()

@Composable fun Logo(size: Dp) {
    val sh = RoundedCornerShape(size / 3.5f)
    Box(Modifier.size(size).shadow(size / 3, sh, ambientColor = Cyan, spotColor = Violet).clip(sh)
        .background(Brush.linearGradient(listOf(Color(0xFF0B1238), Color(0xFF1E1259))), sh)
        .border(size / 30, Brush.linearGradient(listOf(Cyan, Violet, Pink)), sh), Alignment.Center) {
        Text("N", style = TextStyle(brush = Brush.linearGradient(listOf(Cyan, Blue, Violet, Pink)), fontSize = (size.value * .62f).sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic))
    }
}

@Composable fun Robot(size: Dp) {
    val t by rememberInfiniteTransition(label = "r").animateFloat(0.55f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "e")
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val head = Brush.linearGradient(listOf(Color(0xFFA9BCFF), Color(0xFF5B5BE6)))
        val dark = Brush.linearGradient(listOf(Color(0xFF050B2A), Color(0xFF18114F)))
        drawCircle(Brush.radialGradient(listOf(Violet.copy(.5f), Color.Transparent)), radius = w * .55f)
        drawCircle(Brush.linearGradient(listOf(Blue, Violet)), w * .11f, Offset(w * .14f, w * .5f))
        drawCircle(Brush.linearGradient(listOf(Blue, Violet)), w * .11f, Offset(w * .86f, w * .5f))
        drawRoundRect(Brush.linearGradient(listOf(Blue, Violet)), Offset(w * .3f, w * .8f), Size(w * .4f, w * .2f), CornerRadius(w * .1f))
        drawRoundRect(head, Offset(w * .2f, w * .2f), Size(w * .6f, w * .55f), CornerRadius(w * .2f))
        drawRoundRect(dark, Offset(w * .27f, w * .33f), Size(w * .46f, w * .28f), CornerRadius(w * .12f))
        for (x in listOf(.40f, .60f)) {
            val c = Offset(w * x, w * .47f)
            drawCircle(Brush.radialGradient(listOf(Cyan.copy(.8f * t), Color.Transparent), center = c, radius = w * .1f), w * .1f, c)
            drawOval(Brush.verticalGradient(listOf(Color.White, Cyan), c.y - w * .04f, c.y + w * .04f), Offset(c.x - w * .025f, c.y - w * .04f), Size(w * .05f, w * .08f))
        }
    }
}

@Composable fun Ring(d: Dp) {
    val a by rememberInfiniteTransition(label = "s").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(d)) {
        rotate(a) { drawArc(Brush.sweepGradient(listOf(Color.Transparent, Cyan, Violet)), 0f, 300f, false, style = Stroke(d.toPx() * .07f, cap = StrokeCap.Round)) }
    }
}

@Composable fun GBtn(t: String, m: Modifier = Modifier, on: () -> Unit) {
    val s = RoundedCornerShape(26.dp)
    Box(m.fillMaxWidth().height(52.dp).shadow(14.dp, s, ambientColor = Cyan, spotColor = Violet).clip(s)
        .background(Brush.horizontalGradient(listOf(Cyan, Blue, Violet)), s).clickable(onClick = on), Alignment.Center) { T(t, 16, Color.White, FontWeight.SemiBold) }
}
@Composable fun OBtn(t: String, on: () -> Unit) =
    Box(Modifier.fillMaxWidth().height(48.dp).glass(24.dp).clickable(onClick = on), Alignment.Center) { T(t, 14) }

@Composable fun Field(v: String, ch: (String) -> Unit, hint: String, pw: Boolean = false, trail: @Composable (() -> Unit)? = null, single: Boolean = true) =
    Box(Modifier.fillMaxWidth().glass(14.dp)) {
        TextField(v, ch, Modifier.fillMaxWidth(), placeholder = { T(hint, 14, Sub) }, singleLine = single,
            visualTransformation = if (pw) PasswordVisualTransformation() else VisualTransformation.None, trailingIcon = trail,
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Tx, unfocusedTextColor = Tx, cursorColor = Cyan))
    }

@Composable fun GIcon(ic: ImageVector, s: Dp = 38.dp) =
    Box(Modifier.size(s).clip(CircleShape).background(Brush.linearGradient(listOf(Cyan.copy(.35f), Violet.copy(.5f))), CircleShape).border(1.dp, Grad, CircleShape), Alignment.Center) {
        Icon(ic, null, tint = Color.White, modifier = Modifier.size(s * 0.52f))
    }

@Composable fun Avatar(s: Dp, m: Modifier = Modifier) =
    Box(m.size(s).clip(CircleShape).background(Brush.linearGradient(listOf(Blue, Violet)), CircleShape).border(2.dp, Grad, CircleShape), Alignment.Center) {
        val a = Store.avatar
        if (a != null) Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else T(Store.user.take(1).uppercase(), (s.value * .45f).toInt(), Color.White, FontWeight.Bold)
    }

@Composable fun Wave(level: Float, active: Boolean) {
    val t by rememberInfiniteTransition(label = "w").animateFloat(0f, 6.28f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "p")
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val n = 44; val w = size.width / n
        val br = Brush.horizontalGradient(listOf(Blue, Cyan, Violet, Pink), 0f, size.width)
        for (i in 0 until n) {
            val a = if (active) (0.2f + (0.4f + level * 0.6f) * abs(sin(t + i * 0.45f))).coerceAtMost(1f) else 0.12f
            val h = size.height * a
            drawRoundRect(br, Offset(i * w + w / 4, (size.height - h) / 2), Size(w / 2, h), CornerRadius(w / 4))
        }
    }
}

@Composable fun NDialog(title: String, ok: String, cancel: Boolean = true, onOk: () -> Unit, onDismiss: () -> Unit, body: @Composable () -> Unit) =
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().glass(22.dp).background(BgBrush.let { if (Store.dark) Brush.verticalGradient(listOf(Color(0xFF0B1030), Color(0xFF1A0F45))) else it }).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(title, 18, w = FontWeight.Bold); body()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (cancel) T("Cancel", 14, Sub, m = Modifier.clickable { onDismiss() }.padding(12.dp))
                T(ok, 14, Cyan, FontWeight.Bold, Modifier.clickable { onOk() }.padding(12.dp))
            }
        }
    }

// ================= ROOT =================
@Composable fun App() {
    Box(Modifier.fillMaxSize().background(BgBrush)) {
        if (Store.dark) Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Violet.copy(.28f), Color.Transparent), Offset(900f, 0f), 1100f)))
        if (Store.dark) Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Blue.copy(.22f), Color.Transparent), Offset(0f, 2200f), 1000f)))
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            when (Store.screen) { "boot" -> Boot(); "welcome" -> Welcome(); "login" -> Login(); "image" -> ImageGen(); else -> Main() }
        }
    }
}

@Composable fun Boot() {
    LaunchedEffect(Unit) { delay(2600); Store.screen = if (Store.logged) "main" else "welcome" }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            for (i in 0..6) {
                val p = Path(); val y0 = size.height * (.66f + i * .04f)
                p.moveTo(0f, y0); p.cubicTo(size.width * .3f, y0 - 160f - i * 18f, size.width * .6f, y0 + 150f, size.width, y0 - 90f + i * 14f)
                drawPath(p, Brush.horizontalGradient(listOf(Color.Transparent, Blue.copy(.6f), Violet.copy(.7f), Pink.copy(.4f), Color.Transparent)), style = Stroke(3f + i * 1.5f))
            }
        }
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Logo(120.dp); Spacer(Modifier.height(18.dp))
            Row { T("Nexa ", 36, w = FontWeight.Bold); GText("AI", 36) }
            T("Smarter Chats. Bigger Ideas.", 14, Color(0xFFCBD5FF)); Spacer(Modifier.height(70.dp))
            Ring(52.dp); Spacer(Modifier.height(12.dp)); T("Starting up...", 13, Color(0xFFCBD5FF))
        }
    }
}

@Composable fun Welcome() {
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Store.screen = "login" }
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        T("Skip", 14, Color(0xFFCBD5FF), m = Modifier.align(Alignment.TopEnd).clickable { Store.screen = "login" }.padding(8.dp))
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Robot(240.dp); Spacer(Modifier.height(20.dp)); T("Meet Nexa", 26, w = FontWeight.Bold)
            Spacer(Modifier.height(8.dp)); T("Your AI assistant for everyday questions, ideas and creativity.", 15, Color(0xFFCBD5FF), al = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(5) { Box(Modifier.size(if (it == 0) 16.dp else 7.dp, 7.dp).clip(CircleShape).background(if (it == 0) Grad else Brush.linearGradient(listOf(Color(0xFF2B3566), Color(0xFF3A2B7A))), CircleShape)) }
            }
        }
        GBtn("Get Started", Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)) {
            val p = mutableListOf(Manifest.permission.RECORD_AUDIO, photoPerm())
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.launch(p.toTypedArray())
        }
    }
}

@Composable fun Login() {
    val ctx = LocalContext.current
    var signUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }; var show by remember { mutableStateOf(false) }
    fun enter(name: String) {
        if (name.isNotBlank()) Store.user = name.substringBefore("@").replaceFirstChar { it.uppercase() }
        Store.logged = true; Store.save(); Store.screen = "main"; Store.tab = "home"
    }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Logo(46.dp); Spacer(Modifier.width(10.dp)); T("Nexa ", 22, w = FontWeight.Bold); GText("AI", 22)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().glass(22.dp)) {
            listOf("Log In" to false, "Sign Up" to true).forEach { (l, v) ->
                Box(Modifier.weight(1f).padding(3.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (signUp == v) Brush.horizontalGradient(listOf(Blue.copy(.7f), Violet.copy(.7f))) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)), RoundedCornerShape(20.dp))
                    .clickable { signUp = v }.padding(10.dp), Alignment.Center) { T(l, 14) }
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
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (Store.tab) { "home" -> Home(); "chat" -> ChatScreen(); "voice" -> VoiceScreen(); "history" -> HistoryScreen(); else -> Profile() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Cyan.copy(.5f), Violet.copy(.5f), Color.Transparent))))
        Row(Modifier.fillMaxWidth().background(if (Store.dark) Brush.verticalGradient(listOf(Color(0xE60A1030), Color(0xF2060818))) else Brush.verticalGradient(listOf(Color.White, Color(0xFFEFEAFF)))).padding(vertical = 8.dp)) {
            tabs.forEach { (k, l, ic) ->
                val sel = Store.tab == k
                Column(Modifier.weight(1f).clickable { Store.tab = k }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(ic, null, tint = if (sel) Cyan else Sub, modifier = Modifier.size(24.dp))
                    T(l, 10, if (sel) Cyan else Sub)
                    Box(Modifier.padding(top = 3.dp).size(if (sel) 18.dp else 0.dp, 3.dp).clip(CircleShape).background(Grad, CircleShape))
                }
            }
        }
    }
}

@Composable fun Home() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val greet = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).let { if (it < 12) "Good morning" else if (it < 18) "Good afternoon" else "Good evening" }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Menu, null, tint = Tx, modifier = Modifier.clickable { Store.tab = "history" })
            Spacer(Modifier.width(16.dp)); T("Nexa ", 18, w = FontWeight.SemiBold); GText("AI", 18); Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.clickable { toast(ctx, "Nexa Pro is coming soon") })
            Spacer(Modifier.width(12.dp)); Avatar(34.dp, Modifier.clickable { Store.tab = "profile" })
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Color(0xFF16307F), Color(0xFF3A1D8C), Color(0xFF5B21B6))), RoundedCornerShape(20.dp))
            .border(1.dp, Brush.linearGradient(listOf(Cyan.copy(.6f), Violet.copy(.6f))), RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T("$greet, ${Store.user}", 18, Color.White, FontWeight.Bold); T("What do you want to explore today?", 12, Color(0xFFCBD5FF)) }
            Robot(84.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Triple("Chat", "Ask anything", Icons.Default.Chat), Triple("Voice", "Speak", Icons.Default.Mic), Triple("Image", "Upload / Create", Icons.Default.Image)).forEach { (t, s, ic) ->
                Column(Modifier.weight(1f).glass(16.dp).clickable { when (t) { "Chat" -> Store.tab = "chat"; "Voice" -> Store.tab = "voice"; else -> Store.screen = "image" } }.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { GIcon(ic); T(t, 13, w = FontWeight.SemiBold); T(s, 10, Sub, al = TextAlign.Center) }
            }
        }
        Row { T("Quick Prompts", 15, w = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); T("See all >", 12, Cyan, m = Modifier.clickable { Store.tab = "history" }) }
        listOf(Triple("Explain something", "Explain something fascinating about science in simple terms.", Icons.Default.Lightbulb),
            Triple("Write a message", "Help me write a friendly message to a colleague.", Icons.Default.Edit),
            Triple("Create an image", "", Icons.Default.Image), Triple("Help me code", "Help me write a small Kotlin function and explain it.", Icons.Default.Code)).forEach { (t, q, ic) ->
            Row(Modifier.fillMaxWidth().glass(14.dp).clickable {
                if (q.isEmpty()) Store.screen = "image" else { Store.current = null; Store.tab = "chat"; Store.ask(ctx.applicationContext, q, null, scope) } }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GIcon(ic, 30.dp); Spacer(Modifier.width(12.dp)); T(t, 14, m = Modifier.weight(1f)); Icon(Icons.Default.KeyboardArrowRight, null, tint = Sub)
            }
        }
    }
}

@Composable fun ChatScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }; var pic by remember { mutableStateOf<Bitmap?>(null) }; var menu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? ->
        u?.let { pic = ctx.contentResolver.openInputStream(it)?.use { s -> BitmapFactory.decodeStream(s) } } }
    val askPhoto = rememberPerm(photoPerm()) { ok -> if (ok) picker.launch("image/*") else toast(ctx, "Allow photo access to attach images") }
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
        if (msgs.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Robot(130.dp); T("Ask me anything", 16, Sub) } }
        else LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = ls, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(msgs) { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
                    val shape = if (m.mine) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
                    Column(Modifier.widthIn(max = 290.dp).clip(shape)
                        .background(if (m.mine) Brush.linearGradient(listOf(Blue, Violet)) else Brush.linearGradient(listOf(Color(0xCC121C4A), Color(0xCC1E1459))), shape)
                        .padding(12.dp)) {
                        m.img?.let { Image(it.asImageBitmap(), null, Modifier.heightIn(max = 180.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit); Spacer(Modifier.height(6.dp)) }
                        T(m.text, 14, Color.White)
                    }
                }
            }
            if (Store.busy) item { Ring(24.dp) }
        }
        pic?.let { Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(it.asImageBitmap(), null, Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            IconButton({ pic = null }) { Icon(Icons.Default.Close, null, tint = Sub) } } }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(input, { input = it }, "Type a message...", trail = { IconButton({ askPhoto() }) { Icon(Icons.Default.AttachFile, null, tint = Sub) } }) }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(48.dp).shadow(12.dp, CircleShape, ambientColor = Cyan, spotColor = Violet).clip(CircleShape).background(Grad, CircleShape).clickable {
                if (input.isNotBlank() && !Store.busy) { Store.ask(ctx.applicationContext, input.trim(), pic, scope); input = ""; pic = null } }, Alignment.Center) { Icon(Icons.Default.Send, null, tint = Color.White) }
        }
    }
}

@Composable fun VoiceScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Tap to speak") }; var heard by remember { mutableStateOf("") }; var reply by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(0f) }; var listening by remember { mutableStateOf(false) }
    val tts = remember { TextToSpeech(ctx) { } }
    val rec = remember { if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null }
    val tag = when (Store.lang) { "Español" -> "es-ES"; "Français" -> "fr-FR"; else -> "en-US" }
    DisposableEffect(Unit) { onDispose { rec?.destroy(); tts.stop(); tts.shutdown() } }
    fun stop() { rec?.cancel(); tts.stop(); listening = false; level = 0f; status = "Tap to speak" }
    fun start() {
        if (rec == null) { toast(ctx, "No speech engine found on this phone"); return }
        tts.stop(); heard = ""; reply = ""
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { status = "Listening..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) { level = ((r + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false; status = "Thinking..." }
            override fun onError(e: Int) {
                listening = false; level = 0f
                status = if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "Didn't catch that. Tap to retry" else "Mic error ($e). Tap to retry"
            }
            override fun onResults(b: Bundle?) {
                level = 0f
                val said = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (said == null) { status = "Tap to speak"; return }
                heard = said; status = "Thinking..."
                Store.ask(ctx.applicationContext, said, null, scope) { ans ->
                    reply = ans; status = "Tap to speak"
                    tts.language = Locale.forLanguageTag(tag); tts.speak(ans, TextToSpeech.QUEUE_FLUSH, null, "n")
                }
            }
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard = it } }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        listening = true
    }
    val askMic = rememberPerm(Manifest.permission.RECORD_AUDIO) { ok -> if (ok) start() else toast(ctx, "Allow microphone access to talk to Nexa") }
    LaunchedEffect(Unit) { if (Store.autoListen) { Store.autoListen = false; askMic() } }
    val pulse by animateFloatAsState(1f + level * 0.18f, label = "pulse")
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        T("Voice Chat", 18, w = FontWeight.SemiBold); Spacer(Modifier.height(36.dp))
        Box(Modifier.size(230.dp).clip(CircleShape).clickable { if (listening) stop() else askMic() }, Alignment.Center) {
            Box(Modifier.size(230.dp * pulse).background(Brush.radialGradient(listOf(Violet.copy(.45f), Blue.copy(.15f), Color.Transparent)), CircleShape))
            Box(Modifier.size(160.dp).border(2.dp, Brush.sweepGradient(listOf(Cyan, Violet, Pink, Cyan)), CircleShape))
            Box(Modifier.size(128.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF0B1238), Color(0xFF22126B))), CircleShape)
                .border(3.dp, Grad, CircleShape), Alignment.Center) { Icon(Icons.Default.Mic, null, tint = Cyan, modifier = Modifier.size(56.dp)) }
        }
        Spacer(Modifier.height(18.dp)); T(status, 17, w = FontWeight.SemiBold); T("Ask anything, get instant answers.", 12, Cyan)
        Spacer(Modifier.height(14.dp)); Wave(level, listening || Store.busy)
        Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (heard.isNotEmpty()) T("“$heard”", 14, Cyan, m = Modifier.fillMaxWidth(), al = TextAlign.Center)
            if (reply.isNotEmpty()) Box(Modifier.fillMaxWidth().glass(14.dp).padding(12.dp)) { T(reply, 14) }
        }
        Box(Modifier.width(150.dp).height(46.dp).shadow(10.dp, RoundedCornerShape(23.dp), ambientColor = Pink, spotColor = Pink).clip(RoundedCornerShape(23.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFFFF4D6D), Color(0xFFFF2E93))), RoundedCornerShape(23.dp))
            .clickable { stop(); reply = ""; heard = ""; Store.tab = "home" }, Alignment.Center) { T("End", 16, Color.White, FontWeight.SemiBold) }
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
                val sh = RoundedCornerShape(20.dp)
                Box(Modifier.clip(sh).background(if (style == s) Brush.horizontalGradient(listOf(Blue.copy(.7f), Violet.copy(.7f))) else Brush.horizontalGradient(listOf(Color(0x66121C4A), Color(0x661E1459))), sh)
                    .border(1.dp, Brush.horizontalGradient(listOf(Cyan.copy(.6f), Violet.copy(.6f))), sh).clickable { style = s }.padding(horizontal = 16.dp, vertical = 8.dp)) { T(s, 13) }
            }
        }
        GBtn(if (loading) "Generating..." else "Generate") {
            if (prompt.isBlank()) toast(ctx, "Describe the image first") else if (!loading) {
                loading = true
                scope.launch {
                    val (b, err) = withContext(Dispatchers.IO) { Gemini.image("$prompt, $style style") }
                    loading = false
                    if (b != null) { bmp = b; if (!Store.foreground) notify(ctx.applicationContext, "Nexa AI", "Your image is ready") } else toast(ctx, err)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(360.dp).glass(16.dp), Alignment.Center) {
            if (loading) Ring(48.dp)
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
    val icons = listOf(Icons.Default.Lightbulb, Icons.Default.Image, Icons.Default.School, Icons.Default.Code, Icons.Default.Flight, Icons.Default.FitnessCenter)
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
                Row(Modifier.fillMaxWidth().glass(14.dp).clickable { Store.current = c; Store.tab = "chat" }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    GIcon(icons[Store.chats.indexOf(c).coerceAtLeast(0) % icons.size], 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { T(c.title, 14, w = FontWeight.SemiBold); T(c.msgs.lastOrNull()?.text?.take(40) ?: "", 12, Sub) }
                    IconButton({ Store.chats.remove(c); if (Store.current == c) Store.current = null }) { Icon(Icons.Default.Delete, null, tint = Sub) }
                }
            }
        }
    }
}

@Composable fun Item(ic: ImageVector, t: String, trail: String = "", on: () -> Unit, extra: @Composable () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().clickable(onClick = on).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ic, null, tint = Cyan, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp)); T(t, 14, m = Modifier.weight(1f)); T(trail, 12, Sub)
        extra(); Spacer(Modifier.width(4.dp)); Icon(Icons.Default.KeyboardArrowRight, null, tint = Sub)
    }

@Composable fun Profile() {
    val ctx = LocalContext.current; var dlg by remember { mutableStateOf("") }; var name by remember { mutableStateOf(Store.user) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? ->
        u?.let { ctx.contentResolver.openInputStream(it)?.use { s -> BitmapFactory.decodeStream(s) }?.let { b -> Store.setAvatar(ctx, b) } } }
    val askPhoto = rememberPerm(photoPerm()) { ok -> if (ok) picker.launch("image/*") else toast(ctx, "Allow photo access to set a profile picture") }
    val askNotif = rememberPerm(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null) { ok ->
        if (ok) { Store.notif = true; Store.save(); notify(ctx, "Nexa AI", "Notifications are on") } else toast(ctx, "Allow notifications in system settings")
    }
    val overlay = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(ctx)) { ctx.startService(Intent(ctx, FloatingService::class.java)); Store.floating = true } else toast(ctx, "Overlay permission not granted")
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(70.dp, Modifier.clickable { askPhoto() }); Spacer(Modifier.width(14.dp))
            Column { T(Store.user, 18, w = FontWeight.Bold); T("@${Store.user.lowercase()}", 12, Sub)
                Box(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(Brush.horizontalGradient(listOf(Blue.copy(.7f), Violet.copy(.7f))), RoundedCornerShape(10.dp))
                    .clickable { toast(ctx, "You are on the Free Plan") }.padding(horizontal = 10.dp, vertical = 3.dp)) { T("Free Plan", 11, Color.White) } }
        }
        Column(Modifier.fillMaxWidth().glass(18.dp)) {
            Item(Icons.Default.Lock, "Account & Security", on = { dlg = "account" })
            Item(Icons.Default.Palette, "Appearance", if (Store.dark) "Dark Mode" else "Light Mode", { Store.dark = !Store.dark; Store.save() },
                extra = { Switch(Store.dark, { Store.dark = it; Store.save() }, colors = SwitchDefaults.colors(checkedTrackColor = Cyan, checkedThumbColor = Color.White)) })
            Item(Icons.Default.Notifications, "Notifications", if (Store.notif) "On" else "Off", { if (Store.notif) { Store.notif = false; Store.save() } else askNotif() })
            Item(Icons.Default.Language, "Language", Store.lang, { Store.lang = when (Store.lang) { "English" -> "Español"; "Español" -> "Français"; else -> "English" }; Store.save() })
            Item(Icons.Default.Settings, "Chat Settings", on = { dlg = "chat" })
            Item(Icons.Default.Shield, "Privacy & Data", on = { dlg = "privacy" })
            Item(Icons.Default.Info, "About Nexa AI", "v1.0.0", { dlg = "about" })
        }
        Row(Modifier.fillMaxWidth().glass(18.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.BubbleChart, null, tint = Cyan); Spacer(Modifier.width(14.dp)); T("Floating Assistant", 14, m = Modifier.weight(1f))
            Switch(Store.floating, { on ->
                if (on) { if (Settings.canDrawOverlays(ctx)) { ctx.startService(Intent(ctx, FloatingService::class.java)); Store.floating = true }
                    else { overlay.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))); toast(ctx, "Allow \"display over other apps\"") } }
                else { ctx.stopService(Intent(ctx, FloatingService::class.java)); Store.floating = false } },
                colors = SwitchDefaults.colors(checkedTrackColor = Cyan, checkedThumbColor = Color.White))
        }
        Row(Modifier.fillMaxWidth().glass(18.dp).clickable { Store.logged = false; Store.save(); Store.current = null; Store.screen = "login" }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Logout, null, tint = Pink); Spacer(Modifier.width(14.dp)); T("Log out", 14, Pink)
        }
    }
    if (dlg.isNotEmpty()) NDialog(
        when (dlg) { "account" -> "Account & Security"; "chat" -> "Chat Settings"; "privacy" -> "Privacy & Data"; else -> "About Nexa AI" },
        if (dlg == "about") "OK" else if (dlg == "account") "Save" else "Confirm", dlg != "about",
        { when (dlg) { "account" -> { Store.user = name.ifBlank { "Samuel" }; Store.save() }
            "chat", "privacy" -> { Store.chats.clear(); Store.current = null } }; dlg = "" }, { dlg = "" }) {
        when (dlg) {
            "account" -> Field(name, { name = it }, "Display name")
            "chat" -> T("Delete every conversation stored in this app.", 14, Sub)
            "privacy" -> T("Chats stay on this device. Messages are sent to Gemini only to get answers. Clear all local chats?", 14, Sub)
            else -> T("Nexa AI v1.0.0\nSmarter Chats. Bigger Ideas.\nBuilt with Kotlin + Jetpack Compose.", 14, Sub)
        }
    }
}
