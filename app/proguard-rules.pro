# Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}

-repackageclasses
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile

# Keep Xposed entry point
-keep class moe.chenxy.oppopods.hook.HookEntry { *; }
-keep class moe.chenxy.oppopods.hook.HookEntry_YukiHookXposedInit { *; }

# Keep all hooker classes (referenced by name in Xposed framework)
-keep class moe.chenxy.oppopods.hook.** { *; }

# Keep YukiHookAPI generated classes
-keep class com.highcapable.yukihookapi.** { *; }

# Keep Parcelable data classes (used in broadcast extras)
-keep class moe.chenxy.oppopods.utils.miuiStrongToast.data.** { *; }
