package ym.driftBottle.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import ym.driftBottle.repository.PlayerProfileRepository
import ym.driftBottle.service.BottleWaterEffectService
import ym.driftBottle.service.ChatProtectionService
import ym.driftBottle.service.DriftBottleService
import ym.driftBottle.state.ChatInputStateStore

class PlayerLifecycleListener(
    private val playerProfileRepository: PlayerProfileRepository,
    private val driftBottleService: DriftBottleService,
    private val bottleWaterEffectService: BottleWaterEffectService,
    private val chatInputStateStore: ChatInputStateStore,
    private val chatProtectionService: ChatProtectionService,
) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        playerProfileRepository.ensurePlayer(event.player.uniqueId, event.player.name)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val playerUuid = event.player.uniqueId
        driftBottleService.clearPlayerState(playerUuid)
        bottleWaterEffectService.clearPendingSalvagePickup(playerUuid)
        chatInputStateStore.clear(playerUuid)
        chatProtectionService.clear(playerUuid)
    }
}
