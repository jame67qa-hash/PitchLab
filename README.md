# PitchLab（Android）— Phase 1 + 2 + 3：高速相機、四點標定、投球紀錄與自動剪輯

棒球投球追蹤與分析 App 的實作進度。Phase 1 把**高幀率取像 → 逐幀 CV → 物理量換算 → 資料落庫**
這條主鏈打通並可驗證；Phase 2 在此之上補上「**場地四點標定 → 以雷達實測值校準球速 → 好球帶判定
→ 投球歷史紀錄**」，讓量測從「畫面裡的相對數字」變成「有真實尺度的物理量」。

- 語言：Kotlin 2.0.21 / JDK 17
- UI：Jetpack Compose（Material 3）
- 架構：MVVM + Clean Architecture（`ui` / `domain` / `data` / `core` / `di`）
- DI：Hilt 2.52（KSP）
- 非同步：Coroutines + StateFlow / SharedFlow
- 資料庫：Room 2.6.1（KSP）
- 相機：CameraX 1.4.0（Camera2Interop 鎖 AE 幀率）
- CV / AI：OpenCV 4.10.0（官方 Maven AAR）、MediaPipe Tasks Vision 0.10.14
- 導航：Navigation Compose 2.8.4（相機頁 ↔ 紀錄頁）

---

## 1. 這一階段實際交付了什麼

| 需求 | 對應檔案 | 狀態 |
|---|---|---|
| App 依賴 + OpenCV/MediaPipe 整合 | `app/build.gradle.kts`、根 `build.gradle.kts`、`settings.gradle.kts` | ✅ 編譯通過 |
| Hilt Application + 權限宣告 | `PitchLabApp.kt`、`AndroidManifest.xml` | ✅ |
| 全螢幕相機 + Canvas HUD + 權限請求 | `ui/camera/CameraScreen.kt`、`ui/camera/components/HudOverlay.kt` | ✅ |
| 相機狀態機（錄影/分析/指標） | `ui/camera/CameraViewModel.kt` | ✅ |
| `ImageAnalysis.Analyzer` + YUV→Bitmap + OpenCV 介面 | `domain/cv/PitchAnalyzer.kt` | ✅ |
| MOG2 背景相減 + HSV 白色過濾 + 輪廓 + Kalman | `domain/cv/BaseballTracker.kt` | ✅ |
| 2D→3D 座標轉換、球速（MPH）、位移（英吋） | `domain/cv/PerspectiveTransformCalculator.kt` | ✅ |
| 逐幀指標聚合 + 出手判定 + 落點外推 | `domain/cv/PitchMetricsCalculator.kt` | ✅ |
| Room 落庫 | `data/local/*`、`data/repository/*` | ✅ |
| 60/120fps 幀率鎖定 | `core/camera/CameraFpsSelector.kt` + ViewModel 的 `Camera2Interop` | ✅ |
> Phase 1 的 `CircularFrameBuffer` 是「邊錄邊存記憶體緩衝」的預留設計；Phase 3 最後改用
> **已寫入磁碟的 MP4 + MediaExtractor 免重編碼裁切**，理由見第 8.3 節（省記憶體、抗程序被殺、
> 片段可直接分享）。緩衝類別保留但不在主流程使用，不影響功能。

> MediaPipe 在 Phase 3 已正式啟用：`domain/pose/PoseLandmarkerDetector.kt` 載入
> `assets/pose_landmarker_lite.task`，由 `PitchAnalyzer.poseListener` 每 3 幀餵一張 upright Bitmap。

---

## 2. Phase 2 追加：四點標定、雷達校準、投球紀錄

Phase 1 的球速是「單目深度估算」，誤差 ±3–6 mph，而且好球帶是使用者在畫面上手拖出來的
一個矩形——它跟真實的 17 in 本壘板沒有任何對應關係。Phase 2 補的正是這個斷點。

