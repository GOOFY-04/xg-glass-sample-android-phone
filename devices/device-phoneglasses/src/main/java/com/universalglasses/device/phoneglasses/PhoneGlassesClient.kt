package com.universalglasses.device.phoneglasses

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.universalglasses.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * PhoneGlasses implementation of [GlassesClient] — uses a phone's built-in
 * hardware (camera, microphone, speaker) to simulate smart glasses, with
 * on-device AI inference via JNI-embedded llama.cpp and optional cloud API
 * fallback.
 *
 * This is a full five-primitive implementation:
 *   capturePhoto / display / playAudio / startMicrophone / aiGenerate
 *
 * ## AI Backend
 *
 * The fifth primitive [aiGenerate] supports three paths:
 * 1. **Cloud API** (OpenAI / Anthropic) — when [AiGenerateOptions.cloudConfig] is set
 * 2. **JNI local** — llama.cpp models loaded on-device via [NativeEngine]
 *
 * Model files (.gguf + optional mmproj) are expected under
 * `/sdcard/PhoneGlassesModels/`.
 *
 * @param activity      Host Activity (for CameraX lifecycle binding + TTS)
 * @param displaySink   Callback to render display text in host UI
 */
class PhoneGlassesClient(
    private val activity: AppCompatActivity,
    private val displaySink: ((String) -> Unit)? = null,
) : GlassesClient {

    override val model: GlassesModel = GlassesModel.PHONEGLASSES

    override val capabilities: DeviceCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = true,
        canRecordAudio = true,
        canPlayTts = true,
        canPlayAudioBytes = true,
        supportsTapEvents = false,
        supportsStreamingTextUpdates = false,
    )

    // ── State ────────────────────────────────────────────────────────────

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _events = MutableSharedFlow<GlassesEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<GlassesEvent> = _events

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    @Volatile private var activeMic: MicrophoneSession? = null
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var activePlayer: MediaPlayer? = null

    // ── AI state ─────────────────────────────────────────────────────────

    /** JNI engine (lazy-init on first connect). */
    private val nativeEngine = NativeEngine()

    /** OkHttp client for cloud API calls. */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** Stored settings map for building AiCloudConfig automatically. */
    @Volatile var appliedSettings: Map<String, String> = emptyMap()

    // ═══════════════════════════════════════════════════════════════════════
    // Connection
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun connect(): Result<Unit> {
        if (_state.value is ConnectionState.Connected || _state.value is ConnectionState.Connecting) {
            return Result.success(Unit)
        }
        _state.value = ConnectionState.Connecting
        emitLog("PhoneGlasses: connecting ...")

        return try {
            ensureCameraUseCase(jpegQuality = 90)

            // Try JNI init (non-fatal — cloud API still works without it)
            nativeEngine.init().fold(
                onSuccess = { emitLog("PhoneGlasses: JNI engine loaded") },
                onFailure = { emitWarn("PhoneGlasses: JNI not available (${it.message}), cloud-only mode") }
            )

            _state.value = ConnectionState.Connected
            emitLog("PhoneGlasses: connected ✓")
            Result.success(Unit)
        } catch (ce: CancellationException) {
            _state.value = ConnectionState.Disconnected
            Result.failure(ce)
        } catch (e: Exception) {
            val err = (e as? GlassesError) ?: GlassesError.Transport("PhoneGlasses connect failed: ${e.message}", e)
            _state.value = ConnectionState.Error(err)
            Result.failure(err)
        }
    }

    override suspend fun disconnect() {
        emitLog("PhoneGlasses: disconnecting ...")
        try { activeMic?.stop() } catch (_: Exception) {}
        activeMic = null
        try { activePlayer?.release() } catch (_: Exception) {}
        activePlayer = null
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null

        nativeEngine.unloadModel()
        nativeEngine.release()

        withContext(Dispatchers.Main) {
            cameraProvider?.unbindAll()
            imageCapture = null
        }
        _state.value = ConnectionState.Disconnected
        emitLog("PhoneGlasses: disconnected")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ① capturePhoto
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        if (!hasCameraPermission()) {
            emitWarn("PhoneGlasses: CAMERA permission missing")
            return Result.failure(GlassesError.PermissionDenied)
        }
        return try {
            val quality = (options.quality ?: 90).coerceIn(1, 100)
            ensureCameraUseCase(jpegQuality = quality)
            val ic = imageCapture ?: return Result.failure(GlassesError.Busy)

            val cacheFile = File(activity.cacheDir, "pg_capture_${System.currentTimeMillis()}.jpg")
            val r = withTimeout(options.timeoutMs) {
                suspendCancellableCoroutine<Result<CapturedImage>> { cont ->
                    val out = ImageCapture.OutputFileOptions.Builder(cacheFile).build()
                    val executor = ContextCompat.getMainExecutor(activity)
                    ic.takePicture(out, executor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            try {
                                val bytes = cacheFile.readBytes()
                                val (w, h) = decodeJpegSize(bytes)
                                cacheFile.delete()
                                cont.resume(Result.success(CapturedImage(
                                    jpegBytes = bytes, width = w, height = h,
                                    rotationDegrees = null, sourceModel = model,
                                )))
                            } catch (e: Exception) {
                                cacheFile.delete()
                                cont.resume(Result.failure(e))
                            }
                        }
                        override fun onError(exception: ImageCaptureException) {
                            cacheFile.delete()
                            cont.resume(Result.failure(exception))
                        }
                    })
                }
            }
            emitLog("PhoneGlasses: capturePhoto => ${r.isSuccess}")
            r
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ② display
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        return try {
            withContext(Dispatchers.Main) {
                displaySink?.invoke(text) ?: emitWarn("PhoneGlasses: display ignored (no sink)")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ③ playAudio
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> {
        return when (source) {
            is AudioSource.Tts -> playTts(source, options)
            is AudioSource.RawBytes -> playRawBytes(source, options)
        }
    }

    private suspend fun playTts(source: AudioSource.Tts, options: PlayAudioOptions): Result<Unit> {
        val content = source.text.trim()
        if (content.isEmpty()) return Result.success(Unit)
        return try {
            val engine = ensureTts()
            val utteranceId = "pg-${System.currentTimeMillis()}"
            val done = CompletableDeferred<Unit>()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                @Deprecated("Deprecated in API 21")
                override fun onError(id: String?) {
                    if (id == utteranceId) done.completeExceptionally(GlassesError.Transport("TTS error"))
                }
                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) done.completeExceptionally(GlassesError.Transport("TTS error: $errorCode"))
                }
                override fun onDone(id: String?) {
                    if (id == utteranceId) done.complete(Unit)
                }
            })
            withContext(Dispatchers.Main) {
                val rate = options.speechRate
                if (rate != null) engine.setSpeechRate(rate.coerceIn(0.1f, 4.0f))
                val q = if (options.interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val r = engine.speak(content, q, null, utteranceId)
                if (r != TextToSpeech.SUCCESS) throw GlassesError.Transport("TTS speak() returned $r")
            }
            withTimeoutOrNull(30_000) { done.await() }
            emitLog("PhoneGlasses: playAudio(TTS) => done")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("TTS failed: ${e.message}", e))
        }
    }

    private suspend fun playRawBytes(source: AudioSource.RawBytes, options: PlayAudioOptions): Result<Unit> {
        val data = source.data
        if (data.isEmpty()) return Result.success(Unit)
        return try {
            if (options.interrupt) {
                try { activePlayer?.release() } catch (_: Exception) {}
                activePlayer = null
            }
            val pcm = source.pcmFormat
            if (pcm != null) playPcm(data, pcm)
            else playEncoded(data)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Audio playback failed: ${e.message}", e))
        }
    }

    private suspend fun playPcm(data: ByteArray, pcm: PcmFormat) {
        val sampleRate = pcm.sampleRateHz
        val channels = pcm.channelCount
        val channelMask = if (channels <= 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val enc = when (pcm.encoding) {
            AudioEncoding.PCM_S16_LE -> AudioFormat.ENCODING_PCM_16BIT
            AudioEncoding.PCM_S8 -> AudioFormat.ENCODING_PCM_8BIT
            AudioEncoding.OPUS -> throw GlassesError.Unsupported("OPUS not supported")
        }
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, enc).coerceAtLeast(1024)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate).setChannelMask(channelMask).setEncoding(enc).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf).build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release(); throw GlassesError.Transport("AudioTrack not initialized")
        }
        try {
            track.play()
            var written = 0
            while (written < data.size) {
                val n = track.write(data, written, minOf(4096, data.size - written))
                if (n <= 0) break; written += n
            }
            val bps = if (enc == AudioFormat.ENCODING_PCM_16BIT) 2 else 1
            val bpsTotal = sampleRate.toLong() * channels * bps
            if (bpsTotal > 0) delay(data.size * 1000L / bpsTotal + 200L)
        } finally {
            runCatching { track.stop() }; track.release()
        }
    }

    private suspend fun playEncoded(data: ByteArray) {
        val tmpFile = File(activity.cacheDir, "pg_audio_${System.currentTimeMillis()}.tmp")
        try {
            tmpFile.writeBytes(data)
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val mp = MediaPlayer()
                    activePlayer = mp
                    mp.setDataSource(tmpFile.absolutePath)
                    mp.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    mp.setOnCompletionListener {
                        mp.release(); activePlayer = null; tmpFile.delete()
                        if (cont.isActive) cont.resume(Unit)
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        mp.release(); activePlayer = null; tmpFile.delete()
                        if (cont.isActive) cont.resumeWithException(
                            GlassesError.Transport("MediaPlayer error: what=$what extra=$extra"))
                        true
                    }
                    mp.prepare(); mp.start()
                    cont.invokeOnCancellation {
                        mp.release(); activePlayer = null; tmpFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            tmpFile.delete(); throw e
        }
    }

    private suspend fun ensureTts(): TextToSpeech {
        tts?.let { return it }
        return withContext(Dispatchers.Main) {
            tts?.let { return@withContext it }
            suspendCancellableCoroutine { cont ->
                var engine: TextToSpeech? = null
                engine = TextToSpeech(activity) { status ->
                    val inst = engine ?: return@TextToSpeech
                    if (!cont.isActive) return@TextToSpeech
                    if (status != TextToSpeech.SUCCESS) {
                        try { inst.shutdown() } catch (_: Exception) {}
                        cont.resumeWithException(GlassesError.Transport("TextToSpeech init failed: $status"))
                        return@TextToSpeech
                    }
                    // Set Chinese locale for Chinese Android phones
                    val chineseResult = inst.setLanguage(java.util.Locale.CHINESE)
                    if (chineseResult == TextToSpeech.LANG_MISSING_DATA || chineseResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        inst.setLanguage(java.util.Locale.getDefault())
                    }
                    tts = inst
                    cont.resume(inst)
                }
                cont.invokeOnCancellation { try { engine?.shutdown() } catch (_: Exception) {} }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ④ startMicrophone
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)
        if (!hasRecordAudioPermission()) return Result.failure(GlassesError.PermissionDenied)
        if (activeMic != null) return Result.failure(GlassesError.Busy)

        val encoding = when (options.preferredEncoding) {
            AudioEncoding.PCM_S16_LE -> AudioEncoding.PCM_S16_LE
            AudioEncoding.PCM_S8 -> AudioEncoding.PCM_S8
            AudioEncoding.OPUS -> return Result.failure(GlassesError.Unsupported("OPUS not supported"))
        }
        val sampleRate = options.preferredSampleRateHz ?: 16_000
        val channels = options.preferredChannelCount ?: 1
        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_IN_MONO
            2 -> AudioFormat.CHANNEL_IN_STEREO
            else -> return Result.failure(GlassesError.Unsupported("channelCount=$channels"))
        }
        val audioFormat = when (encoding) {
            AudioEncoding.PCM_S16_LE -> AudioFormat.ENCODING_PCM_16BIT
            AudioEncoding.PCM_S8 -> AudioFormat.ENCODING_PCM_8BIT
            AudioEncoding.OPUS -> AudioFormat.ENCODING_PCM_16BIT
        }

        return try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBuf <= 0) return Result.failure(GlassesError.Transport("AudioRecord.getMinBufferSize failed"))
            val record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, minBuf * 2)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release(); return Result.failure(GlassesError.Transport("AudioRecord not initialized"))
            }
            val fmt = AudioFormat(encoding = encoding, sampleRateHz = sampleRate, channelCount = channels)
            val shared = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 64)
            val running = AtomicBoolean(true)
            val seq = AtomicLong(0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            val session = object : MicrophoneSession {
                override val format: AudioFormat = fmt
                override val audio: Flow<AudioChunk> = shared
                override suspend fun stop() {
                    if (!running.compareAndSet(true, false)) return
                    try { record.stop() } catch (_: Exception) {}
                    try { record.release() } catch (_: Exception) {}
                    scope.cancel(); activeMic = null
                    shared.tryEmit(AudioChunk(bytes = ByteArray(0), format = fmt, sequence = seq.incrementAndGet(), endOfStream = true))
                }
            }
            record.startRecording()
            scope.launch {
                val buf = ByteArray(minBuf)
                while (running.get()) {
                    val n = try { record.read(buf, 0, buf.size) } catch (_: Exception) { break }
                    if (n > 0) shared.tryEmit(AudioChunk(bytes = buf.copyOfRange(0, n), format = fmt, sequence = seq.incrementAndGet()))
                    else if (n < 0) break
                }
            }
            activeMic = session
            emitLog("PhoneGlasses: startMicrophone => ok ($sampleRate Hz, $channels ch, $encoding)")
            Result.success(session)
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Mic failed: ${e.message}", e))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ⑤ aiGenerate  — 第五原语
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun aiGenerate(
        prompt: String,
        imageBase64: String?,
        options: AiGenerateOptions,
    ): Result<String> {
        // 1. Try cloud API (explicit or from stored settings)
        val cloud = options.cloudConfig ?: AiCloudConfig.fromSettings(appliedSettings)
        if (cloud != null) {
            return callCloudApi(prompt, imageBase64, cloud, options)
        }

        // 2. Fall back to JNI local inference
        return aiGenerateLocal(prompt, imageBase64, options)
    }

    override suspend fun aiGenerateTwoStage(
        extractPrompt: String,
        buildSolvePrompt: (String) -> String,
        imageBase64: String,
    ): Result<String> {
        // Stage 1: vision model extracts text
        val extractResult = aiGenerate(extractPrompt, imageBase64, AiGenerateOptions(maxTokens = 256))
        val extracted = extractResult.getOrElse { return Result.failure(it) }

        // Stage 2: text model solves
        val solvePrompt = buildSolvePrompt(extracted)
        return aiGenerate(solvePrompt, null, AiGenerateOptions(maxTokens = 1024))
    }

    // ── Cloud API ────────────────────────────────────────────────────────

    private suspend fun callCloudApi(
        prompt: String,
        imageBase64: String?,
        config: AiCloudConfig,
        options: AiGenerateOptions,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Auto-append /v1 if missing
            var base = config.apiUrl.trimEnd('/')
            if (!base.endsWith("/v1")) base += "/v1"

            val response = when (config.apiMode.lowercase()) {
                "anthropic" -> callAnthropicApi(base, prompt, imageBase64, config)
                else -> callOpenAiApi(base, prompt, imageBase64, config)
            }
            emitLog("PhoneGlasses: cloud AI (${config.apiMode}) => ${response.length} chars")
            Result.success(response)
        } catch (e: Exception) {
            val msg = (e as? GlassesError)?.message ?: "Cloud API error: ${e.message}"
            emitWarn(msg)

            // Auto-fallback: try the other API format on image-related errors
            val errMsg = e.message?.lowercase() ?: ""
            if (imageBase64 != null && (errMsg.contains("image") || errMsg.contains("not support") || errMsg.contains("404"))) {
                emitLog("PhoneGlasses: auto-fallback to alternate API format")
                val otherMode = if (config.apiMode.lowercase() == "openai") "anthropic" else "openai"
                val altConfig = config.copy(apiMode = otherMode)
                return@withContext callCloudApi(prompt, imageBase64, altConfig, options)
            }
            Result.failure(e)
        }
    }

    private fun callOpenAiApi(baseUrl: String, prompt: String, imageBase64: String?, config: AiCloudConfig): String {
        val json = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", 2048)
            put("temperature", 0.7)
            val messages = JSONArray()
            val userMsg = JSONObject()
            val content = JSONArray()
            content.put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            if (imageBase64 != null) {
                content.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$imageBase64")
                    })
                })
            }
            userMsg.put("role", "user")
            userMsg.put("content", content)
            messages.put(userMsg)
            put("messages", messages)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body)
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $responseBody")

        val respJson = JSONObject(responseBody)
        return respJson.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    private fun callAnthropicApi(baseUrl: String, prompt: String, imageBase64: String?, config: AiCloudConfig): String {
        val json = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", 2048)
            val messages = JSONArray()
            val userMsg = JSONObject().apply {
                put("role", "user")
                val content = JSONArray()
                if (imageBase64 != null) {
                    content.put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", imageBase64)
                        })
                    })
                }
                content.put(JSONObject().apply {
                    put("type", "text")
                    put("text", prompt)
                })
                put("content", content)
            }
            messages.put(userMsg)
            put("messages", messages)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/messages")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body)
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $responseBody")

        val respJson = JSONObject(responseBody)
        return respJson.getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
            .trim()
    }

    // ── JNI Local Inference ──────────────────────────────────────────────

    private suspend fun aiGenerateLocal(
        prompt: String,
        imageBase64: String?,
        options: AiGenerateOptions,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!nativeEngine.isReady) {
                // Try loading default model
                val modelDir = File("/sdcard/PhoneGlassesModels")
                val modelFile = File(modelDir, "moondream.gguf")
                val mmprojFile = File(modelDir, "moondream-mmproj.gguf")
                if (modelFile.exists()) {
                    emitLog("PhoneGlasses: loading model ${modelFile.name} ...")
                    nativeEngine.loadModel(modelFile.absolutePath, nCtx = 1536)
                        .getOrThrow()
                    if (mmprojFile.exists()) {
                        nativeEngine.loadVisionModel(mmprojFile.absolutePath)
                    }
                } else {
                    return@withContext Result.failure(GlassesError.Unsupported(
                        "No local model found. Place .gguf files in /sdcard/PhoneGlassesModels/"))
                }
            }

            val maxTokens = options.maxTokens
            val result: String = if (imageBase64 != null) {
                // Decode base64 → RGB for JNI
                val jpegBytes = Base64.decode(imageBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    ?: return@withContext Result.failure(GlassesError.Transport("Failed to decode image"))
                val w = bitmap.width; val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                val rgb = ByteArray(w * h * 3)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    rgb[i * 3] = ((p shr 16) and 0xFF).toByte()
                    rgb[i * 3 + 1] = ((p shr 8) and 0xFF).toByte()
                    rgb[i * 3 + 2] = (p and 0xFF).toByte()
                }
                bitmap.recycle()
                nativeEngine.generateWithImage(prompt, rgb, w, h, maxTokens).getOrThrow()
            } else {
                nativeEngine.generate(prompt, maxTokens).getOrThrow()
            }

            emitLog("PhoneGlasses: local AI => ${result.length} chars")
            Result.success(result.trim())
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Local AI failed: ${e.message}", e))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Camera helpers
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun ensureCameraUseCase(jpegQuality: Int) {
        if (_state.value is ConnectionState.Error) return
        if (imageCapture != null && cameraProvider != null) return
        withContext(Dispatchers.Main) {
            val provider = cameraProvider ?: run {
                val p = awaitCameraProvider(); cameraProvider = p; p
            }
            provider.unbindAll()
            val ic = ImageCapture.Builder()
                .setJpegQuality(jpegQuality.coerceIn(1, 100)).build()
            try {
                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, ic)
            } catch (_: Throwable) {
                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, ic)
            }
            imageCapture = ic
        }
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider {
        val future = ProcessCameraProvider.getInstance(activity)
        return suspendCancellableCoroutine { cont ->
            future.addListener({
                try { cont.resume(future.get()) }
                catch (e: Exception) { cont.resumeWithException(e) }
            }, ContextCompat.getMainExecutor(activity))
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasRecordAudioPermission() =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun decodeJpegSize(bytes: ByteArray): Pair<Int?, Int?> {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            (if (opts.outWidth > 0) opts.outWidth else null) to
            (if (opts.outHeight > 0) opts.outHeight else null)
        } catch (_: Throwable) { null to null }
    }

    // ── Log helpers ──────────────────────────────────────────────────────

    private fun emitLog(msg: String) { _events.tryEmit(GlassesEvent.Log(msg)) }
    private fun emitWarn(msg: String) { _events.tryEmit(GlassesEvent.Warning(msg)) }

    // ═══════════════════════════════════════════════════════════════════════
    // NativeEngine — JNI bridge to libnative_llama.so
    // ═══════════════════════════════════════════════════════════════════════

    private class NativeEngine {
        private var initialized = false

        val isReady: Boolean get() = initialized && nativeIsModelLoaded()

        // JNI declarations
        private external fun nativeInit(): Boolean
        private external fun nativeGetSystemInfo(): String
        private external fun nativeLoadModel(modelPath: String, nCtx: Int, nGpuLayers: Int): Int
        private external fun nativeLoadVisionModel(mmprojPath: String): Int
        private external fun nativeGenerate(prompt: String, maxTokens: Int): String
        private external fun nativeGenerateWithImage(
            prompt: String, imageRgb: ByteArray, width: Int, height: Int,
            mmprojPath: String, maxTokens: Int,
        ): String
        private external fun nativeUnloadModel()
        private external fun nativeRelease()
        private external fun nativeIsModelLoaded(): Boolean
        private external fun nativeGetModelInfo(): String

        companion object {
            private const val TAG = "PhGlass/JNI"
        }

        fun init(): Result<Unit> {
            return try {
                Log.i(TAG, "Loading libnative_llama.so ...")
                System.loadLibrary("native_llama")
                Log.i(TAG, "✅ native_llama loaded")
                initialized = true
                nativeInit()
                Log.i(TAG, nativeGetSystemInfo().take(120))
                Result.success(Unit)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ ${e.message}", e)
                initialized = false
                Result.failure(RuntimeException("Native load failed: ${e.message}"))
            } catch (e: Exception) {
                Log.e(TAG, "❌ ${e.message}", e)
                initialized = false
                Result.failure(e)
            }
        }

        fun loadModel(path: String, nCtx: Int = 2048): Result<String> {
            if (!initialized) return Result.failure(RuntimeException("Not initialized"))
            return try {
                val code = nativeLoadModel(path, nCtx, 0)
                if (code == 0) Result.success(nativeGetModelInfo())
                else Result.failure(RuntimeException("Load failed (code=$code): ${nativeGetModelInfo()}"))
            } catch (e: Exception) { Result.failure(e) }
        }

        fun loadVisionModel(mmprojPath: String): Result<Unit> {
            if (!initialized) return Result.failure(RuntimeException("Not initialized"))
            return try {
                val code = nativeLoadVisionModel(mmprojPath)
                if (code == 0) Result.success(Unit)
                else Result.failure(RuntimeException("Vision load failed (code=$code)"))
            } catch (e: Exception) { Result.failure(e) }
        }

        fun generate(prompt: String, maxTokens: Int = 512): Result<String> {
            if (!initialized || !nativeIsModelLoaded())
                return Result.failure(RuntimeException("Model not loaded"))
            return try {
                val result = nativeGenerate(prompt, maxTokens)
                if (result.startsWith("[ERROR]")) Result.failure(RuntimeException(result.removePrefix("[ERROR] ")))
                else Result.success(result)
            } catch (e: Exception) { Result.failure(e) }
        }

        fun generateWithImage(
            prompt: String, imageRgb: ByteArray, width: Int, height: Int, maxTokens: Int = 256,
        ): Result<String> {
            if (!initialized || !nativeIsModelLoaded())
                return Result.failure(RuntimeException("Model not loaded"))
            return try {
                val result = nativeGenerateWithImage(prompt, imageRgb, width, height, "", maxTokens)
                if (result.startsWith("[ERROR]")) Result.failure(RuntimeException(result.removePrefix("[ERROR] ")))
                else Result.success(result)
            } catch (e: Exception) { Result.failure(e) }
        }

        fun unloadModel() { if (initialized) nativeUnloadModel() }
        fun release() { if (initialized) { nativeRelease(); initialized = false } }
    }
}
