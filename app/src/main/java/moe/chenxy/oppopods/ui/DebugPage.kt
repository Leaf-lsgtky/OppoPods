package moe.chenxy.oppopods.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.BtLogEntry
import moe.chenxy.oppopods.pods.BtLogStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun DebugPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    loggingEnabled: Boolean = false,
    onLoggingEnabledChange: (Boolean) -> Unit = {},
    onOpenLog: () -> Unit = {}
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().scrollEndHaptic(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
        overscrollEffect = null,
    ) {
        item {
            Card {
                SwitchPreference(
                    title = stringResource(R.string.debug_enable_logging),
                    summary = stringResource(R.string.debug_enable_logging_summary),
                    checked = loggingEnabled,
                    onCheckedChange = onLoggingEnabledChange
                )
                ArrowPreference(
                    title = stringResource(R.string.debug_view_log),
                    onClick = onOpenLog
                )
            }
        }
    }
}

@Composable
fun DebugLogPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onClear: () -> Unit = {}
) {
    val entries by BtLogStore.entries.collectAsState()
    val listState = rememberLazyListState()
    var expandedEntries by remember { mutableStateOf(setOf<Int>()) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().scrollEndHaptic().overScrollVertical(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
        overscrollEffect = null,
    ) {
        item {
            Card {
                ArrowPreference(
                    title = stringResource(R.string.debug_clear_log),
                    onClick = onClear
                )
            }
        }

        if (entries.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.debug_no_log),
                    modifier = Modifier.padding(top = 24.dp).padding(horizontal = 12.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }

        items(entries.size) { index ->
            val entry = entries.reversed()[index]
            val isExpanded = index in expandedEntries
            BtLogEntryItem(
                entry = entry,
                expanded = isExpanded,
                onToggleExpand = {
                    expandedEntries = if (isExpanded) expandedEntries - index
                    else expandedEntries + index
                }
            )
        }
    }
}

@Composable
private fun BtLogEntryItem(
    entry: BtLogEntry,
    expanded: Boolean,
    onToggleExpand: () -> Unit
) {
    Card(
        modifier = Modifier.padding(top = 8.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onToggleExpand
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (entry.isSend) Color(0xFF4CAF50) else Color(0xFF2196F3),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (entry.isSend) stringResource(R.string.debug_log_send)
                            else stringResource(R.string.debug_log_recv),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                    if (entry.label != null) {
                        Text(
                            text = "  ${entry.label}",
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 13.sp
                        )
                    }
                }
                Text(
                    text = entry.timeFormatted(),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = formatHexAnnotated(entry.hex),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatHexAnnotated(hex: String): androidx.compose.ui.text.AnnotatedString {
    val bytes = hex.chunked(2)
    val formatted = bytes.joinToString(" ")

    return buildAnnotatedString {
        append(formatted)

        fun colorBytes(startByte: Int, endByte: Int, color: Color) {
            val startChar = startByte * 3
            val endChar = minOf(endByte * 3 + 2, formatted.length)
            if (startChar < formatted.length) {
                addStyle(SpanStyle(color = color), startChar, endChar)
            }
        }

        val dimmed = Color(0xFF888888)
        val cmdColor = Color(0xFF64B5F6)
        val payloadColor = Color(0xFFFFB74D)

        colorBytes(0, 0, dimmed)    // header AA
        colorBytes(1, 1, dimmed)    // length
        colorBytes(2, 3, dimmed)    // reserved 00 00
        colorBytes(4, 5, cmdColor)  // command
        colorBytes(6, 8, dimmed)    // seq + payload length
        if (bytes.size > 9) {
            colorBytes(9, bytes.size - 1, payloadColor) // payload
        }
    }
}
