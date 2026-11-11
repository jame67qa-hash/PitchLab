# OpenCV / MediaPipe / CameraX 通過 JNI 反射訪問的類不參與混淆
-keep class org.opencv.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class androidx.camera.** { *; }
-dontwarn org.opencv.**
