# Socket.io-client
-keep class io.socket.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Room
-keep class androidx.room.** { *; }

# App models
-keep class com.supportbubble.app.models.** { *; }

# BubbleAppSettings — Gson-serialised in AllowedAppsManager (SharedPreferences)
-keep class com.supportbubble.app.BubbleAppSettings { *; }

# Room database entities & DAOs
-keep class com.supportbubble.app.database.** { *; }

# Firebase (covered by firebase-crashlytics-gradle plugin, but explicit for safety)
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
