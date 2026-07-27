# The transfer protocol is reached only through TransferClient/TransferServer,
# so R8 is free to shrink it -- nothing here is loaded reflectively.

# ZXing reflects over format enums when building its reader hint map.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Kotlin coroutines' debug agent probes for these; they are absent in release.
-dontwarn kotlinx.coroutines.debug.**
