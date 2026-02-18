package moe.chenxy.oppopods.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.NoiseControlMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable

@Composable
fun AncSwitch(ancStatus: NoiseControlMode, onAncModeChange: (NoiseControlMode) -> Unit) {
    val isDarkMode = isSystemInDarkTheme()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AncButton(
            iconRes = R.drawable.ic_transparency,
            label = stringResource(R.string.transparency_title),
            isSelected = ancStatus == NoiseControlMode.TRANSPARENCY,
            isDarkMode = isDarkMode,
            onClick = { onAncModeChange(NoiseControlMode.TRANSPARENCY) },
            modifier = Modifier.weight(1f)
        )
        AncButton(
            iconRes = R.drawable.ic_anc,
            label = stringResource(R.string.noise_cancellation_title),
            isSelected = ancStatus == NoiseControlMode.NOISE_CANCELLATION,
            isDarkMode = isDarkMode,
            onClick = { onAncModeChange(NoiseControlMode.NOISE_CANCELLATION) },
            modifier = Modifier.weight(1f)
        )
        AncButton(
            iconRes = R.drawable.ic_normal,
            label = stringResource(R.string.off),
            isSelected = ancStatus == NoiseControlMode.OFF,
            isDarkMode = isDarkMode,
            onClick = { onAncModeChange(NoiseControlMode.OFF) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AncButton(
    iconRes: Int,
    label: String,
    isSelected: Boolean,
    isDarkMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .pressable(interactionSource = interactionSource, indication = SinkFeedback())
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isSelected -> MiuixTheme.colorScheme.primary
                        isDarkMode -> Color(0xFF3C3C3C)
                        else -> Color(0xFFE8E8E8)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = label,
                colorFilter = ColorFilter.tint(
                    when {
                        isSelected -> Color.White
                        isDarkMode -> Color.LightGray
                        else -> Color(0xFF5E5E5E)
                    }
                ),
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground
        )
    }
}
