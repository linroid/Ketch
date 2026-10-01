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
