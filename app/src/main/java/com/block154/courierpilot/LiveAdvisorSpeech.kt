package com.block154.courierpilot

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Owns the live-advisor TextToSpeech lifecycle and pending utterance queue. */
internal class LiveAdvisorSpeech(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingSpeech: String? = null

    fun announceOffer(platform: String, parsed: ParsedOffer) {
        val price = parsed.money?.let { "${it.major().toPlainString()} ${it.currencyCode}" }
        speak(listOfNotNull(platform, price).joinToString(". ") + ".")
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        pendingSpeech = null
    }

    private fun speak(text: String) {
        pendingSpeech = text
        val engine = tts
        if (engine != null && ready) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            pendingSpeech = null
            return
        }
        if (engine == null) {
            tts = TextToSpeech(appContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.ENGLISH
                    pendingSpeech?.let { pending ->
                        tts?.speak(pending, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
                        pendingSpeech = null
                    }
                }
            }
        }
    }

    private companion object {
        const val UTTERANCE_ID = "courierpilot-offer"
    }
}
