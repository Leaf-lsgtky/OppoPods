package moe.chenxy.oppopods.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
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

@Composable
fun AncSwitch(ancStatus: NoiseControlMode, onAncModeChange: (NoiseControlMode) -> Unit) {
    val isDarkMode = isSystemInDarkTheme()
    val switchWidth = 109.dp
    val switchFullWidth = switchWidth * 3
    val colModifier = Modifier.width(switchWidth).padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 5.dp)
    Box(
        Modifier
            .padding(start = 12.dp, end = 12.dp)
            .fillMaxWidth()
            .height(58.dp)
            .background(if (isDarkMode) Color.DarkGray else Color(0xFFE2E2E8), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        val offsetX = animateDpAsState(
            targetValue = when (ancStatus) {
                NoiseControlMode.OFF -> 0.dp
                NoiseControlMode.NOISE_CANCELLATION -> switchWidth
                NoiseControlMode.TRANSPARENCY -> switchWidth * 2
            },
            label = "AncSwitchAnimation",
            animationSpec = spring(0.78f, Spring.StiffnessLow)
        )

        Box {
            Row(
                Modifier
                    .width(switchWidth)
                    .fillMaxHeight()
            ) {
                Box(
                    modifier = Modifier
                        .width(switchWidth)
                        .fillMaxHeight()
                        .padding(3.dp)
                        .offset(x = offsetX.value)
                        .shadow(10.dp, RoundedCornerShape(8.dp))
                        .background(if (isDarkMode) Color.Gray else Color.White, RoundedCornerShape(8.dp))
                )
            }
            Row(
                modifier = Modifier.width(switchFullWidth),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = colModifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onAncModeChange(NoiseControlMode.OFF) }
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(
                        painterResource(R.drawable.noise_cancellation),
                        contentDescription = "ANC Off",
                        colorFilter = ColorFilter.tint(if (isDarkMode) Color.LightGray else Color.Gray)
                    )
                }
                Column(
                    modifier = colModifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onAncModeChange(NoiseControlMode.NOISE_CANCELLATION) }
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        painterResource(R.drawable.noise_cancellation),
                        contentDescription = "ANC On",
                        colorFilter = ColorFilter.tint(if (isDarkMode) Color.White else Color.DarkGray)
                    )
                }
                Column(
                    modifier = colModifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onAncModeChange(NoiseControlMode.TRANSPARENCY) }
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        painterResource(R.drawable.transparency),
                        contentDescription = "Transparency",
                        colorFilter = ColorFilter.tint(if (isDarkMode) Color.White else Color.DarkGray)
                    )
                }
            }
        }
    }

    Box(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 5.dp, bottom = 5.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.width(switchFullWidth),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = colModifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.off), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = colModifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.noise_cancellation_title), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = colModifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.transparency_title), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
