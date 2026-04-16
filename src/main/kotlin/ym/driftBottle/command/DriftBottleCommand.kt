package ym.driftBottle.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ym.driftBottle.gui.DriftBottleAdminGuiService
import ym.driftBottle.gui.DriftBottleGuiService
import ym.driftBottle.lang.LangService
import ym.driftBottle.repository.PlayerProfileRepository

class DriftBottleCommand(
    private val guiService: DriftBottleGuiService,
    private val adminGuiService: DriftBottleAdminGuiService,
    private val playerProfileRepository: PlayerProfileRepository,
    private val lang: LangService,
    private val onReload: () -> Unit,
) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val subcommand = args.firstOrNull()?.lowercase()
        if (subcommand == "reload") {
            if (!sender.hasPermission("driftbottle.admin")) {
                sender.sendMessage(lang.get("general.no-permission"))
                return true
            }
            return runCatching {
                onReload()
                sender.sendMessage(lang.get("general.reload-success"))
            }.fold(
                onSuccess = { true },
                onFailure = {
                    sender.sendMessage(lang.format("general.reload-failed", mapOf("reason" to (it.message ?: "unknown"))))
                    true
                },
            )
        }

        val player = sender as? Player ?: run {
            sender.sendMessage(lang.get("general.player-only"))
            return true
        }
        playerProfileRepository.ensurePlayer(player.uniqueId, player.name)
        if (subcommand == "open") {
            val bottleId = args.getOrNull(1)?.toLongOrNull()
            if (bottleId == null) {
                player.sendMessage(lang.get("general.invalid-bottle-id"))
                return true
            }
            guiService.openBottleDetailDirect(player, bottleId)
            return true
        }
        when (subcommand) {
            "admin" -> adminGuiService.openAdminMain(player)
            "throw" -> guiService.promptCreateBottle(player)
            "salvage" -> guiService.salvageBottle(player)
            "my" -> guiService.openMyBottlesMenu(player, 0)
            "inbox" -> guiService.openInboxMenu(player, 0)
            "blacklist" -> guiService.openBlacklistMenu(player)
            "profile" -> guiService.openProfileMenu(player)
            else -> guiService.openMainMenu(player)
        }
        return true
    }
}
