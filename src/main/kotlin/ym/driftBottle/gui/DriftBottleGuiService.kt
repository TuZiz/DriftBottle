package ym.driftBottle.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import ym.driftBottle.config.BottleConfig
import ym.driftBottle.config.gui.GuiConfigRegistry
import ym.driftBottle.config.gui.GuiElementConfig
import ym.driftBottle.config.gui.GuiMenuConfig
import ym.driftBottle.lang.LangService
import ym.driftBottle.model.BottleListEntry
import ym.driftBottle.model.BottleListScope
import ym.driftBottle.model.BottleStatus
import ym.driftBottle.model.BottleThreadView
import ym.driftBottle.model.PagedResult
import ym.driftBottle.repository.PlayerProfileRepository
import ym.driftBottle.service.BlacklistService
import ym.driftBottle.service.BottleWaterEffectService
import ym.driftBottle.service.DriftBottleService
import ym.driftBottle.service.EconomyService
import ym.driftBottle.util.HeadTextureSupport
import ym.driftBottle.state.ChatInputContext
import ym.driftBottle.state.ChatInputStateStore
import ym.driftBottle.util.TextColorizer
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DriftBottleGuiService(
    private val plugin: JavaPlugin,
    private val guiConfigRegistry: GuiConfigRegistry,
    private val bottleConfigProvider: () -> BottleConfig,
    private val lang: LangService,
    private val driftBottleService: DriftBottleService,
    private val blacklistService: BlacklistService,
    private val bottleWaterEffectService: BottleWaterEffectService,
    private val economyService: EconomyService,
    private val playerProfileRepository: PlayerProfileRepository,
    private val chatInputStateStore: ChatInputStateStore,
) {

    private val numberFormat = DecimalFormat("0.00")
    private val placeholders = GuiPlaceholderResolver()
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private val pageSize = 21

    fun openMainMenu(player: Player) {
        playerProfileRepository.ensurePlayer(player.uniqueId, player.name)
        val summary = driftBottleService.summarizePlayer(player.uniqueId)
        val menuConfig = guiConfigRegistry.get("main")
        val menu = createMenu(menuConfig)
        val bottleConfig = bottleConfigProvider()
        val values = baseValues(player) + mapOf(
            "open_bottle_count" to summary.openBottleCount.toString(),
            "max_active_bottles" to bottleConfig.maxActiveBottlesPerPlayer.toString(),
            "throw_cost" to numberFormat.format(bottleConfig.throwCost),
            "salvage_reward" to numberFormat.format(bottleConfig.salvageReward),
            "drifting_count" to summary.driftingCount.toString(),
            "thrown_count" to summary.thrownCount.toString(),
            "salvaged_count" to summary.salvagedCount.toString(),
            "sent_reply_count" to summary.sentReplyCount.toString(),
            "received_reply_count" to summary.receivedReplyCount.toString(),
        )
        renderStaticItems(menu, menuConfig, values, headOwner = player)
        setFunctionItem(menu, menuConfig, "throw_bottle", values, action = { promptCreateBottle(it) })
        setFunctionItem(menu, menuConfig, "salvage_bottle", values, action = { salvageBottle(it) })
        setFunctionItem(menu, menuConfig, "my_bottles", values, action = { openMyBottlesMenu(it, 0) })
        setFunctionItem(menu, menuConfig, "inbox", values, action = { openInboxMenu(it, 0) })
        setFunctionItem(menu, menuConfig, "blacklist", values, action = { openBlacklistMenu(it) })
        setFunctionItem(menu, menuConfig, "profile", values, action = { openProfileMenu(it) }, headOwner = player)
        setFunctionItem(menu, menuConfig, "status", values)
        menu.open(player)
    }

    fun promptCreateBottle(player: Player) {
        bottleWaterEffectService.findNearestWaterTargetAsync(player) { target ->
            if (target == null) {
                player.sendMessage(lang.get("bottle.throw-water-required"))
                return@findNearestWaterTargetAsync
            }
            chatInputStateStore.store(player.uniqueId, ChatInputContext.CreateBottle)
            player.closeInventory()
            player.sendMessage(lang.get("bottle.throw-prompt"))
        }
    }

    fun salvageBottle(player: Player) {
        if (bottleWaterEffectService.hasPendingSalvagePickup(player.uniqueId)) {
            player.sendMessage(lang.get("bottle.salvage-pickup-pending"))
            return
        }
        bottleWaterEffectService.findNearestWaterTargetAsync(player) { target ->
            if (target == null) {
                player.sendMessage(lang.get("bottle.salvage-water-required"))
                return@findNearestWaterTargetAsync
            }
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline) {
                    return@Runnable
                }
                player.closeInventory()
                startSalvageTitleSequence(player, target)
            })
        }
    }

    private fun startSalvageTitleSequence(player: Player, target: BottleWaterEffectService.WaterTarget) {
        val frames = listOf(
            lang.get("title.salvage.search.1"),
            lang.get("title.salvage.search.2"),
            lang.get("title.salvage.search.3"),
        )
        val taskHolder = arrayOfNulls<org.bukkit.scheduler.BukkitTask>(1)
        var index = 0
        taskHolder[0] = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (!player.isOnline) {
                taskHolder[0]?.cancel()
                return@Runnable
            }
            if (index < frames.size) {
                player.sendTitle(frames[index], lang.get("title.salvage.search-subtitle"), 0, 10, 0)
                index++
                return@Runnable
            }
            taskHolder[0]?.cancel()
            val candidateResult = driftBottleService.findSalvageCandidate(player)
            val bottle = candidateResult.payload
            if (candidateResult.success && bottle != null) {
                val started = bottleWaterEffectService.startSalvagePickup(
                    player = player,
                    target = target,
                    bottleId = bottle.id,
                    labelContext = BottleWaterEffectService.BottleVisualLabelContext(
                        ownerAlias = bottle.ownerAlias,
                        anonymousCode = driftBottleService.anonymousCodeOf(bottle.ownerUuid),
                    ),
                    onReady = { pickupPlayer, _ ->
                        pickupPlayer.sendMessage(lang.get("bottle.salvage-pickup-ready"))
                    },
                    onPicked = { pickupPlayer, pickedBottleId ->
                        val pickupResult = driftBottleService.completeSalvageBottlePickup(pickupPlayer, pickedBottleId)
                        pickupPlayer.sendMessage(pickupResult.message)
                        val pickedBottle = pickupResult.payload
                        if (pickupResult.success && pickedBottle != null) {
                            pickupPlayer.sendTitle(
                                lang.get("title.salvage.success"),
                                lang.format("title.salvage.success-subtitle", mapOf("bottle_id" to pickedBottle.id.toString())),
                                5,
                                30,
                                10,
                            )
                            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                                if (pickupPlayer.isOnline) {
                                    openBottleDetailMenu(pickupPlayer, pickedBottle.id, BottleListScope.SALVAGED, 0)
                                }
                            }, 20L)
                        } else if (pickupPlayer.isOnline) {
                            pickupPlayer.sendTitle(
                                lang.get("title.salvage.failed"),
                                lang.get("title.salvage.failed-subtitle"),
                                5,
                                30,
                                10,
                            )
                        }
                    },
                    onExpired = { pickupPlayer, _ ->
                        if (pickupPlayer.isOnline) {
                            pickupPlayer.sendMessage(lang.get("bottle.salvage-pickup-expired"))
                        }
                    },
                )
                if (!started) {
                    player.sendMessage(lang.get("bottle.salvage-pickup-pending"))
                }
                return@Runnable
            }
            player.sendMessage(candidateResult.message)
            player.sendTitle(lang.get("title.salvage.failed"), lang.get("title.salvage.failed-subtitle"), 5, 30, 10)
        }, 0L, 8L)
    }

    fun openMyBottlesMenu(player: Player, page: Int) {
        openPagedBottleMenu(player, BottleListScope.OWNED, page)
    }

    fun openInboxMenu(player: Player, page: Int) {
        openPagedBottleMenu(player, BottleListScope.SALVAGED, page)
    }

    fun openBlacklistMenu(player: Player) {
        val menuConfig = guiConfigRegistry.get("blacklist")
        val menu = createMenu(menuConfig)
        val values = baseValues(player)
        renderStaticItems(menu, menuConfig, values)
        setFunctionItem(menu, menuConfig, "back", values, action = { openMainMenu(it) })
        val entries = blacklistService.list(player.uniqueId).take(templateSlots(menuConfig, "blacklist_entry").size)
        entries.forEachIndexed { index, entry ->
            val (_, element) = menuConfig.findByTemplate("blacklist_entry") ?: return@forEachIndexed
            val slot = templateSlots(menuConfig, "blacklist_entry")[index]
            val itemValues = values + mapOf(
                "target_name" to (entry.blockedName ?: entry.blockedUuid.toString()),
            )
            val item = buildConfiguredItem(
                element = element,
                values = itemValues,
                headOwner = Bukkit.getOfflinePlayer(entry.blockedUuid),
            )
            menu.setItem(slot, item) {
                blacklistService.remove(it.uniqueId, entry.blockedUuid)
                it.sendMessage(lang.get("blacklist.removed"))
                openBlacklistMenu(it)
            }
        }
        menu.open(player)
    }

    fun openProfileMenu(player: Player) {
        playerProfileRepository.ensurePlayer(player.uniqueId, player.name)
        val summary = driftBottleService.summarizePlayer(player.uniqueId)
        val menuConfig = guiConfigRegistry.get("profile")
        val menu = createMenu(menuConfig)
        val values = baseValues(player) + mapOf(
            "thrown_count" to summary.thrownCount.toString(),
            "open_bottle_count" to summary.openBottleCount.toString(),
            "drifting_count" to summary.driftingCount.toString(),
            "salvaged_count" to summary.salvagedCount.toString(),
            "sent_reply_count" to summary.sentReplyCount.toString(),
            "received_reply_count" to summary.receivedReplyCount.toString(),
            "max_active_bottles" to bottleConfigProvider().maxActiveBottlesPerPlayer.toString(),
            "throw_cost" to numberFormat.format(bottleConfigProvider().throwCost),
            "salvage_reward" to numberFormat.format(bottleConfigProvider().salvageReward),
        )
        renderStaticItems(menu, menuConfig, values, headOwner = player)
        setFunctionItem(menu, menuConfig, "profile", values, action = { openProfileMenu(it) }, headOwner = player)
        setFunctionItem(menu, menuConfig, "summary", values)
        setFunctionItem(menu, menuConfig, "economy", values)
        setFunctionItem(menu, menuConfig, "replies", values)
        setFunctionItem(menu, menuConfig, "overview", values)
        setFunctionItem(menu, menuConfig, "back", values, action = { openMainMenu(it) })
        menu.open(player)
    }

    fun openBottleDetailMenu(player: Player, bottleId: Long, scope: BottleListScope, returnPage: Int) {
        val thread = driftBottleService.getBottleThreadFor(player.uniqueId, bottleId) ?: run {
            player.sendMessage(lang.get("bottle.detail-no-access"))
            openScopeMenu(player, scope, returnPage)
            return
        }
        val bottle = thread.bottle
        val menuConfig = guiConfigRegistry.get("bottle_detail")
        val menu = createMenu(menuConfig)
        val values = baseValues(player) + detailValues(player, thread)

        renderStaticItems(menu, menuConfig, values)
        setFunctionItem(menu, menuConfig, "bottle_meta", values)
        setFunctionItem(menu, menuConfig, "counterpart", values)
        setFunctionItem(menu, menuConfig, "flow_info", values)
        setFunctionItem(menu, menuConfig, "refresh", values, action = { openBottleDetailMenu(it, bottleId, scope, returnPage) })
        setFunctionItem(menu, menuConfig, "bottle_content", values)
        setFunctionItem(menu, menuConfig, "reply_action", values, action = if (driftBottleService.canReply(player.uniqueId, thread)) {
            { promptReply(it, bottleId, scope, returnPage) }
        } else null)
        setFunctionItem(menu, menuConfig, "blacklist_action", values, action = if (bottle.counterpartUuidFor(player.uniqueId) != null) {
            {
                val result = driftBottleService.blacklistCounterpart(it, bottleId)
                it.sendMessage(result.message)
                openBottleDetailMenu(it, bottleId, scope, returnPage)
            }
        } else null)
        setFunctionItem(menu, menuConfig, "close_action", values, action = if (bottle.isOwner(player.uniqueId)) {
            {
                if (bottle.status == BottleStatus.CLOSED) {
                    val result = driftBottleService.deleteClosedBottle(it, bottleId)
                    it.sendMessage(result.message)
                    if (result.success) {
                        openScopeMenu(it, scope, returnPage)
                    } else {
                        openBottleDetailMenu(it, bottleId, scope, returnPage)
                    }
                } else {
                    val result = driftBottleService.closeBottle(it, bottleId)
                    it.sendMessage(result.message)
                    openBottleDetailMenu(it, bottleId, scope, returnPage)
                }
            }
        } else null)
        setFunctionItem(menu, menuConfig, "back", values, action = { openScopeMenu(it, scope, returnPage) })
        setFunctionItem(menu, menuConfig, "home", values, action = { openMainMenu(it) })

        val replySlots = templateSlots(menuConfig, "reply_entry")
        thread.replies.takeLast(replySlots.size).forEachIndexed { index, reply ->
            val (_, element) = menuConfig.findByTemplate("reply_entry") ?: return@forEachIndexed
            val replyValues = values + mapOf(
                "reply_name" to reply.senderAlias,
                "reply_time" to formatTime(reply.createdAt),
                "reply_content" to preview(reply.content, 40),
            )
            val item = buildConfiguredItem(element, replyValues, null)
            menu.setItem(replySlots[index], item)
        }

        menu.open(player)
    }

    fun openBottleDetailDirect(player: Player, bottleId: Long) {
        val thread = driftBottleService.getBottleThreadFor(player.uniqueId, bottleId) ?: run {
            player.sendMessage(lang.get("bottle.detail-no-access"))
            return
        }
        val scope = if (thread.bottle.isOwner(player.uniqueId)) {
            BottleListScope.OWNED
        } else {
            BottleListScope.SALVAGED
        }
        openBottleDetailMenu(player, bottleId, scope, 0)
    }

    fun handleChatInput(player: Player, context: ChatInputContext, message: String) {
        when (context) {
            ChatInputContext.CreateBottle -> {
                bottleWaterEffectService.findNearestWaterTargetAsync(player) { target ->
                    if (target == null) {
                        player.sendMessage(lang.get("bottle.throw-water-required"))
                        openMainMenu(player)
                        return@findNearestWaterTargetAsync
                    }
                    val result = driftBottleService.createBottle(player, message)
                    player.sendMessage(result.message)
                    val bottle = result.payload
                    if (result.success && bottle != null) {
                        bottleWaterEffectService.playThrowEffect(
                            player = player,
                            target = target,
                            labelContext = BottleWaterEffectService.BottleVisualLabelContext(
                                ownerAlias = bottle.ownerAlias,
                                anonymousCode = driftBottleService.anonymousCodeOf(bottle.ownerUuid),
                            ),
                        ) {
                            if (player.isOnline) {
                                openMyBottlesMenu(player, 0)
                            }
                        }
                    } else {
                        openMainMenu(player)
                    }
                }
            }

            is ChatInputContext.ReplyBottle -> {
                val result = driftBottleService.replyToBottle(player, context.bottleId, message)
                player.sendMessage(result.message)
                openBottleDetailMenu(player, context.bottleId, context.scope, context.returnPage)
            }
        }
    }

    private fun promptReply(player: Player, bottleId: Long, scope: BottleListScope, returnPage: Int) {
        chatInputStateStore.store(
            player.uniqueId,
            ChatInputContext.ReplyBottle(
                bottleId = bottleId,
                scope = scope,
                returnPage = returnPage,
            ),
        )
        player.closeInventory()
        player.sendMessage(lang.get("bottle.reply-prompt"))
    }

    private fun openPagedBottleMenu(player: Player, scope: BottleListScope, page: Int) {
        val menuKey = if (scope == BottleListScope.OWNED) "my_bottles" else "inbox"
        val pageData = driftBottleService.listBottles(player.uniqueId, scope, page, pageSize)
        val menuConfig = guiConfigRegistry.get(menuKey)
        val menu = createMenu(menuConfig)
        val summary = driftBottleService.summarizePlayer(player.uniqueId)
        val values = baseValues(player) + mapOf(
            "total_items" to pageData.totalItems.toString(),
            "page" to (pageData.page + 1).toString(),
            "total_pages" to pageData.totalPages.toString(),
            "open_bottle_count" to summary.openBottleCount.toString(),
        )
        renderStaticItems(menu, menuConfig, values)
        setFunctionItem(menu, menuConfig, "list_info", values)
        setFunctionItem(menu, menuConfig, "back", values, action = { openMainMenu(it) })
        setFunctionItem(
            menu,
            menuConfig,
            "prev_page",
            values,
            action = if (pageData.page > 0) { { openPagedBottleMenu(it, scope, pageData.page - 1) } } else null,
            renderDisabledWhenNoAction = true,
        )
        setFunctionItem(
            menu,
            menuConfig,
            "next_page",
            values,
            action = if (pageData.page < pageData.totalPages - 1) { { openPagedBottleMenu(it, scope, pageData.page + 1) } } else null,
            renderDisabledWhenNoAction = true,
        )

        val slots = templateSlots(menuConfig, "bottle_entry")
        pageData.entries.take(slots.size).forEachIndexed { index, entry ->
            val (_, element) = menuConfig.findByTemplate("bottle_entry") ?: return@forEachIndexed
            val entryValues = values + listEntryValues(scope, entry)
            val item = buildConfiguredItem(element, entryValues, null)
            menu.setItem(slots[index], item) {
                openBottleDetailMenu(it, entry.bottle.id, scope, pageData.page)
            }
        }

        menu.open(player)
    }

    private fun detailValues(player: Player, thread: BottleThreadView): Map<String, String> {
        val bottle = thread.bottle
        val counterpartAlias = bottle.counterpartAliasFor(player.uniqueId)
        val contentLines = wrapLines(bottle.content, 4, 24)
        val flowLines = flowLines(player, bottle)
        val replyState = replyButtonState(player, thread)
        val closeState = closeButtonState(player, bottle)
        val blacklistState = blacklistButtonState(player, bottle)
        return mapOf(
            "bottle_name" to "#${bottle.id}",
            "bottle_status" to statusText(bottle.status),
            "bottle_time" to formatTime(bottle.createdAt),
            "reply_count" to thread.replies.size.toString(),
            "counterpart_name" to (counterpartAlias ?: lang.get("bottle.detail.no-counterpart-short")),
            "counterpart_line_1" to when {
                counterpartAlias == null -> lang.get("bottle.detail.no-counterpart-long")
                bottle.isOwner(player.uniqueId) -> lang.get("bottle.detail.counterpart-salvager")
                else -> lang.get("bottle.detail.counterpart-owner")
            },
            "counterpart_line_2" to when {
                counterpartAlias == null -> lang.get("bottle.detail.waiting-salvage")
                else -> counterpartAlias
            },
            "flow_line_1" to flowLines.getOrElse(0) { " " },
            "flow_line_2" to flowLines.getOrElse(1) { " " },
            "flow_line_3" to flowLines.getOrElse(2) { " " },
            "content_line_1" to contentLines[0],
            "content_line_2" to contentLines[1],
            "content_line_3" to contentLines[2],
            "content_line_4" to contentLines[3],
            "reply_button_name" to replyState.name,
            "reply_line_1" to replyState.line1,
            "reply_line_2" to replyState.line2,
            "reply_hint" to replyState.hint,
            "blacklist_name" to blacklistState.name,
            "blacklist_line_1" to blacklistState.line1,
            "blacklist_line_2" to blacklistState.line2,
            "blacklist_hint" to blacklistState.hint,
            "close_name" to closeState.name,
            "close_line_1" to closeState.line1,
            "close_line_2" to closeState.line2,
            "close_hint" to closeState.hint,
        )
    }

    private fun listEntryValues(scope: BottleListScope, entry: BottleListEntry): Map<String, String> {
        val bottle = entry.bottle
        val partnerLine = if (scope == BottleListScope.OWNED) {
            bottle.salvagedByAlias?.let { lang.format("bottle.list.partner-salvaged", mapOf("alias" to it)) }
                ?: lang.get("bottle.list.partner-waiting")
        } else {
            lang.format("bottle.list.partner-owner", mapOf("alias" to bottle.ownerAlias))
        }
        return mapOf(
            "entry_name" to "#${bottle.id}",
            "entry_status" to statusText(bottle.status),
            "entry_partner" to partnerLine,
            "entry_preview" to preview(bottle.content, 32),
            "entry_reply_count" to entry.replyCount.toString(),
            "entry_updated" to formatTime(entry.lastReplyAt ?: bottle.updatedAt),
        )
    }

    private fun replyButtonState(player: Player, thread: BottleThreadView): ButtonState {
        val bottle = thread.bottle
        return when {
            bottle.status == BottleStatus.CLOSED -> ButtonState(
                name = lang.get("bottle.detail.reply-name-disabled"),
                line1 = lang.get("bottle.detail.reply-closed"),
                line2 = statusText(bottle.status),
                hint = lang.get("bottle.detail.reply-hint-disabled"),
            )

            bottle.counterpartUuidFor(player.uniqueId) == null -> ButtonState(
                name = lang.get("bottle.detail.reply-name-disabled"),
                line1 = lang.get("bottle.detail.reply-no-counterpart"),
                line2 = lang.get("bottle.detail.waiting-salvage"),
                hint = lang.get("bottle.detail.reply-hint-disabled"),
            )

            driftBottleService.canReply(player.uniqueId, thread) -> ButtonState(
                name = lang.get("bottle.detail.reply-name-ready"),
                line1 = lang.get("bottle.detail.reply-ready"),
                line2 = lang.format(
                    "bottle.detail.reply-progress",
                    mapOf("count" to thread.replies.size.toString(), "limit" to bottleConfigProvider().maxThreadReplies.toString()),
                ),
                hint = lang.get("bottle.detail.reply-hint-ready"),
            )

            thread.replies.size >= bottleConfigProvider().maxThreadReplies -> ButtonState(
                name = lang.get("bottle.detail.reply-name-disabled"),
                line1 = lang.get("bottle.detail.reply-maxed"),
                line2 = lang.format(
                    "bottle.detail.reply-progress",
                    mapOf("count" to thread.replies.size.toString(), "limit" to bottleConfigProvider().maxThreadReplies.toString()),
                ),
                hint = lang.get("bottle.detail.reply-hint-disabled"),
            )

            else -> ButtonState(
                name = lang.get("bottle.detail.reply-name-disabled"),
                line1 = lang.get("bottle.detail.reply-turn"),
                line2 = lang.format(
                    "bottle.detail.reply-progress",
                    mapOf("count" to thread.replies.size.toString(), "limit" to bottleConfigProvider().maxThreadReplies.toString()),
                ),
                hint = lang.get("bottle.detail.reply-hint-disabled"),
            )
        }
    }

    private fun closeButtonState(player: Player, bottle: ym.driftBottle.model.DriftBottle): ButtonState {
        return when {
            !bottle.isOwner(player.uniqueId) -> ButtonState(
                name = lang.get("bottle.detail.close-name-disabled"),
                line1 = lang.get("bottle.detail.close-owner-only"),
                line2 = statusText(bottle.status),
                hint = lang.get("bottle.detail.close-hint-disabled"),
            )

            bottle.status == BottleStatus.CLOSED -> ButtonState(
                name = lang.get("bottle.detail.delete-name-ready"),
                line1 = lang.get("bottle.detail.delete-ready"),
                line2 = statusText(bottle.status),
                hint = lang.get("bottle.detail.delete-hint-ready"),
            )

            else -> ButtonState(
                name = lang.get("bottle.detail.close-name-ready"),
                line1 = lang.get("bottle.detail.close-ready"),
                line2 = statusText(bottle.status),
                hint = lang.get("bottle.detail.close-hint-ready"),
            )
        }
    }

    private fun blacklistButtonState(player: Player, bottle: ym.driftBottle.model.DriftBottle): ButtonState {
        val counterpartExists = bottle.counterpartUuidFor(player.uniqueId) != null
        return if (counterpartExists) {
            ButtonState(
                name = lang.get("bottle.detail.blacklist-name-ready"),
                line1 = lang.get("bottle.detail.blacklist-ready"),
                line2 = lang.get("bottle.detail.blacklist-note"),
                hint = lang.get("bottle.detail.blacklist-hint-ready"),
            )
        } else {
            ButtonState(
                name = lang.get("bottle.detail.blacklist-name-disabled"),
                line1 = lang.get("bottle.detail.blacklist-disabled"),
                line2 = lang.get("bottle.detail.waiting-salvage"),
                hint = lang.get("bottle.detail.blacklist-hint-disabled"),
            )
        }
    }

    private fun flowLines(player: Player, bottle: ym.driftBottle.model.DriftBottle): List<String> {
        val key = when {
            bottle.status == BottleStatus.CLOSED -> "bottle.detail.flow.closed"
            bottle.isOwner(player.uniqueId) && bottle.status == BottleStatus.DRIFTING -> "bottle.detail.flow.owner_drifting"
            bottle.isOwner(player.uniqueId) -> "bottle.detail.flow.owner_salvaged"
            else -> "bottle.detail.flow.salvager"
        }
        return lang.formatLines(key)
    }

    private fun openScopeMenu(player: Player, scope: BottleListScope, page: Int) {
        when (scope) {
            BottleListScope.OWNED -> openMyBottlesMenu(player, page)
            BottleListScope.SALVAGED -> openInboxMenu(player, page)
        }
    }

    private fun statusText(status: BottleStatus): String {
        return lang.get("bottle.status.${status.name.lowercase()}")
    }

    private fun preview(content: String, maxLength: Int): String {
        val normalized = content.replace('\n', ' ').trim()
        return if (normalized.length <= maxLength) normalized else normalized.take(maxLength - 1) + "…"
    }

    private fun wrapLines(content: String, maxLines: Int, lineLength: Int): List<String> {
        val normalized = content.replace('\n', ' ').trim()
        if (normalized.isEmpty()) {
            return List(maxLines) { " " }
        }
        val lines = mutableListOf<String>()
        var remaining = normalized
        while (remaining.isNotEmpty() && lines.size < maxLines) {
            if (remaining.length <= lineLength) {
                lines += remaining
                remaining = ""
            } else {
                lines += remaining.take(lineLength)
                remaining = remaining.drop(lineLength)
            }
        }
        if (remaining.isNotEmpty() && lines.isNotEmpty()) {
            val lastIndex = lines.lastIndex
            lines[lastIndex] = lines[lastIndex].dropLast(1) + "…"
        }
        while (lines.size < maxLines) {
            lines += " "
        }
        return lines
    }

    private fun formatTime(time: Long): String {
        return timeFormatter.format(Instant.ofEpochMilli(time))
    }

    private fun createMenu(config: GuiMenuConfig): SimpleMenu = SimpleMenu(config.shape.size, TextColorizer.color(config.title))

    private fun renderStaticItems(
        menu: SimpleMenu,
        menuConfig: GuiMenuConfig,
        values: Map<String, String>,
        headOwner: OfflinePlayer? = null,
    ) {
        menuConfig.elementsByChar.values
            .filter { it.function == null && it.template == null }
            .forEach { element ->
                val item = buildConfiguredItem(
                    element = element,
                    values = values,
                    headOwner = if (element.head) headOwner else null,
                )
                menuConfig.shape.slots(element.charKey).forEach { slot ->
                    menu.setItem(slot, item.clone())
                }
            }
    }

    private fun setFunctionItem(
        menu: SimpleMenu,
        menuConfig: GuiMenuConfig,
        function: String,
        values: Map<String, String>,
        action: ((Player) -> Unit)? = null,
        headOwner: OfflinePlayer? = null,
        renderDisabledWhenNoAction: Boolean = false,
    ) {
        val slot = functionSlot(menuConfig, function) ?: return
        val element = menuConfig.findByFunction(function)?.second ?: return
        val item = when {
            action == null && renderDisabledWhenNoAction && hasDisabledVisuals(element) ->
                buildConfiguredItem(element, values, headOwner, disabled = true)
            else -> buildConfiguredItem(element, values, headOwner, disabled = false)
        }
        menu.setItem(slot, item, action)
    }

    private fun buildConfiguredItem(
        element: GuiElementConfig,
        values: Map<String, String>,
        headOwner: OfflinePlayer?,
        disabled: Boolean = false,
    ): ItemStack {
        val material = if (disabled) element.disabledMaterial ?: element.material else element.material
        val name = if (disabled) element.disabledName ?: element.name else element.name
        val lore = if (disabled) element.disabledLore ?: element.lore else element.lore
        val amount = if (disabled) element.disabledAmount ?: element.amount else element.amount
        val glow = if (disabled) element.disabledGlow ?: element.glow else element.glow
        val customModelData = if (disabled) element.disabledCustomModelData ?: element.customModelData else element.customModelData
        val head = if (disabled) element.disabledHead ?: element.head else element.head
        val texture = if (disabled) element.disabledTexture ?: element.texture else element.texture
        val item = if (head || headOwner != null || texture != null) {
            ItemStack(Material.PLAYER_HEAD)
        } else {
            ItemStack(material, amount)
        }
        val meta = item.itemMeta ?: return item
        if (meta is SkullMeta) {
            if (!HeadTextureSupport.applyTexture(meta, texture) && headOwner != null) {
                meta.owningPlayer = headOwner
            }
        }
        meta.setDisplayName(TextColorizer.color(placeholders.apply(name, values)))
        meta.lore = TextColorizer.color(placeholders.apply(lore, values))
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        if (customModelData != null) {
            @Suppress("DEPRECATION")
            meta.setCustomModelData(customModelData)
        }
        if (glow) {
            meta.addEnchant(Enchantment.LURE, 1, true)
        }
        item.itemMeta = meta
        item.amount = amount.coerceAtLeast(1)
        return item
    }

    private fun hasDisabledVisuals(element: GuiElementConfig): Boolean {
        return element.disabledMaterial != null ||
            element.disabledName != null ||
            element.disabledLore != null ||
            element.disabledAmount != null ||
            element.disabledGlow != null ||
            element.disabledCustomModelData != null ||
            element.disabledHead != null ||
            element.disabledTexture != null
    }

    private fun functionSlot(menuConfig: GuiMenuConfig, function: String): Int? {
        val (charKey, _) = menuConfig.findByFunction(function) ?: return null
        return menuConfig.shape.firstSlot(charKey)
    }

    private fun templateSlots(menuConfig: GuiMenuConfig, templateName: String): List<Int> {
        val (charKey, _) = menuConfig.findByTemplate(templateName) ?: return emptyList()
        return menuConfig.shape.slots(charKey)
    }

    private fun baseValues(player: Player): Map<String, String> {
        return mapOf(
            "player_name" to player.name,
            "balance" to numberFormat.format(economyService.balance(player)),
        )
    }

    private data class ButtonState(
        val name: String,
        val line1: String,
        val line2: String,
        val hint: String,
    )
}
