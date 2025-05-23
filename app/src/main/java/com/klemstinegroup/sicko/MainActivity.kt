package com.klemstinegroup.sicko

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
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
//import androidx.compose.foundation.layout.width // Unused import
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
//import androidx.compose.material3.TextButton // Unused import
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
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
private const val DEFAULT_TEMPERATURE = 1.0f // For more creative/varied output
private const val DEFAULT_SESSION_MAX_TOKENS = 2048 // Max tokens for LlmInferenceSessionOptions

enum class LlmCallType {
    INITIAL_SCENE,      // For generateInitialGamePage
    SUMMARIZE_TURN,     // For the first part of onPlayerAction
    GENERATE_SCENE,     // For the second part of onPlayerAction
    DIAGNOSIS           // For requestDsmDiagnosis
}

open class MainActivity : ComponentActivity() {

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
    var htmlContent by mutableStateOf("<html><body><h1>Welcome!</h1><p>Loading game...</p></body></html>")
    var isLlmReady by mutableStateOf(false)
    var isProcessing by mutableStateOf(false)
    var progressValue by mutableStateOf(0f)
    private var gameRestoredFromPrefs by mutableStateOf(false)

    val imageCacheMap: SnapshotStateMap<String, String> = mutableStateMapOf()
    private val currentlyDownloading: MutableSet<String> = mutableSetOf()
    val playerActionSummaries: SnapshotStateList<String> = mutableStateListOf()
    var dsmDiagnosisContent by mutableStateOf<String?>(null)
    val sceneHistory: SnapshotStateList<String> = mutableStateListOf()
    var showDsmDiagnosisDialog by mutableStateOf(false)


    private lateinit var openFileLauncher: ActivityResultLauncher<Array<String>>

    // For passing context between summary and scene generation calls
    private var lastActionForSceneGeneration: String? = null
    private var lastHtmlForSceneGeneration: String? = null

    // Scroll preservation state
    @Volatile var scrollYForNextPartialLoad: Int? = null
    @Volatile var isNextLoadAPartialUpdate: Boolean = false


    inner class WebAppInterface(private val onAction: (String) -> Unit) {
        @JavascriptInterface
        fun performAction(action: String) {
            Log.d(TAG, "WebAppInterface: performAction called with action: $action")
            onPlayerAction(action)
        }
    }

