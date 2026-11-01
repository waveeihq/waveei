# R8 rules for the release build. OkHttp, Okio, Compose, AndroidX and kotlinx.serialization ship their own rules;
# the app reaches nothing by reflection or JNI, and the manifest keeps its activity, services and receiver.

# Readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The in-app log names exceptions by class (error.javaClass.simpleName); keep those names readable.
-keepnames class * extends java.lang.Throwable
