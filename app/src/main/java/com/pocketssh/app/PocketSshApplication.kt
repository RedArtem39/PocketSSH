package com.pocketssh.app

import android.app.Application
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * sshj's curve25519-sha256 key exchange and ed25519 key handling resolve algorithms like X25519
 * through the standard JCE provider lookup. Android itself pre-registers its own crippled,
 * legacy-only BouncyCastle fork under the exact name "BC" (DSA/DH/RSA, nothing modern) before any
 * app code runs. `Security.insertProviderAt` silently no-ops when a provider with the same name
 * is already installed, so simply inserting our real BouncyCastleProvider does nothing — the OS's
 * copy has to be removed first for ours to actually take over the "BC" name.
 */
class PocketSshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
