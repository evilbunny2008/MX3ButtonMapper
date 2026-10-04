# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.

# Shizuku starts KeyInjectorUserService in its own shell-UID process by
# reflection (Class.forName + getDeclaredConstructor().newInstance()), so
# R8 can't see that anything ever calls its constructor and strips it.
# Without this, release builds fail every bind with
# "NoSuchMethodException: <obfuscated>.<init> []", key injection never
# comes up, and every remapped button falls through to its original
# behaviour (e.g. Menu opening the TCL TV's own settings bar).
-keep class com.odiousapps.mx3buttonmapper.KeyInjectorUserService {
    <init>(...);
}
