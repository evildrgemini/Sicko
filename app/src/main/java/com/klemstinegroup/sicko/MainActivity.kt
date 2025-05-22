package com.klemstinegroup.sicko

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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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


class MainActivity : ComponentActivity() {

    // Companion object for constants
    companion object {
        internal const val TAG = "MainActivity" // For logging
        private const val CACHED_MODEL_FILE_NAME = "cached_llm_model.task"
        private const val PREFS_NAME = "SickoGamePrefs"
        private const val KEY_HTML_CONTENT = "htmlContent"
        private const val KEY_IMAGE_CACHE_MAP = "imageCacheMap" // For storing URL to local file mapping
    }

    private var llmInference: LlmInference? = null
    private var llmSession: LlmInferenceSession? = null
    private var htmlContent by mutableStateOf("<html><body><h1>Welcome!</h1><p>Loading game...</p></body></html>")
    private var isLlmReady by mutableStateOf(false)
    private var isProcessing by mutableStateOf(false)
    private var progressValue by mutableStateOf(0f)
    private var gameRestoredFromPrefs by mutableStateOf(false)

    // In-memory map for image cache: Pollinations URL -> local filename
    private val imageCacheMap: SnapshotStateMap<String, String> = mutableStateMapOf()
    private val currentlyDownloading: MutableSet<String> = mutableSetOf()


    private lateinit var openFileLauncher: ActivityResultLauncher<Array<String>>

    // JavaScript Interface for WebView
    inner class WebAppInterface(private val onAction: (String) -> Unit) {
        @JavascriptInterface
        fun performAction(action: String) {
            Log.d(TAG, "WebAppInterface: performAction called with action: $action")
            onPlayerAction(action)
        }
    }

