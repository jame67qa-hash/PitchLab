# OpenCV / MediaPipe / CameraX 通过 JNI 反射访问的类不参与混淆
-keep class org.opencv.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class androidx.camera.** { *; }
-dontwarn org.opencv.**
