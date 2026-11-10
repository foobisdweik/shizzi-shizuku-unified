package dev.shizzi.ui.onboarding

import dev.shizzi.CompatibilityState
import dev.shizzi.PermissionStatus
import dev.shizzi.HelperState

data class OnboardingState(
    val helper: HelperState,
    val compatibility: CompatibilityState,
    val permissions: List<PermissionStatus>,
)
