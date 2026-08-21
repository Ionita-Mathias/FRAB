# Entry points declared in the manifest.
-keep class ch.genedis.tvfileserver.App { *; }
-keep class ch.genedis.tvfileserver.ui.MainActivity { *; }
-keep class ch.genedis.tvfileserver.ui.SettingsActivity { *; }
-keep class ch.genedis.tvfileserver.server.FileServerService { *; }
-keep class ch.genedis.tvfileserver.server.BootReceiver { *; }

# ZXing: only the QR encoder is used, but the writer is resolved through a registry that
# R8 cannot see through.
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.EncodeHintType { *; }
-dontwarn com.google.zxing.**

# WebDAV parses small XML payloads with the platform DOM parser, which is instantiated by
# name through the JAXP factory lookup.
-keep class org.apache.harmony.xml.parsers.** { *; }
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
-dontwarn org.xml.sax.**

# Kotlin coroutines internals referenced reflectively by the debug agent.
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Annotations that are compile-time only.
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# Keep the Leanback GuidedStep fragments: they are instantiated by class name on restore.
-keep class * extends androidx.leanback.app.GuidedStepSupportFragment { *; }