    private fun onPlayerAction(action: String) {
        if (isLlmReady && !isProcessing) {
            isProcessing = true
            updateGameHtml("<html><body><h1>Processing your action...</h1><p>Please wait.</p></body></html>", isStreaming = false)

            lastActionForSceneGeneration = action
            lastHtmlForSceneGeneration = sceneHistory.lastOrNull() ?: htmlContent

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
        val currentAction = lastActionForSceneGeneration
        val previousHtml = lastHtmlForSceneGeneration

        if (currentAction == null || previousHtml == null) {
            Log.e(TAG, "Missing action or HTML for scene generation.")
            updateGameHtml("<p>System: Error in processing turn. Missing context.</p>")
            isProcessing = false
            progressValue = 0f
            return
        }

        updateGameHtml("<html><body><h1>Generating next scene...</h1><p>Please wait.</p></body></html>", isStreaming = false)

        val sceneGenerationPrompt = "You are Dr. Gemini, a manipulative psychologist running a therapeutic simulation. Maintain your persona.\n" +
                "The previous turn was summarized as: '$summary'.\n" +
                "The player was in a scene described by the following HTML:\n```html\n$previousHtml\n```\n" +
                "And the player chose the action: '$currentAction'.\n\n" +
                "**TASK: GENERATE THE NEXT GAME SCENE HTML**\n" +
                "Generate **only the HTML code** for the next game scene. The HTML should be structured in the following order:\n" +
                "1.  A title (e.g., `<h1>Your Title</h1>`).\n" +
                "2.  An `<img>` tag for Pollinations.ai. The `src` attribute MUST be in the format `https://image.pollinations.ai/prompt/YOUR_URL_ENCODED_PROMPT?width=368&height=448`. URL-encode the image prompt and ensure the width and height parameters are included exactly as shown. The image should be evocative and relevant to the psychological themes being explored.\n" +
                "3.  A description of the new scene (e.g., `<p>Scene details...</p>`). As Dr. Gemini, craft the narrative to guide the player down tempting, psychologically exploratory, and potentially perverse (in a fantasy/symbolic context) avenues. Use your internal 'notes' (player profile, desires, fears, previous choices) and 'predictions' to tailor the scenario to test their boundaries and reveal their underlying psyche.\n" +
                "4.  A `div` with class `further-thoughts`. Inside this div, optionally add 1-2 HTML form elements (slider, radio group, checkbox, or text input) if they fit the narrative context. These are for player reflection and to gather more nuanced data for your 'notes'.\n" +
                "    Example Slider: `<p>Rate your current anxiety (0=Calm, 10=Panic): <input type='range' name='anxiety_level' min='0' max='10' value='3'></p>`\n" +
                "    Example Radios: `<p>Your dominant emotion: <input type='radio' name='emotion' value='curiosity'> Curiosity <input type='radio' name='emotion' value='fear'> Fear <input type='radio' name='emotion' value='desire'> Desire</p>`\n" +
                "5.  **Main Player Choices (Turn Advancement):** Present 3-4 distinct choices for the player to advance the game. Each choice MUST be a clickable HTML element (e.g., `<button>` or `<a>`) with an `onclick` attribute calling `Android.performAction('ACTION_DESCRIPTION'); return false;`. These choices should be clearly grouped together, perhaps as a list or styled to appear as primary options.\n\n" +
                "Ensure the HTML is responsive, uses legible font sizes, is well-formed, self-contained, and includes basic CSS. Do not include any explanations or text outside the HTML tags."

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
        loadPlayerActionSummariesFromPrefs()
        loadSceneHistoryFromPrefs()

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
                            if (sceneHistory.isNotEmpty()) htmlContent = sceneHistory.last()
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
                        modifier = Modifier.padding(innerPadding),
                        mainActivity = this, // Pass MainActivity instance
                        htmlContent = htmlContent,
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
            val contentInsideBlock = match.groupValues[1].trim()
            val startsWithHtmlTag = contentInsideBlock.startsWith("<html>", ignoreCase = true)
            val endsWithHtmlTag = contentInsideBlock.endsWith("</html>", ignoreCase = true)

            if (startsWithHtmlTag && endsWithHtmlTag) return contentInsideBlock
            val startIndexInBlock = contentInsideBlock.indexOf("<html>", ignoreCase = true)
            val endIndexInBlock = contentInsideBlock.lastIndexOf("</html>", ignoreCase = true)

            if (startIndexInBlock != -1 && endIndexInBlock != -1 && endIndexInBlock > startIndexInBlock) {
                return contentInsideBlock.substring(startIndexInBlock, endIndexInBlock + "</html>".length)
            } else if (startsWithHtmlTag) return contentInsideBlock
            return contentInsideBlock
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

    fun updateGameHtml(newContent: String, isStreaming: Boolean = false, isDiagnosis: Boolean = false) {
        val cleanHtml = if (isStreaming) newContent else extractHtml(newContent)
        val isFullHtmlDocument = cleanHtml.trim().startsWith("<html>", ignoreCase = true) &&
                cleanHtml.trim().endsWith("</html>", ignoreCase = true)
        Log.d(TAG, "updateGameHtml: isStreaming=$isStreaming, isDiagnosis=$isDiagnosis, isFullHtmlDocument=$isFullHtmlDocument, cleanHtml starts with: ${cleanHtml.take(30)}")

        val finalHtml = if (isFullHtmlDocument) {
            if (!cleanHtml.contains("<meta name=\"viewport\"", ignoreCase = true)) {
                val headEndIndex = cleanHtml.indexOf("</head>", ignoreCase = true)
                if (headEndIndex != -1) {
                    cleanHtml.substring(0, headEndIndex) + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" + cleanHtml.substring(headEndIndex)
                } else {
                    // Fallback if </head> not found, try to inject after <head>
                    val headStartIndex = cleanHtml.indexOf("<head>", ignoreCase = true)
                    if (headStartIndex != -1) {
                        val insertAfter = headStartIndex + "<head>".length
                        cleanHtml.substring(0, insertAfter) + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" + cleanHtml.substring(insertAfter)
                    } else {
                        // Very basic fallback: prepend if no head tag found (less ideal)
                        "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"></head>" + cleanHtml.substringAfter("<html>")
                    }
                }
            } else {
                cleanHtml
            }
        } else {
            """
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1.0"><style>
            html { box-sizing: border-box; font-size: 16px; }
            *, *:before, *:after { box-sizing: inherit; }
            body{font-family:sans-serif;margin:10px;background-color:#f0f0f0;color:#333; line-height:1.6;}
            h1{color:#1a237e;text-align:center;margin-top:0; font-size: 1.8em;}
            img{max-width:100%;height:auto;border-radius:8px;margin-bottom:15px;display:block;margin-left:auto;margin-right:auto;border:1px solid #ddd;box-shadow:2px 2px 5px rgba(0,0,0,.1)}
            .actions{margin-top:20px;text-align:center}
            .action-button,button,a.action-link{background-color:#3949ab;color:#fff;padding:12px 18px;text-decoration:none;border-radius:5px;margin:5px;border:none;cursor:pointer;display:inline-block;font-size:1em}
            a.action-link:hover,button:hover{background-color:#283593}
            p{line-height:1.6;margin-bottom:10px; font-size: 1em;}
            .further-thoughts{margin-top:25px;padding:15px;border-top:1px dashed #ccc;background-color:#e9e9e9;border-radius:5px;}
            .further-thoughts h3{margin-top:0;color:#2c3e50; font-size: 1.2em;}
            .further-thoughts p{margin-bottom:8px;color:#34495e; font-size: 0.95em;}
            .further-thoughts input[type='range']{width:80%;margin-top:5px;}
            .further-thoughts input[type='text']{padding:8px;border:1px solid #ccc;border-radius:4px;width:calc(100% - 18px);margin-top:5px; font-size: 0.95em;}
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
        } else {
            this.htmlContent = finalHtml // Update the state variable for Compose
            if (!isStreaming && isLlmReady && !finalHtml.contains("<p>System:") &&
                !finalHtml.contains("<h1>Processing your action...</h1>") &&
                !finalHtml.contains("<h1>Generating next scene...</h1>") &&
                !finalHtml.contains("<h1>Generating Initial Scene...</h1>")
            ) {
                sceneHistory.add(finalHtml)
                saveSceneHistoryToPrefs()
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_HTML_CONTENT, finalHtml).apply()
                manageImageCacheAndStoreMap(finalHtml)
            }
        }
    }

    private fun resetGame() {
        Log.i(TAG, "Resetting game state.")
        isNextLoadAPartialUpdate = false // Ensure flags are reset
        scrollYForNextPartialLoad = null

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
        isProcessing = true
        val summariesString = playerActionSummaries.joinToString(separator = "\n\n", prefix = "Player Action Summaries:\n")
        val diagnosisPrompt = "Based on the following summaries of player actions and game states:\n\n$summariesString\n\n" +
                "Provide a detailed DSM-V style psychological analysis. " +
                "Present this analysis as well-formed HTML content suitable for display in a WebView. " +
                "You MAY use simple HTML form elements like radio buttons, checkboxes, or text areas if they help structure parts of your analysis or pose reflective questions to the player based on the diagnosis. " +
                "Focus on patterns and potential interpretations, not definitive diagnoses. Ensure all HTML is self-contained within the body. Use legible font sizes."
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
        isNextLoadAPartialUpdate = false // Reset flags
        scrollYForNextPartialLoad = null
        updateGameHtml("<p>System: Starting to copy new model file...</p>")
        lifecycleScope.launch {
            try {
                File(cacheDir, CACHED_MODEL_FILE_NAME).takeIf { it.exists() }?.delete()
                val newCachedModelFile = copyUriToCache(uri)
                if (newCachedModelFile != null) {
                    initializeLlmInference(newCachedModelFile.absolutePath) { success ->
                        if (success) {
                            sceneHistory.clear(); saveSceneHistoryToPrefs()
                            gameRestoredFromPrefs = false
                            playerActionSummaries.clear(); savePlayerActionSummariesToPrefs()
                            generateInitialGamePage()
                        } else {
                            isProcessing = false
                        }
                    }
                } else {
                    updateGameHtml("<p>System: Error copying model file.</p>")
                    Toast.makeText(this@MainActivity, "Error copying model", Toast.LENGTH_LONG).show()
                    isProcessing = false
                    if (!isLlmReady) openFileLauncher.launch(arrayOf("*/*"))
                }
            } catch (e: Exception) {
                updateGameHtml("<p>System: Error: ${e.localizedMessage}.</p>")
                Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                isProcessing = false
                if (!isLlmReady) openFileLauncher.launch(arrayOf("*/*"))
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
            outputStream.flush()
            return@withContext outputFile
        } catch (e: IOException) {
            Log.e(TAG, "Error copying URI to cache", e)
            outputFile.delete()
            return@withContext null
        } finally {
            inputStream?.close()
            outputStream?.close()
            withContext(Dispatchers.Main) { progressValue = 0f }
        }
    }

    private fun initializeLlmInference(modelPath: String, onLlmInitialized: (success: Boolean) -> Unit) {
        isProcessing = true; isLlmReady = false; progressValue = -1f
        isNextLoadAPartialUpdate = false // Reset flags
        scrollYForNextPartialLoad = null
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
                    .setTopK(DEFAULT_TOP_K)
                    .setTemperature(DEFAULT_TEMPERATURE)
                    .setRandomSeed(DEFAULT_RANDOM_SEED)
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
                if (!isLlmReady) isProcessing = false
                progressValue = 0f
                onLlmInitialized(isLlmReady)
            }
        }
    }

    private fun generateInitialGamePage() {
        if (!isLlmReady) {
            updateGameHtml("<p>System: LLM not ready. Load model.</p>"); return
        }
        isProcessing = true
        isNextLoadAPartialUpdate = false // This is a full load, not partial initially
        scrollYForNextPartialLoad = null

        val initialPrompt = "You are Dr. Gemini, a manipulative psychologist. This is the VERY FIRST turn of a new game.\n" +
                "**TASK 1: SUMMARIZE THE INITIAL STATE**\n" +
                "Provide a concise summary of this initial game state/scene you are about to create. Prefix this summary with `ACTION_SUMMARY: ` and end it with a newline (e.g., ACTION_SUMMARY: Player awakens in a mysterious, pulsating room with a single, ominous button.).\n\n" +
                "**TASK 2: GENERATE THE STARTING SCENE HTML**\n" +
                "Then, generate **only the HTML code** for this starting scene. The HTML should be structured in the following order:\n" +
                "1.  A title (e.g., `<h1>Your Title</h1>`).\n" +
                "2.  An `<img>` tag for Pollinations.ai. The `src` attribute MUST be in the format `https://image.pollinations.ai/prompt/YOUR_URL_ENCODED_PROMPT?width=368&height=448`. URL-encode the image prompt and ensure the width and height parameters are included exactly as shown. The image should be thematically relevant and visually striking.\n" +
                "3.  A description of the scene (e.g., `<p>Scene details...</p>`), designed to be intriguing and to subtly guide the player towards psychologically revealing choices.\n" +
                "4.  A `div` with class `further-thoughts`. Inside this div, optionally add 1-2 HTML form elements (slider, radio group, checkbox, or text input) if contextually appropriate for initial reflections. Place this div *before* the main player choices section. Ensure these are clearly labeled.\n" +
                "    Example: `<p>Initial feeling: <input type='radio' name='initial_feeling' value='curious'> Curious <input type='radio' name='initial_feeling' value='uneasy'> Uneasy</p>`\n" +
                "5.  **Main Player Choices (Turn Advancement):** Present 3-4 distinct choices for the player's first actions in the game. Each choice MUST be a clickable HTML element (e.g., `<button>` or `<a>`) with an `onclick` attribute calling `Android.performAction('ACTION_DESCRIPTION'); return false;`. These choices should be clearly grouped together.\n\n" +
                "Ensure the HTML is responsive, uses legible font sizes, is well-formed, self-contained, and includes basic CSS. Remember your persona and aim to create an engaging, slightly unsettling experience from the very start. Do not include any explanations or text outside the HTML tags, except for the initial ACTION_SUMMARY line."
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

        if (!isProcessing) isProcessing = true
        progressValue = -1f

        val thinkingMsg = when(callType) {
            LlmCallType.SUMMARIZE_TURN -> ""
            LlmCallType.INITIAL_SCENE -> "<html><body><h1>Generating Initial Scene...</h1><p>Please wait.</p></body></html>"
            LlmCallType.GENERATE_SCENE -> ""
            LlmCallType.DIAGNOSIS -> "<html><body><h1>Generating Diagnosis...</h1><p>Please wait.</p></body></html>"
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
                            lifecycleScope.launch(Dispatchers.Main) {
                                val currentBuiltResponse = fullResponseBuilder.toString()
                                if (done) {
                                    isNextLoadAPartialUpdate = false // Final response, so next load is not partial
                                    val finalResponse = currentBuiltResponse.trim()
                                    val duration = System.currentTimeMillis() - startTime
                                    var summaryText: String? = null
                                    Log.d(TAG, "Final Response (Type: $callType, Duration: $duration ms): $finalResponse")

                                    when (callType) {
                                        LlmCallType.SUMMARIZE_TURN -> {
                                            if (finalResponse.startsWith("ACTION_SUMMARY: ")) {
                                                summaryText = finalResponse.substring("ACTION_SUMMARY: ".length).trim()
                                                if (summaryText.isNotBlank()) {
                                                    Log.d(TAG, "Extracted Summary: $summaryText")
                                                    playerActionSummaries.add(summaryText)
                                                    savePlayerActionSummariesToPrefs()
                                                    onSummaryReceivedAndProceedToSceneGeneration(summaryText)
                                                } else {
                                                    Log.w(TAG, "ACTION_SUMMARY prefix found but summary was blank.")
                                                    updateGameHtml("<p>System: Failed to get valid summary.</p>")
                                                    isProcessing = false; progressValue = 0f; scrollYForNextPartialLoad = null
                                                }
                                            } else {
                                                Log.w(TAG, "Expected ACTION_SUMMARY: prefix not found.")
                                                updateGameHtml("<p>System: Error in summary response format.</p>")
                                                isProcessing = false; progressValue = 0f; scrollYForNextPartialLoad = null
                                            }
                                        }
                                        LlmCallType.INITIAL_SCENE, LlmCallType.GENERATE_SCENE -> {
                                            var htmlPart = finalResponse
                                            if (callType == LlmCallType.INITIAL_SCENE && finalResponse.startsWith("ACTION_SUMMARY: ")) {
                                                val summaryEndIndex = finalResponse.indexOf('\n', "ACTION_SUMMARY: ".length)
                                                if (summaryEndIndex != -1) {
                                                    summaryText = finalResponse.substring("ACTION_SUMMARY: ".length, summaryEndIndex).trim()
                                                    htmlPart = finalResponse.substring(summaryEndIndex + 1).trim()
                                                } else {
                                                    summaryText = finalResponse.substring("ACTION_SUMMARY: ".length).trim()
                                                    htmlPart = ""
                                                }
                                                if(summaryText.isNotBlank()){
                                                    playerActionSummaries.add(summaryText)
                                                    savePlayerActionSummariesToPrefs()
                                                }
                                            }
                                            updateGameHtml(htmlPart, isStreaming = false) // Final update
                                            Toast.makeText(this@MainActivity, if(callType == LlmCallType.INITIAL_SCENE) "Initial scene in $duration ms" else "Scene updated in $duration ms", Toast.LENGTH_SHORT).show()
                                            isProcessing = false; progressValue = 0f; scrollYForNextPartialLoad = null
                                        }
                                        LlmCallType.DIAGNOSIS -> {
                                            updateGameHtml(finalResponse, isStreaming = false, isDiagnosis = true)
                                            Toast.makeText(this@MainActivity, "Diagnosis done in $duration ms", Toast.LENGTH_SHORT).show()
                                            isProcessing = false; progressValue = 0f; scrollYForNextPartialLoad = null
                                        }
                                    }
                                } else { // Streaming (done == false)
                                    if (callType == LlmCallType.INITIAL_SCENE || callType == LlmCallType.GENERATE_SCENE || callType == LlmCallType.DIAGNOSIS) {
                                        isNextLoadAPartialUpdate = true // Mark that the next load is partial
                                        val tempHtmlToDisplay = extractHtml(currentBuiltResponse)
                                        if (tempHtmlToDisplay.startsWith("<html>", ignoreCase = true) || callType == LlmCallType.DIAGNOSIS) {
                                            if (callType == LlmCallType.DIAGNOSIS) {
                                                // For diagnosis, we update the dialog content directly,
                                                // updateGameHtml will wrap it if it's not full HTML.
                                                this@MainActivity.dsmDiagnosisContent = tempHtmlToDisplay
                                            } else {
                                                updateGameHtml(tempHtmlToDisplay, isStreaming = true)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    })
            } catch (e: Exception) {
                Log.e(TAG, "Inference Error (Type: $callType)", e)
                withContext(Dispatchers.Main) {
                    val errorMsg = "<p>System: Inference error: ${e.localizedMessage}</p>"
                    isNextLoadAPartialUpdate = false // Ensure flag is reset on error
                    scrollYForNextPartialLoad = null
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
                if (cachedFile == null || !cachedFile.exists() || cachedFile.length() == 0L) { // Check for empty file too
                    if (url !in currentlyDownloading) {
                        currentlyDownloading.add(url)
                        Log.d(TAG, "Downloading image: $url")
                        val downloadedFilename = downloadImage(this@MainActivity, url)
                        if (downloadedFilename != null) {
                            imageCacheMap[url] = downloadedFilename
                            mapChanged = true
                            Log.d(TAG, "Image downloaded and cached: $url -> $downloadedFilename")
                        } else {
                            Log.e(TAG, "Failed to download image: $url")
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
        Regex("<img[^>]+src\\s*=\\s*['\"]([^'\"]+pollinations.ai/prompt/[^?'\"]+)['\"][^>]*>", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { matchResult ->
                var baseUrl = matchResult.groupValues[1]
                baseUrl = baseUrl.replace(Regex("/width/\\d+"), "").replace(Regex("/height/\\d+"), "")
                urls.add(baseUrl)
            }
        return urls
    }
    private fun generateHashedFilename(url: String): String {
        return try {
            MessageDigest.getInstance("MD5").digest(url.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) } + ".jpg"
        } catch (e: Exception) { "image_${url.hashCode()}.jpg" }
    }
    private suspend fun downloadImage(context: Context, normalizedImageUrl: String): String? = withContext(Dispatchers.IO) {
        val filename = generateHashedFilename(normalizedImageUrl)
        val file = File(context.cacheDir, filename)
        if (file.exists() && file.length() > 0) {
            return@withContext filename
        }
        try {
            val downloadUrlString = "$normalizedImageUrl?width=368&height=448"
            Log.d(TAG, "Attempting to download from: $downloadUrlString")

            val url = URL(downloadUrlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            connection.doInput = true
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                FileOutputStream(file).use { out -> connection.inputStream.use { it.copyTo(out) } }
                return@withContext filename
            } else {
                Log.e(TAG, "Download failed: HTTP ${connection.responseCode} for $downloadUrlString")
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading image $normalizedImageUrl", e)
            file.delete()
        }
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
            } catch (e: Exception) { Log.e(TAG, "Error parsing action summaries", e) }
        }
    }

    private fun saveSceneHistoryToPrefs() {
        val jsonArray = JSONArray(sceneHistory)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_SCENE_HISTORY, jsonArray.toString()).apply()
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
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing scene history", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeLlmResources()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameScreen(
    modifier: Modifier = Modifier,
    mainActivity: MainActivity, // Pass instance for accessing scroll flags
    htmlContent: String,
    isLlmReady: Boolean,
    isProcessing: Boolean,
    progressValue: Float,
    onPlayerAction: (String) -> Unit,
    imageCacheMap: Map<String, String>,
    appCacheDir: File,
    onResetGameClicked: () -> Unit,
    onRequestDiagnosisClicked: () -> Unit,
    showDiagnosisDialog: Boolean,
    diagnosisContent: String?,
    onDismissDiagnosisDialog: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    if (showDiagnosisDialog && diagnosisContent != null) {
        Dialog(onDismissRequest = onDismissDiagnosisDialog, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Card(elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), modifier = Modifier.fillMaxWidth(0.95f).padding(vertical = 32.dp)) {
                Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("DSM-V Style Analysis", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.height( (LocalConfiguration.current.screenHeightDp * 0.6f).dp) ) {
                        AndroidView(
                            factory = { context -> WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                webViewClient = WebViewClient()
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
            if (progressValue == -1f) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            } else if (progressValue > 0f && progressValue < 1f) {
                LinearProgressIndicator(progress = { progressValue }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical=4.dp).verticalScroll(scrollState) ) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.setSupportZoom(true)

                        addJavascriptInterface((context as MainActivity).WebAppInterface(onPlayerAction), "Android")
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                if (mainActivity.isNextLoadAPartialUpdate) {
                                    view?.evaluateJavascript("(function(){return window.scrollY;})();") { scrollYStr ->
                                        mainActivity.scrollYForNextPartialLoad = scrollYStr?.replace("\"", "")?.toIntOrNull()
                                        Log.d("WebViewScroll", "onPageStarted (partial): Saved scrollY: ${mainActivity.scrollYForNextPartialLoad}")
                                    }
                                } else {
                                    mainActivity.scrollYForNextPartialLoad = null
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val scrollYToRestore = mainActivity.scrollYForNextPartialLoad
                                if (mainActivity.isNextLoadAPartialUpdate && scrollYToRestore != null) {
                                    Log.d("WebViewScroll", "onPageFinished (partial): Restoring scrollY: $scrollYToRestore")
                                    view?.evaluateJavascript("window.scrollTo(0, $scrollYToRestore);", null)
                                    // ScrollYForNextPartialLoad is kept for the next potential partial update's onPageStarted
                                } else {
                                    Log.d("WebViewScroll", "onPageFinished (final): Scrolling to bottom.")
                                    view?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
                                    mainActivity.scrollYForNextPartialLoad = null // Clear as it's a final load or not applicable
                                }
                            }

                            override fun onReceivedError(v: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                                super.onReceivedError(v,req,err)
                                Log.e(MainActivity.TAG, "WebView Error: ${err?.errorCode} - ${err?.description} @ ${req?.url}")
                            }
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                request?.url?.toString()?.takeIf { it.startsWith("https://image.pollinations.ai/prompt/", ignoreCase = true) }?.let { imageUrlString ->
                                    val normalizedUrlKey = imageUrlString.substringBefore('?')
                                    imageCacheMap[normalizedUrlKey]?.let { filename ->
                                        val file = File(appCacheDir, filename)
                                        if (file.exists() && file.length() > 0) {
                                            try {
                                                val mime = when {
                                                    filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
                                                    filename.endsWith(".png", true) -> "image/png"
                                                    filename.endsWith(".webp", true) -> "image/webp"
                                                    else -> "image/jpeg"
                                                }
                                                return WebResourceResponse(mime, "UTF-8", FileInputStream(file))
                                            } catch (e: IOException) {
                                                Log.e(MainActivity.TAG, "Cache read error for $normalizedUrlKey", e)
                                            }
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        loadDataWithBaseURL("https://image.pollinations.ai/", htmlContent, "text/html", "UTF-8", null)
                    }
                },
                update = { webView ->
                    // This is critical: when htmlContent changes, this 'update' block is called.
                    // The WebViewClient's onPageStarted/onPageFinished will handle scroll logic
                    // based on flags set by MainActivity.
                    webView.loadDataWithBaseURL("https://image.pollinations.ai/", htmlContent, "text/html", "UTF-8", null)
                    Log.d("GameScreen", "WebView updated via AndroidView.update with new HTML content.")
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
            Button(onClick=onResetGameClicked,enabled=true,colors=ButtonDefaults.buttonColors(containerColor=Color.Red.copy(alpha=0.7f))){Text("Reset Game")}
            Button(onClick=onRequestDiagnosisClicked,enabled=isLlmReady && !isProcessing){Text("DSM-V Analysis")}
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GameScreenPreview() {
    SickoTheme {
        // Create a dummy MainActivity instance for the preview
        val context = LocalContext.current
        val dummyMainActivity = remember {
            object : MainActivity() {
                init {
                    // Initialize states for preview if necessary
                    this.htmlContent = """
                        <html><head><meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>
                            html { font-size: 100%; } body{font-family:sans-serif;margin:10px;background-color:#f0f0f0;color:#333; font-size: 1rem;}
                            h1{color:#1a237e;text-align:center; font-size: 1.5rem;} img{max-width:100%;height:auto;border-radius:8px;margin-bottom:10px;display:block;margin-left:auto;margin-right:auto;border:1px solid #ddd}
                            .actions{margin-top:20px;text-align:center} .action-button,button{background-color:#3949ab;color:#fff;padding:10px 15px;text-decoration:none;border-radius:5px;margin:5px;border:none;cursor:pointer; font-size: 0.9rem;}
                            p{line-height:1.6; font-size: 0.9rem;} .further-thoughts{margin-top:25px;padding:15px;border-top:1px dashed #ccc;background-color:#e9e9e9;border-radius:5px;}
                            .further-thoughts h3{margin-top:0;color:#2c3e50; font-size: 1.1rem;} .further-thoughts p{margin-bottom:8px;color:#34495e; font-size: 0.85rem;}
                        </style></head>
                        <body><h1>The Dragon's Peak</h1><img src="https://image.pollinations.ai/prompt/${URLEncoder.encode("a vibrant fantasy landscape with floating islands and dragons", StandardCharsets.UTF_8.name())}?width=368&height=448" alt="Image of a vibrant fantasy landscape"><p>You stand at the precipice of a windy cliff, overlooking a valley filled with floating islands. In the distance, a majestic dragon circles a crystalline peak.</p>
                        <div class="further-thoughts"><h3>Further Thoughts?</h3><p>Rate your excitement (0-10): <input type='range' name='excitement_level' min='0' max='10' value='7'></p><p>Your next move is driven by: <input type='radio' name='drive' value='curiosity' checked> Curiosity <input type='radio' name='drive' value='caution'> Caution</p></div>
                        <div class='actions'><button onclick="Android.performAction('approach the dragon');return false">Approach Dragon</button><button onclick="Android.performAction('search for a path down');return false">Search Path</button></div>
                        <div style="height:800px;background-color:#aabbcc">Scrollable Content Below</div></body></html>
                    """.trimIndent()
                    this.isLlmReady = true
                    this.isProcessing = false
                    this.progressValue = 0f
                    // Initialize other necessary states that GameScreen might access from MainActivity
                }
            }
        }

        GameScreen(
            mainActivity = dummyMainActivity,
            htmlContent = dummyMainActivity.htmlContent,
            isLlmReady = dummyMainActivity.isLlmReady,
            isProcessing = dummyMainActivity.isProcessing,
            progressValue = dummyMainActivity.progressValue,
            onPlayerAction = { Log.d("Preview", "Action: $it") },
            imageCacheMap = remember { mutableStateMapOf() }, // Use remember for SnapshotStateMap
            appCacheDir = context.cacheDir ?: File("."),
            onResetGameClicked = {},
            onRequestDiagnosisClicked = {},
            showDiagnosisDialog = false,
            diagnosisContent = null,
            onDismissDiagnosisDialog = {}
        )
    }
}
