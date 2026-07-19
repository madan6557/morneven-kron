# Preserve metadata required by Room, Hilt, Credential Manager, and workers.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }
-keep class net.zetetic.** { *; }
-keep class com.morneven.kron.data.KronDatabase$* { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn org.conscrypt.**