| 需求 | 對應檔案 | 狀態 |
|---|---|---|
| 四點單應解算（純 Kotlin，可單元測試） | `domain/cv/HomographySolver.kt` | ✅ 28 個測試全過 |
| 標定資料模型（只存原始輸入，不存死矩陣） | `domain/model/CalibrationProfile.kt` | ✅ |
| 由角點推好球帶幾何（aspect 修正） | `domain/cv/CalibrationGeometry.kt` | ✅ |
| 好球/壞球判定（含球半徑寬容） | `domain/cv/StrikeZoneEvaluator.kt` | ✅ |
| 速度刻度（雷達實測 / App 估算） | `domain/cv/CalibrationRegistry.kt` | ✅ |
| 標定 UI（凍結畫面點四角 + 相機高度 + 雷達輸入） | `ui/calibration/CalibrationOverlay.kt` | ✅ |
| 標定持久化 | `data/local/CalibrationEntity.kt`、`CalibrationDao.kt`、`RoomCalibrationRepository.kt` | ✅ |
| 投球歷史頁 | `ui/history/HistoryScreen.kt`、`HistoryViewModel.kt` | ✅ |
| 導航（相機 ↔ 紀錄） | `ui/PitchLabNavHost.kt` | ✅ |
| Room v1 → v2 顯式 migration | `di/AppModule.kt`（`MIGRATION_1_2`） | ✅ 不刪使用者資料 |

### 2.1 單應矩陣：為什麼自己解，不用 OpenCV

四個點對應是「8 個未知、8 個方程」的**恰定問題**，把每組對應展成兩條線性方程：

```
[x y 1 0 0 0 -u'x -u'y] · h = u'
[0 0 0 x y 1 -u'x -u'y] · h = v'      （h33 固定為 1，矩陣列優先 9 元素）
```

`HomographySolver` 用部分主元的 Gauss 消去解這 8×9 增廣矩陣，`|pivot| < 1e-10` 判為奇異
（四點共線或重複點）回傳 `null`。這樣做的三個好處：

1. **不用等 OpenCV native 載入**，JVM 單元測試就能驗算——這是 Phase 1 最花時間的環節；
2. 四點沒有外點，**不需要 RANSAC**，閉式解就是最小平方最佳解；
3. 相機執行緒上沒有 `Mat` 配置，沒有 native 記憶體洩漏風險。

驗證方式見 `HomographySolverTest`：給一個已知矩陣 `h_true` 產生四組對應，反解後用
**第五個未參與解算的點**檢查，誤差 < 1e-6。

### 2.2 標定只存原始輸入，不存矩陣

`CalibrationProfile` 存的是：四角歸一化座標、相機高度、好球帶上下緣、`speedScale`、
當時的雷達值與 App 估算值。**不存 3×3 矩陣**，理由：

- 矩陣綁定當時的分析解析度（Phase 1 會把 1280×720 縮到 720 寬）；換手機或改設定後矩陣直接失效；
- 存原始輸入才能「重新計算」與「顯示誤差」，矩陣存下來是一團無法解釋的數字。

角點順序鎖定 **左後 → 右後 → 右前 → 左前**，對應場地英吋座標：

```
(-8.5,-8.5)  (8.5,-8.5)
(-8.5, 0.0)  (8.5, 0.0)     ← 本壘板前緣，往投手板方向為 -y
```

### 2.3 歸一化座標的 aspect 陷阱（最容易寫錯的一行）

角點的 x / y 是**各自**歸一化的，所以同樣 1 英吋在兩個方向的歸一化長度不同：

```
1 in (x) = widthNorm / 17
1 in (y) = widthNorm * aspect / 17      aspect = 影像寬 / 高
```

少乘 `aspect`，好球帶框會變成一條扁線（16:9 下高度只剩 56%）。`CalibrationGeometry`
就是為了把這個換算集中在一處並用單元測試釘死。

好球帶框：以本壘板前緣為基準往上量，下緣 19 in、上緣 42 in（`171 cm` 打者的膝到胸），
橫向與本壘板同寬 17 in 並置中。

### 2.4 好球判定

```
球高 = 相機高度 − 落點縱向偏移（y 向下為正）
好球 = |橫向偏移| ≤ 8.5 + 1.47  in  且  19 − 1.47 ≤ 球高 ≤ 42 + 1.47
```

