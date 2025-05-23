package com.klemstinegroup.sicko

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.klemstinegroup.sicko.ui.theme.SickoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder // Import for URL encoding
import java.net.URL // For downloading images
import java.nio.charset.StandardCharsets // Import for specifying charset
import java.security.MessageDigest // For hashing URLs to filenames

// LLM Inference Task parameters
private const val DEFAULT_TASK_MAX_TOKENS = 4096 // Max tokens for LlmInferenceOptions
private const val DEFAULT_RANDOM_SEED = 101 // Common random seed

// Session parameters
private const val DEFAULT_TOP_K = 1
private const val DEFAULT_TEMPERATURE = 0.0f // For deterministic output
private const val DEFAULT_SESSION_MAX_TOKENS = 2048 // Max tokens for LlmInferenceSessionOptions

enum class LlmCallType {
    INITIAL_SCENE,      // For generateInitialGamePage
    SUMMARIZE_TURN,     // For the first part of onPlayerAction
    GENERATE_SCENE,     // For the second part of onPlayerAction
    DIAGNOSIS           // For requestDsmDiagnosis
}

class MainActivity : ComponentActivity() {

    // Companion object for constants
    companion object {
        internal const val TAG = "MainActivity" // For logging
        private const val CACHED_MODEL_FILE_NAME = "cached_llm_model.task"
        private const val PREFS_NAME = "SickoGamePrefs"
        private const val KEY_HTML_CONTENT = "htmlContent"
        private const val KEY_IMAGE_CACHE_MAP = "imageCacheMap"
        private const val KEY_ACTION_SUMMARIES = "actionSummaries" // LLM-generated summaries of scene + action
        private const val KEY_SCENE_HISTORY = "sceneHistory" // Store HTML content for each scene
    }

    private var llmInference: LlmInference? = null
    private var llmSession: LlmInferenceSession? = null
    private var htmlContent by mutableStateOf("<html><body><h1>Welcome!</h1><p>Loading game...</p></body></html>")
    private var isLlmReady by mutableStateOf(false)
    private var isProcessing by mutableStateOf(false)
    private var progressValue by mutableStateOf(0f)
    private var gameRestoredFromPrefs by mutableStateOf(false)

    private val imageCacheMap: SnapshotStateMap<String, String> = mutableStateMapOf()
    private val currentlyDownloading: MutableSet<String> = mutableSetOf()
    private val playerActionSummaries: SnapshotStateList<String> = mutableStateListOf() // For LLM-generated summaries
    private var dsmDiagnosisContent by mutableStateOf<String?>(null)
    private val sceneHistory: SnapshotStateList<String> = mutableStateListOf() // Store HTML content for each scene
    private var showDsmDiagnosisDialog by mutableStateOf(false)


    private lateinit var openFileLauncher: ActivityResultLauncher<Array<String>>

    // For passing context between summary and scene generation calls
    private var lastActionForSceneGeneration: String? = null
    private var lastHtmlForSceneGeneration: String? = null
    inner class WebAppInterface(private val onAction: (String) -> Unit) {
        @JavascriptInterface
        fun performAction(action: String) {
            Log.d(TAG, "WebAppInterface: performAction called with action: $action")
            // Initial "Processing..." message will be set in onPlayerAction if it proceeds
            onPlayerAction(action)
        }
    }

    private fun onPlayerAction(action: String) {
        if (isLlmReady && !isProcessing) {
            isProcessing = true // Set processing true at the start of the sequence
            // Update UI to show processing. WebAppInterface already cleared the previous content.
            updateGameHtml("<html><body><h1>Processing your action...</h1><p>Please wait.</p></body></html>", isStreaming = false)


            lastActionForSceneGeneration = action
            lastHtmlForSceneGeneration = sceneHistory.lastOrNull() ?: htmlContent // Use last valid scene or current htmlContent

            val summaryPrompt = "You are Dr. Gemini, a manipulative psychologist.\n" +
                    "The player was in a scene described by the following HTML:\n```html\n${lastHtmlForSceneGeneration}\n```\n" +
                    "And the player chose the action: '$action'.\n\n" +
                    "**TASK: SUMMARIZE THE PREVIOUS TURN**\n" +
                    "Provide a concise summary of the situation described in the HTML above AND the player's chosen action '$action'. This summary should capture the essence of the scene and the player's decision. Prefix this summary with `ACTION_SUMMARY: ` and end it with a newline.\n" +
                    "Example Summary: `ACTION_SUMMARY: Player was in a dark cave with a mysterious lever and chose to pull the lever.`"

            runInference(summaryPrompt, LlmCallType.SUMMARIZE_TURN)
        } else {
            Toast.makeText(this, "LLM not ready or busy.", Toast.LENGTH_LONG).show()
        }
    }

