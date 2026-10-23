package com.nexa.ai

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.UtteranceProgressListener
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Replaces the old VoiceScreen in MainActivity.kt (the workflow removes the old one while building).
//
// How it works:
//  - Tap the big mic once. Nexa keeps listening until you tap End.
//  - It answers out loud. If it is not interrupted, it finishes the whole answer.
//  - If you start talking while it answers, it stops and answers your NEW question instead.
//    A late answer to the old question is thrown away.

private class VoiceState {
    var speaking = false
    var turn = 0
    var reply = ""
    var active = false
    var spokeAt = 0L
    var listenFn: () -> Unit = {}
}

private fun words(s: String): List<String> =
    s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

// True when what the mic heard is a real new question, not Nexa's own voice coming back into the mic.
private fun isInterruption(heard: String, reply: String): Boolean {
    val h = words(heard)
    if (h.isEmpty()) return false
    if (h.any { it == "stop" || it == "wait" }) return true
    val known = words(reply).toSet()
    return h.count { it !in known } >= 2
}

@Composable fun VoiceScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Tap to speak") }
    var heard by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(0f) }
    var session by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val vs = remember { VoiceState() }
    val main = remember { Handler(Looper.getMainLooper()) }
    val rec = remember { if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null }

    fun stopSpeaking() {
        vs.speaking = false
        vs.spokeAt = System.currentTimeMillis()
        Store.tts?.stop()
    }

    fun stopAll() {
        session = false; vs.turn += 1; vs.speaking = false; vs.active = false
        main.removeCallbacksAndMessages(null)
        rec?.cancel(); Store.tts?.stop()
        level = 0f; status = "Tap to speak"
    }

    // A new question always replaces whatever was being answered.
    fun respond(said: String) {
        stopSpeaking()
        vs.turn += 1
        val my = vs.turn
        vs.reply = ""
        heard = said; reply = ""; status = "Thinking..."
        Store.ask(ctx.applicationContext, said, null, scope) { ans ->
            if (my != vs.turn || !session) return@ask
            reply = ans; vs.reply = ans
            if (Store.speak(ans, "reply")) { vs.speaking = true; status = "Speaking..." } else status = "Listening..."
        }
    }

    fun listen() {
        val r = rec ?: return
        if (!session || vs.active) return
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { if (!vs.speaking && status != "Thinking...") status = "Listening..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) { level = ((v + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) {
                vs.active = false; level = 0f
                if (!session) return
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { status = "Allow microphone access"; session = false; return }
                val wait = if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 250L else 1000L
                main.postDelayed({ vs.listenFn() }, wait)
            }
            override fun onResults(b: Bundle?) {
                vs.active = false; level = 0f
                if (!session) return
                val said = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!said.isNullOrBlank()) {
                    val recentlySpoke = vs.speaking || System.currentTimeMillis() - vs.spokeAt < 1500
                    if (!recentlySpoke || isInterruption(said, vs.reply)) respond(said)
                }
                main.postDelayed({ vs.listenFn() }, 150)
            }
            override fun onPartialResults(b: Bundle?) {
                val p = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (p.isBlank()) return
                if (vs.speaking) {
                    // you started talking over Nexa: stop the old answer right away
                    if (isInterruption(p, vs.reply)) { stopSpeaking(); heard = p; status = "Listening..." }
                } else if (status != "Thinking...") heard = p
            }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Store.langTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L))
        vs.active = true
    }
    vs.listenFn = { listen() }

    DisposableEffect(Unit) {
        // when Nexa finishes a whole answer, go back to plain listening
        Store.tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == "reply") main.post {
                    vs.speaking = false; vs.spokeAt = System.currentTimeMillis()
                    if (session) { status = "Listening..."; vs.listenFn() }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == "reply") main.post {
                    vs.speaking = false; vs.spokeAt = System.currentTimeMillis()
                    if (session) { status = "Listening..."; vs.listenFn() }
                }
            }
        })
        onDispose { session = false; vs.turn += 1; main.removeCallbacksAndMessages(null); rec?.destroy(); Store.tts?.stop() }
    }

    val askMic = rememberPerm(Manifest.permission.RECORD_AUDIO) { ok ->
        if (ok) {
            session = true; heard = ""; reply = ""; vs.speaking = false; vs.active = false; vs.reply = ""
            Store.tts?.stop(); listen()
        } else toast(ctx, "Allow microphone access to talk to Nexa")
    }
    LaunchedEffect(Unit) { if (Store.autoListen) { Store.autoListen = false; askMic() } }

    val pulse by animateFloatAsState(1f + level * 0.18f, label = "pulse")
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(), Alignment.Center) {
            T("Voice Chat", 18, w = FontWeight.SemiBold)
            Icon(Icons.Default.RecordVoiceOver, null, tint = Cyan, modifier = Modifier.align(Alignment.CenterEnd).clickable { picker = true }.padding(4.dp))
        }
        Spacer(Modifier.height(32.dp))
        Box(Modifier.size(230.dp).clip(CircleShape).clickable { if (session) stopAll() else askMic() }, Alignment.Center) {
            Box(Modifier.size(230.dp * pulse).background(Brush.radialGradient(listOf(Violet.copy(.45f), Blue.copy(.15f), Color.Transparent)), CircleShape))
            Box(Modifier.size(160.dp).border(2.dp, Brush.sweepGradient(listOf(Cyan, Violet, Pink, Cyan)), CircleShape))
            Box(Modifier.size(128.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF0B1238), Color(0xFF22126B))), CircleShape)
                .border(3.dp, Grad, CircleShape), Alignment.Center) { Icon(Icons.Default.Mic, null, tint = Cyan, modifier = Modifier.size(56.dp)) }
        }
        Spacer(Modifier.height(18.dp)); T(status, 17, w = FontWeight.SemiBold)
        T(if (session) "Just talk. Speak over Nexa to interrupt. Tap End to finish." else "Tap the mic once, then just talk.", 12, Cyan, al = TextAlign.Center)
        Spacer(Modifier.height(14.dp)); Wave(level, session)
        Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (heard.isNotEmpty()) T("\u201C$heard\u201D", 14, Cyan, m = Modifier.fillMaxWidth(), al = TextAlign.Center)
            if (reply.isNotEmpty()) Box(Modifier.fillMaxWidth().glass(14.dp).padding(12.dp)) { T(reply, 14) }
        }
        Box(Modifier.width(150.dp).height(46.dp).shadow(10.dp, RoundedCornerShape(23.dp), ambientColor = Pink, spotColor = Pink).clip(RoundedCornerShape(23.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFFFF4D6D), Color(0xFFFF2E93))), RoundedCornerShape(23.dp))
            .clickable { stopAll(); reply = ""; heard = ""; Store.tab = "home" }, Alignment.Center) { T("End", 16, Color.White, FontWeight.SemiBold) }
    }
    if (picker) VoicePicker { picker = false }
}
