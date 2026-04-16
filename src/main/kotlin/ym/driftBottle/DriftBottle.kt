package ym.driftBottle

import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.java.JavaPlugin
import ym.driftBottle.command.DriftBottleCommand
import ym.driftBottle.config.AppConfig
import ym.driftBottle.config.gui.GuiConfigRegistry
import ym.driftBottle.config.gui.GuiFileLoader
import ym.driftBottle.database.Database
import ym.driftBottle.gui.DriftBottleAdminGuiService
import ym.driftBottle.gui.DriftBottleGuiService
import ym.driftBottle.lang.LangService
import ym.driftBottle.listener.ChatInputListener
import ym.driftBottle.listener.MenuListener
import ym.driftBottle.listener.PlayerLifecycleListener
import ym.driftBottle.listener.SalvagePickupListener
import ym.driftBottle.repository.BlacklistRepository
import ym.driftBottle.repository.CurrencyLedgerRepository
import ym.driftBottle.repository.DriftBottleRepository
import ym.driftBottle.repository.PlayerProfileRepository
import ym.driftBottle.service.BlacklistService
import ym.driftBottle.service.BottleWaterEffectService
import ym.driftBottle.service.ChatProtectionService
import ym.driftBottle.service.DriftBottleAdminService
import ym.driftBottle.service.DriftBottleService
import ym.driftBottle.service.EconomyService
import ym.driftBottle.state.ChatInputStateStore

class DriftBottle : JavaPlugin() {

    private var database: Database? = null
    private var bottleWaterEffectService: BottleWaterEffectService? = null
    private lateinit var guiConfigRegistry: GuiConfigRegistry
    private lateinit var appConfig: AppConfig
    private lateinit var lang: LangService

    override fun onEnable() {
        saveDefaultConfig()
        appConfig = AppConfig.load(this)
        lang = LangService(this, appConfig.language.currentLanguage)
        guiConfigRegistry = GuiConfigRegistry(GuiFileLoader(this)).also { it.loadAll() }

        val database = Database(dataFolder, appConfig.database)
        database.initSchema()
        this.database = database

        val playerProfileRepository = PlayerProfileRepository(database)
        val blacklistRepository = BlacklistRepository(database)
        val currencyLedgerRepository = CurrencyLedgerRepository(database)
        val driftBottleRepository = DriftBottleRepository(database)

        val chatInputStateStore = ChatInputStateStore()
        val chatProtectionService = ChatProtectionService({ appConfig.chatProtection }, lang)
        val blacklistService = BlacklistService(blacklistRepository)
        val bottleWaterEffectService = BottleWaterEffectService(this) { appConfig.bottle }
        this.bottleWaterEffectService = bottleWaterEffectService
        val economyService = EconomyService(resolveEconomy())
        val driftBottleService = DriftBottleService(
            bottleConfigProvider = { appConfig.bottle },
            lang = lang,
            bottleRepository = driftBottleRepository,
            blacklistService = blacklistService,
            economyService = economyService,
            currencyLedgerRepository = currencyLedgerRepository,
            playerProfileRepository = playerProfileRepository,
        )
        val adminService = DriftBottleAdminService(
            lang = lang,
            bottleRepository = driftBottleRepository,
            playerProfileRepository = playerProfileRepository,
        )
        val guiService = DriftBottleGuiService(
            plugin = this,
            guiConfigRegistry = guiConfigRegistry,
            bottleConfigProvider = { appConfig.bottle },
            lang = lang,
            driftBottleService = driftBottleService,
            blacklistService = blacklistService,
            bottleWaterEffectService = bottleWaterEffectService,
            economyService = economyService,
            playerProfileRepository = playerProfileRepository,
            chatInputStateStore = chatInputStateStore,
        )
        val adminGuiService = DriftBottleAdminGuiService(
            guiConfigRegistry = guiConfigRegistry,
            lang = lang,
            adminService = adminService,
            openPlayerMainMenu = guiService::openMainMenu,
        )

        getCommand("driftbottle")?.setExecutor(
            DriftBottleCommand(
                guiService = guiService,
                adminGuiService = adminGuiService,
                playerProfileRepository = playerProfileRepository,
                lang = lang,
                onReload = {
                    appConfig = AppConfig.load(this)
                    lang.reload(appConfig.language.currentLanguage)
                    guiConfigRegistry.loadAll()
                },
            ),
        )

        server.pluginManager.registerEvents(MenuListener(), this)
        server.pluginManager.registerEvents(
            PlayerLifecycleListener(
                playerProfileRepository = playerProfileRepository,
                driftBottleService = driftBottleService,
                bottleWaterEffectService = bottleWaterEffectService,
                chatInputStateStore = chatInputStateStore,
                chatProtectionService = chatProtectionService,
            ),
            this,
        )
        server.pluginManager.registerEvents(
            ChatInputListener(this, chatInputStateStore, chatProtectionService, guiService),
            this,
        )
        server.pluginManager.registerEvents(SalvagePickupListener(bottleWaterEffectService), this)

        logger.info(lang.format("plugin.enabled-log", mapOf("economy_enabled" to economyService.isEnabled().toString())))
    }

    override fun onDisable() {
        bottleWaterEffectService?.clearAllPendingSalvagePickups()
        bottleWaterEffectService = null
        database?.close()
        database = null
    }

    private fun resolveEconomy(): Economy? {
        val registration = server.servicesManager.getRegistration(Economy::class.java) ?: return null
        return registration.provider
    }
}