1.47 in 是棒球半徑——規則上好球帶以「球的邊緣」通過為準。這裡刻意**忽略相機俯仰角**，
所以 README 與 App 都要求「鏡頭盡量水平」，否則球高會有系統性偏差（這也是 Phase 3 要加
相機姿態估計的原因）。

### 2.5 速度刻度：用雷達把估算拉回真實

`PitchMetricsCalculator` 的深度是 `Z = f_px · R_real / r_px`。若整體深度被低估 15%，
球速也會低估 15%。標定時輸入一顆「雷達實測 MPH」與「App 當下估算 MPH」：

```
speedScale = radarMph / estimatedMph        （限制在 0.5 ~ 2.5×）
Z' = speedScale · Z  ⇒  |v| 同步縮放
```

`CalibrationRegistry` 用 `@Volatile` 持有目前的 `speedScale`，因為這個值在 60–120fps 的
逐幀路徑上被讀取，不能每幀查 Room。UI 上校準後顯示 `MPH · CAL`，未校準顯示 `MPH · EST`，
歷史頁每一筆也保留 `calibrated` 旗標，避免舊資料被誤認為已校準。

### 2.6 標定流程（為什麼要凍結畫面）

```
按「標定」→ 暫停逐幀分析 → 請求一幀 Bitmap（依 rotationDegrees 轉正）
        → 依序點四角 → 設定相機高度 → 按「計算」（顯示 px/in 與四角誤差 in）
        → 輸入雷達值/估算值 → 按「儲存」→ 寫 Room + 灌 registry + 重算好球帶框
```

不用「即時預覽點擊」的原因：投球時畫面在動、場上有人走動，角點很容易點偏；
而且分析暫停後畫面不會跳格，`ContentScale.Fit` 的 letterbox 偏移也能精確扣除。

> 這裡修掉了一個會讓整個標定功能啞掉的 bug：`PitchAnalyzer.analyze()` 在
> `processingEnabled == false` 時原本直接 `image.close()` 返回，導致 `requestDebugFrame()`
> 永遠等不到 Bitmap。現在停用分析時仍會處理待抓幀請求，並依 `imageInfo.rotationDegrees`
> 把 Bitmap 轉正，才與分析用的 upright 座標系重合。

### 2.7 資料庫 v2

- 新表 `calibrations`（四角攤平 `p0x … p3y` 八個 `REAL` 欄位 + 相機高度 + 好球帶上下緣 + 刻度 + 兩個參考值）；
- `pitch_sessions` 增加 `is_strike`（nullable，`NULL` = 未判定）與 `calibrated`（`NOT NULL DEFAULT 0`）；
- `MIGRATION_1_2` 用 `ALTER TABLE ... ADD COLUMN` 顯式升級，**沒有** `fallbackToDestructiveMigration`
  —— 否則使用者一升級，歷史紀錄就被清空。`calibrated` 的 `defaultValue = "0"` 也必須寫在
  `@ColumnInfo`，否則 Room 開啟時會把 migration 給的 DEFAULT 當成 schema 不符而崩潰。

## 3. 分層結構

