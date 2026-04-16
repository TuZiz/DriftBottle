package ym.driftBottle.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import ym.driftBottle.config.gui.GuiConfigRegistry
import ym.driftBottle.config.gui.GuiElementConfig
import ym.driftBottle.config.gui.GuiMenuConfig
import ym.driftBottle.lang.LangService
import ym.driftBottle.model.AdminPlayerListEntry
import ym.driftBottle.model.BottleListEntry
import ym.driftBottle.model.BottleStatus
import ym.driftBottle.model.BottleThreadView
import ym.driftBottle.service.DriftBottleAdminService
import ym.driftBottle.util.HeadTextureSupport
import ym.driftBottle.util.TextColorizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DriftBottleAdminGuiService(
    private val guiConfigRegistry: GuiConfigRegistry,
    private val lang: LangService,
    private val adminService: DriftBottleAdminService,
    private val openPlayerMainMenu: (Player) -> Unit,
) {

    private val placeholders = GuiPlaceholderResolver()
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private val bottlePageSize = 14
    private val playerPageSize = 14

    fun openAdminMain(player: Player) {
        if (!hasViewPermission(player)) {
            player.sendMessage(lang.get("general.no-permission"))
            return
        }
        val stats = adminService.stats()
        val menuConfig = guiConfigRegistry.get("admin_main")
        val menu = createMenu(menuConfig)
        val values = baseValues(player) + mapOf(
            "total_bottles" to stats.totalBottles.toString(),
            "drifting_bottles" to stats.driftingBottles.toString(),
            "salvaged_bottles" to stats.salvagedBottles.toString(),
            "closed_bottles" to stats.closedBottles.toString(),
            "total_replies" to stats.totalReplies.toString(),
            "total_players" to stats.totalPlayers.toString(),
        )
        renderStaticItems(menu, menuConfig, values)
        setFunctionItem(menu, menuConfig, "all_bottles", values, action = { openBottleList(it, null, 0) })
        setFunctionItem(menu, menuConfig, "player_stats", values, action = { openPlayerList(it, 0) })
        setFunctionItem(menu, menuConfig, "back", values, action = { openPlayerMainMenu(it) })
        setFunctionItem(menu, menuConfig, "summary", values)
        menu.open(player)
    }

    private fun openBottleList(player: Player, status: BottleStatus?, page: Int) {
        if (!hasViewPermission(player)) {
            player.sendMessage(lang.get("general.no-permission"))
            return
        }
        val pageData = adminService.listBottles(status, page, bottlePageSize)
        val menuConfig = guiConfigRegistry.get("admin_bottles")
        val menu = createMenu(menuConfig)
        val values = baseValues(player) + mapOf(
            "filter_name" to statusName(status),
            "page" to (pageData.page + 1).toString(),
            "total_pages" to pageData.totalPages.toString(),
            "total_items" to pageData.totalItems.toString(),
        )
        renderStaticItems(menu, menuConfig, values)
        setFunctionItem(menu, menuConfig, "list_info", values)
        setFunctionItem(menu, menuConfig, "back", values, action = { openAdminMain(it) })
        setFunctionItem(menu, menuConfig, "filter_all", values, action = { openBottleList(it, null, 0) })
        setFunctionItem(menu, menuConfig, "filter_drifting", values, action = { openBottleList(it, BottleStatus.DRIFTING, 0) })
        setFunctionItem(menu, menuConfig, "filter_salvaged", values, action = { openBottleList(it, BottleStatus.SALVAGED, 0) })
        setFunctionItem(menu, menuConfig, "filter_closed", values, action = { openBottleList(it, BottleStatus.CLOSED, 0) })
        setFunctionItem(
            menu,
            menuConfig,
            "prev_page",
            values,
            action = if (pageData.page > 0) { { openBottleList(it, status, pageData.page - 1) } } else null,
            renderDisabledWhenNoAction = true,
        )
        setFunctionItem(
            menu,
            menuConfig,
            "next_page",
            values,
            action = if (pageData.page < pageData.totalPages - 1) { { openBottleList(it, status, pageData.page + 1) } } else null,
            renderDisabledWhenNoAction = true,
        )

        val slots = templateSlots(menuConfig, "admin_bottle_entry")
        pageData.entries.take(slots.size).forEachIndexed { index, entry ->
            val (_, element) = menuConfig.findByTemplate("admin_bottle_entry") ?: return@forEachIndexed
            val item = buildConfiguredItem(element, values + bottleEntryValues(entry), null)
            menu.setItem(slots[index], item) {
                openBottleDetail(it, entry.bottle.id, status, pageData.page)
            }
        }
        menu.open(player)
    }

    private fun openBottleDetail(player: Player, bottleId: Long, returnStatus: BottleStatus?, returnPage: Int) {
        if (!hasViewPermission(player)) {
            player.sendMessage(lang.get("general.no-permission"))
            return
        }
        val thread = adminService.getBottleThread(bottleId) ?: run {
            player.sendMessage(lang.get("admin.bottle-not-found"))
            openBottleList(player, returnStatus, returnPage)
            return
        }
        val menuConfig = guiConfigRegistry.get("admin_bottle_detail")
        val menu = createMenu(menuConfig)
        val values = baseValues(player) + bottleDetailValues(thread)
        renderStaticItems(menu, menuConfig, values)
        setFunctionItem(menu, menuConfig, "bottle_meta", values)
        setFunctionItem(menu, menuConfig, "participants", values)
        setFunctionItem(menu, menuConfig, "bottle_content", values)
        setFunctionItem(menu, menuConfig, "refresh", values, action = { openBottleDetail(it, bottleId, returnStatus, returnPage) })
        setFunctionItem(menu, menuConfig, "back", values, action = { openBottleList(it, returnStatus, returnPage) })
        if (thread.bottle.status != BottleStatus.CLOSED && hasManagePermission(player)) {
            setFunctionItem(menu, menuConfig, "force_close", values, action = {
                val result = adminService.forceCloseBottle(bottleId)
                it.sendMessage(result.message)
                openBottleDetail(it, bottleId, returnStatus, returnPage)
            })
        }
        if (hasManagePermission(player)) {
            setFunctionItem(menu, menuConfig, "delete_bottle", values, action = { openDeleteConfirm(it, bottleId, returnStatus, returnPage) })
        }

        val replySlots = templateSlots(menuConfig, "admin_reply_entry")
        thread.replies.takeLast(replySlots.size).forEachIndexed { index, reply ->
            val (_, element) = menuConfig.findByTemplate("admin_reply_entry") ?: return@forEachIndexed
            val itemValues = values + mapOf(
                "reply_name" to reply.senderAlias,
                "reply_uuid" to reply.senderUuid.toString(),
                "reply_time" to formatTime(reply.createdAt),
                "reply_content" to preview(reply.content, 40),
            )
            val item = buildConfiguredItem(element, itemValues, null)
            menu.setItem(replySlots[index], item)
        }
        menu.open(player)
    }

    private fun openDeleteConfirm(player: Player, bottleId: Long, returnStatus: BottleStatus?, returnPage: Int) {
        if (!hasManagePermission(player)) {
            player.sendMessage(lang.get("general.no-permission"))
            return
        }
        val thread = adminService.getBottleThread(bottleId) ?: run {
            player.sendMessage(lang.get("admin.bottle-not-found"))
            openBottleList(player, returnStatus, returnPage)
            return
        }
        val menuConfig = guiConfigRegistry.get("admin_confirm")
        val menu = createMenu(menuConfig)
        val values = baseValues(player) + bottleDetailValues(thread)
        renderStaticItems(menu, menuConfig, values)
        setFunctionItem(menu, menuConfig, "confirm_delete", values, action = {
            val result = adminService.deleteBottle(bottleId)
            it.sendMessage(result.message)
            openBottleList(it, returnStatus, returnPage)
        })
        setFunctionItem(menu, menuConfig, "cancel", values, action = { openBottleDetail(it, bottleId, returnStatus, returnPage) })
        setFunctionItem(menu, menuConfig, "bottle_meta", values)
        menu.open(player)
    }

    private fun openPlayerList(player: Player, page: Int) {
        if (!hasViewPermission(player)) {
            player.sendMessage(lang.get("general.no-permission"))
            return
        }
        val pageData = adminService.listPlayers(page, playerPageSize)
        val menuConfig = guiConfigRegistry.get("admin_players")
        val menu = createMenu(menuConfig)
        val values = baseValues(player) + mapOf(
            "page" to (pageData.page + 1).toString(),
            "total_pages" to pageData.totalPages.toString(),
            "total_items" to pageData.totalItems.toString(),
        )
        renderStaticItems(menu, menuConfig, values)
        setFunctionItem(menu, menuConfig, "list_info", values)
        setFunctionItem(menu, menuConfig, "back", values, action = { openAdminMain(it) })
        setFunctionItem(
            menu,
            menuConfig,
            "prev_page",
            values,
            action = if (pageData.page > 0) { { openPlayerList(it, pageData.page - 1) } } else null,
            renderDisabledWhenNoAction = true,
        )
        setFunctionItem(
            menu,
            menuConfig,
            "next_page",
            values,
            action = if (pageData.page < pageData.totalPages - 1) { { openPlayerList(it, pageData.page + 1) } } else null,
            renderDisabledWhenNoAction = true,
        )

        val slots = templateSlots(menuConfig, "admin_player_entry")
        pageData.entries.take(slots.size).forEachIndexed { index, entry ->
            val (_, element) = menuConfig.findByTemplate("admin_player_entry") ?: return@forEachIndexed
            val item = buildConfiguredItem(element, values + playerEntryValues(entry), Bukkit.getOfflinePlayer(entry.profile.uuid))
            menu.setItem(slots[index], item)
        }
        menu.open(player)
    }

    private fun bottleEntryValues(entry: BottleListEntry): Map<String, String> {
        val bottle = entry.bottle
        return mapOf(
            "entry_id" to bottle.id.toString(),
            "entry_status" to statusName(bottle.status),
            "entry_owner_alias" to bottle.ownerAlias,
            "entry_owner_uuid" to bottle.ownerUuid.toString(),
            "entry_salvager_alias" to (bottle.salvagedByAlias ?: lang.get("admin.none")),
            "entry_reply_count" to entry.replyCount.toString(),
            "entry_preview" to preview(bottle.content, 32),
            "entry_updated" to formatTime(entry.lastReplyAt ?: bottle.updatedAt),
        )
    }

    private fun bottleDetailValues(thread: BottleThreadView): Map<String, String> {
        val bottle = thread.bottle
        val contentLines = wrapLines(bottle.content, 4, 24)
        return mapOf(
            "bottle_id" to bottle.id.toString(),
            "bottle_status" to statusName(bottle.status),
            "owner_alias" to bottle.ownerAlias,
            "owner_uuid" to bottle.ownerUuid.toString(),
            "salvager_alias" to (bottle.salvagedByAlias ?: lang.get("admin.none")),
            "salvager_uuid" to (bottle.salvagedByUuid?.toString() ?: lang.get("admin.none")),
            "created_time" to formatTime(bottle.createdAt),
            "salvaged_time" to formatTime(bottle.salvagedAt),
            "updated_time" to formatTime(bottle.updatedAt),
            "reply_count" to thread.replies.size.toString(),
            "content_line_1" to contentLines[0],
            "content_line_2" to contentLines[1],
            "content_line_3" to contentLines[2],
            "content_line_4" to contentLines[3],
        )
    }

    private fun playerEntryValues(entry: AdminPlayerListEntry): Map<String, String> {
        return mapOf(
            "entry_player_name" to entry.profile.lastName,
            "entry_player_uuid" to entry.profile.uuid.toString(),
            "entry_thrown_count" to entry.summary.thrownCount.toString(),
            "entry_open_bottle_count" to entry.summary.openBottleCount.toString(),
            "entry_drifting_count" to entry.summary.driftingCount.toString(),
            "entry_salvaged_count" to entry.summary.salvagedCount.toString(),
            "entry_sent_reply_count" to entry.summary.sentReplyCount.toString(),
            "entry_received_reply_count" to entry.summary.receivedReplyCount.toString(),
            "entry_updated" to formatTime(entry.profile.updatedAt),
        )
    }

    private fun hasViewPermission(player: Player): Boolean {
        return player.hasPermission(VIEW_PERMISSION) || player.hasPermission(ADMIN_PERMISSION)
    }

    private fun hasManagePermission(player: Player): Boolean {
        return player.hasPermission(MANAGE_PERMISSION) || player.hasPermission(ADMIN_PERMISSION)
    }

    private fun statusName(status: BottleStatus?): String {
        return status?.let { lang.get("bottle.status.${it.name.lowercase()}") } ?: lang.get("admin.filter.all")
    }

    private fun preview(content: String, maxLength: Int): String {
        val normalized = content.replace('\n', ' ').trim()
        return if (normalized.length <= maxLength) normalized else normalized.take((maxLength - 3).coerceAtLeast(1)) + "..."
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
            lines[lines.lastIndex] = lines.last().dropLast(3.coerceAtMost(lines.last().length)) + "..."
        }
        while (lines.size < maxLines) {
            lines += " "
        }
        return lines
    }

    private fun formatTime(time: Long?): String {
        return time?.let { timeFormatter.format(Instant.ofEpochMilli(it)) } ?: lang.get("admin.none")
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
        )
    }

    companion object {
        const val ADMIN_PERMISSION = "driftbottle.admin"
        const val VIEW_PERMISSION = "driftbottle.admin.view"
        const val MANAGE_PERMISSION = "driftbottle.admin.manage"
    }
}
