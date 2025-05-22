package com.klemstinegroup.sicko

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.klemstinegroup.sicko.ui.theme.SickoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

private const val TAG = "MainActivity" // For logging
private const val CACHED_MODEL_FILE_NAME = "cached_llm_model.task"

class MainActivity : ComponentActivity() {

    private lateinit var llmInference: LlmInference
    private var llmResponse by mutableStateOf("Please select a model file to start.")
    private var isLlmReady by mutableStateOf(false)
    private var isCopying by mutableStateOf(false)
    private var copyProgress by mutableStateOf(0f)
    private var modelLoadedFromCache by mutableStateOf(false)


    private lateinit var openFileLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d(TAG, "onCreate: Initializing ActivityResultLauncher.")

        openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                Log.d(TAG, "onActivityResult: Model file URI selected: $it")
                handleModelFileUri(it)
            } ?: run {
                Log.w(TAG, "onActivityResult: No file selected.")
                llmResponse = "No model file selected. Please select a file."
                Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show()
            }
        }
        Log.d(TAG, "onCreate: ActivityResultLauncher initialized.")

        lifecycleScope.launch {
            val existingCachedFile = File(cacheDir, CACHED_MODEL_FILE_NAME)
            if (existingCachedFile.exists() && existingCachedFile.length() > 0) {
                Log.i(TAG, "onCreate: Found existing cached model: ${existingCachedFile.absolutePath}. Attempting to initialize.")
                llmResponse = "Found existing cached model. Initializing..."
                modelLoadedFromCache = true
                initializeLlmInference(existingCachedFile.absolutePath)
            } else {
                Log.d(TAG, "onCreate: No valid existing cached model found.")
                llmResponse = "Please select a model file to start."
                modelLoadedFromCache = false
            }
        }


        setContent {
            SickoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        llmResponse = llmResponse,
                        isLlmReady = isLlmReady,
                        isCopying = isCopying,
                        copyProgress = copyProgress,
                        modelLoadedFromCache = modelLoadedFromCache,
                        onSelectFileClicked = {
                            if (!isCopying) {
                                Log.d(TAG, "onSelectFileClicked: Launching file picker.")
                                openFileLauncher.launch(arrayOf("*/*")) // Allow any file type for .task
                            } else {
                                Toast.makeText(this, "Model is currently being copied/loaded.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onGenerateTestClicked = {
                            if (isLlmReady) {
                                runInference("What is the capital of Wisconsin?")
                            } else {
                                Toast.makeText(this, "LLM not ready. Select and load a model file first.", Toast.LENGTH_LONG).show()
                            }
                        },
                        onClearCacheAndSelectNewClicked = {
                            if (!isCopying) {
                                deleteCachedModel()
                                Toast.makeText(this, "Cached model cleared. Select a new file.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Cannot clear cache while model is being copied/loaded.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
        Log.d(TAG, "onCreate: setContent completed.")
    }

    private fun handleModelFileUri(uri: Uri) {
        Log.d(TAG, "handleModelFileUri: Processing URI: $uri")
        modelLoadedFromCache = false

        val cachedFile = File(cacheDir, CACHED_MODEL_FILE_NAME)
        var sourceFileLength: Long = -1L

        try {
            // Get the file size for comparison and progress calculation
            sourceFileLength = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            if (sourceFileLength == -1L) {
                Log.w(TAG, "handleModelFileUri: Could not determine source file size for URI: $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleModelFileUri: Error getting source file size: ${e.message}", e)
            llmResponse = "Error accessing selected file properties."
            isLlmReady = false
            Toast.makeText(this@MainActivity, llmResponse, Toast.LENGTH_LONG).show()
            return
        }

        // Check if the selected file is identical to the already cached one
        if (cachedFile.exists() && cachedFile.length() == sourceFileLength && sourceFileLength != -1L) {
            Log.i(TAG, "handleModelFileUri: Selected file matches existing cached model. Skipping copy. Size: $sourceFileLength bytes.")
            llmResponse = "Using existing cached model."
            Toast.makeText(this@MainActivity, "Using existing cached model.", Toast.LENGTH_SHORT).show()
            initializeLlmInference(cachedFile.absolutePath) // Initialize with the cached model
            return
        } else {
            if (cachedFile.exists()) {
                Log.i(TAG, "handleModelFileUri: Cached file exists but does not match selected file (Cached: ${cachedFile.length()} B, Selected: $sourceFileLength B). Will overwrite.")
            } else {
                Log.i(TAG, "handleModelFileUri: No matching cached file found. Proceeding with copy.")
            }
        }

        isCopying = true
        isLlmReady = false
        copyProgress = 0f
        llmResponse = "Starting to copy model file..."

        lifecycleScope.launch {
            var success = false
            try {
                val newCachedModelFile = copyUriToCache(uri, sourceFileLength)
                if (newCachedModelFile != null) {
                    Log.i(TAG, "handleModelFileUri: Model copied to cache: ${newCachedModelFile.absolutePath}")
                    initializeLlmInference(newCachedModelFile.absolutePath)
                    success = true
                } else {
                    llmResponse = "Error: Failed to copy model file to cache."
                    Toast.makeText(this@MainActivity, llmResponse, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleModelFileUri: Error during copy or init: ${e.message}", e)
                llmResponse = "Error: ${e.localizedMessage}"
                Toast.makeText(this@MainActivity, llmResponse, Toast.LENGTH_LONG).show()
            } finally {
                if (!success) { // If copy failed before initializeLlmInference was called
                    withContext(Dispatchers.Main) { isCopying = false }
                }
            }
        }
    }

    private suspend fun copyUriToCache(uri: Uri, knownFileLength: Long): File? = withContext(Dispatchers.IO) {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        val outputFile = File(cacheDir, CACHED_MODEL_FILE_NAME)

        try {
            inputStream = contentResolver.openInputStream(uri)
            outputStream = FileOutputStream(outputFile) // This will overwrite if the file exists
            if (inputStream == null) {
                Log.e(TAG, "copyUriToCache: Failed to open input stream from URI.")
                return@withContext null
            }

            val fileLength = if (knownFileLength != -1L) knownFileLength else {
                try { contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L }
                catch (e: Exception) {
                    Log.w(TAG, "copyUriToCache: Could not determine file size during copy for URI: $uri")
                    -1L
                }
            }

            Log.d(TAG, "copyUriToCache: Starting copy. File size: $fileLength bytes. Output: ${outputFile.absolutePath}")

            val buffer = ByteArray(8192) // 8KB buffer
            var bytesCopied: Long = 0
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                bytesCopied += bytesRead
                if (fileLength > 0) {
                    val progress = (bytesCopied.toFloat() / fileLength.toFloat())
                    withContext(Dispatchers.Main) {
                        copyProgress = progress
                        llmResponse = "Copying model... ${(progress * 100).toInt()}%"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        llmResponse = "Copying model... (size unknown, ${bytesCopied / (1024 * 1024)} MB copied)"
                    }
                }
            }
            outputStream.flush()
            Log.i(TAG, "copyUriToCache: File copied successfully. Total bytes: $bytesCopied")
            // UI update for "Model copied successfully" is handled by initializeLlmInference's start
            return@withContext outputFile
        } catch (e: IOException) {
            Log.e(TAG, "copyUriToCache: IOException during copy: ${e.message}", e)
            withContext(Dispatchers.Main) {
                llmResponse = "Error copying file: ${e.localizedMessage}"
            }
            outputFile.delete()
            return@withContext null
        } finally {
            try { inputStream?.close() } catch (e: IOException) { Log.e(TAG, "copyUriToCache: Error closing input stream", e) }
            try { outputStream?.close() } catch (e: IOException) { Log.e(TAG, "copyUriToCache: Error closing output stream", e) }
        }
    }

    // Reintroduced GPU attempt with CPU fallback
    private fun initializeLlmInference(modelPath: String) {
        val modelFile = File(modelPath)
        Log.i(TAG, "initializeLlmInference: Initializing LlmInference with model: \"${modelFile.name}\"")

        if (!llmResponse.contains("Copying model...", ignoreCase = true)) {
            llmResponse = "Initializing LLM with model: ${modelFile.name}..."
        }
        isLlmReady = false
        isCopying = true

        lifecycleScope.launch(Dispatchers.IO) {
            var finalLlmResponse = ""
            var finalIsLlmReady = false
            var finalToastMessage = ""
            var activeBackendInfo = "N/A"

            Log.i(TAG, "initializeLlmInference: Starting initialization attempt for ${modelFile.name}")
            try {
                // Attempt 1: GPU Backend
                Log.i(TAG, "initializeLlmInference: Attempting GPU backend...")
                val gpuOptionsBuilder = LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTopK(64)
                    .setPreferredBackend(Backend.GPU)

                val gpuTaskOptions = gpuOptionsBuilder.build()
                Log.d(TAG, "initializeLlmInference: GPU LlmInferenceOptions built. Calling LlmInference.createFromOptions...")
                val gpuInference = LlmInference.createFromOptions(this@MainActivity, gpuTaskOptions)
                Log.i(TAG, "initializeLlmInference: LlmInference.createFromOptions (GPU) call COMPLETED.")

                llmInference = gpuInference
                finalIsLlmReady = true
                activeBackendInfo = "GPU"
                finalLlmResponse = "LLM Ready ($activeBackendInfo backend). (${modelFile.name})"
                finalToastMessage = "LLM Initialized on GPU backend!"
                Log.i(TAG, "initializeLlmInference: Successfully initialized with GPU backend.")

            } catch (gpuException: Exception) {
                Log.w(TAG, "initializeLlmInference: GPU backend initialization FAILED: ${gpuException.message}. Falling back to CPU.", gpuException)
                // Update UI immediately about GPU failure before trying CPU
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "GPU backend failed: ${gpuException.localizedMessage?.take(100)}. Trying CPU.", Toast.LENGTH_LONG).show()
                    llmResponse = "GPU backend failed. Trying CPU..."
                }

                // Attempt 2: CPU Backend (Fallback)
                try {
                    Log.i(TAG, "initializeLlmInference: Attempting CPU backend (fallback)...")
                    val cpuOptionsBuilder = LlmInferenceOptions.builder()
                        .setModelPath(modelPath)
                        .setMaxTopK(64)
                        .setPreferredBackend(Backend.CPU)

                    val cpuTaskOptions = cpuOptionsBuilder.build()
                    Log.d(TAG, "initializeLlmInference: CPU LlmInferenceOptions built. Calling LlmInference.createFromOptions...")
                    val cpuInference = LlmInference.createFromOptions(this@MainActivity, cpuTaskOptions)
                    Log.i(TAG, "initializeLlmInference: LlmInference.createFromOptions (CPU) call COMPLETED.")

                    llmInference = cpuInference
                    finalIsLlmReady = true
                    activeBackendInfo = "CPU (GPU fallback)"
                    finalLlmResponse = "LLM Ready ($activeBackendInfo backend). (${modelFile.name})"
                    finalToastMessage = "LLM Initialized on CPU backend (GPU failed)."
                    Log.i(TAG, "initializeLlmInference: Successfully initialized with CPU backend after GPU failed.")

                } catch (cpuException: Exception) {
                    Log.e(TAG, "initializeLlmInference: CPU backend initialization FAILED (after GPU also failed): ${cpuException.message}", cpuException)
                    finalIsLlmReady = false
                    activeBackendInfo = "Failed (GPU & CPU)"
                    finalLlmResponse = "Error initializing LLM (GPU & CPU backends failed): ${cpuException.localizedMessage}"
                    finalToastMessage = "LLM Initialization Failed on both GPU and CPU backends."
                    withContext(Dispatchers.Main) { modelLoadedFromCache = false }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isLlmReady = finalIsLlmReady
                    llmResponse = finalLlmResponse // Set the final response message
                    if (finalToastMessage.isNotEmpty()) {
                        Toast.makeText(this@MainActivity, finalToastMessage, if(finalIsLlmReady) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    }
                    isCopying = false
                    Log.i(TAG, "initializeLlmInference: Final state - Ready: $isLlmReady, Backend: $activeBackendInfo, Response: $llmResponse")
                }
            }
        }
    }

    private fun runInference(inputPrompt: String) {
        if (!isLlmReady || !::llmInference.isInitialized) {
            llmResponse = "LLM is not ready. Please select and load a model file first."
            Log.w(TAG, "runInference called but LLM not ready or uninitialized.")
            Toast.makeText(this, llmResponse, Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "runInference: Generating response for prompt: \"$inputPrompt\"")
        llmResponse = "Generating response..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                val result = llmInference.generateResponse(inputPrompt)
                val endTime = System.currentTimeMillis()
                val duration = endTime - startTime
                Log.i(TAG, "runInference: Inference took $duration ms. Result: $result")
                withContext(Dispatchers.Main) {
                    llmResponse = result ?: "No response or null result from LLM."
                    Toast.makeText(this@MainActivity, "Inference took $duration ms", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "runInference: Error during LlmInference.generateResponse: ${e.message}", e)
                    llmResponse = "Error during inference: ${e.message}"
                    Toast.makeText(this@MainActivity, "Inference error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun deleteCachedModel() {
        val cachedFile = File(cacheDir, CACHED_MODEL_FILE_NAME)
        if (cachedFile.exists()) {
            if (cachedFile.delete()) {
                Log.i(TAG, "deleteCachedModel: Successfully deleted cached model: ${cachedFile.absolutePath}")
                isLlmReady = false
                modelLoadedFromCache = false
                llmResponse = "Cached model deleted. Please select a new model."
            } else {
                Log.e(TAG, "deleteCachedModel: Failed to delete cached model: ${cachedFile.absolutePath}")
                llmResponse = "Error: Failed to delete cached model."
            }
        } else {
            Log.d(TAG, "deleteCachedModel: Cached model not found, no need to delete.")
            llmResponse = "No cached model to delete. Please select a model."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Cleaning up.")
        if (::llmInference.isInitialized) {
            Log.d(TAG, "onDestroy: LlmInference instance exists. Calling close().")
            try {
                llmInference.close()
            } catch (e: Exception) {
                Log.e(TAG, "onDestroy: Error closing LlmInference: ${e.message}", e)
            }
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    llmResponse: String,
    isLlmReady: Boolean,
    isCopying: Boolean,
    copyProgress: Float,
    modelLoadedFromCache: Boolean,
    onSelectFileClicked: () -> Unit,
    onGenerateTestClicked: () -> Unit,
    onClearCacheAndSelectNewClicked: () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(text = llmResponse)
            Spacer(modifier = Modifier.height(10.dp))

            if (isCopying) {
                if (llmResponse.contains("Copying model...", ignoreCase = true) && copyProgress > 0f && copyProgress < 1f) {
                    LinearProgressIndicator(progress = { copyProgress })
                }
                else if ((llmResponse.contains("Initializing LLM", ignoreCase = true) ||
                            llmResponse.contains("Trying CPU", ignoreCase = true) ||
                            llmResponse.contains("GPU backend failed", ignoreCase = true)) && !isLlmReady) {
                    LinearProgressIndicator() // Indeterminate for initialization attempts
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Button(onClick = onSelectFileClicked, enabled = !isCopying) {
                Text(if (isCopying) "Processing..." else if (modelLoadedFromCache && isLlmReady) "Select Different Model" else "Select Model File (.task)")
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (modelLoadedFromCache || isLlmReady) {
                Button(onClick = onClearCacheAndSelectNewClicked, enabled = !isCopying) {
                    Text("Clear Cached Model & Select New")
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
            Button(onClick = onGenerateTestClicked, enabled = isLlmReady && !isCopying) {
                Text("Generate Test Response")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    SickoTheme {
        MainScreen(
            llmResponse = "Preview: Initializing LLM...", // General init message
            isLlmReady = false,
            isCopying = true,
            copyProgress = 0.0f,
            modelLoadedFromCache = false,
            onSelectFileClicked = {},
            onGenerateTestClicked = {},
            onClearCacheAndSelectNewClicked = {}
        )
    }
}