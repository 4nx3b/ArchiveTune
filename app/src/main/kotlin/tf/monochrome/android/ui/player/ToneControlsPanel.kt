package tf.monochrome.android.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import tf.monochrome.android.domain.model.ToneControls

@Composable
fun ToneControlsPanel(
    tone: ToneControls,
    accent: Color,
    onChange: (ToneControls) -> Unit,
    contentColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "TONE",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Switch(
                checked = tone.enabled,
                onCheckedChange = { checked -> onChange(tone.copy(enabled = checked)) },
            )
        }

        ToneGainRow(
            label = "Bass",
            gainDb = tone.bassGainDb,
            enabled = tone.enabled,
            accent = accent,
            contentColor = contentColor,
            onGainChange = { gain -> onChange(tone.copy(bassGainDb = gain)) },
        )
        ToneGainRow(
            label = "Treble",
            gainDb = tone.trebleGainDb,
            enabled = tone.enabled,
            accent = accent,
            contentColor = contentColor,
            onGainChange = { gain -> onChange(tone.copy(trebleGainDb = gain)) },
        )
    }
}

@Composable
private fun ToneGainRow(
    label: String,
    gainDb: Float,
    enabled: Boolean,
    accent: Color,
    contentColor: Color,
    onGainChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.takeIf { it != Color.Unspecified }
                    ?: MaterialTheme.colorScheme.onSurface,
            )
            val signed = if (gainDb > 0f) "+%.1f dB".format(gainDb) else "%.1f dB".format(gainDb)
            Text(
                if (abs(gainDb) < 0.05f) "0 dB" else signed,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) accent else accent.copy(alpha = 0.5f),
            )
        }
        Slider(
            value = if (gainDb.isNaN()) 0f else gainDb.coerceIn(ToneControls.GAIN_MIN, ToneControls.GAIN_MAX),
            onValueChange = onGainChange,
            valueRange = ToneControls.GAIN_MIN..ToneControls.GAIN_MAX,
            enabled = enabled,
            steps = 47,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
