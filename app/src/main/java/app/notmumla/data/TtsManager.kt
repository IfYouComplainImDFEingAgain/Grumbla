package app.notmumla.data

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Thin wrapper around Android TextToSpeech for reading chat messages aloud. */
@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    @Volatile private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { tts.language = Locale.getDefault() }
                ready = true
            }
        }
    }

    fun speak(text: String) {
        if (ready && text.isNotBlank()) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "chat")
        }
    }
}
