-keepattributes *Annotation*, SourceFile, LineNumberTable, InnerClasses, EnclosingMethod, Signature

-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

-keep class ps.reso.instaeclipse.** { *; }
-keepclassmembers class ps.reso.instaeclipse.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

-keepclassmembers class * {
    *** get*();
    void set*(***);
}

-dontwarn android.support.**
-dontwarn androidx.**
-dontwarn com.android.**
-dontwarn org.lsposed.**
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
