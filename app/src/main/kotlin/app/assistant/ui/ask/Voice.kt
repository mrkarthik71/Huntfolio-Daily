package app.assistant.ui.ask

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.assistant.R

/**
 * Voice input through the phone's own speech recogniser (the system dialog; Apex never records
 * audio itself, so no microphone permission is needed). Returns a function that starts listening;
 * [onText] gets the recogniser's guesses, best first.
 */
@Composable
fun rememberVoiceInput(onText: (List<String>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val prompt = stringResource(R.string.voice_prompt)
    val unavailable = stringResource(R.string.voice_unavailable)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Every guess the recogniser offers, best first; Ask picks the one it can act on.
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let(onText)
    }
    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_GUESSES)
            // Indian English: it also writes Hinglish in Latin letters, which is what Ask's rules read.
            // (Hindi or Tamil script would only reach the AI, which answers but cannot act.)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}

private const val MAX_GUESSES = 5
