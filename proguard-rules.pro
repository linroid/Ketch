# Shared ProGuard/R8 rules for KDown release artifacts (Android & Desktop)

# kotlinx-serialization
-keepclassmembers class kotlinx.serialization.json.** {
  *** Companion;
}
-keepclasseswithmembers class **$$serializer {
  static **$$serializer INSTANCE;
  kotlinx.serialization.KSerializer[] childSerializers();
}
-keepclassmembers @kotlinx.serialization.Serializable class ** {
  *** Companion;
  *** INSTANCE;
  kotlinx.serialization.KSerializer serializer(...);
}

# Ktor / coroutines
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**

# Netty
-dontwarn io.netty.**

# SLF4J / Logback
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**

# Log4J2 (on classpath for Netty hierarchy resolution, shrunk away)
-dontwarn org.apache.logging.log4j.**
-dontwarn org.osgi.**
-dontwarn aQute.bnd.**
-dontwarn edu.umd.cs.findbugs.**

# SQLDelight
-dontwarn app.cash.sqldelight.**
