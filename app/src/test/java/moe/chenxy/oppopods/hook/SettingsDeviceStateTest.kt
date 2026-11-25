package moe.chenxy.oppopods.hook

import moe.chenxy.oppopods.pods.DeviceProfile
import moe.chenxy.oppopods.pods.PodCommand
import moe.chenxy.oppopods.pods.ProfileKeys
import org.junit.Assert.*
import org.junit.Test

class SettingsDeviceStateTest {
    private val ready = SettingsDeviceState(
        connected = true,
        ancMode = 1,
        supportedAncModes = listOf(1, 3, 2),
    )

    @Test
    fun transparencyAndNoiseCancellationMatchControllerModes() {
        assertEquals(3, ready.modeForSelection(1))
        assertEquals(2, ready.modeForSelection(2))
        assertEquals(1, ready.copy(ancMode = 3).selectedIndex)
        assertEquals(2, ready.copy(ancMode = 2).selectedIndex)
    }

    @Test
    fun adaptiveUsesItsOwnSelectionWhenSupported() {
        val adaptive = ready.copy(ancMode = 4, supportedAncModes = listOf(1, 3, 2, 4))
        assertEquals(3, adaptive.selectedIndex)
        assertEquals(4, adaptive.modeForSelection(3))
    }

    @Test
    fun unknownProfileOrUnconfirmedModeCannotSendCommands() {
        assertFalse(ready.copy(supportedAncModes = null).canControlAnc)
        assertFalse(ready.copy(ancMode = null).canControlAnc)
        assertFalse(ready.copy(ancMode = 4).canControlAnc)
        assertNull(ready.copy(connected = null).modeForSelection(0))
        assertNull(ready.copy(connected = false).modeForSelection(0))
    }

    @Test
    fun invalidSelectionCannotFallBackToTurningAncOff() {
        assertNull(ready.modeForSelection(-1))
        assertNull(ready.modeForSelection(3))
        assertNull(ready.modeForSelection(Int.MAX_VALUE))
    }

    @Test
    fun knownDeviceWithoutAncHidesControl() {
        val noAnc = ready.copy(supportedAncModes = emptyList())
        assertFalse(noAnc.showAnc)
        assertFalse(noAnc.canControlAnc)
        assertFalse(ready.copy(supportedAncModes = listOf(1)).showAnc)
        assertTrue(SettingsDeviceState().showAnc)
        assertFalse(SettingsDeviceState().canControlAnc)
    }

    @Test
    fun disconnectClearsLiveValuesAndKeepsOnlyDeviceCapabilities() {
        val state = ready.copy(battery = SettingsBatterySnapshot(90, 40, 100)).disconnected()
        assertEquals(false, state.connected)
        assertNull(state.battery)
        assertNull(state.ancMode)
        assertEquals(ready.supportedAncModes, state.supportedAncModes)
        assertNull(state.modeForSelection(1))
        assertTrue(ready.canControlAnc)
    }

    @Test
    fun pendingCommandKeepsOldSelectionUntilMatchingReport() {
        val pending = ready.copy(pendingAncMode = 3)
        assertFalse(pending.canControlAnc)
        assertEquals(0, pending.selectedIndex)
        assertNull(pending.modeForSelection(2))
        assertEquals(3, pending.withAncReport(1).pendingAncMode)
        val confirmed = pending.withAncReport(3)
        assertNull(confirmed.pendingAncMode)
        assertEquals(1, confirmed.selectedIndex)
        assertTrue(confirmed.canControlAnc)
    }

    @Test
    fun timeoutRestoresLastReportedModeWithoutInventingSuccess() {
        val pending = ready.copy(pendingAncMode = 2)
        val timedOut = pending.copy(pendingAncMode = null)
        assertEquals(1, timedOut.ancMode)
        assertEquals(0, timedOut.selectedIndex)
        assertTrue(timedOut.canControlAnc)
        val paused = pending.withoutLiveConfirmation()
        assertNull(paused.connected)
        assertNull(paused.ancMode)
        assertNull(paused.pendingAncMode)
        assertFalse(paused.canControlAnc)
    }

    @Test
    fun overallUsesLowerEarLevelAndDoesNotPreferFullCase() {
        assertEquals(40, SettingsBatterySnapshot(90, 40, 100).overall)
        assertEquals(90, SettingsBatterySnapshot(90, -1, 100).overall)
        assertEquals(40, SettingsBatterySnapshot(-1, 40, 100).overall)
    }

    @Test
    fun overallFallsBackToCaseOnlyWhenBothEarsAreUnknown() {
        assertEquals(60, SettingsBatterySnapshot(-1, -1, 60).overall)
        assertEquals(-1, SettingsBatterySnapshot(-1, -1, -1).overall)
        assertEquals(0, SettingsBatterySnapshot(0, 80, 100).overall)
    }

    @Test
    fun missingAndInvalidLevelsRemainUnknownInsteadOfBecomingFakeBattery() {
        assertEquals(-1, SettingsBatterySnapshot.level(null, true))
        assertEquals(-1, SettingsBatterySnapshot.level(90, false))
        assertEquals(-1, SettingsBatterySnapshot.level(-1, true))
        assertEquals(-1, SettingsBatterySnapshot.level(255, true))
        assertEquals(0, SettingsBatterySnapshot.level(0, true))
        assertEquals(100, SettingsBatterySnapshot.level(100, true))
    }

    @Test
    fun supportedModesFollowActualProfileCommandsAndKeepSemanticOrder() {
        val profile = DeviceProfile(
            id = "test", name = "test", adaptiveVisible = true,
            commands = listOf(
                ProfileKeys.ANC_NC, ProfileKeys.ANC_OFF,
                ProfileKeys.ANC_ADAPTIVE, ProfileKeys.ANC_TRANSPARENCY,
            ).associateWith { PodCommand(0) },
        )
        assertEquals(listOf(1, 3, 2, 4), profile.supportedAncModes())
        assertEquals(listOf(1, 3, 2), profile.copy(adaptiveVisible = false).supportedAncModes())
        assertEquals(listOf(1, 2), profile.copy(commands = profile.commands.filterKeys {
            it == ProfileKeys.ANC_OFF || it == ProfileKeys.ANC_NC
        }).supportedAncModes())
        assertTrue(profile.copy(commands = emptyMap()).supportedAncModes().isEmpty())
    }
}
