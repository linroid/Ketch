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

# Koog AI framework and its transitive dependencies — many optional
# classes are not present at runtime (OpenTelemetry, gRPC, Reactor,
# lettuce, netty internals, Apache HttpClient5, OkHttp GraalVM, etc.)
-dontwarn io.grpc.**
-dontwarn io.lettuce.**
-dontwarn io.micrometer.**
-dontwarn io.netty.**
# Prevent ProGuard from computing common supertypes for netty logging
# classes that reference missing Log4J2 superclasses.
-keep class io.netty.util.internal.logging.** { *; }
-dontwarn io.opentelemetry.**
-dontwarn javax.annotation.**
-dontwarn javax.enterprise.**
-dontwarn okhttp3.internal.graal.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.apache.commons.pool2.**
-dontwarn org.apache.hc.**
-dontwarn org.apache.httpcomponents.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.graalvm.**
-dontwarn org.jboss.marshalling.**
-dontwarn org.LatencyUtils.**
-dontwarn reactor.**
-dontwarn io.github.oshai.kotlinlogging.**
-dontwarn com.oracle.svm.**
