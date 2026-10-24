package io.github.nanomuse.ui.cloud

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsScaffold

/** Compatibility destination for old account links while cross-device Relay access is paused. */
@Composable
fun RelayPendingScreen(onBack: () -> Unit) {
    SettingsScaffold(title = stringResource(R.string.nm_pc_hub_title), onBack = onBack) {
        Text(
            text = stringResource(R.string.nm_pc_hub_body),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}
