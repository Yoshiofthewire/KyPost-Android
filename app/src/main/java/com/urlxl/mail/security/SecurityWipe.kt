package com.urlxl.mail.security

import android.content.Context
import com.urlxl.mail.data.DataRuntime
import com.urlxl.mail.push.SecurePairingStore

/**
 * Full destructive reset: runs when [LockoutPolicy.WIPE_THRESHOLD] wrong PIN attempts
 * accumulate, and when the user explicitly turns "Require Unlock to Open" off (which also
 * clears the PIN, since a stale PIN with lock disabled would be confusing state). Closes and
 * deletes the Room database, clears pairing credentials (forcing re-pairing), and clears the
 * app-lock PIN/flags — the app ends up in exactly its first-run state.
 */
object SecurityWipe {
    suspend fun wipeAndResetApp(context: Context) {
        DataRuntime.graph(context).database.close()
        context.deleteDatabase("kypost_mail.db")
        SecurePairingStore(context).clearPairing()
        AppLockStore(context).reset()
    }
}
