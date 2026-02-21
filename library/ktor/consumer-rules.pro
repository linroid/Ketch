# Ktor: suppress warnings for optional platform features
-dontwarn io.ktor.**

# Keep CIO engine service provider (JVM)
-keep class io.ktor.client.engine.cio.CIOEngineContainer { *; }
