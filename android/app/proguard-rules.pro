# Minification is currently disabled for release builds (see build.gradle.kts).
-keepclassmembers class com.ps4smartcheat.core.** { *** Companion; }
-keepclasseswithmembers class com.ps4smartcheat.core.** { kotlinx.serialization.KSerializer serializer(...); }
