# Jackson Kotlin invokes signature-polymorphic MethodHandle methods. ProGuard 7.8 resolves
# their call-site descriptors as ordinary methods and reports false missing-member warnings.
# Keep other unresolved references visible; this does not remove or replace MethodHandle calls.
-dontwarn java.lang.invoke.MethodHandle

# ProGuard's return type specialization breaks @JvmMultifileClass parts: it narrows Okio's
# Sink.buffer() to return RealBufferedSink but keeps the cast to BufferedSink, so the app
# fails at startup with "VerifyError: Bad return type".
# https://github.com/Guardsquare/proguard/issues/533
-optimizations !method/specialization/returntype

# Unlike R8, ProGuard does not keep the providers listed in META-INF/services, so ServiceLoader
# throws ServiceConfigurationError for every provider it removed. Keep the providers of each
# service the app still looks up.
-keep class * implements io.ktor.client.HttpClientEngineContainer { <init>(); }
-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider {
  <init>();
}
-keep class * implements ai.koog.http.client.KoogHttpClient$Factory { <init>(); }

# Enum.valueOf() looks up the public values() reflectively; ProGuard removes or privatizes it
# when nothing calls it directly. Android's default R8 rules keep it, Compose Desktop's do not.
-keepclassmembers enum * {
  public static **[] values();
  public static ** valueOf(java.lang.String);
}

# Atomic field updaters (atomicfu, Ktor's network selectors, coroutines) find their fields by
# name. R8 rewrites those names when it obfuscates; ProGuard does not.
-keepclassmembers class * {
  volatile <fields>;
}

# Line numbers, so the stack traces in a release's logs can be decoded with its mapping.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Attributes ProGuard only drops when it obfuscates. Without InnerClasses, a nested class's
# simpleName becomes "Outer$Inner", as the logs' state names would; reflection on generic
# signatures and local classes needs the others.
-keepattributes InnerClasses,EnclosingMethod,Signature

# OkHttp reads its public suffix list from its own package (META-INF/proguard rules ship only in
# OkHttp's Android artifact).
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# JNA (the torrent engine's file system calls) binds its Java classes to native code by name.
-keep class com.sun.jna.** { *; }

# Logs name states, errors and exceptions by their class names (DownloadState.logLabel,
# describeCauses), which obfuscation would turn into single letters.
-keepnames class com.linroid.ketch.api.**
-keepnames class * extends java.lang.Throwable

# kotlin-reflect is left out of the app (see build.gradle.kts). Koog's reflective tool sets,
# kotlinx-schema's reflective generator, Jackson's Kotlin module and Ktor's loading of server
# modules by name refer to it; the app reaches none of them.
-dontwarn kotlin.reflect.full.**
-dontwarn kotlin.reflect.jvm.**
