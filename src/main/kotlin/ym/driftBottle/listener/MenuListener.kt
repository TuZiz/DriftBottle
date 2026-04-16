package ym.driftBottle.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import ym.driftBottle.gui.SimpleMenu

class MenuListener : Listener {

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? SimpleMenu ?: return
        event.isCancelled = true
        val player = event.whoClicked as? org.bukkit.entity.Player ?: return
        if (event.clickedInventory == null || event.clickedInventory != event.inventory) {
            return
        }
        holder.handleClick(player, event.rawSlot)
    }
}
