-keep class com.mobilemcp.pro.** { *; }
-keep class org.java_websocket.** { *; }
-dontwarn org.slf4j.**
-keep class com.k2fsa.sherpa.onnx.** { *; }

# PDFBox Android references the optional Gemalto JPEG2000 decoder when present.
# PRIME does not bundle that optional codec; keep R8 strict everywhere else.
-dontwarn com.gemalto.jp2.**
