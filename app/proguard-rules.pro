# App-level ProGuard/R8 rules for Ketch release artifacts (Android & Desktop)
#
# Library-specific rules (serialization, Ktor, coroutines, SQLDelight) are now
# shipped as consumer-rules.pro inside each library module's AAR.

# SLF4J / Logback (from library:server, JVM-only)
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-keep class ch.qos.logback.classic.spi.LogbackServiceProvider { *; }
-keep class ch.qos.logback.classic.Logger { *; }
-keep class ch.qos.logback.core.** { *; }
-keep class ch.qos.logback.classic.** { *; }
-dontwarn org.osgi.**
-dontwarn aQute.bnd.**
-dontwarn edu.umd.cs.findbugs.**

# DNS-SD (JmDNS) — JVM-only; not available on Android
-dontwarn com.appstractive.dnssd.**