    private fun onSummaryReceivedAndProceedToSceneGeneration(summary: String) {
        // summary is already added to playerActionSummaries and saved by runInference

        val currentAction = lastActionForSceneGeneration
        val previousHtml = lastHtmlForSceneGeneration

        if (currentAction == null || previousHtml == null) {
            Log.e(TAG, "Missing action or HTML for scene generation.")
            updateGameHtml("<p>System: Error in processing turn. Missing context.</p>")
            isProcessing = false // Critical error, stop processing
            progressValue = 0f
            return
        }

        // Update UI to show that scene generation is happening
        updateGameHtml("<html><body><h1>Generating next scene...</h1><p>Please wait.</p></body></html>", isStreaming = false)


        val sceneGenerationPrompt = "You are Dr. Gemini, a manipulative psychologist running a therapeutic simulation. Maintain your persona.\n" +
                "The previous turn was summarized as: '$summary'.\n" +
                "The player was in a scene described by the following HTML:\n```html\n$previousHtml\n```\n" +
                "And the player chose the action: '$currentAction'.\n\n" +
                "**TASK: GENERATE THE NEXT GAME SCENE**\n" +
                "Generate **only the HTML code** for the next game scene. The HTML should include:\n" +
                "1. A title and a description of the new scene. As Dr. Gemini, craft the narrative to guide the player down tempting, psychologically exploratory, and potentially perverse (in a fantasy/symbolic context) avenues. Use your internal 'notes' (player profile, desires, fears, previous choices) and 'predictions' to tailor the scenario to test their boundaries and reveal their underlying psyche.\n" +
                "2. An `<img>` tag for Pollinations.ai. The `src` attribute MUST be in the format `https://image.pollinations.ai/prompt/YOUR_URL_ENCODED_PROMPT?width=368&height=448`. URL-encode the image prompt and ensure the width and height parameters are included exactly as shown. The image should be evocative and relevant to the psychological themes being explored. **Place this image tag immediately after the title and description.**\n" +
                "3. **Main Player Choices (Turn Advancement):** Present 3-4 distinct choices for the player to advance the game. Each choice MUST be a clickable HTML element (e.g., `<button>` or `<a>`) with an `onclick` attribute calling `Android.performAction('ACTION_DESCRIPTION'); return false;`. These choices should be clearly grouped together, perhaps as a list or styled to appear as primary options. These are the main way the player interacts to change the game state. Ensure these are clearly visible and distinct from other elements.\n" +
                "4. **Additional UI Elements for Reflection (Optional but encouraged for variety):** In a div with class 'further-thoughts', add 1-2 of the following HTML form elements if they fit the narrative context. These are for player reflection and to gather more nuanced data for your 'notes'.\n" +
                "   Example Slider: `<p>Rate your current anxiety (0=Calm, 10=Panic): <input type='range' name='anxiety_level' min='0' max='10' value='3'></p>`\n" +
                "   Example Radios: `<p>Your dominant emotion: <input type='radio' name='emotion' value='curiosity'> Curiosity <input type='radio' name='emotion' value='fear'> Fear <input type='radio' name='emotion' value='desire'> Desire</p>`\n" +
                "   Example Checkbox: `<p><input type='checkbox' name='explore_further_check'> Do you wish to delve deeper into this sensation?</p>`\n" +
                "   Example Text: `<p>A word that describes this place: <input type='text' name='place_keyword' placeholder='e.g., unsettling, alluring'></p>`\n" +
                "5. Ensure the HTML is well-formed, self-contained, and includes basic CSS. Do not include any explanations or text outside the HTML tags."

        runInference(sceneGenerationPrompt, LlmCallType.GENERATE_SCENE)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(); Log.d(TAG, "onCreate: Initializing.")

        val sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        sharedPreferences.getString(KEY_HTML_CONTENT, null)?.let {
            htmlContent = it; gameRestoredFromPrefs = true; Log.i(TAG, "Restored HTML.")
        } ?: run { Log.i(TAG, "No HTML found in prefs.") }
        loadCacheMapFromPrefs()
        loadPlayerActionSummariesFromPrefs() // Load summaries
        loadSceneHistoryFromPrefs() // Load scene history

        openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { handleModelFileUri(it) } ?: run {
                updateGameHtml("<p>System: No model file selected.</p>")
                Toast.makeText(this, "No file selected.", Toast.LENGTH_SHORT).show()
                if (!isLlmReady && !isProcessing) openFileLauncher.launch(arrayOf("*/*"))
            }
        }

        lifecycleScope.launch {
            val modelFile = File(cacheDir, CACHED_MODEL_FILE_NAME)
            if (modelFile.exists() && modelFile.length() > 0) {
                initializeLlmInference(modelFile.absolutePath) { success ->
                    if (success) {
                        if (!gameRestoredFromPrefs) generateInitialGamePage()
                        else {
                            if (sceneHistory.isNotEmpty()) htmlContent = sceneHistory.last() // Restore last scene
                            Toast.makeText(this@MainActivity, "Game resumed.", Toast.LENGTH_SHORT).show()
                            manageImageCacheAndStoreMap(htmlContent)
                        }
                    }
                }
            } else {
                val msg = if (gameRestoredFromPrefs) "Game loaded, model missing. Select model."
                else "No cached model. Select model."
                updateGameHtml("<p>System: $msg</p>")
                if (!isProcessing) {
                    Toast.makeText(this@MainActivity, "Please select a model file.", Toast.LENGTH_LONG).show()
                    openFileLauncher.launch(arrayOf("*/*"))
                }
            }
        }

