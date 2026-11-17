# 本工程没有混淆（minifyEnabled false），这份规则只在以后打开混淆时才生效。
# WebView 通过反射调用 JS 接口时，相关类名不能被改。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class io.github.fanqin314.quaddiary.** { *; }
