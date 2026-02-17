package com.roman.zemzeme.iconswitch

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import kotlin.random.Random

internal object IconSwitcher {

    const val ALIAS_COUNT = 100
    private const val PACKAGE = "com.roman.zemzeme"

    val NAMES = arrayOf(
        "Zemzeme", "NetChat", "TalkBox", "MsgHub", "BuzzTalk",
        "PingMe", "ChatNow", "QuickMsg", "TalkLine", "MsgWave",
        "ChatZone", "HiTalk", "TxtFlow", "ConnectMe", "ChatBolt",
        "MsgPulse", "TalkSnap", "ChatLink", "FlowChat", "PingTalk",
        "MsgDash", "ChatVibe", "TalkSync", "MsgNest", "ChatBee",
        "QuickTalk", "MsgLoop", "ChatGrid", "TalkNet", "MsgDrop",
        "ChatPing", "TalkWave", "MsgBlip", "ChatRush", "TalkMesh",
        "MsgLink", "ChatSpark", "TalkZap", "MsgBuzz", "ChatDrift",
        "TalkPad", "MsgVault", "ChatOrb", "TalkDeck", "MsgBeam",
        "ChatNode", "TalkBase", "MsgCore", "ChatFlux", "TalkEdge",
        "MsgRing", "ChatPlex", "TalkStar", "MsgHive", "ChatBit",
        "TalkNote", "MsgPort", "ChatWeave", "TalkCast", "MsgPeer",
        "ChatBridge", "TalkFusion", "MsgSwift", "ChatPulsar", "TalkRelay",
        "MsgHop", "ChatTrace", "TalkPath", "MsgRoute", "ChatAxis",
        "TalkGo", "MsgLift", "ChatDot", "TalkAir", "MsgWire",
        "ChatJet", "TalkBit", "MsgNova", "ChatRipple", "TalkMint",
        "MsgCloud", "ChatBreeze", "TalkEcho", "MsgWarp", "ChatStream",
        "TalkQuick", "MsgBolt", "ChatSphere", "TalkSpace", "MsgTap",
        "ChatFlash", "TalkBlaze", "MsgGlow", "ChatPulse", "TalkVolt",
        "MsgSnap", "ChatDial", "TalkRush", "MsgZone", "ChatWave"
    )

    data class SwitchResult(val index: Int, val name: String)

    /** Pick a random next alias (different from current) without applying it. */
    fun pickNext(context: Context): SwitchResult {
        val current = IconSwitchPreferences(context).currentAliasIndex
        var next: Int
        do {
            next = Random.nextInt(1, ALIAS_COUNT + 1)
        } while (next == current)
        return SwitchResult(index = next, name = NAMES[next - 1])
    }

    /** Apply a previously picked alias. */
    fun applySwitch(context: Context, nextIndex: Int) {
        val prefs = IconSwitchPreferences(context)
        val current = prefs.currentAliasIndex
        val pm = context.packageManager

        // Enable new alias first to prevent zero launcher entries
        setAliasEnabled(pm, nextIndex, true)
        // Then disable old alias
        setAliasEnabled(pm, current, false)

        prefs.currentAliasIndex = nextIndex
    }

    /** Pick and apply in one step (used by IconSwitchTaskDescription etc.). */
    fun switchNow(context: Context): SwitchResult {
        val result = pickNext(context)
        applySwitch(context, result.index)
        return result
    }

    private fun setAliasEnabled(pm: PackageManager, index: Int, enabled: Boolean) {
        val component = ComponentName(PACKAGE, "$PACKAGE.iconswitch.Alias_$index")
        val state = if (enabled)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
