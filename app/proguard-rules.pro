# kotlinx.serialization keeps its generated serializers via the plugin; keep the models anyway.
-keep,includedescriptorclasses class app.aoide.data.**$$serializer { *; }
-keepclassmembers class app.aoide.data.** { *** Companion; }
-keepclasseswithmembers class app.aoide.data.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.slf4j.**
