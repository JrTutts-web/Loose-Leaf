# pdfbox-android loads fonts, filters and security handlers reflectively.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**
-dontwarn javax.**
-dontwarn java.awt.**

# Tesseract (full flavour) is called from native code through JNI.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
