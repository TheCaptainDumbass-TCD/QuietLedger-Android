package com.quietwire.quietledger

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quietwire.quietledger.core.biometric.BiometricGate
import com.quietwire.quietledger.session.SessionState
import com.quietwire.quietledger.ui.QuietLedgerApp
import com.quietwire.quietledger.ui.lock.LockScreen
import com.quietwire.quietledger.ui.lock.LockViewModel
import com.quietwire.quietledger.ui.lock.SetupScreen
import com.quietwire.quietledger.ui.theme.QuietLedgerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep financial figures out of screenshots and the recent apps
        // preview. Release builds only: in debug this has to be off, because
        // it turns every screenshot black and there is no way to share a
        // layout problem without one.
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )

            // Overlay defence, for the passphrase screen in particular. A
            // malicious app drawing on top can both read what is under its
            // transparent regions and intercept taps, which is how tapjacking
            // works. Android 12 added a way to hide other apps' overlays
            // outright while this window is in front.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.setHideOverlayWindows(true)
            }

            // Below Android 12 there is no way to remove the overlay, but the
            // view can at least refuse touches that arrive through one, so a
            // hidden button cannot be pressed on the user's behalf.
            window.decorView.filterTouchesWhenObscured = true
        }

        enableEdgeToEdge()
        setContent {
            QuietLedgerTheme {
                val viewModel: LockViewModel = hiltViewModel()
                val session by viewModel.sessionState.collectAsStateWithLifecycle()
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val windowSizeClass = calculateWindowSizeClass(this)

                // Measured, not inferred. Earlier the size class was guessed
                // from how many grid columns appeared, and the guess was
                // wrong. Filter logcat on QuietLedger/Window to see it.
                if (BuildConfig.DEBUG) {
                    // LocalWindowInfo.containerSize rather than
                    // Configuration.screenWidthDp, which lint flags because the
                    // Configuration values do not track the actual window on
                    // foldables and in multi-window.
                    val density = LocalDensity.current
                    val containerSize = LocalWindowInfo.current.containerSize
                    LaunchedEffect(containerSize, windowSizeClass) {
                        with(density) {
                            Log.i(
                                "QuietLedger/Window",
                                "width=${containerSize.width.toDp()} " +
                                    "height=${containerSize.height.toDp()} " +
                                    "density=${density.density} " +
                                    "widthClass=${windowSizeClass.widthSizeClass} " +
                                    "heightClass=${windowSizeClass.heightSizeClass}"
                            )
                        }
                    }
                }

                // The effective policy depends on what the hardware can do, so
                // the attempt is started once the lock screen is reached rather
                // than in the view model's constructor.
                val availability = remember { BiometricGate.availability(this) }
                LaunchedEffect(session) {
                    if (session is SessionState.Locked) viewModel.startAttempt(availability)
                }

                when (session) {
                    is SessionState.Unlocked ->
                        QuietLedgerApp(
                            widthSizeClass = windowSizeClass.widthSizeClass,
                            onLock = viewModel::lock
                        )

                    is SessionState.NeedsSetup ->
                        SetupScreen(state = ui, onFinish = viewModel::setUp)

                    is SessionState.Locked ->
                        LockScreen(
                            state = ui,
                            onUnlockWithDeviceKey = viewModel::biometricSucceeded,
                            onUnlockWithPassphrase = viewModel::unlockWithPassphrase,
                            onUsePassphrase = viewModel::showPassphraseEntry,
                            canUsePassphrase = viewModel.hasPassphrase,
                            biometricAvailability = availability,
                            onBiometricPrompt = { promptBiometric(viewModel) },
                            onTotpCodeChange = viewModel::updateTotpCode,
                            onRecoveryCodeChange = viewModel::updateRecoveryCode,
                            onUseRecoveryCode = viewModel::useRecoveryCode,
                            onSubmitTotp = viewModel::submitTotpCode
                        )

                    is SessionState.Unrecoverable ->
                        LockScreen(
                            state = ui.copy(error = (session as SessionState.Unrecoverable).message),
                            onUnlockWithDeviceKey = viewModel::biometricSucceeded,
                            onUnlockWithPassphrase = viewModel::unlockWithPassphrase,
                            onUsePassphrase = viewModel::showPassphraseEntry,
                            canUsePassphrase = viewModel.hasPassphrase,
                            biometricAvailability = availability,
                            onBiometricPrompt = { promptBiometric(viewModel) },
                            onTotpCodeChange = viewModel::updateTotpCode,
                            onRecoveryCodeChange = viewModel::updateRecoveryCode,
                            onUseRecoveryCode = viewModel::useRecoveryCode,
                            onSubmitTotp = viewModel::submitTotpCode
                        )

                    SessionState.Starting -> Unit
                }
            }
        }
    }

    private fun promptBiometric(viewModel: LockViewModel) {
        BiometricGate.prompt(
            activity = this,
            onSucceeded = viewModel::biometricSucceeded,
            onFailed = viewModel::reportBiometricFailure,
            // Backing out is not an error, so the screen stays as it was.
            onCancelled = {}
        )
    }
}
