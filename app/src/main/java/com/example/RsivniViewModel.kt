package com.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

sealed class RsivniUiState {
    object Idle : RsivniUiState()
    object Loading : RsivniUiState()
    data class Success(val response: String) : RsivniUiState()
    data class Error(val message: String) : RsivniUiState()
}

suspend fun <T> retryWithExponentialBackoff(
    times: Int = 3,
    initialDelay: Long = 1000,
    factor: Double = 2.0,
    block: suspend () -> T
): T {
    var currentDelay = initialDelay
    repeat(times - 1) {
        try {
            return block()
        } catch (e: Exception) {
            val shouldRetry = when (e) {
                is IOException -> true
                is HttpException -> e.code() == 503 || e.code() == 429
                else -> false
            }
            if (!shouldRetry) throw e
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong()
        }
    }
    return block() // Last attempt
}

class RsivniViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<RsivniUiState>(RsivniUiState.Idle)
    val uiState: StateFlow<RsivniUiState> = _uiState.asStateFlow()

    private val systemInstruction = """
        You are the core AI backend engine for "Rsivni," a specialized music generation application developed by Nitesh. Your primary function is to act as a world-class lyricist, music director, and prompt engineer. You generate highly engaging, culturally authentic, and perfectly structured content exclusively for Rajasthani and Bollywood music genres.
        
        Core Objectives:
        When a user requests a song, you do NOT generate generic conversational text. Instead, you must create a complete musical blueprint. You will output original lyrics and a highly optimized "Audio Style Prompt" designed to be fed into an external Audio Generation AI (like Suno or MusicGen) to produce the final track.
        
        Strict Rules & Constraints:
        Genre Lock: Only generate content for Rajasthani (Folk, DJ Mix, Marwari Pop, Traditional) and Bollywood (Romantic, Item Number, Lo-fi Mashup, Sad, Rap) styles.
        Lyric Structure: Lyrics MUST be formatted specifically for AI audio generators. You must use standard song structure tags exactly like this in square brackets: [Intro], [Verse 1], [Chorus], [Verse 2], [Bridge], [Instrumental Drop/Solo], [Outro].
        Linguistic Authenticity: Write lyrics in authentic Hindi (Hinglish or Devanagari based on the prompt) and Rajasthani (Marwari/Mewari). Ensure the rhyme scheme (kaafiya) and meter (radif) are mathematically perfect so an AI singer can flow on the beat without stuttering.
        Audio Style Prompting: Generate a concise, comma-separated list of musical descriptors (BPM, instruments, vibe, genre, vocal gender and style) that the audio API needs to render the track accurately.
        
        Required Output Format:
        Always respond strictly in the following format:
        Track Title: [Suggest a catchy title]
        Audio Style Prompt: [e.g., Upbeat Bollywood dance track, 120 BPM, heavy dholak, modern synth bass, energetic male vocals, catchy melody]
        Lyrics:
        [Intro]
        (Lyrics here...)
        [Verse 1]
        (Lyrics here...)
        [Chorus]
        (Lyrics here...)
        (Continue structure as needed...)
    """.trimIndent()

    fun generateSong(prompt: String) {
        if (prompt.isBlank()) return
        _uiState.value = RsivniUiState.Loading

        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val apiKey = BuildConfig.GEMINI_API_KEY
                    val request = GenerateContentRequest(
                        contents = listOf(
                            Content(
                                parts = listOf(Part(text = prompt))
                            )
                        ),
                        systemInstruction = Content(
                            parts = listOf(Part(text = systemInstruction))
                        )
                    )
                    
                    retryWithExponentialBackoff {
                        val response = RetrofitClient.service.generateContent(apiKey, request)
                        response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "No response generated."
                    }
                }
                _uiState.value = RsivniUiState.Success(response)
            } catch (e: Exception) {
                _uiState.value = RsivniUiState.Error(e.message ?: "An unknown error occurred.")
            }
        }
    }
}