```
app/src/main/java/com/pitchlab/
├── PitchLabApp.kt                 # @HiltAndroidApp + OpenCV native 預載
├── MainActivity.kt                # @AndroidEntryPoint + edge-to-edge
├── core/
│   ├── camera/CameraIntrinsics.kt # 焦距/主點換算（像素內參）
│   ├── camera/CameraFpsSelector.kt# 從 CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES 選最高幀率
│   └── cv/
│       ├── OpenCvNativeLoader.kt  # 載入 libopencv_java4
│       ├── YuvPlaneExtractor.kt   # YUV_420_888 -> 灰度 Mat / NV21
│       └── YuvToRgbConverter.kt   # YUV -> Bitmap（給 MediaPipe 與抓幀）
├── domain/
│   ├── model/                     # BallDetection / FrameAnalysisResult / CameraUiState / LivePitchMetrics
│   │   ├── PitchSession.kt        # + isStrike / calibrated
│   │   └── CalibrationProfile.kt  # 四角 + 相機高度 + 好球帶 + speedScale
│   ├── cv/
│   │   ├── BaseballTracker.kt     # MOG2 + HSV + 輪廓 + Kalman
│   │   ├── PerspectiveTransformCalculator.kt
│   │   ├── PitchMetricsCalculator.kt   # 深度乘 speedScale + 好球判定
│   │   ├── PitchAnalyzer.kt       # ImageAnalysis.Analyzer（含凍結抓幀）
│   │   ├── HomographySolver.kt    # 四點單應（純 Kotlin 高斯消去）
│   │   ├── CalibrationGeometry.kt # 角點 → 好球帶框（aspect 修正）
│   │   ├── StrikeZoneEvaluator.kt # 好球/壞球（含球半徑寬容）
│   │   ├── CalibrationRegistry.kt # @Volatile speedScale（逐幀路徑用）
│   │   ├── ReleaseDetector.kt     # 手腕加速度峰值 → 出手點（純 Kotlin 可測）
│   │   └── CircularFrameBuffer.kt # Phase 1 預留，主流程改用 MP4 裁切
│   ├── pose/PoseLandmarkerDetector.kt   # MediaPipe Pose（assets 模型、失敗降級）
│   ├── media/PitchClipExtractor.kt      # MediaExtractor+MediaMuxer 免重編碼裁片段
│   └── repository/                # PitchSessionRepository / CalibrationRepository
├── data/
│   ├── local/                     # Room Entity / Dao / Database（v3 + 顯式 migration）
│   └── repository/                # RoomPitchSessionRepository / RoomCalibrationRepository
├── di/AppModule.kt                # Hilt providers + MIGRATION_1_2 / 2_3
└── ui/
    ├── PitchLabNavHost.kt         # camera ↔ history
    ├── camera/CameraScreen.kt, CameraViewModel.kt
    ├── camera/components/HudOverlay.kt
    ├── calibration/CalibrationOverlay.kt
    ├── history/HistoryScreen.kt, HistoryViewModel.kt
    └── theme/                     # Color / Type / Theme
```

---

## 4. 核心演算法與公式

### 4.1 棒球偵測（`BaseballTracker.kt`）

兩條路徑，UI 可即時切換：

1. **MOG2 運動檢測（預設）**：在**灰度 Y plane** 上做 `createBackgroundSubtractorMOG2(300, 26.0, false)`
   → 閾值 200 → 開/閉運算 → `findContours`。
   在純灰度上跑省掉一次全圖色彩轉換，720p 下約省 15–25 ms/幀。
2. **HSV 白色過濾**：`inRange(H 0–180, S 0–55, V 175–255)`，白球低飽和高明度；
   精度更高，但需要 BGR 幀，代價是每幀一次 YUV→Bitmap→Mat。

兩條路徑共用同一套候選篩選：

- 面積 `35 ~ 12000 px²`
- 圓度 `4πA/P² ≥ 0.55`
- 包圍盒長寬比 `0.55 ~ 1.8`
- 內切區域灰度均值 `≥ 105`

### 4.2 Kalman 平滑

狀態向量 `[x, y, vx, vy]`，觀測 `[x, y]`：

```
transitionMatrix = [[1,0,1,0],[0,1,0,1],[0,0,1,0],[0,0,0,1]]
measurementMatrix = [[1,0,0,0],[0,1,0,0]]
processNoiseCov ≈ 2e-2 * I, measurementNoiseCov ≈ 1.2e-1 * I
```

資料關聯用「預測位置 + 門控半徑（3×半徑或 28px，丟幀時收緊到 2×）」，
連續丟幀 ≤ 12 幀時用預測值延續軌跡並讓 confidence 線性衰減，超過則重置 Kalman。
> 註：OpenCV 4.10 的 Java 綁定把成員暴露為 `get_xxx/set_xxx`，程式碼用顯式 setter 寫入自建 Mat，避免依賴 getter 的共享語義。

### 4.3 像素 → 物理空間

**像素焦距**（不依賴標定板）：

```
f_px = f_mm / sensor_width_mm * image_width_px
```

