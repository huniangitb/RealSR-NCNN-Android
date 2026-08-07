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

# Anime4KCPP JNI 回调: libanime4k.so 通过 JNI 反射调用这些静态方法,
# R8 无法感知 native 引用, 必须保留类名与方法名(否则 NoSuchMethodError 崩溃)
-keep class com.tumuyan.ncnn.realsr.Anime4kProcessor {
    public static void onNativeProgress(int, int);
    public static void onNativeInfo(java.lang.String);
}
#-renamesourcefileattribute SourceFile

# ---- 项目特定规则 ----

# FileProvider：通过 manifest authorities 引用，禁止混淆
-keep class androidx.core.content.FileProvider { *; }

# 被反射/Manifest 引用的入口类保持（MainActivity 等由 manifest 引用，AGP 自动处理，
# 这里显式声明以防裁剪）
-keep class com.tumuyan.ncnn.realsr.MainActivity { *; }
-keep class com.tumuyan.ncnn.realsr.ProcessingService { *; }

# mnnsr JNI 回调: libmnnsr.so 通过 JNI 反射调用这些静态方法,
# R8 无法感知 native 引用, 必须保留类名与方法名(否则 NoSuchMethodError 崩溃)
-keep class com.tumuyan.ncnn.realsr.MnnsrProcessor {
    public static void onNativeProgress(int, int, int, int);
    public static void onNativeInfo(java.lang.String);
    native <methods>;
}
# 探针测试/处理入口由 Java 侧调用, 保留 native 方法名(含 probe), 防止混淆后
# Java_<类>_<方法> JNI 符号匹配失败(UnsatisfiedLinkError)
-keepclasseswithmembernames class com.tumuyan.ncnn.realsr.MnnsrProcessor {
    native <methods>;
}
# Anime4kProcessor 同理由 JNI 回调 + Java 调用, 一并保留 native 方法名
-keepclasseswithmembernames class com.tumuyan.ncnn.realsr.Anime4kProcessor {
    native <methods>;
}