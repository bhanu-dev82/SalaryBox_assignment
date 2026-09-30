# MediaPipe Tasks: the graph is reached reflectively/from JNI.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face.** { *; }
-dontwarn com.google.mediapipe.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Compose
-dontwarn androidx.compose.ui.tooling.**