**單目深度**（已知球徑，反推距離）：

```
Z = f_px * R_real / r_px        (R_real = 1.47 in)
```

**反投影到相機座標系**：

```
X = (u - cx) * Z / fx
Y = (v - cy) * Z / fy
```

**球速**：對 `X(t), Y(t), Z(t)` 各做最小二乘斜率得到 `(vx, vy, vz)`：

```
speed_mph = sqrt(vx² + vy² + vz²) / 17.6      // 1 mph = 17.6 in/s
```

**整段平均速度**（投手板到本壘板 60.5 ft = 726 in）：

```
mph = (726 / 飛行秒數) / 17.6
release_mph ≈ mph * 1.035                     // 空氣阻力補償（可調）
```

**落點外推**（把速度延長到本壘板平面 `z = 0`）：

```
t_plate = (0 - z_now) / vz
x_plate = x_now + vx * t_plate
y_plate = y_now + vy * t_plate
```

**單應矩陣（Perspective Transform）**：`Calib3d.findHomography(RANSAC)` 把打擊區四角
（像素）映到真實場地座標（英吋），`Core.perspectiveTransform` 逐點換算，
`isInStrikeZone()` 用本壘板 17 in 寬 + 好球帶 19–42 in 判定。
Phase 1 已完成演算法，UI 尚未接標定向導（Phase 2）。

### 4.4 幀率鎖定（本階段最容易被忽略的坑）

CameraX 的 `ImageAnalysis` **不會自己要求高幀率**，多數機型預設 30fps。做法：

```kotlin
val range = pickFrom(characteristics.get(CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))
Camera2Interop.Extender(analysisBuilder)
    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
```

- 優先精確區間 `[120,120]` → `[60,60]`，其次選「上界最高、下界盡量高」的區間，
  並在 UI 明示「AE 會在區間內浮動」。
- 錄影 UseCase 用**同一個區間**同樣寫入（CameraX 要求同一 Key 的多個 UseCase 值一致，
  否則直接拋 `IllegalArgumentException`）。
- 若設備只有 `[30,30]`，UI 會顯示降級提示，而不是假裝 60fps。

---

## 5. 精度與已知限制（請務必先讀）

1. **單目測速本質仍是估算**：`Z` 由球體像素半徑反推，半徑受運動模糊、曝光、遮擋影響。
   Phase 2 已提供 `speedScale` 校準（雷達實測 / App 估算），但它是**單一線性倍率**：
   它修正的是系統性偏差，不會讓每一球的誤差歸零。UI 已用 `CAL` / `EST` 區分兩種數字。
2. **相機位置假設**：程式假設相機在本壘板正後方或投手後方，`z=0` 平面即本壘板；
   若相機偏移，落點與位移需要 `plateDepthIn` 修正（參數已預留）。
3. **三路並發**：`Preview + VideoCapture + ImageAnalysis` 在部分 LEGACY 設備不支援，
   程式會自動降級為「僅分析」，UI 顯示「錄影不可用」，不會崩潰。
4. **MediaPipe ABI**：`tasks-vision 0.10.14` 的 native 只含 `arm64-v8a` / `armeabi-v7a`；
   `x86_64` 是為了模擬器跑 OpenCV，Phase 3 在模擬器上無法使用 Pose。
5. **標定的硬性前提**：四角必須在畫面內且不被遮擋；相機要盡量水平（好球判定忽略俯仰角）；
   四點不能共線。若不符合，App 會顯示「無法解算」而不是給出錯誤的好球帶。
6. **未做**：自動剪輯成品輸出（MP4 裁切）、球種識別、多相機同步 — 屬 Phase 3。

---

## 6. 建置與執行

需求：Android Studio Ladybug 以上 / JDK 17 / Android SDK 34。

