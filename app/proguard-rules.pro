# App-level ProGuard/R8 rules for Ketch release artifacts (Android & Desktop)
#
# Library-specific rules (serialization, Ktor, coroutines, SQLDelight) are now
# shipped as consumer-rules.pro inside each library module's AAR.

# SLF4J (from library:server and Koog, JVM-only). The desktop app logs through slf4j-simple,
# which SLF4J finds through ServiceLoader.
-dontwarn org.slf4j.**
-keep class org.slf4j.simple.SimpleServiceProvider { <init>(); }
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

# Koog finds a ToolSet's @Tool methods through Kotlin reflection and resolves every member
# function, inherited ones included, to its JVM method. Nothing calls them directly, so
# shrinkers strip them, along with the synthetic `$default` methods reflection calls when the
# model leaves out a parameter that has a default value. ProGuard then leaves Kotlin metadata
# that names missing methods (KotlinReflectionInternalError); R8 leaves a class without tools
# ("No tools found in class").
# The classes are kept whole, not just their members: R8 drops kotlin.Metadata from a class no
# rule keeps, and the tools would lose their parameter names and default values.
# The parameters' @LLMDescription annotations describe the tool arguments to the model.
-keep interface ai.koog.agents.core.tools.reflect.ToolSet { *; }
-keep class * implements ai.koog.agents.core.tools.reflect.ToolSet { *; }
-keepattributes RuntimeVisibleParameterAnnotations

# kotlinx-schema, which writes the tool schemas, recognizes a description annotation by its
# simple class name and reads its `value`. Renamed by R8, @LLMDescription is no longer found
# and the model gets tools and parameters without descriptions.
-keep @interface ai.koog.agents.core.tools.annotations.LLMDescription { *; }
