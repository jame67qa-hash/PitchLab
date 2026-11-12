package com.pitchlab.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitchlab.core.camera.CameraFpsSelector
import com.pitchlab.core.camera.CameraIntrinsics
import com.pitchlab.core.camera.CameraIntrinsicsFactory
import com.pitchlab.domain.cv.CalibrationGeometry
import com.pitchlab.domain.cv.CalibrationRegistry
import com.pitchlab.domain.cv.HomographySolver
import com.pitchlab.domain.cv.PitchAnalyzer
import com.pitchlab.domain.cv.ReleaseDetector
import com.pitchlab.domain.cv.WristSample
import com.pitchlab.domain.cv.PitchMechanicsEvaluator
import com.pitchlab.domain.cv.PitchMetricsCalculator
import com.pitchlab.domain.model.CalibrationProfile
import com.pitchlab.domain.model.CameraUiState
import com.pitchlab.domain.model.FrameAnalysisResult
import com.pitchlab.domain.model.LivePitchMetrics
import com.pitchlab.domain.model.NormalizedPoint
import com.pitchlab.domain.model.NormalizedRect
import com.pitchlab.domain.feedback.PitchAnnouncement
import com.pitchlab.domain.feedback.PitchFeedbackPlayer
import com.pitchlab.domain.media.PitchClipExtractor
import com.pitchlab.domain.model.PitchSession
import com.pitchlab.domain.pose.PoseFrame
import com.pitchlab.domain.pose.PoseIndices
import com.pitchlab.domain.pose.PoseLandmarkerDetector
import com.pitchlab.domain.repository.CalibrationRepository
import com.pitchlab.domain.repository.PitchSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 相機頁面狀態機（MVVM 中的 VM）。
 *
 * 職責邊界：
 * - 負責 CameraX 的綁定/解綁、幀率鎖定、錄像生命週期；
 * - 負責把 [PitchAnalyzer] 拋出的純數據結果翻譯成 UI 狀態；
 * - 負責把一次投球/一次錄像落庫（Room）；
 * - **不**持有任何 Android View（surfaceProvider 由 Compose 傳入）。
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val analyzer: PitchAnalyzer,
    private val fpsSelector: CameraFpsSelector,
    private val metricsCalculator: PitchMetricsCalculator,
    private val repository: PitchSessionRepository,
    private val calibrationRepository: CalibrationRepository,
    private val calibrationRegistry: CalibrationRegistry,
    private val homographySolver: HomographySolver,
    private val calibrationGeometry: CalibrationGeometry,
    private val poseDetector: PoseLandmarkerDetector,
    private val releaseDetector: ReleaseDetector,
    private val clipExtractor: PitchClipExtractor,
    private val mechanicsEvaluator: PitchMechanicsEvaluator,
    private val feedbackPlayer: PitchFeedbackPlayer
) : ViewModel() {

    private companion object {
        /** UI 狀態推送節流：40ms ≈ 25Hz，足夠讓準星平滑，又不至於每幀重組整個界面 */
        const val UI_PUSH_INTERVAL_NS = 40_000_000L

        const val ANALYSIS_WIDTH = 1280
        const val ANALYSIS_HEIGHT = 720
        const val PREVIEW_WIDTH = 1920
        const val PREVIEW_HEIGHT = 1080

        /** 每 N 幀送一次姿態推論（60fps -> 20fps；2 vCPU 可接受的成本） */
        const val POSE_FRAME_INTERVAL = 3
        /** 姿態輸入寬度：lite 模型內部本來就會縮，提前縮可省 3/4 的轉換成本 */
        const val POSE_INPUT_WIDTH = 384
        /** 出手偏移上限，超過視為上一球的殘留 */
        const val MAX_CLIP_CENTER_MS = 6_000L
        /** 自動剪輯前後各保留的長度 */
        const val CLIP_BEFORE_MS = 2_000L
        const val CLIP_AFTER_MS = 2_000L
    }

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    /** 標定用的凍結畫面（獨立於 uiState，避免 Bitmap 進 data class 影響相等比較） */
    private val _calibrationFrame = MutableStateFlow<android.graphics.Bitmap?>(null)
    val calibrationFrame: StateFlow<android.graphics.Bitmap?> = _calibrationFrame.asStateFlow()

    private var activeProfile: CalibrationProfile = CalibrationProfile.NONE

    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pitchlab-analysis").apply { priority = Thread.NORM_PRIORITY }
    }

    /** 姿態推論專用執行緒：與 CV 分析分離，避免 MediaPipe 阻塞球體追蹤 */
    private val poseExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pitchlab-pose").apply { priority = Thread.NORM_PRIORITY }
    }
    private val poseBusy = AtomicBoolean(false)
    private var poseFrameCount = 0
    private var wristSampleCount = 0
    private var releaseCount = 0

    /** 錄影起點（elapsedRealtime，毫秒）；出手時間用它換算成片段偏移 */
    private var recordStartedElapsedMs = 0L
    /** 最近一次出手相對錄影開始的偏移；null = 這段錄影還沒偵測到出手 */
    private var lastReleaseOffsetMs: Long? = null
    /** 只保留最近 6 秒內的出手，避免上一球的偏移被套到新的一球 */
    private var lastReleaseAtElapsedMs = 0L

    /** 最近一幀姿態快照；出手時用它算姿勢分數（不重跑推論） */
    @Volatile
    private var latestPoseFrame: PoseFrame? = null
    /** 最近一次出手的姿勢評分 */
    private var lastMechanics: PitchMechanicsEvaluator.MechanicsFeedback? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var cameraCharacteristics: CameraCharacteristics? = null
    private var intrinsics: CameraIntrinsics? = null
    private var bound = false
    private var videoSupported = true

    private var lastUiPushNs = 0L
    private var recordStartedAtMillis = 0L
    private var totalPitchCount = 0
    private var strikeCount = 0
    private var ballCount = 0
    private var latestMetrics: LivePitchMetrics = LivePitchMetrics.EMPTY
    private var bindJob: Job? = null

    init {
        // 分析執行緒 -> VM：唯一的跨執行緒入口，SharedFlow 自帶背壓丟棄策略
        viewModelScope.launch {
            analyzer.results.collect { result -> onFrameAnalyzed(result) }
        }
        viewModelScope.launch {
            repository.observeCount().collect { count ->
                _uiState.update { it.copy(storedSessionCount = count) }
            }
        }
        viewModelScope.launch {
            calibrationRepository.observeActive().collect { profile -> applyProfile(profile) }
        }

        // Phase 3：姿態 -> 出手偵測 -> 自動剪輯
        val poseReady = poseDetector.available
        analyzer.setPoseInterval(POSE_FRAME_INTERVAL)
        _uiState.update { it.copy(feedbackEnabled = feedbackPlayer.enabled) }
        if (poseReady) {
            analyzer.poseListener = { bitmap, timestampNs -> submitPoseFrame(bitmap, timestampNs) }
        }
        _uiState.update { state ->
            state.copy(
                poseAvailable = poseReady,
                statusMessage = if (poseReady) {
                    state.statusMessage
                } else {
                    "姿態模型不可用，出手偵測停用（其餘功能正常）"
                }
            )
        }
    }

    /**
     * 姿態取幀：在分析執行緒上先縮圖（成本 ~1ms），再把推論丟到獨立執行緒。
     * 若上一幀還在推論就整幀丟棄——出手偵測看的是峰值趨勢，不需要補幀。
     */
    private fun submitPoseFrame(bitmap: Bitmap, timestampNs: Long) {
        if (!poseBusy.compareAndSet(false, true)) return
        val scaled = try {
            val targetWidth = POSE_INPUT_WIDTH
            val ratio = targetWidth.toFloat() / bitmap.width.toFloat()
            val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } catch (t: Throwable) {
            poseBusy.set(false)
            return
        }
        val timestampMs = timestampNs / 1_000_000L
        poseExecutor.execute {
            try {
                poseFrameCount++
                val pose = poseDetector.detectPose(scaled, timestampMs)
                if (pose != null) {
                    latestPoseFrame = pose
                    val leftWrist = pose.point(PoseIndices.LEFT_WRIST)
                    val rightWrist = pose.point(PoseIndices.RIGHT_WRIST)
                    val wrist = when {
                        leftWrist == null -> rightWrist
                        rightWrist == null -> leftWrist
                        leftWrist.visibility >= rightWrist.visibility -> leftWrist
                        else -> rightWrist
                    }
                    if (wrist != null && wrist.visibility >= 0.5) {
                        wristSampleCount++
                        releaseDetector.update(
                            WristSample(
                                timestampMs = timestampMs,
                                x = wrist.x,
                                y = wrist.y,
                                visibility = wrist.visibility
                            )
                        )?.let { release -> onReleaseDetected(release) }
                    }
                }
            } catch (t: Throwable) {
                // 單幀推論失敗不影響球體追蹤，靜默跳過
            } finally {
                scaled.recycle()
                poseBusy.set(false)
            }
        }
    }

    private fun onReleaseDetected(release: ReleaseDetector.Release) {
        val now = SystemClock.elapsedRealtime()
        val offset = if (_uiState.value.isRecording && recordStartedElapsedMs > 0L) {
            release.timestampMs - recordStartedElapsedMs
        } else {
            null
        }
        if (offset != null && offset in 0L..MAX_CLIP_CENTER_MS) {
            lastReleaseOffsetMs = offset
            lastReleaseAtElapsedMs = now
        }
        releaseCount++

        // 出手影格姿勢評分：直接用出手當下最近一幀的關鍵點，不再重跑推論
        val mechanics = latestPoseFrame?.let { frame ->
            mechanicsEvaluator.evaluate(frame)
        }
        lastMechanics = mechanics
        _uiState.update { state ->
            state.copy(
                releaseOffsetMs = offset,
                mechanicsScore = mechanics?.score,
                mechanicsGrade = mechanics?.grade,
                statusMessage = buildString {
                    if (offset != null) {
                        append("出手偵測：${offset} ms")
                    } else {
                        append("出手偵測：加速度峰值（未錄影，無法剪輯）")
                    }
                    mechanics?.let { append(" · 姿勢 ${it.score} ${it.grade}") }
                }
            )
        }
    }

    /**
     * 把數據庫裡的最新標定灌進 registry（逐幀路徑讀它）與 UI 狀態，
     * 並用標定角點重算好球帶框（讓框依真實尺寸落在打者區域，而不是手拖猜的框）。
     */
    private fun applyProfile(profile: CalibrationProfile?) {
        val active = profile ?: CalibrationProfile.NONE
        activeProfile = active
        calibrationRegistry.update(active)

        val aspect = _uiState.value.analysisAspectRatio
        val calibratedZone = if (active.isValid && _uiState.value.analysisWidth > 0) {
            calibrationGeometry.strikeZoneRect(active, aspect)
        } else {
            null
        }

        _uiState.update { state ->
            state.copy(
                speedCalibrated = active.isSpeedCalibrated,
                geometryCalibrated = active.isValid,
                calibrationSummary = active.summary(),
                strikeZone = calibratedZone ?: state.strikeZone,
                calibration = state.calibration.copy(
                    speedCalibrated = active.isSpeedCalibrated,
                    cameraHeightIn = active.cameraHeightIn.toFloat(),
                    cameraPitchDeg = active.cameraPitchDeg.toFloat(),
                    zoneTopIn = active.strikeZoneTopIn.toFloat(),
                    zoneBottomIn = active.strikeZoneBottomIn.toFloat(),
                    message = state.calibration.message
                )
            )
        }
    }

    // ------------------------------------------------------------------ 相機綁定

    /**
     * 綁定 CameraX。必須在拿到相機權限且 Compose 提供 surfaceProvider 之後調用。
     *
     * @param targetRotation 當前顯示方向（Surface.ROTATION_*），保證輸出圖與螢幕一致
     */
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        targetRotation: Int
    ) {
        if (bound || bindJob?.isActive == true) return
        bindJob = viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "正在連接相機…") }
            try {
                val provider = awaitCameraProvider()
                cameraProvider = provider

                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) {
                    _uiState.update {
                        it.copy(errorMessage = "未檢測到後置相機", statusMessage = "相機不可用")
                    }
                    return@launch
                }

                val characteristics = readBackCameraCharacteristics()
                cameraCharacteristics = characteristics
                val availableRanges: Array<Range<Int>>? = characteristics
                    ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val fpsChoice = fpsSelector.select(availableRanges)

                // ---- 解析度選擇：分析與預覽都固定 16:9，避免疊加層幾何錯位 ----
                val analysisSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val previewSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(PREVIEW_WIDTH, PREVIEW_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                // ---- ImageAnalysis：YUV_420_888 + 只保留最新幀 ----
                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetRotation(targetRotation)

                // ---- 關鍵：用 Camera2Interop 鎖定 AE 目標幀率 ----
                // 不加這一步，多數機型 ImageAnalysis 會穩定在 30fps。
                Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    fpsChoice.range
                )

                // ---- 錄像 UseCase：同樣鎖幀率，否則 Recorder 會按 CamcorderProfile 走 30fps ----
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.FHD,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
                        )
                    )
                    .setExecutor(ContextCompat.getMainExecutor(context))
                    .build()

                val videoBuilder = VideoCapture.Builder(recorder)
                    .setTargetRotation(targetRotation)
                Camera2Interop.Extender(videoBuilder).setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    fpsChoice.range
                )

                val previewUseCase = Preview.Builder()
                    .setResolutionSelector(previewSelector)
                    .setTargetRotation(targetRotation)
                    .build()
                    .apply { setSurfaceProvider(surfaceProvider) }

                val analysisUseCase = analysisBuilder.build().apply {
                    setAnalyzer(analysisExecutor, analyzer)
                }
                val videoUseCase = videoBuilder.build()

                preview = previewUseCase
                imageAnalysis = analysisUseCase
                videoCapture = videoUseCase

                // ---- 綁定：三路併發在部分 LEGACY 裝置不被支援，做降級 ----
                provider.unbindAll()
                var boundWithVideo = true
                try {
                    provider.bindToLifecycle(
                        lifecycleOwner, selector, previewUseCase, videoUseCase, analysisUseCase
                    )
                } catch (illegalArgument: IllegalArgumentException) {
                    boundWithVideo = false
                    provider.unbindAll()
                    videoCapture = null
                    provider.bindToLifecycle(lifecycleOwner, selector, previewUseCase, analysisUseCase)
                }
                videoSupported = boundWithVideo
                bound = true

                analyzer.reset()
                analyzer.setProcessingEnabled(true)

                _uiState.update { state ->
                    state.copy(
                        cameraReady = true,
                        videoSupported = boundWithVideo,
                        requestedFps = fpsChoice.targetFps,
                        requestRangeLabel = "AE ${fpsChoice.range}",
                        statusMessage = buildString {
                            append(fpsChoice.note)
                            if (!boundWithVideo) {
                                append(" · 三路併發不支援，已降級爲純分析")
                            }
                        },
                        errorMessage = null
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        cameraReady = false,
                        errorMessage = "相機初始化失敗：${t.message}",
                        statusMessage = "相機不可用"
                    )
                }
            }
        }
    }

    /** 讀取後置相機特性（幀率區間 / 焦距 / 傳感器尺寸都來自這裏） */
    private fun readBackCameraCharacteristics(): CameraCharacteristics? = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
        backId?.let { manager.getCameraCharacteristics(it) }
    } catch (security: SecurityException) {
        null
    } catch (t: Throwable) {
        null
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (t: Throwable) {
                        continuation.resumeWithException(t)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }

    // ------------------------------------------------------------------ 幀回調

    private fun onFrameAnalyzed(result: FrameAnalysisResult) {
        // 解析度變化（切換畫質/旋轉）時重建內參
        if (intrinsics == null ||
            intrinsics?.imageWidth != result.width ||
            intrinsics?.imageHeight != result.height
        ) {
            val rebuilt = cameraCharacteristics?.let { characteristics ->
                CameraIntrinsicsFactory.create(characteristics, result.width, result.height)
            }
            if (rebuilt != null) {
                intrinsics = rebuilt
                analyzer.updateIntrinsics(rebuilt)
            }
            // 分析解析度一確定，就把標定角點換算成好球帶框
            if (activeProfile.isValid) applyProfile(activeProfile)
        }

        latestMetrics = metricsCalculator.ingest(result, intrinsics)

        // 一次投球結算 -> 落庫 + 計數
        metricsCalculator.consumeCompleted()?.let { completed ->
            totalPitchCount++
            if (completed.isStrike == true) strikeCount++ else if (completed.isStrike == false) ballCount++
            viewModelScope.launch { persistPitch(completed) }
        }

        val now = result.timestampNanos
        if (now - lastUiPushNs < UI_PUSH_INTERVAL_NS && result.ball != null) return
        if (now - lastUiPushNs < UI_PUSH_INTERVAL_NS && result.ball == null && latestMetrics.trackedFrames > 0) {
            return
        }
        lastUiPushNs = now

        val marker = result.ball?.let {
            NormalizedPoint(x = it.normalizedX, y = it.normalizedY)
        }

        _uiState.update { state ->
            state.copy(
                isAnalyzing = true,
                measuredFps = result.measuredFps,
                lastProcessingMs = result.processingMillis,
                analysisWidth = result.width,
                analysisHeight = result.height,
                ballDetected = result.ball != null,
                detectionSource = result.source,
                ballMarker = marker,
                ballRadiusNorm = result.ball?.normalizedRadius ?: 0f,
                liveMetrics = latestMetrics,
                pitchCount = totalPitchCount,
                strikeCount = strikeCount,
                ballCount = ballCount
            )
        }
    }

    // ------------------------------------------------------------------ 錄像

    fun toggleRecording() {
        if (_uiState.value.isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val capture = videoCapture
        if (capture == null) {
            _uiState.update {
                it.copy(errorMessage = "此裝置不支援「預覽 + 錄像 + 逐幀分析」三路併發，無法錄像")
            }
            return
        }

        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "pitches")
        if (!directory.exists() && !directory.mkdirs()) {
            _uiState.update { it.copy(errorMessage = "無法創建錄像目錄：${directory.absolutePath}") }
            return
        }

        val fileName = "pitch_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val file = File(directory, fileName)
        val options = FileOutputOptions.Builder(file).build()

        var pending = capture.output.prepareRecording(context, options)
        val audioGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (audioGranted) {
            pending = pending.withAudioEnabled()
        }

        metricsCalculator.reset()
        releaseDetector.reset()
        lastReleaseOffsetMs = null
        lastReleaseAtElapsedMs = 0L
        lastMechanics = null
        recordStartedAtMillis = System.currentTimeMillis()
        recordStartedElapsedMs = SystemClock.elapsedRealtime()

        try {
            recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                onRecordEvent(event, file)
            }
            _uiState.update {
                it.copy(
                    isRecording = true,
                    statusMessage = if (audioGranted) "錄製中（含收音）" else "錄製中（靜音）"
                )
            }
        } catch (t: Throwable) {
            _uiState.update { it.copy(errorMessage = "錄像啓動失敗：${t.message}") }
        }
    }

    fun stopRecording() {
        val active = recording ?: return
        active.stop()
        recording = null
        _uiState.update { it.copy(isRecording = false, statusMessage = "正在封裝影片…") }
    }

    private fun onRecordEvent(event: VideoRecordEvent, file: File) {
        when (event) {
            is VideoRecordEvent.Start -> _uiState.update { it.copy(isRecording = true) }

            is VideoRecordEvent.Finalize -> {
                recording = null
                val failed = event.hasError()
                if (failed) {
                    if (file.exists()) file.delete()
                    _uiState.update {
                        it.copy(
                            isRecording = false,
                            errorMessage = "錄像失敗（錯誤碼 ${event.error}）",
                            statusMessage = "錄像失敗"
                        )
                    }
                } else {
                    val duration = (System.currentTimeMillis() - recordStartedAtMillis).coerceAtLeast(0L)
                    _uiState.update {
                        it.copy(
                            isRecording = false,
                            lastRecordingPath = file.absolutePath,
                            statusMessage = "已儲存 ${file.name}"
                        )
                    }
                    viewModelScope.launch {
                        saveRecordingSession(file, duration)
                        autoClipIfNeeded(file)
                    }
                }
            }

            else -> Unit
        }
    }

    private suspend fun saveRecordingSession(file: File, durationMillis: Long) {
        val metrics = metricsCalculator.consumeCompleted() ?: latestMetrics
        val session = PitchSession(
            startedAtMillis = recordStartedAtMillis,
            durationMillis = durationMillis,
            fastestMph = metrics.instantaneousMph,
            averageMph = metrics.averageMph,
            horizontalBreakInches = metrics.horizontalBreakInches,
            verticalBreakInches = metrics.verticalBreakInches,
            plateXInches = metrics.plateLocation?.xInches,
            plateYInches = metrics.plateLocation?.yInches,
            isStrike = metrics.isStrike,
            calibrated = metrics.speedCalibrated,
            videoPath = file.absolutePath,
            detectedFps = _uiState.value.requestedFps,
            trackedFrames = metrics.trackedFrames,
            releaseOffsetMs = currentReleaseOffset(),
            poseScore = currentMechanics()?.score,
            separationDeg = currentMechanics()?.separationDeg,
            elbowDeg = currentMechanics()?.leadElbowDeg,
            trunkTiltDeg = currentMechanics()?.trunkTiltDeg,
            releaseHeightRatio = currentMechanics()?.releaseHeightRatio,
            releaseHeightFt = metrics.releaseHeightFeet,
            releaseDistanceFt = metrics.releaseDistanceFeet
        )
        runCatching { repository.save(session) }
        announce(session)
    }

    private suspend fun persistPitch(metrics: LivePitchMetrics) {
        val session = PitchSession(
            startedAtMillis = System.currentTimeMillis(),
            durationMillis = metrics.timeToPlateMillis ?: 0L,
            fastestMph = metrics.instantaneousMph,
            averageMph = metrics.averageMph,
            horizontalBreakInches = metrics.horizontalBreakInches,
            verticalBreakInches = metrics.verticalBreakInches,
            plateXInches = metrics.plateLocation?.xInches,
            plateYInches = metrics.plateLocation?.yInches,
            isStrike = metrics.isStrike,
            calibrated = metrics.speedCalibrated,
            videoPath = _uiState.value.lastRecordingPath,
            detectedFps = _uiState.value.requestedFps,
            trackedFrames = metrics.trackedFrames,
            releaseOffsetMs = currentReleaseOffset(),
            poseScore = currentMechanics()?.score,
            separationDeg = currentMechanics()?.separationDeg,
            elbowDeg = currentMechanics()?.leadElbowDeg,
            trunkTiltDeg = currentMechanics()?.trunkTiltDeg,
            releaseHeightRatio = currentMechanics()?.releaseHeightRatio,
            releaseHeightFt = metrics.releaseHeightFeet,
            releaseDistanceFt = metrics.releaseDistanceFeet
        )
        runCatching { repository.save(session) }
        announce(session)
    }

    /** 出手回饋（語音 + 震動）；關閉時 [PitchFeedbackPlayer] 會自行略過 */
    private fun announce(session: PitchSession) {
        feedbackPlayer.feedback(
            session.isStrike,
            PitchAnnouncement.text(session.isStrike, session.fastestMph, session.calibrated)
        )
    }

    /** 姿勢分數只在對應當前這一球時寫入（與出手偏移共用同一個時間窗） */
    private fun currentMechanics(): PitchMechanicsEvaluator.MechanicsFeedback? =
        if (currentReleaseOffset() != null) lastMechanics else null

    /** 只有「最近 6 秒內」的出手才對應當前這一球 */
    private fun currentReleaseOffset(): Long? {
        val offset = lastReleaseOffsetMs ?: return null
        val ageMs = SystemClock.elapsedRealtime() - lastReleaseAtElapsedMs
        return if (ageMs in 0L..MAX_CLIP_CENTER_MS) offset else null
    }

    /**
     * 自動剪輯：從剛封裝好的錄影裁出「出手前後各 2 秒」。
     * 剪輯是純容器層複製（不重編碼），在 IO 執行緒跑，失敗只記錄狀態不影響紀錄。
     */
    private suspend fun autoClipIfNeeded(file: File) {
        val offset = lastReleaseOffsetMs ?: return
        if (!file.exists() || file.length() == 0L) return
        val clipFile = File(file.parentFile, file.nameWithoutExtension + "_pitch.mp4")
        val success = withContext(Dispatchers.IO) {
            clipExtractor.extract(
                input = file,
                output = clipFile,
                centerMs = offset,
                beforeMs = CLIP_BEFORE_MS,
                afterMs = CLIP_AFTER_MS
            )
        }
        if (success) {
            runCatching { repository.updateClip(file.absolutePath, clipFile.absolutePath) }
            _uiState.update {
                it.copy(
                    lastClipPath = clipFile.absolutePath,
                    statusMessage = "已產生出手片段 ${clipFile.name}"
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    statusMessage = "出手片段產生失敗（原始影片仍已保留）"
                )
            }
        }
    }

    // ------------------------------------------------------------------ 交互

    /** HSV 白色過濾開關（更準但更耗 CPU，預設走 MOG2） */
    fun toggleColorMode() {
        val enabled = !_uiState.value.colorModeEnabled
        analyzer.setColorModeEnabled(enabled)
        _uiState.update {
            it.copy(
                colorModeEnabled = enabled,
                statusMessage = if (enabled) "白色過濾模式：精度優先" else "運動檢測模式：幀率優先"
            )
        }
    }

    /** 出手回饋開關（語音 + 震動）；預設關閉，避免被錄進片段或干擾旁人 */
    fun toggleFeedback() {
        val enabled = !_uiState.value.feedbackEnabled
        feedbackPlayer.enabled = enabled
        _uiState.update {
            it.copy(
                feedbackEnabled = enabled,
                statusMessage = if (enabled) "出手回饋：語音＋震動" else "出手回饋已關閉"
            )
        }
        if (enabled) {
            feedbackPlayer.feedback(null, "出手回饋已開啟")
        }
    }

    /** 拖拽調整好球帶（歸一化 0..1） */
    fun updateStrikeZone(rect: NormalizedRect) {
        val clamped = NormalizedRect(
            left = rect.left.coerceIn(0.02f, 0.9f),
            top = rect.top.coerceIn(0.02f, 0.9f),
            right = rect.right.coerceIn(rect.left + 0.05f, 0.98f),
            bottom = rect.bottom.coerceIn(rect.top + 0.05f, 0.98f)
        )
        _uiState.update { it.copy(strikeZone = clamped) }
    }

    /** 抓一幀做調試截屏，Phase 3 會換成送給 MediaPipe Pose */
    fun requestDebugFrame(onBitmap: (android.graphics.Bitmap) -> Unit) {
        analyzer.frameListener = { bitmap, _ -> onBitmap(bitmap) }
        analyzer.requestDebugFrame()
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearSessions() {
        viewModelScope.launch {
            repository.clear()
            totalPitchCount = 0
            _uiState.update { it.copy(pitchCount = 0, statusMessage = "歷史記錄已清空") }
        }
    }

    // ------------------------------------------------------------------ 標定

    /**
     * 進入標定模式：
     * 1) 暫停逐幀分析（避免凍結畫面被下一幀覆蓋，也省電）；
     * 2) 抓一張當前畫面當作標定底圖；
     * 3) 交由 UI 讓使用者依序點本壘板四角。
     */
    fun enterCalibrationMode() {
        if (_uiState.value.calibration.visible) return
        analyzer.setProcessingEnabled(false)
        _uiState.update { state ->
            state.copy(
                calibration = state.calibration.copy(
                    visible = true,
                    hasFrame = false,
                    corners = emptyList(),
                    message = "正在抓取畫面…",
                    estimatedMphInput = state.liveMetrics.instantaneousMph
                        ?.let { String.format("%.1f", it) } ?: ""
                )
            )
        }
        analyzer.frameListener = { bitmap, _ ->
            // converter 會重用 Bitmap，這裡必須 copy 一份再交給 UI
            _calibrationFrame.value = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            _uiState.update { state ->
                state.copy(
                    calibration = state.calibration.copy(
                        hasFrame = true,
                        message = "畫面已凍結，請依序點擊本壘板四個角"
                    )
                )
            }
        }
        analyzer.requestDebugFrame()
    }

    fun exitCalibrationMode() {
        analyzer.frameListener = null
        analyzer.setProcessingEnabled(true)
        _calibrationFrame.value = null
        _uiState.update { state ->
            state.copy(
                calibration = state.calibration.copy(
                    visible = false,
                    hasFrame = false,
                    corners = emptyList(),
                    message = null
                )
            )
        }
    }

    fun addCalibrationCorner(point: NormalizedPoint) {
        _uiState.update { state ->
            val current = state.calibration
            if (!current.visible || current.corners.size >= 4) return@update state
            val updated = current.corners + point
            state.copy(
                calibration = current.copy(
                    corners = updated,
                    plateWidthPx = 0.0,
                    pixelsPerInch = 0.0,
                    reprojectionErrorIn = 0.0,
                    message = if (updated.size == 4) {
                        "四角已完整，按「計算」檢查誤差"
                    } else {
                        "已記錄 ${updated.size} 個角點"
                    }
                )
            )
        }
    }

    fun undoCalibrationCorner() {
        _uiState.update { state ->
            val current = state.calibration
            if (current.corners.isEmpty()) return@update state
            state.copy(calibration = current.copy(corners = current.corners.dropLast(1), message = "已撤銷一點"))
        }
    }

    fun resetCalibrationCorners() {
        _uiState.update { state ->
            state.copy(
                calibration = state.calibration.copy(
                    corners = emptyList(),
                    pixelsPerInch = 0.0,
                    plateWidthPx = 0.0,
                    reprojectionErrorIn = 0.0,
                    message = "已清空角點，請重新點擊"
                )
            )
        }
    }

    fun updateCameraHeight(value: Float) {
        _uiState.update { state ->
            state.copy(
                calibration = state.calibration.copy(
                    cameraHeightIn = value.coerceIn(20f, 80f)
                )
            )
        }
    }

    /** 鏡頭俯仰角：正值上仰。範圍刻意收在 ±15°，超過就不是「腳架沒擺平」而是刻意仰拍。 */
    fun updateCameraPitch(value: Float) {
        _uiState.update { state ->
            state.copy(
                calibration = state.calibration.copy(
                    cameraPitchDeg = value.coerceIn(-15f, 15f)
                )
            )
        }
    }

    fun updateZoneBounds(topIn: Float, bottomIn: Float) {
        _uiState.update { state ->
            val top = topIn.coerceIn(24f, 60f)
            val bottom = bottomIn.coerceIn(12f, top - 6f)
            state.copy(
                calibration = state.calibration.copy(zoneTopIn = top, zoneBottomIn = bottom)
            )
        }
    }

    fun updateRadarInput(text: String) {
        _uiState.update { state ->
            state.copy(calibration = state.calibration.copy(radarMphInput = text))
        }
        refreshScalePreview()
    }

    fun updateEstimatedInput(text: String) {
        _uiState.update { state ->
            state.copy(calibration = state.calibration.copy(estimatedMphInput = text))
        }
        refreshScalePreview()
    }

    /** 速度刻度 = 雷達實測 / App 估算；超出 0.5~2.5 視為輸入錯誤 */
    private fun refreshScalePreview() {
        val cal = _uiState.value.calibration
        val radar = cal.radarMphInput.trim().toDoubleOrNull()
        val estimated = cal.estimatedMphInput.trim().toDoubleOrNull()
        val scale = if (radar != null && estimated != null &&
            radar in 20.0..130.0 && estimated in 20.0..130.0 && estimated > 0.0
        ) {
            (radar / estimated).takeIf { it in 0.5..2.5 }
        } else {
            null
        }
        _uiState.update { state ->
            state.copy(
                calibration = state.calibration.copy(
                    speedScalePreview = scale,
                    message = when {
                        radar == null || estimated == null -> state.calibration.message
                        scale == null -> "刻度超出合理範圍（0.5~2.5×），請確認兩個數值"
                        else -> "將套用 ${String.format("%.2f", scale)}× 速度刻度"
                    }
                )
            )
        }
    }

    /**
     * 由四角解單應矩陣並回報擬合品質。
     * 角點是歸一化座標、目標是英吋，所以誤差單位也是英吋（不是像素）。
     */
    fun computeCalibration() {
        val cal = _uiState.value.calibration
        if (cal.corners.size != 4) {
            _uiState.update { state ->
                state.copy(calibration = state.calibration.copy(message = "請先點滿四個角點"))
            }
            return
        }
        val src = cal.corners.map { HomographySolver.XY(it.x.toDouble(), it.y.toDouble()) }
        val h = homographySolver.solve(src, fieldCorners())
        if (h == null) {
            _uiState.update { state ->
                state.copy(calibration = state.calibration.copy(message = "四點共線或重複，無法解算，請重新點擊"))
            }
            return
        }
        val error = homographySolver.averageReprojectionError(src, fieldCorners(), h)
        val widthPx = _uiState.value.analysisWidth.takeIf { it > 0 } ?: 1280
        val pixelsPerInch = calibrationGeometry.pixelsPerInch(cal.corners, widthPx)
        val plateWidthPx = calibrationGeometry.plateWidthNormalized(cal.corners) * widthPx
        _uiState.update { state ->
            state.copy(
                calibration = state.calibration.copy(
                    pixelsPerInch = pixelsPerInch,
                    plateWidthPx = plateWidthPx,
                    reprojectionErrorIn = error,
                    message = "標定完成：${String.format("%.1f", pixelsPerInch)} px/in · " +
                        "四角誤差 ${String.format("%.2f", error)} in"
                )
            )
        }
    }

    /** 儲存標定（幾何 + 速度刻度），存檔後立即生效 */
    fun saveCalibration() {
        val cal = _uiState.value.calibration
        if (cal.corners.size != 4) {
            _uiState.update { state ->
                state.copy(calibration = state.calibration.copy(message = "請先點滿四個角點"))
            }
            return
        }
        if (cal.reprojectionErrorIn == 0.0) computeCalibration()

        val scale = cal.speedScalePreview ?: activeProfile.speedScale
        val profile = CalibrationProfile(
            corners = cal.corners,
            cameraHeightIn = cal.cameraHeightIn.toDouble(),
            cameraPitchDeg = cal.cameraPitchDeg.toDouble(),
            strikeZoneTopIn = cal.zoneTopIn.toDouble(),
            strikeZoneBottomIn = cal.zoneBottomIn.toDouble(),
            speedScale = scale,
            radarReferenceMph = cal.radarMphInput.trim().toDoubleOrNull(),
            estimatedAtCalibrationMph = cal.estimatedMphInput.trim().toDoubleOrNull()
        )
        viewModelScope.launch {
            runCatching { calibrationRepository.save(profile) }
                .onSuccess {
                    applyProfile(profile)
                    exitCalibrationMode()
                    _uiState.update { it.copy(statusMessage = "標定已儲存並生效") }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(errorMessage = "標定儲存失敗：${error.message}") }
                }
        }
    }

    fun clearCalibration() {
        viewModelScope.launch {
            runCatching { calibrationRepository.clearAll() }
            calibrationRegistry.clear()
            activeProfile = CalibrationProfile.NONE
            _uiState.update { state ->
                state.copy(
                    speedCalibrated = false,
                    geometryCalibrated = false,
                    calibrationSummary = "尚未標定",
                    strikeZone = NormalizedRect(0.30f, 0.38f, 0.70f, 0.80f),
                    calibration = state.calibration.copy(
                        speedCalibrated = false,
                        corners = emptyList(),
                        speedScalePreview = null,
                        pixelsPerInch = 0.0,
                        plateWidthPx = 0.0,
                        reprojectionErrorIn = 0.0,
                        message = "已清除標定，恢復預設好球帶"
                    ),
                    statusMessage = "標定已清除"
                )
            }
        }
    }

    /** 本壘板四角的場地座標（英吋）：左後、右後、右前、左前 */
    private fun fieldCorners(): List<HomographySolver.XY> = listOf(
        HomographySolver.XY(-8.5, -8.5),
        HomographySolver.XY(8.5, -8.5),
        HomographySolver.XY(8.5, 0.0),
        HomographySolver.XY(-8.5, 0.0)
    )

    // ------------------------------------------------------------------ 生命週期

    override fun onCleared() {
        super.onCleared()
        runCatching { recording?.stop() }
        recording = null
        runCatching { imageAnalysis?.clearAnalyzer() }
        // 單執行緒執行器保證 release 排在任何排隊幀之後執行
        analysisExecutor.execute { analyzer.reset() }
        analysisExecutor.shutdown()
        runCatching { cameraProvider?.unbindAll() }
        bound = false
    }
}