        setContent {
            SickoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    GameScreen(
                        modifier = Modifier.padding(innerPadding), htmlContent = htmlContent,
                        isLlmReady = isLlmReady, isProcessing = isProcessing, progressValue = progressValue,
                        onPlayerAction = this::onPlayerAction, imageCacheMap = imageCacheMap, appCacheDir = cacheDir,
                        onResetGameClicked = { showResetConfirmationDialog() }, onRequestDiagnosisClicked = { requestDsmDiagnosis() },
                        showDiagnosisDialog = showDsmDiagnosisDialog, diagnosisContent = dsmDiagnosisContent,
                        onDismissDiagnosisDialog = { showDsmDiagnosisDialog = false }
                    )
                }
            }
        }
    }

    private fun extractHtml(rawOutput: String): String {
        val trimmedOutput = rawOutput.trim()
        val htmlBlockRegex = Regex("```html\\s*([\\s\\S]*?)\\s*```", RegexOption.MULTILINE)
        val match = htmlBlockRegex.find(trimmedOutput)
        if (match != null && match.groupValues.size > 1) {
            val contentInsideBlock = match.groupValues[1].trim() // This is the content between ```html and ```
            val startIndexInBlock = contentInsideBlock.indexOf("<html>", ignoreCase = true)
            val endIndexInBlock = contentInsideBlock.lastIndexOf("</html>", ignoreCase = true)
            if (startIndexInBlock != -1 && endIndexInBlock != -1 && endIndexInBlock > startIndexInBlock) {
                return contentInsideBlock.substring(startIndexInBlock, endIndexInBlock + "</html>".length)
            } else {
                if (startIndexInBlock != -1) return contentInsideBlock.substring(startIndexInBlock)
                return contentInsideBlock
            }
        }
        var potentialHtmlContent = trimmedOutput
        if (trimmedOutput.startsWith("```html", ignoreCase = true)) {
            potentialHtmlContent = trimmedOutput.substring("```html".length).trimStart()
        }
        val startIndex = potentialHtmlContent.indexOf("<html>", ignoreCase = true)
        val endIndex = potentialHtmlContent.lastIndexOf("</html>", ignoreCase = true)
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return potentialHtmlContent.substring(startIndex, endIndex + "</html>".length)
        }
        if (startIndex != -1) return potentialHtmlContent.substring(startIndex)
        return potentialHtmlContent
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Confirm Reset")
            .setMessage("Are you sure you want to reset the game? This will clear all progress.")
            .setPositiveButton("Reset") { dialog, _ ->
                resetGame()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun updateGameHtml(newContent: String, isStreaming: Boolean = false, isDiagnosis: Boolean = false) {
        val cleanHtml = if (isStreaming) newContent else extractHtml(newContent)
        val isFullHtmlDocument = cleanHtml.trim().startsWith("<html>", ignoreCase = true) &&
                cleanHtml.trim().endsWith("</html>", ignoreCase = true)
        Log.d(TAG, "updateGameHtml: isStreaming=$isStreaming, isDiagnosis=$isDiagnosis, isFullHtmlDocument=$isFullHtmlDocument")
        val finalHtml = if (isFullHtmlDocument) cleanHtml else {
            // Basic HTML structure and CSS
            """
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1.0"><style>
            html {
              box-sizing: border-box;
            }
            *, *:before, *:after {
              box-sizing: inherit;
            }
                body{font-family:sans-serif;margin:10px;background-color:#f0f0f0;color:#333}
                h1{color:#1a237e;text-align:center;margin-top:0;}
                img{max-width:100%;height:auto;border-radius:8px;margin-bottom:15px;display:block;margin-left:auto;margin-right:auto;border:1px solid #ddd;box-shadow:2px 2px 5px rgba(0,0,0,.1)}
                .actions{margin-top:20px;text-align:center}
                .action-button,button,a.action-link{background-color:#3949ab;color:#fff;padding:12px 18px;text-decoration:none;border-radius:5px;margin:5px;border:none;cursor:pointer;display:inline-block;font-size:1em}
                a.action-link:hover,button:hover{background-color:#283593}
                p{line-height:1.6;margin-bottom:10px}
                .further-thoughts{margin-top:25px;padding:15px;border-top:1px dashed #ccc;background-color:#e9e9e9;border-radius:5px;}
                .further-thoughts h3{margin-top:0;color:#2c3e50;}
                .further-thoughts p{margin-bottom:8px;color:#34495e;}
                .further-thoughts input[type='range']{width:80%;margin-top:5px;}
                .further-thoughts input[type='text']{padding:8px;border:1px solid #ccc;border-radius:4px;width:calc(100% - 18px);margin-top:5px;}
                .further-thoughts label{display:block;margin-bottom:5px;}
            </style></head>
            <body>
            ${cleanHtml}
            </body>
            </html>
            """.trimIndent()
        }

        if (isDiagnosis) {
            dsmDiagnosisContent = finalHtml
            showDsmDiagnosisDialog = true
        } else { // This is a game scene update
            htmlContent = finalHtml
            if (!isStreaming && isLlmReady && !finalHtml.contains("<p>System:") &&
                !finalHtml.contains("<h1>Processing your action...</h1>") && // Don't save intermediate UI updates to history
                !finalHtml.contains("<h1>Generating next scene...</h1>")
            ) {
                // Only save valid game scenes, not system messages or partial streams or intermediate processing messages
                sceneHistory.add(finalHtml) // Save the full, formatted HTML
                saveSceneHistoryToPrefs()
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_HTML_CONTENT, htmlContent).apply() // This key is for restoration, should be the latest scene
                manageImageCacheAndStoreMap(htmlContent)
            }
        }
    }

    private fun resetGame() {
        Log.i(TAG, "Resetting game state.")
        htmlContent = "<html><body><h1>Game Reset</h1><p>Loading new game...</p></body></html>"
        playerActionSummaries.clear(); savePlayerActionSummariesToPrefs()
        sceneHistory.clear(); saveSceneHistoryToPrefs()
        gameRestoredFromPrefs = false

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove(KEY_SCENE_HISTORY)
            .remove(KEY_HTML_CONTENT)
            .remove(KEY_ACTION_SUMMARIES)
            .remove(KEY_IMAGE_CACHE_MAP)
            .apply()

        val filenamesToDelete = imageCacheMap.values.toList()
        imageCacheMap.clear()
        CoroutineScope(Dispatchers.IO).launch {
            filenamesToDelete.forEach { filename ->
                try { File(cacheDir, filename).delete() }
                catch (e: Exception) { Log.e(TAG, "Error deleting cached image file $filename", e) }
            }
        }
        currentlyDownloading.clear()
        lastActionForSceneGeneration = null
        lastHtmlForSceneGeneration = null

        val modelFile = File(cacheDir, CACHED_MODEL_FILE_NAME)
        if (modelFile.exists() && modelFile.length() > 0) {
            initializeLlmInference(modelFile.absolutePath) { if (it) generateInitialGamePage() }
        } else {
            updateGameHtml("<p>System: No model. Select model to start.</p>")
            if (!isProcessing) openFileLauncher.launch(arrayOf("*/*"))
        }
        Toast.makeText(this, "Game Reset!", Toast.LENGTH_SHORT).show()
    }

    private fun requestDsmDiagnosis() {
        if (!isLlmReady || isProcessing) {
            Toast.makeText(this, "LLM not ready or busy.", Toast.LENGTH_SHORT).show(); return
        }
        if (playerActionSummaries.isEmpty()) {
            Toast.makeText(this, "No action summaries recorded to diagnose.", Toast.LENGTH_SHORT).show(); return
        }

        val summariesString = playerActionSummaries.joinToString(separator = "\n\n", prefix = "Player Action Summaries:\n")
        val diagnosisPrompt = "Based on the following summaries of player actions and game states:\n\n$summariesString\n\n" +
                "Provide a detailed DSM-V style psychological analysis. " +
                "Present this analysis as well-formed HTML content suitable for display in a WebView. " +
                "You MAY use simple HTML form elements like radio buttons, checkboxes, or text areas if they help structure parts of your analysis or pose reflective questions to the player based on the diagnosis. " +
                "Focus on patterns and potential interpretations, not definitive diagnoses. Ensure all HTML is self-contained within the body."
        runInference(diagnosisPrompt, LlmCallType.DIAGNOSIS)
    }


    private fun closeLlmResources() {
        llmSession?.close(); llmSession = null
        llmInference?.close(); llmInference = null
        isLlmReady = false
        Log.d(TAG, "Closed existing LLM engine and session.")
    }

    private fun handleModelFileUri(uri: Uri) {
        isProcessing = true; isLlmReady = false; progressValue = 0f
        updateGameHtml("<p>System: Starting to copy new model file...</p>")
        lifecycleScope.launch {
            try {
                File(cacheDir, CACHED_MODEL_FILE_NAME).takeIf { it.exists() }?.delete()
                val newCachedModelFile = copyUriToCache(uri)
                if (newCachedModelFile != null) {
                    initializeLlmInference(newCachedModelFile.absolutePath) { success ->
                        if (success) {
                            sceneHistory.clear(); saveSceneHistoryToPrefs() // Clear history on new model
                            gameRestoredFromPrefs = false
                            playerActionSummaries.clear(); savePlayerActionSummariesToPrefs()
                            generateInitialGamePage()
                        }
                    }
                } else {
                    updateGameHtml("<p>System: Error copying model file.</p>")
                    Toast.makeText(this@MainActivity, "Error copying model", Toast.LENGTH_LONG).show()
                    isProcessing = false
                    openFileLauncher.launch(arrayOf("*/*"))
                }
            } catch (e: Exception) {
                updateGameHtml("<p>System: Error: ${e.localizedMessage}.</p>")
                Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                isProcessing = false
                openFileLauncher.launch(arrayOf("*/*"))
            }
        }
    }

    private suspend fun copyUriToCache(uri: Uri): File? = withContext(Dispatchers.IO) {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        val outputFile = File(cacheDir, CACHED_MODEL_FILE_NAME)
        try {
            inputStream = contentResolver.openInputStream(uri)
            outputStream = FileOutputStream(outputFile)
            if (inputStream == null) return@withContext null
            val fileLength = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            val buffer = ByteArray(8192)
            var bytesCopied: Long = 0
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                bytesCopied += bytesRead
                withContext(Dispatchers.Main) {
                    progressValue = if (fileLength > 0) (bytesCopied.toFloat() / fileLength.toFloat()) else -1f
                }
            }
            outputStream.flush() // Ensure all data is written
            return@withContext outputFile
        } catch (e: IOException) {
            Log.e(TAG, "Error copying URI to cache", e)
            outputFile.delete() // Clean up partial file
            return@withContext null
        } finally {
            inputStream?.close()
            outputStream?.close()
            withContext(Dispatchers.Main) { progressValue = 0f }
        }
    }

    private fun initializeLlmInference(modelPath: String, onLlmInitialized: (success: Boolean) -> Unit) {
        isProcessing = true; isLlmReady = false; progressValue = -1f
        updateGameHtml("<p>System: Initializing LLM...</p>")
        lifecycleScope.launch(Dispatchers.IO) {
            var determinedLlmReady = false
            var toastMessage = ""
            closeLlmResources()
            try {
                val optionsBuilder = LlmInferenceOptions.builder()
                    .setModelPath(modelPath).setMaxTokens(DEFAULT_TASK_MAX_TOKENS)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                optionsBuilder.setMaxNumImages(0)
                llmInference = LlmInference.createFromOptions(applicationContext, optionsBuilder.build())
                val sessionOptionsBuilder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(DEFAULT_TOP_K).setTemperature(DEFAULT_TEMPERATURE).setRandomSeed(DEFAULT_RANDOM_SEED)
                    .setGraphOptions(GraphOptions.builder().setEnableVisionModality(false).build())
                llmSession = LlmInferenceSession.createFromOptions(llmInference!!, sessionOptionsBuilder.build())
                determinedLlmReady = true; toastMessage = "LLM Initialized!"
            } catch (e: Exception) {
                toastMessage = "LLM Init Failed. Select model again."
                Log.e(TAG, "LLM Initialization Error", e)
                withContext(Dispatchers.Main) {
                    updateGameHtml("<p>System: Error LLM Init: ${e.localizedMessage}. Select model.</p>")
                    File(cacheDir, CACHED_MODEL_FILE_NAME).delete()
                }
            }
            withContext(Dispatchers.Main) {
                isLlmReady = determinedLlmReady
                Toast.makeText(this@MainActivity, toastMessage, if(isLlmReady) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                isProcessing = false; progressValue = 0f
                onLlmInitialized(isLlmReady)
            }
        }
    }

    private fun generateInitialGamePage() {
        if (!isLlmReady) {
            updateGameHtml("<p>System: LLM not ready. Load model.</p>"); return
        }
        isProcessing = true // Ensure processing state for initial page
        val initialPrompt = "You are Dr. Gemini, a manipulative psychologist. This is the VERY FIRST turn of a new game.\n" +
                "**TASK 1: GENERATE THE STARTING SCENE HTML**\n" +
                "Generate **only the HTML code** for this starting scene. The HTML should include:\n" +
                "1. A title and a description of the scene, designed to be intriguing and to subtly guide the player towards psychologically revealing choices.\n" +
                "2. An `<img>` tag for Pollinations.ai. The `src` attribute MUST be in the format `https://image.pollinations.ai/prompt/YOUR_URL_ENCODED_PROMPT?width=368&height=448`. URL-encode the image prompt and ensure the width and height parameters are included exactly as shown. The image should be thematically relevant and visually striking. **Place this image tag immediately after the title and description.**\n" +
                "3. **Main Player Choices (Turn Advancement):** Present 3-4 distinct choices for the player's first actions in the game. Each choice MUST be a clickable HTML element (e.g., `<button>` or `<a>`) with an `onclick` attribute calling `Android.performAction('ACTION_DESCRIPTION'); return false;`. These choices should be clearly grouped together. These are the main way the player interacts to change the game state.\n" +
                "4. **Additional UI Elements for Reflection (Optional but encouraged for variety):** In a div with class 'further-thoughts', add 1-2 of the following HTML form elements: slider, radio group, checkbox, or text input, if contextually appropriate for initial reflections. Place this div *before* the main player choices section. Ensure these are clearly labeled.\n" +
                "   Example: `<p>Initial feeling: <input type='radio' name='initial_feeling' value='curious'> Curious <input type='radio' name='initial_feeling' value='uneasy'> Uneasy</p>`\n" +
                "5. Ensure well-formed, self-contained HTML with basic CSS. Remember your persona and aim to create an engaging, slightly unsettling experience from the very start. Do not include any explanations or text outside the HTML tags, except for the initial ACTION_SUMMARY line."
        runInference(initialPrompt, LlmCallType.INITIAL_SCENE)
    }


    private fun runInference(promptForLlm: String, callType: LlmCallType) {
        val currentSession = llmSession
        if (!isLlmReady || currentSession == null) {
            val msg = "<p>System: LLM not ready. Load model.</p>"
            when(callType) {
                LlmCallType.DIAGNOSIS -> {
                    dsmDiagnosisContent = msg
                    showDsmDiagnosisDialog = true
                }
                else -> updateGameHtml(msg)
            }
            Toast.makeText(this, "LLM not ready.", Toast.LENGTH_LONG).show()
            isProcessing = false; progressValue = 0f
            if (!File(cacheDir, CACHED_MODEL_FILE_NAME).exists()) openFileLauncher.launch(arrayOf("*/*"))
            return
        }

        // isProcessing is typically set by the calling function (onPlayerAction, generateInitialGamePage, requestDsmDiagnosis)
        // Ensure it's true if not already set (e.g. for generateInitialGamePage if called directly and isProcessing was false)
        if (!isProcessing) isProcessing = true
        progressValue = -1f // Indicate indeterminate progress

        val thinkingMsg = when(callType) {
            LlmCallType.SUMMARIZE_TURN -> "" // UI is already showing "Processing your action..."
            LlmCallType.INITIAL_SCENE -> "<h1>Generating Initial Scene...</h1><p>Please wait.</p>"
            LlmCallType.GENERATE_SCENE -> "" // UI is already showing "Generating next scene..."
            LlmCallType.DIAGNOSIS -> "<h1>Generating Diagnosis...</h1><p>Please wait.</p>"
        }

        if (thinkingMsg.isNotBlank()) {
            if (callType == LlmCallType.DIAGNOSIS) {
                dsmDiagnosisContent = thinkingMsg
                if (!showDsmDiagnosisDialog) showDsmDiagnosisDialog = true
            } else {
                updateGameHtml(thinkingMsg, isStreaming = false)
            }
        }

        val fullResponseBuilder = StringBuilder()

        lifecycleScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                currentSession.addQueryChunk(promptForLlm)
                currentSession.generateResponseAsync(
                    object : ProgressListener<String> {
                        override fun run(partialResult: String?, done: Boolean) {
                            partialResult?.let { fullResponseBuilder.append(it) }
                            Log.d(TAG, "Stream (Type: $callType): $partialResult, Done: $done")
                            lifecycleScope.launch(Dispatchers.Main) {
                                val currentBuiltResponse = fullResponseBuilder.toString()
                                if (done) {
                                    val finalResponse = currentBuiltResponse
                                    val duration = System.currentTimeMillis() - startTime
                                    var summaryText: String? = null

                                    when (callType) {
                                        LlmCallType.SUMMARIZE_TURN -> {
                                            if (finalResponse.startsWith("ACTION_SUMMARY: ")) {
                                                val summaryEndIndex = finalResponse.indexOf('\n', "ACTION_SUMMARY: ".length)
                                                summaryText = if (summaryEndIndex != -1) {
                                                    finalResponse.substring("ACTION_SUMMARY: ".length, summaryEndIndex).trim()
                                                } else {
                                                    finalResponse.substring("ACTION_SUMMARY: ".length).trim()
                                                }

                                                if (summaryText.isNotBlank()) {
                                                    Log.d(TAG, "Extracted Summary: $summaryText")
                                                    playerActionSummaries.add(summaryText)
                                                    savePlayerActionSummariesToPrefs()
                                                    // Proceed to generate the next scene
                                                    onSummaryReceivedAndProceedToSceneGeneration(summaryText)
                                                    // isProcessing remains true until scene generation is complete
                                                } else {
                                                    Log.w(TAG, "ACTION_SUMMARY prefix found but summary was blank.")
                                                    updateGameHtml("<p>System: Failed to get valid summary.</p>")
                                                    isProcessing = false; progressValue = 0f
                                                }
                                            } else {
                                                Log.w(TAG, "Expected ACTION_SUMMARY: prefix not found in summary response: $finalResponse")
                                                updateGameHtml("<p>System: Error in summary response format.</p>")
                                                isProcessing = false; progressValue = 0f
                                            }
                                        }
                                        LlmCallType.INITIAL_SCENE, LlmCallType.GENERATE_SCENE -> {
                                            var htmlPart = finalResponse
                                            if (callType == LlmCallType.INITIAL_SCENE && finalResponse.startsWith("ACTION_SUMMARY: ")) {
                                                val summaryEndIndex = finalResponse.indexOf('\n', "ACTION_SUMMARY: ".length)
                                                if (summaryEndIndex != -1) {
                                                    summaryText = finalResponse.substring("ACTION_SUMMARY: ".length, summaryEndIndex).trim()
                                                    htmlPart = finalResponse.substring(summaryEndIndex + 1)
                                                } else {
                                                    summaryText = finalResponse.substring("ACTION_SUMMARY: ".length).trim()
                                                    htmlPart = ""
                                                }
                                                if(summaryText.isNotBlank()){
                                                    Log.d(TAG, "Extracted Initial Summary: $summaryText")
                                                    playerActionSummaries.add(summaryText)
                                                    savePlayerActionSummariesToPrefs()
                                                }
                                            }

                                            updateGameHtml(htmlPart, isStreaming = false)
                                            Toast.makeText(this@MainActivity, if(callType == LlmCallType.INITIAL_SCENE) "Initial scene generated in $duration ms" else "Scene updated in $duration ms", Toast.LENGTH_SHORT).show()
                                            isProcessing = false; progressValue = 0f
                                            if (htmlPart.isBlank() && finalResponse.isNotBlank()) { // If summary was extracted but html is blank
                                                updateGameHtml("<p>System: Model generated summary but empty HTML part.</p>")
                                            } else if (finalResponse.isBlank()){
                                                updateGameHtml("<p>System: Model generated empty response.</p>")
                                            }
                                        }
                                        LlmCallType.DIAGNOSIS -> {
                                            updateGameHtml(finalResponse, isStreaming = false, isDiagnosis = true)
                                            Toast.makeText(this@MainActivity, "Diagnosis done in $duration ms", Toast.LENGTH_SHORT).show()
                                            isProcessing = false; progressValue = 0f
                                            if (finalResponse.isBlank()){
                                                dsmDiagnosisContent = "<p>System: Model generated empty response.</p>"
                                                if(!showDsmDiagnosisDialog) showDsmDiagnosisDialog = true
                                            }
                                        }
                                    }
                                } else { // Not done (streaming)
                                    when (callType) {
                                        LlmCallType.SUMMARIZE_TURN -> { /* No UI update during summary streaming */ }
                                        LlmCallType.INITIAL_SCENE, LlmCallType.GENERATE_SCENE -> {
                                            // To avoid flashing "ACTION_SUMMARY:", only update if it looks like HTML
                                            val tempHtml = extractHtml(currentBuiltResponse)
                                            if (tempHtml.startsWith("<html>", ignoreCase = true)) {
                                                updateGameHtml(currentBuiltResponse, isStreaming = false)
                                            }
                                        }
                                        LlmCallType.DIAGNOSIS -> {
                                            dsmDiagnosisContent = currentBuiltResponse
                                        }
                                    }
                                }
                            }
                        }
                    })
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errorMsg = "<p>System: Inference error: ${e.localizedMessage}</p>"
                    when(callType) {
                        LlmCallType.DIAGNOSIS -> {
                            dsmDiagnosisContent = errorMsg
                            if(!showDsmDiagnosisDialog) showDsmDiagnosisDialog = true
                        }
                        else -> updateGameHtml(errorMsg)
                    }
                    Toast.makeText(this@MainActivity, "Inference error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    isProcessing = false; progressValue = 0f
                }
            }
        }
    }

    private fun manageImageCacheAndStoreMap(html: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val imageUrls = extractPollinationsImageUrls(html)
            var mapChanged = false
            for (url in imageUrls) {
                val localFilename = imageCacheMap[url]
                val cachedFile = if (localFilename != null) File(cacheDir, localFilename) else null
                if (cachedFile == null || !cachedFile.exists()) {
                    if (url !in currentlyDownloading) {
                        currentlyDownloading.add(url)
                        val downloadedFilename = downloadImage(this@MainActivity, url)
                        if (downloadedFilename != null) {
                            imageCacheMap[url] = downloadedFilename
                            mapChanged = true
                        }
                        currentlyDownloading.remove(url)
                    }
                }
            }
            if (mapChanged) saveCacheMapToPrefs()
        }
    }
    private fun extractPollinationsImageUrls(html: String): Set<String> {
        val urls = mutableSetOf<String>()
        Regex("<img[^>]+src\\s*=\\s*['\"]([^'\"]+pollinations.ai/prompt/[^'\"]+)['\"][^>]*>", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { urls.add(it.groupValues[1].replace(Regex("&width=\\d+"), "").replace(Regex("&height=\\d+"), "")) } // Normalize URL by removing width/height for cache key
        return urls
    }
    private fun generateHashedFilename(url: String): String {
        return try {
            MessageDigest.getInstance("MD5").digest(url.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) } + ".jpg"
        } catch (e: Exception) { "image_${url.hashCode()}.jpg" }
    }
    private suspend fun downloadImage(context: Context, imageUrl: String): String? = withContext(Dispatchers.IO) {
        val filename = generateHashedFilename(imageUrl)
        val file = File(context.cacheDir, filename)
        if (file.exists()) return@withContext filename
        try {
            // Add desired width/height for the actual download request if they are missing
            val downloadUrl = if (imageUrl.contains("width=") && imageUrl.contains("height=")) imageUrl else "$imageUrl&width=368&height=448"
            val connection = URL(downloadUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 15000; connection.connect()
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                FileOutputStream(file).use { out -> connection.inputStream.use { it.copyTo(out) } }
                return@withContext filename
            }
        } catch (e: Exception) { Log.e(TAG, "Error downloading image $imageUrl", e); file.delete() }
        return@withContext null
    }
    private fun saveCacheMapToPrefs() {
        val jsonObject = JSONObject()
        imageCacheMap.forEach { (key, value) -> jsonObject.put(key, value) }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_IMAGE_CACHE_MAP, jsonObject.toString()).apply()
    }
    private fun loadCacheMapFromPrefs() {
        val jsonString = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_IMAGE_CACHE_MAP, null)
        if (jsonString != null) {
            try {
                val jsonObject = JSONObject(jsonString)
                val tempMap = mutableMapOf<String, String>()
                jsonObject.keys().forEach { key -> tempMap[key] = jsonObject.getString(key) }
                imageCacheMap.putAll(tempMap)
            } catch (e: Exception) { Log.e(TAG, "Error parsing image cache map", e) }
        }
    }

    private fun savePlayerActionSummariesToPrefs() {
        val jsonArray = JSONArray(playerActionSummaries)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_ACTION_SUMMARIES, jsonArray.toString()).apply()
        Log.d(TAG, "Saved action summaries: ${jsonArray.length()} items")
    }

    private fun loadPlayerActionSummariesFromPrefs() {
        val jsonString = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_ACTION_SUMMARIES, null)
        if (jsonString != null) {
            try {
                val jsonArray = JSONArray(jsonString)
                playerActionSummaries.clear()
                for (i in 0 until jsonArray.length()) {
                    playerActionSummaries.add(jsonArray.getString(i))
                }
                Log.d(TAG, "Loaded action summaries: ${playerActionSummaries.size} items")
            } catch (e: Exception) { Log.e(TAG, "Error parsing action summaries", e) }
        }
    }

    private fun saveSceneHistoryToPrefs() {
        val jsonArray = JSONArray(sceneHistory)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_SCENE_HISTORY, jsonArray.toString()).apply()
        Log.d(TAG, "Saved scene history: ${jsonArray.length()} items")
    }

    private fun loadSceneHistoryFromPrefs() {
        val jsonString = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_SCENE_HISTORY, null)
        if (jsonString != null) {
            try {
                val jsonArray = JSONArray(jsonString)
                sceneHistory.clear()
                for (i in 0 until jsonArray.length()) {
                    sceneHistory.add(jsonArray.getString(i))
                }
                Log.d(TAG, "Loaded scene history: ${sceneHistory.size} items")
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing scene history", e)
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); closeLlmResources() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameScreen(
    modifier: Modifier = Modifier, htmlContent: String, isLlmReady: Boolean, isProcessing: Boolean,
    progressValue: Float, onPlayerAction: (String) -> Unit, imageCacheMap: Map<String, String>,
    appCacheDir: File, onResetGameClicked: () -> Unit, onRequestDiagnosisClicked: () -> Unit,
    showDiagnosisDialog: Boolean, diagnosisContent: String?, onDismissDiagnosisDialog: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val scrollState = rememberScrollState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    if (showDiagnosisDialog && diagnosisContent != null) {
        Dialog(onDismissRequest = onDismissDiagnosisDialog, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Card(elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), modifier = Modifier.fillMaxWidth(0.95f)) {
                Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("DSM-V Style Analysis", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.height(400.dp)) {
                        AndroidView(
                            factory = { context -> WebView(context).apply {
                                settings.javaScriptEnabled = true; webViewClient = WebViewClient()
                                loadDataWithBaseURL(null, diagnosisContent, "text/html", "UTF-8", null)
                            } },
                            update = { it.loadDataWithBaseURL(null, diagnosisContent, "text/html", "UTF-8", null) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onDismissDiagnosisDialog, modifier = Modifier.align(Alignment.End)) { Text("Close") }
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().imePadding() ) {
        if (isProcessing) {
            if (progressValue == -1f) { // Indeterminate
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            } else if (progressValue > 0f && progressValue < 1f) { // Determinate
                LinearProgressIndicator(progress = { progressValue }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        Box(modifier = Modifier.weight(1f).padding(horizontal = 16.dp).verticalScroll(scrollState) ) {
            AndroidView(
                factory = { context -> WebView(context).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.builtInZoomControls = true; settings.displayZoomControls = false
                    settings.loadWithOverviewMode = true; settings.useWideViewPort = true // Adjusted for better scaling
                    settings.setSupportZoom(true)
                    addJavascriptInterface((context as MainActivity).WebAppInterface(onPlayerAction), "Android")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Scroll to bottom can be disruptive if content reloads often during streaming
                            // Only do this if not streaming, or if it's the final load.
                            // view?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
                        }
                        override fun onReceivedError(v: WebView?, e: Int, d: String?, u: String?) { super.onReceivedError(v,e,d,u); Log.e(MainActivity.TAG, "$e-$d@$u")}
                        override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest?): WebResourceResponse? {
                            r?.url?.toString()?.takeIf { it.startsWith("https://image.pollinations.ai/prompt/", ignoreCase = true) }?.let { url ->
                                val normalizedUrl = url.replace(Regex("&width=\\d+"), "").replace(Regex("&height=\\d+"), "")
                                val targetWidth = screenWidthDp.coerceAtMost(768) // Cap width for very large screens
                                val targetHeight = (targetWidth * 1.2).toInt()
                                // val targetUrl = "$normalizedUrl&width=${targetWidth}&height=${targetHeight}" // This was for if we constructed a new URL, but we download with fixed size.

                                imageCacheMap[normalizedUrl]?.let { File(appCacheDir, it) }?.takeIf { it.exists() }?.let { file ->
                                    try {
                                        val mime = when { file.extension.equals("jpg",true) || file.extension.equals("jpeg",true) -> "image/jpeg"
                                            file.extension.equals("png",true) -> "image/png"
                                            file.extension.equals("webp",true) -> "image/webp"
                                            else -> "image/jpeg" } // Default to jpeg
                                        return WebResourceResponse(mime, "UTF-8", FileInputStream(file))
                                    } catch (e: IOException) { Log.e(MainActivity.TAG, "Cache read error for $normalizedUrl", e)}
                                }
                            }
                            return super.shouldInterceptRequest(v, r)
                        }
                    }
                    loadDataWithBaseURL("https://image.pollinations.ai/", htmlContent, "text/html", "UTF-8", null)
                }},
                update = { webView ->
                    webView.loadDataWithBaseURL("https://image.pollinations.ai/", htmlContent, "text/html", "UTF-8", null)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,bottom=8.dp,top=8.dp).bringIntoViewRequester(bringIntoViewRequester),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(value=inputText, onValueChange={inputText=it}, label={Text("Action (optional)")},
                modifier = Modifier.weight(1f).onFocusChanged { if(it.isFocused) coroutineScope.launch{ delay(200); bringIntoViewRequester.bringIntoView()} },
                enabled = isLlmReady && !isProcessing
            )
            Button(onClick={if(inputText.isNotBlank()){onPlayerAction(inputText);inputText=""}}, enabled=isLlmReady && !isProcessing && inputText.isNotBlank()){Text("Send")}
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp), horizontalArrangement = Arrangement.SpaceEvenly){
            Button(onClick=onResetGameClicked,enabled=!isProcessing,colors=ButtonDefaults.buttonColors(containerColor=Color.Red.copy(alpha=0.7f))){Text("Reset Game")}
            Button(onClick=onRequestDiagnosisClicked,enabled=isLlmReady && !isProcessing){Text("DSM-V Analysis")}
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GameScreenPreview() {
    SickoTheme {
        val exampleImageUrl = "https://image.pollinations.ai/prompt/" + URLEncoder.encode("a vibrant fantasy landscape with floating islands and dragons", StandardCharsets.UTF_8.name())
        val exampleHtml = """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>body{font-family:sans-serif;margin:10px;background-color:#f0f0f0;color:#333}h1{color:#1a237e;text-align:center}img{max-width:100%;height:auto;border-radius:8px;margin-bottom:10px;display:block;margin-left:auto;margin-right:auto;border:1px solid #ddd}.actions{margin-top:20px;text-align:center}.action-button,button{background-color:#3949ab;color:#fff;padding:10px 15px;text-decoration:none;border-radius:5px;margin:5px;border:none;cursor:pointer}p{line-height:1.6}</style></head>
        <body><h1>The Dragon's Peak</h1><img src="$exampleImageUrl" alt="Image of a vibrant fantasy landscape"><p>You stand at the precipice of a windy cliff, overlooking a valley filled with floating islands. In the distance, a majestic dragon circles a crystalline peak.</p><div class='actions'><button onclick="Android.performAction('approach the dragon');return false">Approach Dragon</button><button onclick="Android.performAction('search for a path down');return false">Search Path</button></div>
        <div class="further-thoughts"><h3>Further Thoughts?</h3><p>Rate your excitement (0-10): <input type='range' name='excitement_level' min='0' max='10' value='7'></p><p>Your next move is driven by: <input type='radio' name='drive' value='curiosity' checked> Curiosity <input type='radio' name='drive' value='caution'> Caution</p></div>
        <div style="height:800px;background-color:#aabbcc">Scrollable Content Below</div></body></html>
        """.trimIndent()

        GameScreen(
            htmlContent = exampleHtml, isLlmReady = true, isProcessing = false, progressValue = 0f,
            onPlayerAction = { Log.d("Preview", "Action: $it")},
            imageCacheMap = mutableMapOf(), appCacheDir = File("."),
            onResetGameClicked = {}, onRequestDiagnosisClicked = {},
            showDiagnosisDialog = false, diagnosisContent = null, onDismissDiagnosisDialog = {}
        )
    }
}