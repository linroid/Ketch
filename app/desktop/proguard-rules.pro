# Jackson Kotlin invokes signature-polymorphic MethodHandle methods. ProGuard 7.8 resolves
# their call-site descriptors as ordinary methods and reports false missing-member warnings.
# Keep other unresolved references visible; this does not remove or replace MethodHandle calls.
-dontwarn java.lang.invoke.MethodHandle
