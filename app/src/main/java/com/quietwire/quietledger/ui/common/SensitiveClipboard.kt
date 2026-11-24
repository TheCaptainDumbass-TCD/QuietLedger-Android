package com.quietwire.quietledger.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Copies a secret to the clipboard, flagged so the system does not put it on
 * display.
 *
 * Android 13 and above show a preview toast of whatever was copied and keep it
 * in clipboard history. For a TOTP shared secret that is the last thing you
 * want, so the clip is marked sensitive, which suppresses both.
 *
 * It is still the clipboard, and other apps can read it until it is replaced.
 * That is an accepted trade: on a single phone there is no way to get a secret
 * into an authenticator app without it passing through somewhere, and the
 * alternative is the user transcribing 32 characters by hand and getting it
 * wrong.
 */
fun Context.copySensitive(label: String, value: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, value)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }

    clipboard.setPrimaryClip(clip)
}
