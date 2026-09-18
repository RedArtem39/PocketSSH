-keep class net.schmizz.sshj.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.eddsa.**

# sshj supports GSSAPI/Kerberos auth, whose JDK classes don't exist on Android. We never use
# that auth method (only password/private key), so this path is genuinely dead code for us.
-dontwarn javax.security.auth.login.LoginContext
-dontwarn org.ietf.jgss.**
