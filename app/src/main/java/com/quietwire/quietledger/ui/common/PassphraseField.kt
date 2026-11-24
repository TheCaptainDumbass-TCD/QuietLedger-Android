package com.quietwire.quietledger.ui.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Keyboard configuration for any field holding a passphrase.
 *
 * `PasswordVisualTransformation` only hides the glyphs on screen. On its own the
 * keyboard is never told it is handling a secret, so it is free to offer
 * suggestions, autocorrect the input, and keep it in personalised learning.
 * `KeyboardType.Password` maps to the platform's password input type, which
 * switches all of that off.
 *
 * Autocorrect is disabled explicitly too, because a keyboard quietly "fixing" a
 * passphrase produces a wrong-passphrase error with no visible cause.
 */
val PassphraseKeyboardOptions = KeyboardOptions(
    keyboardType = KeyboardType.Password,
    autoCorrectEnabled = false,
    capitalization = KeyboardCapitalization.None,
    imeAction = ImeAction.Done
)
