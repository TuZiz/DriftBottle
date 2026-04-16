package ym.driftBottle.state

import ym.driftBottle.model.BottleListScope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface ChatInputContext {
    data object CreateBottle : ChatInputContext

    data class ReplyBottle(
        val bottleId: Long,
        val scope: BottleListScope,
        val returnPage: Int,
    ) : ChatInputContext
}

class ChatInputStateStore {

    private val waitingContexts = ConcurrentHashMap<UUID, ChatInputContext>()

    fun store(playerUuid: UUID, context: ChatInputContext) {
        waitingContexts[playerUuid] = context
    }

    fun take(playerUuid: UUID): ChatInputContext? = waitingContexts.remove(playerUuid)

    fun clear(playerUuid: UUID) {
        waitingContexts.remove(playerUuid)
    }
}
