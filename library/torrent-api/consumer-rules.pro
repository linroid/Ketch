# kotlinx-serialization: keep companion objects and serializers for @Serializable types
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
