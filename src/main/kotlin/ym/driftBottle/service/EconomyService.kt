package ym.driftBottle.service

import net.milkbowl.vault.economy.Economy
import org.bukkit.entity.Player

class EconomyService(private val economy: Economy?) {

    fun isEnabled(): Boolean = economy != null

    fun balance(player: Player): Double = economy?.getBalance(player) ?: 0.0

    fun has(player: Player, amount: Double): Boolean = economy?.has(player, amount) ?: false

    fun withdraw(player: Player, amount: Double): Boolean {
        if (amount <= 0.0) {
            return true
        }
        val provider = economy ?: return false
        return provider.withdrawPlayer(player, amount).transactionSuccess()
    }

    fun deposit(player: Player, amount: Double): Boolean {
        if (amount <= 0.0) {
            return true
        }
        val provider = economy ?: return false
        return provider.depositPlayer(player, amount).transactionSuccess()
    }
}
