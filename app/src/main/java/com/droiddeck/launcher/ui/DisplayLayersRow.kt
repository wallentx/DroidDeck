package com.droiddeck.launcher.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs

/** The saved SDR choice; HDR always keeps display layers enabled without changing that choice. */
@Composable
internal fun DisplayLayersRow(
    host: MenuHost,
    sdrZeroCopy: Boolean,
    hdr: Boolean,
    onChange: (Boolean) -> Unit,
    chipModifier: Modifier = Modifier,
) {
    val available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    ToggleRow(
        host, "sdr-layers", stringResource(R.string.display_sdr_layers),
        stringResource(when {
            !available -> R.string.display_layers_unavailable
            hdr -> R.string.display_layers_hdr
            else -> R.string.display_sdr_layers_hint
        }),
        checked = SessionPrefs.useDisplayLayers(hdr, sdrZeroCopy),
        enabled = available && !hdr,
        chipModifier = chipModifier,
        onChange = onChange,
    )
}
