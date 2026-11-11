# PitchLab（Android）— Phase 1 + 2：高速相機、四點標定與投球紀錄

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
| 出手點前後緩衝（供 Phase 3 自動剪輯） | `domain/cv/CircularFrameBuffer.kt` | 已實作，尚未接到 MediaPipe |

> MediaPipe **已接進 Gradle 與 Manifest**，Phase 1 不啟動推論（推論屬 Phase 3 的出手偵測），
> 但 `PitchAnalyzer.frameListener` 已預留 Bitmap 出口，Phase 3 直接把幀丟給 PoseLandmarker 即可。

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
│   │   └── CircularFrameBuffer.kt
│   └── repository/                # PitchSessionRepository / CalibrationRepository
├── data/
│   ├── local/                     # Room Entity / Dao / Database（v2 + 顯式 migration）
│   └── repository/                # RoomPitchSessionRepository / RoomCalibrationRepository
├── di/AppModule.kt                # Hilt providers + MIGRATION_1_2
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

---

## 7. 已完成的機器驗證

| 項目 | 結果 |
|---|---|
| `./gradlew assembleDebug` | BUILD SUCCESSFUL（AGP 8.5.2 / Gradle 8.9 / JDK 17） |
| 單元測試 | **28 tests, 0 failures**（Phase 1 的 11 + Phase 2 的 17） |
| APK | 134 MB，`com.pitchlab` **v1.0.0-phase2**，minSdk 24 / target 34 |
| 簽章 | APK Signature Scheme v2：Verifies |
| Room 遷移 | `1.json` / `2.json` schema 與 `MIGRATION_1_2` 的 SQL 逐欄位一致（含 `DEFAULT 0`） |
| 原生庫 | `libopencv_java4.so`（3 ABI）、`libmediapipe_tasks_vision_jni.so`（arm64/armv7） |
| 權限 | CAMERA / RECORD_AUDIO / WRITE_EXTERNAL_STORAGE(≤28) / READ_MEDIA_VIDEO |

**未驗證（缺實機 / 無 KVM 模擬器）**：相機實際幀率、真實球速精度、錄影檔案可播放性、
不同廠商的 `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 行為。這些必須在實機上跑第 5 節清單。

---

## 8. 下一階段（Phase 3）介面預留位置

- `PitchAnalyzer.frameListener` → 直接餵 MediaPipe `PoseLandmarker`（手腕/手肘節點），
  用腕關節加速度峰值判定出手點；目前它已被 Phase 2 的凍結抓幀使用，介面不需改。
- `CircularFrameBuffer` → 出手前一刀（前 2 秒）；出手後再收 2 秒即可交 MediaCodec 合成。
  Phase 2 的 `CameraViewModel` 已有 `startRecordingAt(timestamp)` 的落點可用來對齊。
- `CalibrationProfile` → Phase 3 加相機俯仰角/位置姿態後，好球判定可從「忽略俯仰」升級為
  完整的 3D 投影；現有欄位可向後相容（新增而非改寫）。
- `HistoryScreen` 的 `SessionRow` → 之後接「單球回放 / 剪輯片段播放」只需在該列加一個播放鍵。
---

## 9. 授權與版本

- 授權：MIT（見 `LICENSE`）。
- 版本：`1.0.0-phase2`（versionCode 1）。
- 第三方：OpenCV 4.10.0（Apache-2.0）、MediaPipe Tasks Vision（Apache-2.0）、CameraX / Compose / Room / Hilt（Apache-2.0），皆以 Maven 依賴引入，未修改原始碼。
- 本專案不含任何金鑰或簽章檔；`local.properties` 由使用者自行產生（範例見 `local.properties.example`）。
