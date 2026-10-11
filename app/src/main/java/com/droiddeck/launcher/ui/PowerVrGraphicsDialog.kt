package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.PowerVrGraphicsProfile

internal enum class PowerVrGraphicsDialogPurpose { FIRST_LAUNCH, REPAIR, SETTINGS }

internal data class PowerVrGraphicsUiState(
    val purpose: PowerVrGraphicsDialogPurpose,
    val mode: PowerVrGraphicsProfile.Mode = PowerVrGraphicsProfile.Mode.UNDECIDED,
    val selectedVersion: String? = null,
    val availableVersion: String? = null,
    val selectedInstalled: Boolean = false,
    val availableInstalled: Boolean = false,
    val compatible: Boolean = true,
    val loading: Boolean = false,
    val working: Boolean = false,
    val progress: Int = -1,
    val failed: Boolean = false,
    val failureMessage: String? = null,
) {
    val updateAvailable: Boolean
        get() = mode == PowerVrGraphicsProfile.Mode.EXPERIMENTAL && selectedInstalled &&
            selectedVersion != null && availableVersion != null && selectedVersion != availableVersion

    val needsRepair: Boolean
        get() = mode == PowerVrGraphicsProfile.Mode.EXPERIMENTAL && !selectedInstalled
}

/** Global Steam graphics choice shown only on PowerVR devices. */
@Composable
internal fun PowerVrGraphicsDialog(
    state: PowerVrGraphicsUiState,
    onStandard: () -> Unit,
    onExperimental: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = rememberShown(onDismiss)
    val close = { onCancel(); shown.targetState = false }
    val selectedStandard = state.mode == PowerVrGraphicsProfile.Mode.STANDARD
    val selectedExperimental = state.mode == PowerVrGraphicsProfile.Mode.EXPERIMENTAL

    AppDialog(shown, close, "powerVrGraphics", wide = false, maxWidth = 640.dp) {
        DialogHeader(
            stringResource(R.string.power_vr_graphics_eyebrow),
            stringResource(
                if (state.purpose == PowerVrGraphicsDialogPurpose.FIRST_LAUNCH) {
                    R.string.power_vr_graphics_choose_title
                } else {
                    R.string.power_vr_graphics_title
                },
            ),
        )
        Text(
            stringResource(R.string.power_vr_graphics_intro),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )

        PowerVrChoiceCard(
            title = stringResource(R.string.power_vr_graphics_standard),
            detail = stringResource(R.string.power_vr_graphics_standard_detail),
            selected = selectedStandard,
        )
        PowerVrChoiceCard(
            title = stringResource(R.string.power_vr_graphics_experimental),
            detail = stringResource(R.string.power_vr_graphics_experimental_detail),
            selected = selectedExperimental,
            enabled = state.compatible,
        )

        val status = when {
            state.loading -> stringResource(R.string.power_vr_graphics_checking)
            state.failed -> state.failureMessage?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.power_vr_graphics_failed)
            !state.compatible -> stringResource(R.string.power_vr_graphics_custom_driver)
            state.needsRepair -> stringResource(R.string.power_vr_graphics_repair_needed)
            state.updateAvailable -> stringResource(R.string.power_vr_graphics_update_available)
            selectedExperimental && state.selectedInstalled -> stringResource(R.string.power_vr_graphics_ready)
            state.availableInstalled -> stringResource(R.string.power_vr_graphics_downloaded)
            else -> stringResource(R.string.power_vr_graphics_shared_download)
        }
        Small(status, error = state.failed || state.needsRepair || !state.compatible)

        if (state.working || state.loading) {
            if (state.progress >= 0) {
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        val experimentalAction = when {
            state.failed -> R.string.power_vr_graphics_retry
            state.needsRepair -> R.string.power_vr_graphics_repair
            state.updateAvailable -> R.string.common_update
            else -> R.string.power_vr_graphics_experimental
        }
        Small(stringResource(R.string.power_vr_graphics_next_game))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SecondaryButton(
                stringResource(R.string.power_vr_graphics_standard),
                modifier = Modifier.weight(1f),
                enabled = !state.loading && !state.working && (!selectedStandard || state.failed),
                onClick = onStandard,
            )
            PrimaryButton(
                stringResource(experimentalAction),
                modifier = Modifier.weight(1f),
                enabled = !state.loading && !state.working && state.compatible &&
                    (!selectedExperimental || state.needsRepair || state.updateAvailable || state.failed),
                onClick = onExperimental,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SecondaryButton(stringResource(R.string.common_cancel), onClick = close)
        }
    }
}

@Composable
private fun PowerVrChoiceCard(title: String, detail: String, selected: Boolean, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) pal.signal.copy(alpha = 0.10f) else colors.surface.copy(alpha = 0.6f), shape)
            .border(1.dp, if (selected) pal.signal.copy(alpha = 0.65f) else pal.line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                color = if (enabled) colors.onBackground else colors.onSurfaceVariant,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (selected) Chip(stringResource(R.string.power_vr_graphics_selected), ok = true)
        }
        Text(
            detail,
            color = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.6f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}
