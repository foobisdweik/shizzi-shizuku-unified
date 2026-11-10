-keep class dev.shizzi.TetherService { *; }

-keep interface dev.shizzi.ITetherService { *; }
-keep class dev.shizzi.ITetherService$* { *; }

-keep class dev.shizzi.adb.PairingContext { *; }
-keep class dev.shizzi.adb.AdbPairingClient { *; }

-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

-keep class dev.shizzi.App
-keep class dev.shizzi.MainActivity
-keep class dev.shizzi.SessionService

-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The shell starts these entrypoints by their class names.
-keep class dev.shizzi.HelperMain { *; }
-keep class dev.shizzi.HelperRuntime { *; }
-keep class dev.shizzi.HelperProcess { *; }
