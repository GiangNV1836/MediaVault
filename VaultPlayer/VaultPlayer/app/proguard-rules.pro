-keep class org.videolan.** { *; }
-dontwarn org.videolan.**
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**
-keep class androidx.room.** { *; }
# Bản release không in log (tránh lộ tên file/đường dẫn).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
