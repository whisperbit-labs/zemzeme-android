package com.roman.zemzeme.nostr

import com.roman.zemzeme.mesh.FragmentManager
import com.roman.zemzeme.protocol.ZemzemePacket

/**
 * Separate fragment assembler instance for Nostr Gift-Wrap fragmented messages.
 *
 * Uses the same BLE-compatible [FragmentManager] logic, but isolated from the BLE
 * mesh fragment state so that Nostr fragment reassembly does not interfere with
 * in-flight BLE transfers. The 30-second timeout and cleanup timer are inherited
 * from the underlying [FragmentManager].
 */
object NostrFragmentAssembler {

    private val fragmentManager = FragmentManager()

    /**
     * Process an incoming FRAGMENT-typed [ZemzemePacket] received from a Nostr Gift Wrap.
     *
     * @return The fully reassembled [ZemzemePacket] once all fragments arrive, or null
     *         while reassembly is still in progress.
     */
    fun handleFragment(packet: ZemzemePacket): ZemzemePacket? {
        return fragmentManager.handleFragment(packet)
    }

    /** Clear all partial fragment state (e.g. on panic-clear). */
    fun clear() {
        fragmentManager.clearAllFragments()
    }
}