```bash
# 1) 設定 SDK 路徑（Android Studio 會自動產生，命令列需手動）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2) 建置
./gradlew assembleDebug

# 3) 單元測試（純數學，不需裝置）
./gradlew testDebugUnitTest

# 4) 安裝
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次建置需下載 OpenCV AAR（約 50 MB）與 MediaPipe AAR（約 120 MB），請留足時間與流量。

### 手機端驗收清單（Phase 1）

- [ ] 首次啟動彈出相機/麥克風權限，拒絕後有引導文案
- [ ] 預覽全螢幕、無拉伸（16:9 letterbox）
- [ ] HUD 顯示 `LIVE xx fps`，且在支援 60fps 的機型上實測 ≥ 55fps
- [ ] 好球帶可拖動平移，九宮格與梯形透視正確
- [ ] 投球時出現白色球體標記 + 青色軌跡 + 雷達指針擺動
- [ ] 錄影 10 秒後 `已存 N` 增加，`Android/data/com.pitchlab/files/Movies/pitches/` 有 mp4
- [ ] 切換「白色過濾」後仍能追蹤（幀率允許下降）
- [ ] 夜間/低光下不會因鎖 60fps 導致畫面全黑（若全黑請放寬 FPS 區間）

### 手機端驗收清單（Phase 2）

- [ ] 按「標定」後畫面**凍結**，且凍結畫面方向與預覽一致（不是轉 90 度）
- [ ] 依序點四角，編號 1→4 依序出現在點擊位置（不是整體偏移）
- [ ] 按「計算」後顯示 `px/in` 與四角誤差，誤差 < 1.5 in
- [ ] 輸入雷達值與估算值後顯示 `x.xx×`，儲存後球速標籤從 `EST` 變 `CAL`
- [ ] 好球帶框自動貼到本壘板正上方（寬度約等於本壘板寬，高度約 23 in）
- [ ] 關掉 App 重開，標定與紀錄仍在（Room v2 沒被 destructive migration 清掉）
- [ ] 投 5 球後「好 N / 壞 N」與歷史頁好球率一致
- [ ] 歷史頁可單筆刪除、可清空；`MPH · CAL/EST` 與錄影檔名正確
- [ ] 歷史頁上方出現「進壘點分佈」散點圖，好球綠點落在框內、壞球紅點落在框外
- [ ] 「球速趨勢」折線與列表中最快/平均球速一致；沒有進壘點時散點圖不出現假點

### 手機端驗收清單（Phase 3）

- [ ] 相機頁狀態列出現 `出手 AUTO`（若顯示 `出手 OFF`，表示姿態模型載入失敗，看 logcat `PoseLandmarker`）
- [ ] 投球時甩臂結束後狀態列出現 `出手偵測：N ms`，數字合理（約 300–1500 ms，視你按錄影的時機）
- [ ] 錄影結束後歷史頁該筆出現「▶ 出手片段」，點擊可用系統播放器開啟且**有聲音**
- [ ] 片段長度約 4 秒（前 2 秒 + 後 2 秒），開頭可能比出手點再早一點（同步幀對齊所致，屬正常）
- [ ] 沒偵測到出手的錄影仍然保留原始 mp4，只是沒有片段（不應該整個檔案消失）
- [ ] 升級安裝（Phase 2 → Phase 3）後舊紀錄仍在，且新欄位為空（Room v2→v3 遷移成功）
- [ ] 姿態推論不應讓球速延遲明顯變大：狀態列 `ms/幀` 與 Phase 2 相比增加 < 8 ms

---

## 7. 已完成的機器驗證

| 項目 | 結果 |
|---|---|
| `gradle assembleDebug testDebugUnitTest` | BUILD SUCCESSFUL（AGP 8.5.2 / Gradle 8.9 / JDK 17） |
| 單元測試 | **33 tests, 0 failures**（Phase 1 的 11 + Phase 2 的 17 + Phase 3 的 5） |
| APK | 144 MB（含 5.6 MB 姿態模型），`com.pitchlab` **v1.0.0-phase3**，minSdk 24 / target 34 |
| 簽章 | APK Signature Scheme v2：Verifies |
| Room 遷移 | `1.json` / `2.json` / `3.json` 與 `MIGRATION_1_2`、`MIGRATION_2_3` 逐欄位一致 |
| 原生庫 | `libopencv_java4.so`（3 ABI）、`libmediapipe_tasks_vision_jni.so`（arm64/armv7） |
| 資產 | `assets/pose_landmarker_lite.task`（5,777,746 bytes，已確認打包進 APK） |
| FileProvider | `res/xml/file_paths.xml` 只暴露 `Movies/pitches/`（非全域 external-path） |
| 權限 | CAMERA / RECORD_AUDIO / WRITE_EXTERNAL_STORAGE(≤28) / READ_MEDIA_VIDEO |

**未驗證（缺實機 / 無 KVM 模擬器）**：相機實際幀率、真實球速精度、錄影與剪輯檔可播放性、
MediaPipe 在實機的單幀延遲、不同廠商的 `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 行為。
這些必須在實機上跑第 6 節清單。

