# libtorrent4j JNI classes — keep all native method bindings
-keep class org.libtorrent4j.swig.** { *; }
-keep class org.libtorrent4j.** { native <methods>; }
-dontwarn org.libtorrent4j.**
