package ym.driftBottle.gui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class SimpleMenu(
    size: Int,
    title: String,
) : InventoryHolder {

    private val actions = mutableMapOf<Int, (Player) -> Unit>()
    private val inventory: Inventory = Bukkit.createInventory(this, size, title)

    override fun getInventory(): Inventory = inventory

    fun setItem(slot: Int, item: ItemStack, action: ((Player) -> Unit)? = null) {
        inventory.setItem(slot, item)
        if (action != null) {
            actions[slot] = action
        } else {
            actions.remove(slot)
        }
    }

    fun handleClick(player: Player, slot: Int) {
        actions[slot]?.invoke(player)
    }

    fun open(player: Player) {
        player.openInventory(inventory)
    }
}
