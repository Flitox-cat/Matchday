# Release builds are not minified by default. Keep serialization metadata if you enable R8.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.matchday.app.**$$serializer { *; }
-keepclassmembers class com.matchday.app.** { *** Companion; }
