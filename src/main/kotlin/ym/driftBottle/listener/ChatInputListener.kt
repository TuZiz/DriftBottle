package ym.driftBottle.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.plugin.java.JavaPlugin
import ym.driftBottle.gui.DriftBottleGuiService
import ym.driftBottle.service.ChatProtectionService
import ym.driftBottle.state.ChatInputStateStore

class ChatInputListener(
    private val plugin: JavaPlugin,
    private val chatInputStateStore: ChatInputStateStore,
    private val chatProtectionService: ChatProtectionService,
    private val guiService: DriftBottleGuiService,
) : Listener {

    @EventHandler
    fun onAsyncPlayerChat(event: AsyncPlayerChatEvent) {
        val context = chatInputStateStore.take(event.player.uniqueId) ?: return
        event.isCancelled = true

        val validationMessage = chatProtectionService.validate(event.player.uniqueId, event.message)
        if (validationMessage != null) {
            chatInputStateStore.store(event.player.uniqueId, context)
            plugin.server.scheduler.runTask(plugin, Runnable {
                event.player.sendMessage(validationMessage)
            })
            return
        }

        plugin.server.scheduler.runTask(plugin, Runnable {
            guiService.handleChatInput(event.player, context, event.message.trim())
        })
    }
}
