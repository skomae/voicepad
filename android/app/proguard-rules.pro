# sherpa-onnx calls back into these classes from JNI by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# HanLP loads its dictionary and pinyin tables from files inside the jar and
# reaches them through reflection, so R8 must leave the whole package (and its
# bundled data) alone.
-keep class com.hankcs.hanlp.** { *; }
-dontwarn com.hankcs.hanlp.**
