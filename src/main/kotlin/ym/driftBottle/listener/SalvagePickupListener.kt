package ym.driftBottle.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import ym.driftBottle.service.BottleWaterEffectService

class SalvagePickupListener(
    private val bottleWaterEffectService: BottleWaterEffectService,
) : Listener {

    @EventHandler
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        bottleWaterEffectService.handleItemPickup(event)
    }
}
