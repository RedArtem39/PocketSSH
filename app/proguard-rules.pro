-keep class net.schmizz.sshj.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

# Registered as a real java.security.Provider (see PocketSshApplication) — its algorithm-to-impl
# lookup table is reflection-based, so R8 can't see those references and will strip "unused"
# classes unless kept explicitly.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# sshj supports GSSAPI/Kerberos auth, whose JDK classes don't exist on Android. We never use
# that auth method (only password/private key), so this path is genuinely dead code for us.
-dontwarn javax.security.auth.login.LoginContext
-dontwarn org.ietf.jgss.**
