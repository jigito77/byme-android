package am.snp.shop

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * Голосовой поиск: в WebView нет Web Speech API (window.SpeechRecognition), поэтому сайт получает его
 * через этот мост — распознаёт речь системный SpeechRecognizer Android (inject.js делает совместимый объект).
 */
class VoiceBridge(private val act: MainActivity, private val web: WebView) {

    private var sr: SpeechRecognizer? = null

    @JavascriptInterface
    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(act)

    @JavascriptInterface
    fun start(lang: String?) {
        act.runOnUiThread {
            act.ensurePerms(arrayOf(Manifest.permission.RECORD_AUDIO)) { r ->
                if (r.values.all { it }) {
                    begin(if (lang.isNullOrEmpty()) "ru-RU" else lang)
                } else {
                    emit("error", "not-allowed", false)
                    emit("end", "", false)
                }
            }
        }
    }

    @JavascriptInterface
    fun stop() {
        act.runOnUiThread { sr?.stopListening() }
    }

    private fun begin(lang: String) {
        sr?.destroy()
        val rec = SpeechRecognizer.createSpeechRecognizer(act)
        sr = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                emit("start", "", false)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                emit("result", t, false)
            }

            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (t.isNotEmpty()) emit("result", t, true)
                emit("end", "", false)
            }

            override fun onError(error: Int) {
                val code = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
                    else -> "aborted"
                }
                emit("error", code, false)
                emit("end", "", false)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        rec.startListening(intent)
    }

    private fun emit(type: String, text: String, isFinal: Boolean) {
        val js = "window.__bymeVoice && window.__bymeVoice(" + JSONObject.quote(type) + "," + JSONObject.quote(text) + "," + isFinal + ");"
        act.runOnUiThread { web.evaluateJavascript(js, null) }
    }

    fun destroy() {
        sr?.destroy()
        sr = null
    }
}
