# kotlinx.serialization keeps its generated serializers via the plugin; keep the models anyway.
-keep,includedescriptorclasses class app.aoide.**$$serializer { *; }
-keepclassmembers class app.aoide.** { *** Companion; }
-keepclasseswithmembers class app.aoide.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.slf4j.**