---

## 8. Phase 3 追加：MediaPipe 出手偵測與自動剪輯

### 8.1 為什麼用「加速度峰值」判定出手

投手從抬腿到放球，手腕全程都在移動。若用「速度超過門檻」判定，跨步與抬臂階段就會先觸發，
錄到的片段會變成「投球前的準備動作」而不是放球瞬間。

`ReleaseDetector` 改看**加速度**：

1. 由連續幀的手腕位置算瞬時速度（歸一化單位／秒）；
2. 再做速度差分得到加速度，超過 `accelThreshold = 8.0` 記為候選峰值；
3. 等加速度回落到峰值的 60% 以下（甩臂結束）才**回溯**輸出峰值時間戳——
   這一步是關鍵：輸出的是「峰值發生的那一刻」，不是「發現峰值已過的那一刻」，
   否則剪輯中心會固定晚 100–200 ms；
4. `cooldownMs = 900` 內不重複輸出，避免一次投球被切成兩個出手點；
5. 可見度 < 0.5 的幀只清空速度歷史、不參與運算，避免遮擋前後用一個假位移造出假峰值。

這個類別是純 Kotlin（不碰 Bitmap / MediaPipe），所以 5 個 case 可以在 JVM 上跑：
慢速不觸發、甩腕只出一次且在區間內、900 ms 冷卻抑制第二次、低可見度忽略、`reset()` 清狀態。

### 8.2 姿態管線：為什麼要降頻、為什麼要獨立執行緒

MediaPipe Pose（lite）在 2 vCPU 機器上單幀約 10–20 ms，若跟著 60fps 全跑，會直接把
球體追蹤的分析執行緒吃光。因此：

- **降頻**：`poseListener` 每 3 幀送一次（`POSE_FRAME_INTERVAL = 3`），實測 60fps 下約 20 次推論／秒，
  對「峰值時間」的解析度是 50 ms 級，足夠定位 4 秒片段的中心；
- **先縮圖**：在分析執行緒上先縮到 384 寬（`POSE_INPUT_WIDTH`），轉換成本從 720p 的 ~3 MB
  降到 ~0.5 MB，推論本身也更快；
- **獨立執行緒 + 丟幀**：`pitchlab-pose` 執行緒 + `AtomicBoolean poseBusy`，
  上一幀還在推論就整幀丟棄。出手偵測看的是趨勢，補幀只會累積延遲；
- **與標定抓幀分開**：Phase 2 的 `frameListener` 是「精確一幀、可暫停」，Phase 3 的
  `poseListener` 是「持續、可降頻」，兩者生命週期不同，共用一個 slot 會互相覆蓋（這是實際踩過的坑）。

模型 `pose_landmarker_lite.task`（5.6 MB，sha256 前 16 碼 `59929e1d1ee95287`）放在 `assets/`，
離線可用、不在執行期下載。若初始化失敗（模型缺失／ABI 不支援），`available` 回 false，
App 只停用出手偵測，球速與標定照常運作——是刻意設計的降級路徑，不是隱性崩潰。

### 8.3 剪輯：為什麼不重新編碼

重新編碼要跑一整條 MediaCodec 管線，1080p 素材在手機上很吃 CPU，而且會二次損失畫質。
`PitchClipExtractor` 改用 `MediaExtractor` + `MediaMuxer` **直接複製已編碼的取樣**：

