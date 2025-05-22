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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import com.google.mediapipe.tasks.genai.llminference.VisionModelOptions
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

// Default LLM inference parameters, updated to Gemma model's defaults
private const val DEFAULT_TOP_K = 64 // Gemma default
private const val DEFAULT_TEMPERATURE = 1.0f // Gemma default
private const val DEFAULT_TOP_P = 0.95f // Gemma default
private const val DEFAULT_RANDOM_SEED = 101 // Keeping a general default


class MainActivity : ComponentActivity() {

    private lateinit var llmInference: LlmInference
    private var llmResponse by mutableStateOf("Please select a model file to start.")
    private var isLlmReady by mutableStateOf(false)
    private var isCopying by mutableStateOf(false) // True during file copy or model initialization
    private var copyProgress by mutableStateOf(0f)
    private var modelLoadedFromCache by mutableStateOf(false)
    // Updated defaults based on Gemma model
    private var useGpuPreference by mutableStateOf(false) // Gemma default: "accelerators": "cpu"
    private var isVisionModelPreference by mutableStateOf(true) // Gemma default: "llmSupportImage": true

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
                Log.i(TAG, "onCreate: Found existing cached model: ${existingCachedFile.absolutePath}. Attempting to initialize with GPU pref: $useGpuPreference, Vision pref: $isVisionModelPreference")
                llmResponse = "Found existing cached model. Initializing..."
                modelLoadedFromCache = true
                // Initialize with current preferences (which are now Gemma's defaults)
                initializeLlmInference(existingCachedFile.absolutePath, useGpuPreference)
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
                        useGpuPreference = useGpuPreference,
                        isVisionModelPreference = isVisionModelPreference,
                        onGpuPreferenceChanged = { shouldUseGpu ->
                            onGpuPreferenceChanged(shouldUseGpu)
                        },
                        onVisionModelPreferenceChanged = { isVision ->
                            onVisionModelPreferenceChanged(isVision)
                        },
                        onSelectFileClicked = {
                            if (!isCopying) {
                                Log.d(TAG, "onSelectFileClicked: Launching file picker.")
                                openFileLauncher.launch(arrayOf("*/*"))
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

    private fun onGpuPreferenceChanged(shouldUseGpu: Boolean) {
        if (isCopying) {
            Toast.makeText(this, "Please wait for the current operation to complete.", Toast.LENGTH_SHORT).show()
            return
        }
        useGpuPreference = shouldUseGpu
        val cachedModelFile = File(cacheDir, CACHED_MODEL_FILE_NAME)

        if (cachedModelFile.exists() && cachedModelFile.length() > 0 && isLlmReady) {
            Log.i(TAG, "GPU preference changed to $shouldUseGpu. Re-initializing model: ${cachedModelFile.name}")
            if (::llmInference.isInitialized) {
                try {
                    llmInference.close()
                    Log.d(TAG, "Closed existing LLM instance before re-initializing with new GPU preference.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing LLM instance: ${e.message}", e)
                }
            }
            isLlmReady = false
            initializeLlmInference(cachedModelFile.absolutePath, shouldUseGpu)
        } else {
            llmResponse = "GPU preference set to $shouldUseGpu. Will apply to the next model loaded or initialized."
            Toast.makeText(this, "GPU preference will apply to the next model loaded.", Toast.LENGTH_LONG).show()
        }
    }

    private fun onVisionModelPreferenceChanged(shouldBeVisionModel: Boolean) {
        if (isCopying) {
            Toast.makeText(this, "Please wait for the current operation to complete.", Toast.LENGTH_SHORT).show()
            return
        }
        isVisionModelPreference = shouldBeVisionModel
        val cachedModelFile = File(cacheDir, CACHED_MODEL_FILE_NAME)

        if (cachedModelFile.exists() && cachedModelFile.length() > 0 && isLlmReady) {
            Log.i(TAG, "Vision model preference changed to $shouldBeVisionModel. Re-initializing model: ${cachedModelFile.name}")
            if (::llmInference.isInitialized) {
                try {
                    llmInference.close()
                    Log.d(TAG, "Closed existing LLM instance before re-initializing with new vision model preference.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing LLM instance: ${e.message}", e)
                }
            }
            isLlmReady = false
            initializeLlmInference(cachedModelFile.absolutePath, useGpuPreference)
        } else {
            llmResponse = "Vision model preference set to $shouldBeVisionModel. Will apply to the next model loaded or initialized."
            Toast.makeText(this, "Vision preference will apply to the next model loaded.", Toast.LENGTH_LONG).show()
        }
    }


    private fun handleModelFileUri(uri: Uri) {
        Log.d(TAG, "handleModelFileUri: Processing URI: $uri with GPU pref: $useGpuPreference, Vision pref: $isVisionModelPreference")
        modelLoadedFromCache = false

        val cachedFile = File(cacheDir, CACHED_MODEL_FILE_NAME)
        var sourceFileLength: Long = -1L

        try {
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

        if (cachedFile.exists() && cachedFile.length() == sourceFileLength && sourceFileLength != -1L) {
            Log.i(TAG, "handleModelFileUri: Selected file matches existing cached model. Size: $sourceFileLength bytes.")
            llmResponse = "Using existing cached model."
            Toast.makeText(this@MainActivity, "Using existing cached model.", Toast.LENGTH_SHORT).show()
            initializeLlmInference(cachedFile.absolutePath, useGpuPreference)
            return
        } else {
            if (cachedFile.exists()) {
                Log.i(TAG, "handleModelFileUri: Cached file differs or selected is new. Will overwrite/copy.")
            } else {
                Log.i(TAG, "handleModelFileUri: No cached file found. Proceeding with copy.")
            }
        }

        isCopying = true
        isLlmReady = false
        copyProgress = 0f
        llmResponse = "Starting to copy model file..."

        lifecycleScope.launch {
            var copySuccess = false
            try {
                val newCachedModelFile = copyUriToCache(uri, sourceFileLength)
                if (newCachedModelFile != null) {
                    Log.i(TAG, "handleModelFileUri: Model copied to cache: ${newCachedModelFile.absolutePath}")
                    copySuccess = true
                    initializeLlmInference(newCachedModelFile.absolutePath, useGpuPreference)
                } else {
                    llmResponse = "Error: Failed to copy model file to cache."
                    Toast.makeText(this@MainActivity, llmResponse, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleModelFileUri: Error during copy or init: ${e.message}", e)
                llmResponse = "Error: ${e.localizedMessage}"
                Toast.makeText(this@MainActivity, llmResponse, Toast.LENGTH_LONG).show()
            } finally {
                if (!copySuccess) {
                    withContext(Dispatchers.Main) { if(isCopying && !llmResponse.contains("Initializing")) isCopying = false }
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
            outputStream = FileOutputStream(outputFile)
            if (inputStream == null) {
                Log.e(TAG, "copyUriToCache: Failed to open input stream from URI.")
                return@withContext null
            }
            val fileLength = if (knownFileLength != -1L) knownFileLength else {
                try { contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L }
                catch (e: Exception) { -1L }
            }
            Log.d(TAG, "copyUriToCache: Starting copy. File size: $fileLength bytes.")
            val buffer = ByteArray(8192)
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
            return@withContext outputFile
        } catch (e: IOException) {
            Log.e(TAG, "copyUriToCache: IOException during copy: ${e.message}", e)
            withContext(Dispatchers.Main) { llmResponse = "Error copying file: ${e.localizedMessage}" }
            outputFile.delete()
            return@withContext null
        } finally {
            try { inputStream?.close() } catch (e: IOException) { Log.e(TAG, "copyUriToCache: Error closing input stream", e) }
            try { outputStream?.close() } catch (e: IOException) { Log.e(TAG, "copyUriToCache: Error closing output stream", e) }
        }
    }

    private fun initializeLlmInference(modelPath: String, attemptGpu: Boolean) {
        val modelFile = File(modelPath)
        Log.i(TAG, "initializeLlmInference: Model: \"${modelFile.name}\", Attempt GPU: $attemptGpu, Is Vision Model: $isVisionModelPreference")

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
            val visionStatusString = if (isVisionModelPreference) "(Vision Enabled)" else "(Vision Disabled)"

            Log.i(TAG, "initializeLlmInference: Starting initialization for ${modelFile.name}. Requested GPU: $attemptGpu, Vision Preference: $isVisionModelPreference")

            val optionsBuilder = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
            if (isVisionModelPreference) {
                val visionOptions = VisionModelOptions.builder().build()
                optionsBuilder
                    .setVisionModelOptions(visionOptions)
                    .setMaxNumImages(1)
                Log.d(TAG, "initializeLlmInference: VisionModelOptions added.")
            } else {
                Log.d(TAG, "initializeLlmInference: VisionModelOptions skipped as per preference.")
            }

            if (attemptGpu) {
                try {
                    Log.i(TAG, "initializeLlmInference: Attempting GPU backend $visionStatusString...")
                    withContext(Dispatchers.Main) { llmResponse = "Initializing with GPU backend $visionStatusString..." }

                    optionsBuilder.setPreferredBackend(Backend.GPU)
                    val gpuTaskOptions = optionsBuilder.build()

                    Log.d(TAG, "initializeLlmInference: GPU LlmInferenceOptions built. Calling LlmInference.createFromOptions...")
                    val gpuInference = LlmInference.createFromOptions(this@MainActivity, gpuTaskOptions)
                    Log.i(TAG, "initializeLlmInference: LlmInference.createFromOptions (GPU) call COMPLETED.")

                    llmInference = gpuInference
                    finalIsLlmReady = true
                    activeBackendInfo = "GPU"
                    finalLlmResponse = "LLM Ready ($activeBackendInfo backend $visionStatusString). (${modelFile.name})"
                    finalToastMessage = "LLM Initialized on GPU backend $visionStatusString!"
                    Log.i(TAG, "initializeLlmInference: Successfully initialized with GPU backend $visionStatusString.")
                } catch (gpuException: Exception) {
                    Log.w(TAG, "initializeLlmInference: GPU backend initialization FAILED $visionStatusString: ${gpuException.message}. Falling back to CPU.", gpuException)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "GPU backend failed $visionStatusString: ${gpuException.localizedMessage?.take(100)}. Trying CPU.", Toast.LENGTH_LONG).show()
                        llmResponse = "GPU backend failed $visionStatusString. Trying CPU..."
                    }
                }
            }

            if (!finalIsLlmReady) {
                try {
                    val fallbackType = if (attemptGpu) "(GPU fallback)" else ""
                    Log.i(TAG, "initializeLlmInference: Attempting CPU backend $fallbackType $visionStatusString...")
                    withContext(Dispatchers.Main) {
                        llmResponse = "Initializing with CPU backend $fallbackType $visionStatusString..."
                    }

                    optionsBuilder.setPreferredBackend(Backend.CPU)
                    val cpuTaskOptions = optionsBuilder.build()

                    Log.d(TAG, "initializeLlmInference: CPU LlmInferenceOptions built. Calling LlmInference.createFromOptions...")
                    val cpuInference = LlmInference.createFromOptions(this@MainActivity, cpuTaskOptions)
                    Log.i(TAG, "initializeLlmInference: LlmInference.createFromOptions (CPU) call COMPLETED.")

                    llmInference = cpuInference
                    finalIsLlmReady = true
                    activeBackendInfo = if (attemptGpu) "CPU (GPU fallback)" else "CPU"
                    finalLlmResponse = "LLM Ready ($activeBackendInfo backend $visionStatusString). (${modelFile.name})"
                    finalToastMessage = if (attemptGpu && llmResponse.contains("GPU backend failed")) "LLM Initialized on CPU backend (GPU failed) $visionStatusString."
                    else "LLM Initialized on CPU backend $visionStatusString!"
                    Log.i(TAG, "initializeLlmInference: Successfully initialized with CPU backend $visionStatusString.")
                } catch (cpuException: Exception) {
                    Log.e(TAG, "initializeLlmInference: CPU backend initialization FAILED $visionStatusString: ${cpuException.message}", cpuException)
                    finalIsLlmReady = false
                    activeBackendInfo = "Failed (CPU Attempt)"
                    finalLlmResponse = "Error initializing LLM (CPU backend failed $visionStatusString): ${cpuException.localizedMessage}"
                    finalToastMessage = "LLM Initialization Failed on CPU backend $visionStatusString."
                    withContext(Dispatchers.Main) { modelLoadedFromCache = false }
                }
            }

            withContext(Dispatchers.Main) {
                isLlmReady = finalIsLlmReady
                llmResponse = finalLlmResponse
                if (finalToastMessage.isNotEmpty()) {
                    Toast.makeText(this@MainActivity, finalToastMessage, if(finalIsLlmReady) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                }
                isCopying = false
                Log.i(TAG, "initializeLlmInference: Final state - Ready: $isLlmReady, Backend: $activeBackendInfo $visionStatusString, Response: $llmResponse")
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
            if (::llmInference.isInitialized) {
                try { llmInference.close() } catch (e:Exception) { Log.e(TAG, "Error closing LLM on delete: ${e.message}")}
            }
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
    useGpuPreference: Boolean,
    isVisionModelPreference: Boolean,
    onGpuPreferenceChanged: (Boolean) -> Unit,
    onVisionModelPreferenceChanged: (Boolean) -> Unit,
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
                } else if (llmResponse.contains("Initializing", ignoreCase = true) ||
                    llmResponse.contains("Trying CPU", ignoreCase = true) ||
                    llmResponse.contains("GPU backend failed", ignoreCase = true)) {
                    LinearProgressIndicator()
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = useGpuPreference,
                    onCheckedChange = onGpuPreferenceChanged,
                    enabled = !isCopying
                )
                Text("Use GPU (if available)")
            }
            Spacer(modifier = Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isVisionModelPreference,
                    onCheckedChange = onVisionModelPreferenceChanged,
                    enabled = !isCopying
                )
                Text("Vision Model (for image input)")
            }
            Spacer(modifier = Modifier.height(10.dp))


            Button(onClick = onSelectFileClicked, enabled = !isCopying) {
                Text(if (isCopying && llmResponse.contains("Copying model...", ignoreCase = true)) "Copying..."
                else if (isCopying) "Initializing..."
                else if (modelLoadedFromCache && isLlmReady) "Select Different Model"
                else "Select Model File (.task)")
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
            llmResponse = "Preview: Select a model file.",
            isLlmReady = false,
            isCopying = false,
            copyProgress = 0.0f,
            modelLoadedFromCache = false,
            useGpuPreference = false,
            isVisionModelPreference = false,
            onGpuPreferenceChanged = {},
            onVisionModelPreferenceChanged = {},
            onSelectFileClicked = {},
            onGenerateTestClicked = {},
            onClearCacheAndSelectNewClicked = {}
        )
    }
}
