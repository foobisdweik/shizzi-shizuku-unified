package dev.shizzi

import android.content.pm.PackageManager
import android.os.Bundle
import android.service.quicksettings.TileService
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import dev.shizzi.ui.Screen
import dev.shizzi.ui.theme.Appearance
import dev.shizzi.ui.theme.ShizziTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SessionViewModel by viewModels()

    private var requested: AppPermission? = null

    private var isChaining = false

    private val asked = mutableSetOf<AppPermission>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshPermissions()
            onPermissionResult()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            HelperBridge.service.collect { viewModel.refreshHelperState() }
        }
        SessionService.protect(this)

        // Long-pressing the quick settings tile lands on settings, like the stock hotspot tile.
        val startScreen = when (intent?.action) {
            TileService.ACTION_QS_TILE_PREFERENCES -> Screen.SETTINGS
            else -> Screen.HOME
        }

        setContent {
            val settings by viewModel.settings.collectAsState()
            val loaded = settings ?: return@setContent

            val appearance = Appearance(
                theme = loaded.theme,
                design = loaded.design,
                accent = loaded.accent,
            )

            ShizziTheme(appearance = appearance) {
                val colors = ShizziTheme.colors

                SideEffect {
                    if (loaded.protection.keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    WindowCompat.getInsetsController(window, window.decorView)
                        .isAppearanceLightStatusBars = !colors.isDark
                }

                Surface(color = colors.background) {
                    val state by viewModel.state.collectAsState()
                    val diagnostics by viewModel.diagnosticsState.collectAsState()
                    val compatibility by viewModel.compatibilityState.collectAsState()
                    val permissions by viewModel.permissionState.collectAsState()

                    ShizziApp(
                        state = AppState(
                            session = state,
                            settings = loaded,
                            diagnostics = diagnostics,
                            permissions = permissions,
                        ),
                        onboarding = OnboardingEntry(
                            compatibility = compatibility,
                            onCheckCompatibility = viewModel::checkCompatibility,
                            onDownloadTetheringApex = viewModel::downloadTetheringApex,
                            onInstallTetheringApex = viewModel::installTetheringApex,
                            onRebootDevice = viewModel::rebootDevice,
                            onComplete = viewModel::completeOnboarding,
                        ),
                        actions = AppActions(
                            onToggle = viewModel::toggle,
                            onCancel = viewModel::cancel,
                            onRequestPermission = viewModel::requestPermission,
                            onRequestAllPermissions = ::requestAllPermissions,
                            onGrantPermission = ::grantPermission,
                            onHelperAction = viewModel::actOnHelper,
                            onSetTheme = viewModel::setTheme,
                            onSetDesign = viewModel::setDesign,
                            onSetAccent = viewModel::setAccent,
                            onAddCustomAccent = viewModel::addCustomAccent,
                            onSetLogging = viewModel::setLogging,
                            onSetKeepAlive = viewModel::setKeepAlive,
                            onSetVpnMode = viewModel::setVpnMode,
                            onSetUsbTethering = viewModel::setUsbTethering,
                            onRunProbes = viewModel::runProbes,
                            onDismissDiagnostics = viewModel::dismissDiagnostics,
                            onClearLog = viewModel::clearLog,
                            onRestartOnboarding = viewModel::restartOnboarding,
                            onSetAutomation = viewModel::setAutomation,
                            onRegenerateAutomationToken =
                                viewModel::regenerateAutomationToken,
                        ),
                        startScreen = startScreen,
                    )
                }
            }
        }

        holdFirstFrameUntilSettingsLoad()
    }

    private fun holdFirstFrameUntilSettingsLoad() {
        val content = findViewById<View>(android.R.id.content)

        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (viewModel.settings.value == null) return false

                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
    }

    private fun requestAllPermissions() {
        isChaining = true
        requestNextOutstanding()
    }

    private fun requestNextOutstanding() {
        if (requestHelper()) return

        val outstanding = viewModel.permissionState.value.firstOrNull { !it.isGranted }
        val permission = outstanding?.permission

        if (permission == null) {
            isChaining = false
            return
        }

        grantPermission(permission)
    }

    // Helper setup ends this permission chain. Android pairing can need Developer options.
    private fun requestHelper(): Boolean {
        val state = viewModel.state.value.helperState
        if (state is HelperState.Ready) return false

        if (state !is HelperState.PermissionRequired) stopChain()

        viewModel.actOnHelper()
        return true
    }

    // A permission with no manifest name is granted on a settings screen in
    // another app, so there is no dialog to launch and no result to wait for.
    private fun grantPermission(permission: AppPermission) {
        val name = permission.manifestName

        if (name == null || isDialogSuppressed(permission)) {
            stopChain()
            viewModel.openPermissionSettings(permission)
            return
        }

        requested = permission
        asked += permission
        permissionLauncher.launch(name)
    }

    private fun onPermissionResult() {
        val permission = requested ?: return
        requested = null

        if (!isChaining) return

        if (viewModel.isPermissionGranted(permission)) requestNextOutstanding() else stopChain()
    }

    private fun onHelperResult(isGranted: Boolean) {
        if (!isChaining) return

        if (isGranted) requestNextOutstanding() else stopChain()
    }

    private fun stopChain() {
        isChaining = false
    }

    // shouldShowRequestPermissionRationale is also false before the first ask, so
    // only a permission this process has already requested can be suppressed.
    private fun isDialogSuppressed(permission: AppPermission): Boolean {
        val name = permission.manifestName ?: return false
        if (permission !in asked) return false

        return !shouldShowRequestPermissionRationale(name)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshHelperState()
        viewModel.refreshPermissions()
    }

}