- 影片軌 `SEEK_TO_PREVIOUS_SYNC` 對齊到前一個同步幀（I 幀），失敗再退 3 秒找最近同步幀；
- 逐軌處理：MediaExtractor 沒有 `unselectAllTracks()`，必須 `unselectTrack(index)` 逐軌切換
  （**這是 Phase 3 唯一的編譯錯誤**，API 名稱想錯了）；
- 每軌 `writeSampleData` 前把 `presentationTimeUs` 平移到 `sampleTime - startUs`，
  否則片段開頭會有幾秒空白（muxer 從 0 開始計時，但 extractor 給的是原始時間戳）；
- 音軌也一起複製，片段才有聲音；`stop()` 後驗證檔案非空才算成功。

代價是切割點只能落在 I 幀，實際起點會比要求時間稍早（通常 0.5–2 秒），這是可接受的取捨：
換來 100–300 ms 的剪輯耗時與零畫質損失。

輸出檔名 `原始檔名_pitch.mp4`，例如 `PITCH_20261111_210233_pitch.mp4`。

### 8.4 資料庫 v3 與對外開啟片段

`pitch_sessions` 追加兩欄（皆 nullable，不動舊欄位）：

| 欄位 | 型別 | 用途 |
|---|---|---|
| `clip_path` | TEXT | 自動剪輯片段絕對路徑；null = 未剪輯／失敗 |
| `release_offset_ms` | INTEGER | 出手時間相對錄影開始的偏移，供重新剪輯或除錯 |

`MIGRATION_2_3` 只有兩條 `ALTER TABLE ... ADD COLUMN`，並與自動產生的
`app/schemas/com.pitchlab.data.local.PitchLabDatabase/3.json` 逐欄位核對一致（nullable、無 DEFAULT）。

開啟片段走 `FileProvider`（authority `${applicationId}.fileprovider`，只暴露
`getExternalFilesDir(Movies)/pitches`，不是全域 `external-path`）：Android 7+ 禁止跨 App 傳
`file://`，少了這段會直接 `FileUriExposedException`。

### 8.5 Phase 3 已知限制

- 出手偵測依賴**手臂在畫面內且不被遮擋**；投手若整個人出框，MediaPipe 給不出手腕。
- 沒有 IMU 輔助：手機若同時大幅晃動，手腕加速度會被相機運動污染。
- 片段中心的解析度受降頻影響（約 50 ms），不是「精確到單幀」；要更精確可把
  `POSE_FRAME_INTERVAL` 調到 2，代價是每幀多 5–8 ms。
- 上述能力都只在 JVM 單元測試與編譯層驗證過；實機的推論延遲與剪輯檔案可播放性
  仍要跑第 6 節的 Phase 3 驗收清單。

---

## 9. 下一階段（Phase 4）

- 出手影格的「靜態姿勢評分」：用 MediaPipe 33 點算肩髖分離、前導手肘角度、放球高度，
  給出可比較的姿勢分數，而不是只有球速。
- 多球序列比對：把同一次練投的 10 球疊在同一張好球帶圖上做熱區分析（Phase 3 已有散點圖基礎）。
- 3D 軌跡：加入相機俯仰角／高度後，把目前的「忽略俯仰」投影升級為完整 3D 重建。
- 匯出：把歷史紀錄與片段打包成 CSV + MP4 分享包（目前片段已是獨立檔案，只缺批次匯出）。

---

## 10. 授權與版本

- 授權：MIT（見 `LICENSE`，Copyright 2026 PitchLab contributors）。
- 版本：`1.0.0-phase3`（versionCode 3）。
- 第三方：OpenCV 4.10.0（Apache-2.0）、CameraX 1.4.0（Apache-2.0）、
  MediaPipe Tasks 0.10.14（Apache-2.0）、MediaPipe Pose Landmarker lite 模型（Apache-2.0）。
- 倉庫不含任何金鑰／keystore；debug 簽章由本機 `${HOME}/.android/debug.keystore` 產生。
  `local.properties`（SDK 路徑）不進版控，請複製 `local.properties.example` 自行填寫。
