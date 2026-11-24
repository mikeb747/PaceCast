# PaceCast ProGuard / R8 Rules
# Keep application entry points, BLE GATT callbacks, Foreground Service, and Android 14/15 ActivityOptions reflection
-keep class com.pacecast.app.** { *; }
-dontwarn android.app.ActivityOptions
