# ML Kit ships its own consumer rules; nothing extra needed.

# Drop debug/verbose logging (it logs message snippets) from release builds.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# Anthropic Java SDK (Claude translation). Jackson reads the SDK's model classes reflectively.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
}
-dontwarn com.fasterxml.jackson.databind.**

# The SDK's schema generator reflects over java.lang.reflect.AnnotatedType, which Android lacks.
# We pass a hand-written schema, so that code path never runs.
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType

# Server/desktop-only code paths in the SDK that never run on Android.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
-dontwarn com.google.errorprone.annotations.**
