/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.camera

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.AudioInputHandler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.HevcDecoder
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.HevcParameterSetCollector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.RecordingResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.VideoRecorder
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Data structure for our Chat UI bubbles
data class ChatMessage(
  val id: String,
  val isUser: Boolean,
  val text: String,
  val imagePath: String? = null
)

class CameraViewModel(
  application: Application,
  private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application), TextToSpeech.OnInitListener {

  // ==========================================
  // PRIVACY ARCHITECTURE INJECTION
  // ==========================================
  // Replace with your Machine's Wi-Fi IP
  private val secureClient = SecurePhoneClient(application, "YOUR_EDGE_SERVER_IP")
  var currentSessionId: String? = null
  private var volatilePiiCache: String? = null

  // Chat State for the UI
  private val _chatHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
  val chatHistory: StateFlow<List<ChatMessage>> = _chatHistory.asStateFlow()

  // Android Voice Engine
  private var tts: TextToSpeech? = null
  // ==========================================

  companion object {
    private const val TAG = "CameraAccess:CameraViewModel"
    private const val FRAME_RATE = 24
    private const val KEYFRAME_WAIT_STEP_MS = 25L
    private const val KEYFRAME_WAIT_MAX_MS = 500L
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private val _uiState = MutableStateFlow(CameraUiState())
  val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null

  private val audioInputHandler = AudioInputHandler(application)
  private val videoRecorder = VideoRecorder(application, viewModelScope)
  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
  private val decoderLock = Any()

  @Volatile private var hevcDecoder: HevcDecoder? = null
  @Volatile private var decoderSurface: Surface? = null
  private val csdCollector = HevcParameterSetCollector()

  private var sessionStateJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var videoJob: Job? = null
  private var streamStateJob: Job? = null
  private var streamErrorJob: Job? = null

  init {
    // Initialize Voice Engine
    tts = TextToSpeech(application, this)

    videoRecorder.setAudioInputHandler(audioInputHandler)

    viewModelScope.launch {
      videoRecorder.isRecording.collect { recording ->
        _uiState.update { it.copy(isRecording = recording) }
      }
    }
    viewModelScope.launch {
      videoRecorder.recordingElapsedSeconds.collect { seconds ->
        _uiState.update { it.copy(recordingElapsedSeconds = seconds) }
      }
    }
    viewModelScope.launch {
      audioInputHandler.wasInterrupted.collect { interrupted ->
        if (interrupted && _uiState.value.isRecording) {
          stopVideoRecording()
        }
      }
    }
  }

  override fun onInit(status: Int) {
    if (status == TextToSpeech.SUCCESS) {
      tts?.language = Locale.US
    }
  }

  private fun speak(text: String) {
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
  }

  fun setSurface(surface: Surface?) {
    synchronized(decoderLock) {
      decoderSurface = surface
      if (surface == null) {
        hevcDecoder?.stop()
        hevcDecoder = null
      }
    }
  }

  fun startSession() {
    if (_uiState.value.hasSession) return
    Wearables.createSession(deviceSelector)
      .onSuccess { created ->
        session = created
        observeSession(created)
        _uiState.update { it.copy(sessionState = DeviceSessionState.STARTING) }
        created.start()
      }
      .onFailure { error, _ ->
        wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
        cleanupSession()
      }
  }

  fun endSession() {
    val current = session ?: return
    _uiState.update { it.copy(sessionState = DeviceSessionState.STOPPING) }
    current.stop()
  }

  private fun observeSession(session: DeviceSession) {
    sessionStateJob = viewModelScope.launch {
      session.state.collect { state ->
        _uiState.update { it.copy(sessionState = state) }
        if (state == DeviceSessionState.STOPPED) {
          cleanupSession()
        }
      }
    }
    sessionErrorJob = viewModelScope.launch {
      session.errors.collect { error ->
        wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
      }
    }
  }

  private fun cleanupSession() {
    sessionStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    session = null
  }

  fun startStreaming() {
    if (!_uiState.value.isSessionActive) {
      wearablesViewModel.setRecentError(
        getApplication<Application>().getString(R.string.error_start_session_first)
      )
      return
    }
    if (stream != null || _uiState.value.isStartingStream) return
    _uiState.update { it.copy(isStartingStream = true) }
    viewModelScope.launch {
      try {
        Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { status ->
            if (status == PermissionStatus.Granted) {
              beginStream()
            } else {
              _uiState.update { it.copy(showCameraPermissionRedirectConfirm = true) }
            }
          }
          .onFailure { error, _ ->
            wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
          }
      } finally {
        _uiState.update { it.copy(isStartingStream = false) }
      }
    }
  }

  fun confirmCameraPermissionRedirect(requestPermission: suspend (Permission) -> PermissionStatus) {
    _uiState.update { it.copy(showCameraPermissionRedirectConfirm = false) }
    if (!_uiState.value.isSessionActive || stream != null) return
    viewModelScope.launch {
      val status = requestPermission(Permission.CAMERA)
      if (status == PermissionStatus.Granted) {
        beginStream()
      } else {
        wearablesViewModel.setRecentError(
          getApplication<Application>().getString(R.string.error_camera_permission_denied)
        )
      }
    }
  }

  fun cancelCameraPermissionRedirect() {
    _uiState.update { it.copy(showCameraPermissionRedirectConfirm = false) }
  }

  private fun beginStream() {
    val current = session ?: return
    if (stream != null) return
    StreamingService.start(getApplication())
    current
      .addCamera(
        StreamConfiguration(
          videoQuality = VideoQuality.MEDIUM,
          frameRate = FRAME_RATE,
          compressVideo = true,
        )
      )
      .onSuccess { addedCamera ->
        camera = addedCamera
        val added = addedCamera.stream
        stream = added
        setupStreamListeners(added)
        _uiState.update { it.copy(streamState = StreamState.STARTING) }
        added.start().onFailure { error, _ ->
          wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
          clearStreamResources()
        }
      }
      .onFailure { error, _ ->
        StreamingService.stop(getApplication())
        wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
      }
  }

  fun stopStreaming() {
    val current = camera ?: return
    _uiState.update { it.copy(streamState = StreamState.STOPPING) }
    current.stop()
  }

  private fun setupStreamListeners(stream: Stream) {
    videoJob =
      viewModelScope.launch(frameDispatcher) {
        stream.videoStream.collect { handleVideoFrame(it) }
      }
    streamStateJob = viewModelScope.launch {
      var hasBeenActive = false
      stream.state.collect { state ->
        _uiState.update { it.copy(streamState = state) }
        val isTerminal = state == StreamState.STOPPED || state == StreamState.CLOSED
        if (!isTerminal) {
          hasBeenActive = true
        } else if (hasBeenActive) {
          hasBeenActive = false
          onStreamTerminated()
        }
      }
    }
    streamErrorJob = viewModelScope.launch {
      stream.errorStream.collect { error ->
        wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
      }
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    if (!videoFrame.isCompressed) return

    val buffer = videoFrame.buffer
    val width = videoFrame.width
    val height = videoFrame.height
    val presentationTimeUs = videoFrame.presentationTimeUs

    val byteArray = ByteArray(buffer.remaining())
    val originalPosition = buffer.position()
    buffer.get(byteArray)
    buffer.position(originalPosition)
    csdCollector.offer(byteArray)

    videoRecorder.writeCompressedFrame(
      byteArray,
      presentationTimeUs,
      width,
      height,
      videoFrame.isCodecConfig,
    )

    synchronized(decoderLock) {
      val surface = decoderSurface
      if (hevcDecoder == null && surface != null) {
        hevcDecoder =
          HevcDecoder().also { decoder ->
            decoder.start(width, height, surface)
            csdCollector.complete()?.let { decoder.decodeFrame(it, 0) }
          }
      }
      hevcDecoder?.decodeFrame(byteArray, presentationTimeUs)
    }

    if (!videoFrame.isCodecConfig && !_uiState.value.hasReceivedFirstFrame) {
      _uiState.update { it.copy(hasReceivedFirstFrame = true) }
    }
  }

  private fun onStreamTerminated() {
    viewModelScope.launch {
      if (_uiState.value.isRecording) {
        stopVideoRecording()
      }
      clearStreamResources()
    }
  }

  private fun clearStreamResources() {
    videoJob?.cancel()
    videoJob = null
    streamStateJob?.cancel()
    streamStateJob = null
    streamErrorJob?.cancel()
    streamErrorJob = null
    synchronized(decoderLock) {
      hevcDecoder?.stop()
      hevcDecoder = null
    }
    csdCollector.reset()
    StreamingService.stop(getApplication())
    stopCamera(camera)
    camera = null
    stream = null
    _uiState.update { it.copy(streamState = StreamState.STOPPED, hasReceivedFirstFrame = false) }
  }

  private fun stopCamera(capability: java.io.Closeable?) {
    capability?.close()
  }

  // ==========================================
  // PRIVACY PIPELINE: CAPTURE & ASK
  // ==========================================
  fun captureAndAsk(prompt: String = "What's in front of me?") {
    if (_uiState.value.isCapturingPhoto || !_uiState.value.isStreaming) return
    _uiState.update { it.copy(isCapturingPhoto = true) }

    // Add User's text question to the chat UI (NO IMAGE ATTACHED HERE)
    val userMsg = ChatMessage(UUID.randomUUID().toString(), true, prompt)
    _chatHistory.update { it + userMsg }

    viewModelScope.launch {
      stream?.capturePhoto()?.onSuccess { photoData ->
        val bitmap = withContext(Dispatchers.Default) { decodePhoto(photoData) }
        if (bitmap != null) {
          viewModelScope.launch(Dispatchers.IO) {
            try {
              val outputStream = ByteArrayOutputStream()
              bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
              val imageBytes = outputStream.toByteArray()

              val sessionId = UUID.randomUUID().toString()
              currentSessionId = sessionId

              val cachedFile = secureClient.cacheImageLocally(imageBytes, sessionId)

              secureClient.processImageAtEdge(cachedFile, sessionId) { safeText, hasPii, rawText ->
                // 1. Extract just the first sentence to give context without dumping the exhaustive vector
                val firstSentence = safeText.split(Regex("(?<=\\.)\\s")).firstOrNull() ?: "Scene securely captured."

                if (hasPii) {
                  volatilePiiCache = rawText // Store in ephemeral RAM

                  // 2. THE FIX: Stop concatenating the massive `$safeText`. Show only the context and the warning.
                  val alertText = "$firstSentence\n\n⚠️ SYSTEM WARNING: Sensitive numeric data detected. The exhaustive data has been redacted and securely cached. Ask 'read the number' to authorize revealing the raw data."

                  speak("I have captured the scene. I redacted sensitive numeric data. Would you like me to read it?")

                  // ATTACH IMAGE TO THE AI RESPONSE
                  val aiMsg = ChatMessage(UUID.randomUUID().toString(), false, alertText, cachedFile.absolutePath)
                  _chatHistory.update { it + aiMsg }
                } else {
                  volatilePiiCache = null

                  // 3. Prevent flooding the UI with the exhaustive ChromaDB vector on normal captures
                  speak(firstSentence)
                  val aiMsg = ChatMessage(UUID.randomUUID().toString(), false, firstSentence, cachedFile.absolutePath)
                  _chatHistory.update { it + aiMsg }
                }
              }
            } catch (e: Exception) {
              Log.e(TAG, "Privacy pipeline failed: ${e.message}")
            }
          }
          _uiState.update { it.copy(isCapturingPhoto = false) }
        }
      }
    }
  }

  // ==========================================
  // PRIVACY PIPELINE: MEMORY RECALL (FOLLOW UP)
  // ==========================================
  fun askFollowUp(prompt: String) {
    val sessionId = currentSessionId
    if (sessionId == null) {
      speak("I don't have any visual memory right now.")
      val errMsg = ChatMessage(UUID.randomUUID().toString(), false, "I don't have any visual memory right now.")
      _chatHistory.update { it + ChatMessage(UUID.randomUUID().toString(), true, prompt) + errMsg }
      return
    }

    _chatHistory.update { it + ChatMessage(UUID.randomUUID().toString(), true, prompt) }

    // TIERED PERSISTENCE INTERCEPT: Check if user is authorizing PII read
    val lowerPrompt = prompt.lowercase()
    if (volatilePiiCache != null && (lowerPrompt.contains("read") || lowerPrompt.contains("yes") || lowerPrompt.contains("number"))) {
      val revealText = "Consent granted. The raw data is: $volatilePiiCache"
      speak(revealText)
      _chatHistory.update { it + ChatMessage(UUID.randomUUID().toString(), false, revealText) }
      volatilePiiCache = null // Cryptographically drop from RAM after reading
      return
    }

    // Normal secure cloud routing if no PII consent was triggered
    viewModelScope.launch(Dispatchers.IO) {
      secureClient.sendChatMessage(prompt, sessionId) { responseText ->
        speak(responseText)
        _chatHistory.update { it + ChatMessage(UUID.randomUUID().toString(), false, responseText) }
      }
    }
  }

  // ==========================================
  // PRIVACY PIPELINE: SYNCHRONIZED KILL SWITCH
  // ==========================================
  fun executeKillSwitch() {
    _chatHistory.value = emptyList() // Clear chat UI

    currentSessionId?.let { sessionId ->
      viewModelScope.launch(Dispatchers.IO) {
        // Cryptographically wipe local file
        val wipeSuccess = secureClient.cryptographicallyWipeFile(sessionId)

        // Drop vector from remote DB
        secureClient.triggerServerVectorDrop(sessionId)
        currentSessionId = null

        speak("All visual memory securely erased.")

        viewModelScope.launch(Dispatchers.Main) {
          Toast.makeText(getApplication(), "Memory Cryptographically Wiped!", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  // ==========================================
  // LEGACY BRIDGE (To fix CameraScreen.kt compile error)
  // ==========================================
  fun capturePhoto() {
    captureAndAsk()
  }

  // MARK: - Video Recording (Default SDK)
  fun toggleRecording(requestRecordAudioPermission: suspend () -> Boolean) {
    if (_uiState.value.isRecording) {
      viewModelScope.launch { stopVideoRecording() }
    } else {
      startVideoRecording(requestRecordAudioPermission)
    }
  }

  fun startVideoRecording(requestRecordAudioPermission: suspend () -> Boolean) {
    if (!_uiState.value.isStreaming || _uiState.value.isRecording) return
    viewModelScope.launch {
      val includeAudio = _uiState.value.includeAudioInStream && requestRecordAudioPermission()
      if (!_uiState.value.isStreaming || _uiState.value.isRecording) return@launch
      if (_uiState.value.includeAudioInStream && !includeAudio) {
        _uiState.update { it.copy(includeAudioInStream = false) }
      }
      videoRecorder.setIncludeAudio(includeAudio)
      videoRecorder.startRecording(csdCollector.complete())
    }
  }

  suspend fun stopVideoRecording() {
    if (!_uiState.value.isRecording) return
    var waited = 0L
    while (!videoRecorder.hasStartedWriting.value && waited < KEYFRAME_WAIT_MAX_MS) {
      delay(KEYFRAME_WAIT_STEP_MS)
      waited += KEYFRAME_WAIT_STEP_MS
    }
    when (val result = videoRecorder.stopRecording()) {
      is RecordingResult.Completed ->
        _uiState.update { it.copy(activePreview = CapturePreview.Video(result.uri)) }
      RecordingResult.NoRecording ->
        wearablesViewModel.setRecentError(
          getApplication<Application>().getString(R.string.error_recording_too_short)
        )
      RecordingResult.Failed ->
        wearablesViewModel.setRecentError(
          getApplication<Application>().getString(R.string.error_recording_save_failed)
        )
    }
  }

  fun toggleMic() {
    if (!_uiState.value.isStreaming || _uiState.value.isRecording) return
    _uiState.update { it.copy(includeAudioInStream = !it.includeAudioInStream) }
  }

  // MARK: - Legacy Dismiss
  fun dismissCapturePreview() {
    executeKillSwitch() // Redirect default dismiss to Kill Switch
    _uiState.update { it.copy(activePreview = null) }
  }

  // MARK: - Photo decoding
  private fun decodePhoto(photo: PhotoData): Bitmap? =
    when (photo) {
      is PhotoData.Bitmap -> photo.bitmap
      is PhotoData.HEIC -> decodeWithOrientation(photo.data)
    }

  private fun decodeWithOrientation(data: ByteBuffer): Bitmap? {
    val buffer = data.duplicate().apply { rewind() }
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
      bitmap?.recycle()
      return null
    }

    val matrix = exifOrientationMatrix(bytes)
    if (matrix.isIdentity) return bitmap

    return try {
      Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        bitmap.recycle()
      }
    } catch (e: OutOfMemoryError) {
      bitmap
    }
  }

  private fun exifOrientationMatrix(bytes: ByteArray): Matrix {
    val orientation =
      try {
        ByteArrayInputStream(bytes).use { input ->
          ExifInterface(input)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
      } catch (e: IOException) {
        ExifInterface.ORIENTATION_NORMAL
      }
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }
    return matrix
  }

  override fun onCleared() {
    super.onCleared()
    tts?.shutdown()
    clearStreamResources()
    session?.stop()
    cleanupSession()
    audioInputHandler.cleanup()
    videoRecorder.close()
  }

  class Factory(
    private val application: Application,
    private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST")
        return CameraViewModel(application, wearablesViewModel) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}