package moe.chenxy.oppopods.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import moe.chenxy.oppopods.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设备选择页。
 *
 * 版式遵循 `docs`/ui 规范：LazyColumn 只吃顶部 inset、末尾统一留 24dp + 导航栏
 * 间距；**每一条设备各自一张 Card**（水平 12dp、下 12dp），不使用
 * `Arrangement.spacedBy`，也不把多行塞进同一张卡。
 *
 * 页面骨架（Scaffold + 顶栏 + 返回）由 [moe.chenxy.oppopods.ui.components.SecondaryPageScaffold]
 * 提供，这里只负责内容，避免重复套一层 Scaffold 导致 inset 叠加。
 */
@SuppressLint("MissingPermission")
@Composable
fun DevicePickerPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    scrollBehavior: ScrollBehavior? = null,
    connectedAddress: String? = null,
    lastConnectedAddress: String? = null,
    onDeviceSelected: (BluetoothDevice) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    var showMacDialog by remember { mutableStateOf(false) }
    var macInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.bt_permission_required),
                    color = MiuixTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(
                    text = stringResource(R.string.grant_permission),
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                )
            }
        }
        return
    }

    val adapter = remember(hasPermission) {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    }
    // OPPO 系设备排在前面，避免列表里翻找。
    val pairedDevices = remember(hasPermission, adapter) {
        adapter?.bondedDevices?.toList()?.sortedByDescending {
            it.name?.contains("oppo", ignoreCase = true) == true
        } ?: emptyList()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .then(
                if (scrollBehavior != null) {
                    Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                } else {
                    Modifier
                }
            ),
        // 只吃顶部 inset；底部由末尾 Spacer 统一兜住。
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding()),
    ) {
        // 首个 item 是 Card，按规范补 12dp 顶距（顶栏已经吃掉系统 inset）。
        item { Spacer(Modifier.height(12.dp)) }

        if (pairedDevices.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    BasicComponent(title = stringResource(R.string.no_paired_devices))
                }
            }
        }

        items(pairedDevices, key = { it.address }) { device ->
            val isConnected = connectedAddress != null &&
                    connectedAddress.equals(device.address, ignoreCase = true)
            val isLastConnected = !isConnected && lastConnectedAddress != null &&
                    lastConnectedAddress.equals(device.address, ignoreCase = true)
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                BasicComponent(
                    title = device.name ?: stringResource(R.string.unknown_device),
                    summary = device.address,
                    endActions = {
                        if (isConnected || isLastConnected) {
                            DeviceStateTag(
                                text = stringResource(
                                    if (isConnected) R.string.tag_connected
                                    else R.string.tag_last_connected
                                )
                            )
                        }
                    },
                    onClick = { onDeviceSelected(device) },
                )
            }
        }

        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                BasicComponent(
                    title = stringResource(R.string.input_mac_manually),
                    onClick = { showMacDialog = true },
                )
            }
        }

        item {
            Spacer(
                Modifier
                    .height(24.dp)
                    .navigationBarsPadding()
            )
        }
    }

    OverlayDialog(
        title = stringResource(R.string.input_mac_title),
        show = showMacDialog,
        onDismissRequest = { showMacDialog = false },
    ) {
        TextField(
            value = macInput,
            onValueChange = { macInput = it.uppercase() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 取消保持默认样式，只有「连接」用强调色；两个按钮各占一半铺满整行。
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { showMacDialog = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.connect),
                onClick = {
                    val mac = macInput.trim()
                    if (BluetoothAdapter.checkBluetoothAddress(mac)) {
                        val device = adapter?.getRemoteDevice(mac)
                        if (device != null) {
                            showMacDialog = false
                            onDeviceSelected(device)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = BluetoothAdapter.checkBluetoothAddress(macInput.trim()),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 设备状态标签：灰色圆角矩形。
 *
 * 圆角用 miuix 的 squircle 背景而不是 `RoundedCornerShape`，与卡片、开关等
 * 系统原生形状的连续曲率保持一致。
 */
@Composable
private fun DeviceStateTag(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = MiuixTheme.colorScheme.onSecondaryContainerVariant,
        modifier = Modifier
            .squircleBackground(MiuixTheme.colorScheme.secondaryContainer, 6.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}