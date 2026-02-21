# Shared ProGuard/R8 rules for Ketch release artifacts (Android & Desktop)

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
-keep class io.ktor.client.engine.cio.CIOEngineContainer { *; }

# SLF4J / Logback
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-keep class ch.qos.logback.classic.spi.LogbackServiceProvider { *; }
-keep class ch.qos.logback.classic.Logger { *; }
-keep class ch.qos.logback.core.** { *; }
-keep class ch.qos.logback.classic.** { *; }
-dontwarn org.osgi.**
-dontwarn aQute.bnd.**
-dontwarn edu.umd.cs.findbugs.**

# SQLDelight / SQLite JDBC
-dontwarn app.cash.sqldelight.**
-keep class org.sqlite.** { *; }
