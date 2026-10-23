# Keep application entry points.
-keep class io.github.perfecticons.MainActivity { *; }
-keep class io.github.perfecticons.PerfectIconsApp { *; }

# JGit references optional Java SE management and GSSAPI classes that do not
# exist on Android. They are only used by JMX monitoring and Kerberos auth, which
# this app never enables, so R8 may ignore them.
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn org.eclipse.jgit.util.Monitoring
-dontwarn org.eclipse.jgit.transport.HttpAuthMethod$Negotiate

# JGit loads a few classes reflectively (transport/file-system service loaders);
# its reflective surface is broad, so the keep is deliberately wide and the
# release build is verified in CI rather than narrowed speculatively.
-keep class org.eclipse.jgit.** { *; }
-keep interface org.eclipse.jgit.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# RootInstaller 通过反射把 libsu 缓存的 root 结论重置为“未知”，否则运行期间
# 新授予的 root 不会生效。字段被混淆或裁剪会让重置静默失效，因此保留名字。
-keepnames class com.topjohnwu.superuser.internal.Utils
-keepclassmembers class com.topjohnwu.superuser.internal.Utils {
    private static int currentRootState;
}

# Keep the SAF provider entry point.
-keep class io.github.perfecticons.provider.** { *; }

# JGit's pack garbage collector probes ProcessHandle, unavailable before API 24
# and unused here because auto gc is disabled.
-dontwarn java.lang.ProcessHandle
