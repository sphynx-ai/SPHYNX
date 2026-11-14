# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# GGUF backend (Phase 8): llama_jni.cpp finds these by name - the native methods of LlamaJni, and
# the onPiece(byte[]) method it calls with each piece of an answer.
-keep class com.example.mlbbresearch.inference.LlamaJni { native <methods>; }
-keep interface com.example.mlbbresearch.inference.LlamaJni$PieceSink { *; }
-keep class * implements com.example.mlbbresearch.inference.LlamaJni$PieceSink { public void onPiece(byte[]); }