    // Moved onPlayerAction here to be accessible by WebAppInterface easily
    private fun onPlayerAction(action: String) {
        if (isLlmReady && !isProcessing) {
            // Updated prompt to include image generation instructions
            val prompt = "The current game scene is described by the following HTML:\n```html\n$htmlContent\n```\n" +
                    "The player chose to: '$action'.\n" +
                    "Generate **only the HTML code** for the next game scene. The HTML should include:\n" +
                    "1. A title and a description of the new scene.\n" +
                    "2. An `<img>` tag to display an image relevant to the scene. To do this:\n" +
                    "   a. Generate a concise, visually descriptive image prompt string for the current scene (e.g., 'a dark medieval dungeon with a single torch').\n" +
                    "   b. URL-encode this image prompt string (e.g., spaces become '%20', special characters encoded).\n" +
                    "   c. Set the `<img>` tag's `src` attribute to: `https://image.pollinations.ai/prompt/YOUR_URL_ENCODED_IMAGE_PROMPT_HERE` (replace the placeholder with the actual URL-encoded prompt).\n" +
                    "   d. The `<img>` tag should have an `alt` attribute describing the image (e.g., 'Image of a dark dungeon').\n" +
                    "   e. Style the `<img>` tag with CSS for good presentation: `style=\"max-width: 100%; height: auto; border-radius: 8px; margin-bottom: 10px; display: block; margin-left: auto; margin-right: auto;\"`.\n" +
                    "   f. Place this `<img>` tag appropriately within the scene, perhaps after the title or main description.\n" +
                    "3. At least two distinct, clickable actions for the player. Each clickable action element (e.g., a button or a link `<a href='#'>`) MUST have an 'onclick' attribute that calls `Android.performAction('THE_ACTION_STRING_HERE'); return false;`. Replace 'THE_ACTION_STRING_HERE' with the actual command string for that action (e.g., 'open door', 'take torch').\n" +
                    "   For example: `<button onclick=\"Android.performAction('examine the chest'); return false;\">Examine the Chest</button>`.\n" +
                    "4. Also, list the available actions as text for the player to read, like 'Available actions: examine the chest, go north'.\n" +
                    "5. Ensure the HTML is well-formed, self-contained, and includes basic CSS for readability (background, text color, button styling). Do not include any explanations or text outside the HTML tags."
            runInference(prompt)
        } else {
            Toast.makeText(this, "LLM not ready or busy. Load a model or wait.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d(TAG, "onCreate: Initializing ActivityResultLauncher.")

        // Load saved game state and image cache map
        val sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedHtmlContent = sharedPreferences.getString(KEY_HTML_CONTENT, null)
        if (savedHtmlContent != null) {
            htmlContent = savedHtmlContent
            gameRestoredFromPrefs = true
            Log.i(TAG, "onCreate: Restored HTML content from SharedPreferences.")
        }
        loadCacheMapFromPrefs()


        openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                Log.d(TAG, "onActivityResult: Model file URI selected: $it")
                handleModelFileUri(it)
            } ?: run {
                Log.w(TAG, "onActivityResult: No file selected.")
                updateGameHtml("<p>System: No model file selected. Please select a file to continue.</p>")
                Toast.makeText(this, "No file selected. Please select a model to start.", Toast.LENGTH_LONG).show()
                if (!isLlmReady && !isProcessing) {
                    openFileLauncher.launch(arrayOf("*/*"))
                }
            }
        }
        Log.d(TAG, "onCreate: ActivityResultLauncher initialized.")

        lifecycleScope.launch {
            val existingCachedFile = File(cacheDir, CACHED_MODEL_FILE_NAME)
            if (existingCachedFile.exists() && existingCachedFile.length() > 0) {
                Log.i(TAG, "onCreate: Found existing cached model. Initializing...")
                initializeLlmInference(existingCachedFile.absolutePath) { success ->
                    if (success && !gameRestoredFromPrefs) {
                        generateInitialGamePage()
                    } else if (success && gameRestoredFromPrefs) {
                        Toast.makeText(this@MainActivity, "Game resumed.", Toast.LENGTH_SHORT).show()
                        // Trigger image caching for restored content
                        manageImageCacheAndStoreMap(htmlContent)
                    }
                }
            } else {
                val message = if (gameRestoredFromPrefs) {
                    "Game state loaded, but model file is missing. Please select the model file to continue."
                } else {
                    "No cached model found. Please select a model file to start a new game."
                }
                updateGameHtml("<p>System: $message</p>")
                Log.d(TAG, "onCreate: $message. Prompting for model file.")
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
                        htmlContent = htmlContent,
                        isLlmReady = isLlmReady,
                        isProcessing = isProcessing,
                        progressValue = progressValue,
                        onPlayerAction = this::onPlayerAction,
                        imageCacheMap = imageCacheMap, // Pass the map
                        appCacheDir = cacheDir // Pass cache directory
                    )
                }
            }
        }
        Log.d(TAG, "onCreate: setContent completed.")
    }

    private fun extractHtml(rawOutput: String): String {
        val trimmedOutput = rawOutput.trim()
        val htmlBlockRegex = Regex("```html\\s*([\\s\\S]*?)\\s*```", RegexOption.MULTILINE)
        val match = htmlBlockRegex.find(trimmedOutput)
        if (match != null && match.groupValues.size > 1) {
            val contentInsideBlock = match.groupValues[1].trim()
            val startIndexInBlock = contentInsideBlock.indexOf("<html>", ignoreCase = true)
            val endIndexInBlock = contentInsideBlock.lastIndexOf("</html>", ignoreCase = true)
            if (startIndexInBlock != -1 && endIndexInBlock != -1 && endIndexInBlock > startIndexInBlock) {
                return contentInsideBlock.substring(startIndexInBlock, endIndexInBlock + "</html>".length)
            } else {
                if (startIndexInBlock != -1) {
                    return contentInsideBlock.substring(startIndexInBlock)
                }
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
        if (startIndex != -1) {
            return potentialHtmlContent.substring(startIndex)
        }
        return potentialHtmlContent
    }


    private fun updateGameHtml(newContent: String, isStreaming: Boolean = false) {
        val cleanHtml = if (isStreaming) newContent else extractHtml(newContent)
        val isFullHtmlDocument = cleanHtml.trim().startsWith("<html>", ignoreCase = true) &&
                cleanHtml.trim().endsWith("</html>", ignoreCase = true)

        htmlContent = if (isFullHtmlDocument) {
            cleanHtml
        } else {
            """
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: sans-serif; margin: 10px; background-color: #f0f0f0; color: #333; }
                    h1 { color: #1a237e; text-align: center; }
                    img { 
                        max-width: 100%; 
                        height: auto; 
                        border-radius: 8px; 
                        margin-bottom: 15px; 
                        display: block; 
                        margin-left: auto; 
                        margin-right: auto;
                        border: 1px solid #ddd;
                        box-shadow: 2px 2px 5px rgba(0,0,0,0.1);
                    }
                    .actions { margin-top: 20px; text-align: center; }
                    .action-button, button, a.action-link { 
                        background-color: #3949ab; color: white; padding: 12px 18px; 
                        text-decoration: none; border-radius: 5px; margin: 5px;
                        border: none; cursor: pointer; display: inline-block;
                        font-size: 1em;
                    }
                    a.action-link:hover, button:hover { background-color: #283593; }
                    p { line-height: 1.6; margin-bottom: 10px;}
                    .available-actions { font-style: italic; color: #555; margin-top:15px; text-align:center; }
                </style>
            </head>
            <body>
                ${cleanHtml}
            </body>
            </html>
            """.trimIndent()
        }

        if (!isStreaming && isLlmReady && !cleanHtml.contains("<p>System:")) {
            val sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            with(sharedPreferences.edit()) {
                putString(KEY_HTML_CONTENT, htmlContent)
                apply()
                Log.d(TAG, "Saved HTML content to SharedPreferences.")
            }
            // Manage image caching for the new content
            manageImageCacheAndStoreMap(htmlContent)
        }
    }


    private fun closeLlmResources() {
        llmSession?.close()
        llmSession = null
        llmInference?.close()
        llmInference = null
        isLlmReady = false
        Log.d(TAG, "Closed existing LLM engine and session.")
    }

    private fun handleModelFileUri(uri: Uri) {
        Log.d(TAG, "handleModelFileUri: Processing URI: $uri with CPU (Vision Disabled)")
        isProcessing = true
        isLlmReady = false
        progressValue = 0f
        updateGameHtml("<p>System: Starting to copy new model file...</p>")

        lifecycleScope.launch {
            try {
                val cachedFile = File(cacheDir, CACHED_MODEL_FILE_NAME)
                if (cachedFile.exists()) {
                    cachedFile.delete()
                    Log.i(TAG, "handleModelFileUri: Deleted existing cached model.")
                }
                val newCachedModelFile = copyUriToCache(uri)
                if (newCachedModelFile != null) {
                    Log.i(TAG, "handleModelFileUri: Model copied to cache: ${newCachedModelFile.absolutePath}")
                    initializeLlmInference(newCachedModelFile.absolutePath) { success ->
                        if (success) {
                            gameRestoredFromPrefs = false
                            generateInitialGamePage()
                        }
                    }
                } else {
                    updateGameHtml("<p>System: Error - Failed to copy model file to cache. Please try again.</p>")
                    Toast.makeText(this@MainActivity, "Error copying model", Toast.LENGTH_LONG).show()
                    withContext(Dispatchers.Main) { isProcessing = false }
                    openFileLauncher.launch(arrayOf("*/*"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleModelFileUri: Error during copy or init: ${e.message}", e)
                updateGameHtml("<p>System: Error - ${e.localizedMessage}. Please try selecting the model file again.</p>")
                Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                withContext(Dispatchers.Main) { isProcessing = false }
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
            if (inputStream == null) {
                Log.e(TAG, "copyUriToCache: Failed to open input stream from URI.")
                return@withContext null
            }
            val fileLength = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            Log.d(TAG, "copyUriToCache: Starting copy. Target: ${outputFile.absolutePath}. File size: $fileLength bytes.")
            val buffer = ByteArray(8192)
            var bytesCopied: Long = 0
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                bytesCopied += bytesRead
                if (fileLength > 0) {
                    val progress = (bytesCopied.toFloat() / fileLength.toFloat())
                    withContext(Dispatchers.Main) { progressValue = progress }
                } else {
                    withContext(Dispatchers.Main) { progressValue = -1f }
                }
            }
            outputStream.flush()
            Log.i(TAG, "copyUriToCache: File copied successfully. Total bytes: $bytesCopied to ${outputFile.absolutePath}")
            return@withContext outputFile
        } catch (e: IOException) {
            Log.e(TAG, "copyUriToCache: IOException during copy: ${e.message}", e)
            withContext(Dispatchers.Main) { updateGameHtml("<p>System: Error copying file - ${e.localizedMessage}</p>") }
            outputFile.delete()
            return@withContext null
        } finally {
            inputStream?.close()
            outputStream?.close()
            withContext(Dispatchers.Main) { progressValue = 0f }
        }
    }

    private fun initializeLlmInference(modelPath: String, onLlmInitialized: (success: Boolean) -> Unit) {
        val modelFile = File(modelPath)
        Log.i(TAG, "initializeLlmInference: Model: \"${modelFile.name}\" with CPU (Vision Disabled)")
        isProcessing = true
        isLlmReady = false
        progressValue = -1f
        updateGameHtml("<p>System: Initializing LLM with model: ${modelFile.name} (CPU, Vision Disabled)... This may take a moment.</p>")

        lifecycleScope.launch(Dispatchers.IO) {
            var determinedLlmReady = false
            var toastMessage = ""
            closeLlmResources()
            try {
                Log.i(TAG, "initializeLlmInference (IO): Attempting CPU backend (Vision Disabled)...")
                val optionsBuilder = LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(DEFAULT_TASK_MAX_TOKENS)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                optionsBuilder.setMaxNumImages(0)
                Log.d(TAG, "initializeLlmInference (IO): Vision explicitly disabled.")

                val taskOptions = optionsBuilder.build()
                val createdLlmInference = LlmInference.createFromOptions(applicationContext, taskOptions)
                llmInference = createdLlmInference
                Log.i(TAG, "initializeLlmInference (IO): LlmInference.createFromOptions (CPU) COMPLETED.")

                val sessionOptionsBuilder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(DEFAULT_TOP_K)
                    .setTemperature(DEFAULT_TEMPERATURE)
                    .setRandomSeed(DEFAULT_RANDOM_SEED)
                    .setGraphOptions(GraphOptions.builder().setEnableVisionModality(false).build())
                llmSession = LlmInferenceSession.createFromOptions(createdLlmInference, sessionOptionsBuilder.build())
                Log.i(TAG, "initializeLlmInference (IO): LlmInferenceSession created successfully.")
                determinedLlmReady = true
                toastMessage = "LLM Initialized on CPU (Vision Disabled)!"
            } catch (e: Exception) {
                Log.e(TAG, "initializeLlmInference (IO): CPU backend initialization FAILED: ${e.message}", e)
                toastMessage = "LLM Initialization Failed. Please select the model file again."
                determinedLlmReady = false
                withContext(Dispatchers.Main) {
                    updateGameHtml("<p>System: Error initializing LLM - ${e.localizedMessage}. Please select the model file again.</p>")
                    File(cacheDir, CACHED_MODEL_FILE_NAME).delete()
                }
            }

            withContext(Dispatchers.Main) {
                isLlmReady = determinedLlmReady
                if (toastMessage.isNotEmpty()) {
                    Toast.makeText(this@MainActivity, toastMessage, if(isLlmReady) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                }
                isProcessing = false
                progressValue = 0f
                onLlmInitialized(isLlmReady)
                Log.i(TAG, "initializeLlmInference (Main): End. isLlmReady: $isLlmReady")
            }
        }
    }

    private fun generateInitialGamePage() {
        Log.d(TAG, "generateInitialGamePage: Called.")
        if (!isLlmReady) {
            Log.w(TAG, "generateInitialGamePage: LLM not ready, cannot generate page.")
            updateGameHtml("<p>System: LLM is not ready. Please load a model first.</p>")
            return
        }
        val initialPrompt = "Generate **only the HTML code** for the starting scene of a simple text adventure game. The HTML should include:\n" +
                "1. A title and a description of the scene.\n" +
                "2. An `<img>` tag to display an image relevant to the scene. To do this:\n" +
                "   a. Generate a concise, visually descriptive image prompt string for the current scene (e.g., 'a mysterious forest entrance at dusk').\n" +
                "   b. URL-encode this image prompt string.\n" +
                "   c. Set the `<img>` tag's `src` attribute to: `https://image.pollinations.ai/prompt/YOUR_URL_ENCODED_IMAGE_PROMPT_HERE`.\n" +
                "   d. The `<img>` tag should have an `alt` attribute (e.g., 'Image of a forest entrance').\n" +
                "   e. Style the `<img>` tag: `style=\"max-width: 100%; height: auto; border-radius: 8px; margin-bottom: 10px; display: block; margin-left: auto; margin-right: auto;\"`.\n" +
                "   f. Place this `<img>` tag after the title or main description.\n" +
                "3. At least two distinct, clickable actions for the player. Each clickable action element MUST have an 'onclick' attribute that calls `Android.performAction('THE_ACTION_STRING_HERE'); return false;`.\n" +
                "   For example: `<button onclick=\"Android.performAction('look around'); return false;\">Look Around</button>`.\n" +
                "4. Also, list the available actions as text for the player to read.\n" +
                "5. Make the HTML visually appealing with basic CSS (background, text, button styling). Ensure it is well-formed and self-contained. Do not include any explanations or text outside the HTML tags."
        runInference(initialPrompt)
    }


    private fun runInference(promptForLlm: String) {
        val currentSession = llmSession
        if (!isLlmReady || currentSession == null || llmInference == null) {
            updateGameHtml("<p>System: LLM Session is not ready. Please load a model.</p>")
            Log.w(TAG, "runInference called but LLM not ready.")
            Toast.makeText(this, "LLM not ready. Try restarting or selecting model.", Toast.LENGTH_LONG).show()
            isProcessing = false
            progressValue = 0f
            val existingCachedFile = File(cacheDir, CACHED_MODEL_FILE_NAME)
            if (!existingCachedFile.exists() || existingCachedFile.length() == 0L) {
                openFileLauncher.launch(arrayOf("*/*"))
            }
            return
        }

        if (promptForLlm.contains("starting scene")) {
            updateGameHtml("<h1>Generating Initial Scene...</h1><p>Please wait.</p>", isStreaming = true)
        } else {
            updateGameHtml("<h1>Updating Scene...</h1><p>Thinking...</p>", isStreaming = true)
        }

        Log.d(TAG, "runInference: Sending prompt to LLM (first 100 chars): \"${promptForLlm.take(100)}...\"")
        isProcessing = true
        progressValue = -1f

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
                                updateGameHtml(fullResponseBuilder.toString(), isStreaming = true)

                                if (done) {
                                    val endTime = System.currentTimeMillis()
                                    val duration = endTime - startTime
                                    val finalHtmlResponse = fullResponseBuilder.toString()
                                    Log.i(TAG, "runInference (ProgressListener): Inference took $duration ms.")
                                    Log.d(TAG, "Final Generated HTML (first 100 chars): ${finalHtmlResponse.take(100)}")

                                    updateGameHtml(finalHtmlResponse, isStreaming = false)
                                    Toast.makeText(this@MainActivity, "Scene updated in $duration ms", Toast.LENGTH_SHORT).show()
                                    isProcessing = false
                                    progressValue = 0f

                                    if (finalHtmlResponse.isBlank() ||
                                        htmlContent.contains("<body></body>", ignoreCase = true) ||
                                        htmlContent.contains("<p>System: Model generated empty HTML.</p>", ignoreCase = true) ||
                                        !htmlContent.contains("</", ignoreCase = true)
                                    ) {
                                        updateGameHtml("<p>System: Model generated empty or invalid HTML. Please try the action again or restart.</p>", isStreaming = false)
                                        Log.w(TAG, "Inference done but HTML content seems empty or invalid.")
                                    }
                                }
                            }
                        }
                    })
            } catch (e: Exception) {
                Log.e(TAG, "runInference: Error during inference: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    updateGameHtml("<p>System: Error during inference - ${e.localizedMessage}. Please try again.</p>", isStreaming = false)
                    Toast.makeText(this@MainActivity, "Inference error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    isProcessing = false
                    progressValue = 0f
                }
            }
        }
    }

    // --- Image Caching Logic ---
    private fun manageImageCacheAndStoreMap(html: String) {
        CoroutineScope(Dispatchers.IO).launch { // Perform parsing and downloads off the main thread
            val imageUrls = extractPollinationsImageUrls(html)
            var mapChanged = false
            for (url in imageUrls) {
                val localFilename = imageCacheMap[url]
                val cachedFile = if (localFilename != null) File(cacheDir, localFilename) else null

                if (cachedFile == null || !cachedFile.exists()) {
                    if (url !in currentlyDownloading) {
                        currentlyDownloading.add(url)
                        Log.d(TAG, "Cache miss for $url. Attempting download.")
                        val downloadedFilename = downloadImage(this@MainActivity, url)
                        if (downloadedFilename != null) {
                            imageCacheMap[url] = downloadedFilename
                            mapChanged = true
                            Log.i(TAG, "Successfully downloaded and cached $url as $downloadedFilename")
                        } else {
                            Log.e(TAG, "Failed to download $url")
                        }
                        currentlyDownloading.remove(url)
                    }
                } else {
                    Log.d(TAG, "Image $url already in cache: ${cachedFile.path}")
                }
            }
            if (mapChanged) {
                saveCacheMapToPrefs()
            }
        }
    }

    private fun extractPollinationsImageUrls(html: String): Set<String> {
        val urls = mutableSetOf<String>()
        val imgTagRegex = Regex("<img[^>]+src\\s*=\\s*['\"]([^'\"]+pollinations.ai/prompt/[^'\"]+)['\"][^>]*>", RegexOption.IGNORE_CASE)
        imgTagRegex.findAll(html).forEach { matchResult ->
            urls.add(matchResult.groupValues[1])
        }
        Log.d(TAG, "Extracted Pollinations URLs: $urls")
        return urls
    }

    private fun generateHashedFilename(url: String): String {
        return try {
            val bytes = MessageDigest.getInstance("MD5").digest(url.toByteArray(StandardCharsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) } + ".jpg" // Assume jpg for now
        } catch (e: Exception) {
            Log.e(TAG, "Error generating hashed filename", e)
            "image_${url.hashCode()}.jpg" // Fallback
        }
    }

    private suspend fun downloadImage(context: Context, imageUrl: String): String? = withContext(Dispatchers.IO) {
        val filename = generateHashedFilename(imageUrl)
        val file = File(context.cacheDir, filename)
        if (file.exists()) { // Should ideally be checked before calling downloadImage by manageImageCache
            Log.d(TAG, "Image already exists in cache: ${file.absolutePath}")
            return@withContext filename
        }
        Log.d(TAG, "Downloading $imageUrl to ${file.absolutePath}")
        try {
            val url = URL(imageUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000 // 15 seconds
            connection.readTimeout = 15000 // 15 seconds
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                FileOutputStream(file).use { outputStream ->
                    connection.inputStream.use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.i(TAG, "Image downloaded successfully: ${file.absolutePath}")
                return@withContext filename
            } else {
                Log.e(TAG, "Download failed for $imageUrl: HTTP ${connection.responseCode} ${connection.responseMessage}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during image download for $imageUrl", e)
            file.delete() // Clean up partial file if download failed
        }
        return@withContext null
    }


    private fun saveCacheMapToPrefs() {
        val jsonObject = JSONObject()
        imageCacheMap.forEach { (key, value) ->
            try {
                jsonObject.put(key, value)
            } catch (e: Exception) {
                Log.e(TAG, "Error putting item into JSON for cache map", e)
            }
        }
        val jsonString = jsonObject.toString()
        val sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putString(KEY_IMAGE_CACHE_MAP, jsonString)
            apply()
        }
        Log.d(TAG, "Image cache map saved to SharedPreferences.")
    }

    private fun loadCacheMapFromPrefs() {
        val sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val jsonString = sharedPreferences.getString(KEY_IMAGE_CACHE_MAP, null)
        if (jsonString != null) {
            try {
                val jsonObject = JSONObject(jsonString)
                val tempMap = mutableMapOf<String, String>()
                jsonObject.keys().forEach { key ->
                    tempMap[key] = jsonObject.getString(key)
                }
                imageCacheMap.putAll(tempMap) // Update the SnapshotStateMap
                Log.d(TAG, "Image cache map loaded from SharedPreferences: $imageCacheMap")
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing image cache map from SharedPreferences", e)
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Cleaning up.")
        closeLlmResources()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameScreen(
    modifier: Modifier = Modifier,
    htmlContent: String,
    isLlmReady: Boolean,
    isProcessing: Boolean,
    progressValue: Float,
    onPlayerAction: (String) -> Unit,
    imageCacheMap: Map<String, String>, // Pass the map
    appCacheDir: File // Pass cache directory
) {
    var inputText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scrollState)
    ) {
        if (isProcessing) {
            if (progressValue == -1f) {
                LinearProgressIndicator(modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp))
            } else if (progressValue > 0f && progressValue < 1f) {
                LinearProgressIndicator(
                    progress = { progressValue },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        Box(modifier = Modifier
            .weight(1f)
            .padding(horizontal = 16.dp)
        ) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false

                        addJavascriptInterface((context as MainActivity).WebAppInterface(onPlayerAction), "Android")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                Log.d(MainActivity.TAG, "WebView onPageFinished for URL: $url. Scrolling to bottom.")
                                view?.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?
                            ) {
                                super.onReceivedError(view, errorCode, description, failingUrl)
                                Log.e(MainActivity.TAG, "WebView Error: $errorCode - $description on $failingUrl")
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val requestUrl = request?.url?.toString()
                                if (requestUrl != null && requestUrl.startsWith("https://image.pollinations.ai/prompt/")) {
                                    val localFilename = imageCacheMap[requestUrl]
                                    if (localFilename != null) {
                                        val cachedFile = File(appCacheDir, localFilename)
                                        if (cachedFile.exists()) {
                                            try {
                                                Log.i(MainActivity.TAG, "Serving image from cache: $requestUrl -> ${cachedFile.absolutePath}")
                                                // Determine MIME type (simple check for now)
                                                val mimeType = when {
                                                    localFilename.endsWith(".jpg", ignoreCase = true) || localFilename.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                                                    localFilename.endsWith(".png", ignoreCase = true) -> "image/png"
                                                    localFilename.endsWith(".webp", ignoreCase = true) -> "image/webp"
                                                    else -> "image/jpeg" // Default
                                                }
                                                return WebResourceResponse(mimeType, "UTF-8", FileInputStream(cachedFile))
                                            } catch (e: IOException) {
                                                Log.e(MainActivity.TAG, "Error reading cached file: ${cachedFile.absolutePath}", e)
                                            }
                                        } else {
                                            Log.w(MainActivity.TAG, "Cache map has entry for $requestUrl but file not found: $localFilename")
                                        }
                                    } else {
                                        Log.d(MainActivity.TAG, "Image not in cache map (yet?): $requestUrl. Allowing network load.")
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        loadDataWithBaseURL("https://image.pollinations.ai/", htmlContent, "text/html", "UTF-8", null)
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL("https://image.pollinations.ai/", htmlContent, "text/html", "UTF-8", null)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 8.dp)
                .bringIntoViewRequester(bringIntoViewRequester),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Enter your action (optional)") },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            coroutineScope.launch {
                                delay(200)
                                bringIntoViewRequester.bringIntoView()
                            }
                        }
                    },
                enabled = isLlmReady && !isProcessing
            )
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onPlayerAction(inputText)
                        inputText = ""
                    }
                },
                enabled = isLlmReady && !isProcessing && inputText.isNotBlank()
            ) {
                Text("Send")
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
fun GameScreenPreview() {
    SickoTheme {
        val exampleImageUrl = "https://image.pollinations.ai/prompt/" + URLEncoder.encode("a vibrant fantasy landscape with floating islands and dragons", StandardCharsets.UTF_8.name())
        val exampleHtml = """
        <html>
        <head>
            <style>
                body { font-family: sans-serif; margin: 10px; background-color: #f0f0f0; color: #333; }
                h1 { color: #1a237e; text-align: center;}
                img { max-width: 100%; height: auto; border-radius: 8px; margin-bottom: 10px; display: block; margin-left: auto; margin-right: auto; border: 1px solid #ddd;}
                .actions { margin-top: 20px; text-align: center;}
                .action-button, button { background-color: #3949ab; color: white; padding: 10px 15px; text-decoration: none; border-radius: 5px; margin: 5px; border: none; cursor: pointer; }
                p { line-height: 1.6; }
                 .available-actions { font-style: italic; color: #555; margin-top:15px; text-align:center; }
            </style>
        </head>
        <body>
            <h1>The Dragon's Peak</h1>
            <img src="$exampleImageUrl" alt="Image of a vibrant fantasy landscape" style="max-width: 100%; height: auto; border-radius: 8px; margin-bottom: 10px; display: block; margin-left: auto; margin-right: auto;">
            <p>You stand at the precipice of a windy cliff, overlooking a valley filled with floating islands. In the distance, a majestic dragon circles a crystalline peak.</p>
            <div class='actions'>
                <button onclick="Android.performAction('approach the dragon'); return false;">Approach the Dragon</button>
                <button onclick="Android.performAction('search for a path down'); return false;">Search for Path Down</button>
            </div>
            <p class="available-actions">Available actions: approach the dragon, search for a path down</p>
            <div style="height: 800px; background-color: #aabbcc;">Scrollable Content Below</div>
        </body>
        </html>
        """.trimIndent() // Added a tall div to make scrolling obvious in preview

        GameScreen(
            htmlContent = exampleHtml,
            isLlmReady = true,
            isProcessing = false,
            progressValue = 0f,
            onPlayerAction = { Log.d("Preview", "Action: $it")},
            imageCacheMap = mutableMapOf(), // Provide an empty map for preview
            appCacheDir = File(".") // Provide a dummy file for preview
        )
    }
}