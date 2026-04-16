package ym.driftBottle.config.gui

class GuiConfigRegistry(private val loader: GuiFileLoader) {

    private val menus = linkedMapOf<String, GuiMenuConfig>()

    fun loadAll() {
        loader.ensureDefaults()
        menus.clear()
        menus["main"] = loader.load("main.yml", "main")
        menus["my_bottles"] = loader.load("my_bottles.yml", "my_bottles")
        menus["inbox"] = loader.load("inbox.yml", "inbox")
        menus["bottle_detail"] = loader.load("bottle_detail.yml", "bottle_detail")
        menus["blacklist"] = loader.load("blacklist.yml", "blacklist")
        menus["profile"] = loader.load("profile.yml", "profile")
        menus["admin_main"] = loader.load("admin/main.yml", "admin_main")
        menus["admin_bottles"] = loader.load("admin/bottles.yml", "admin_bottles")
        menus["admin_bottle_detail"] = loader.load("admin/bottle_detail.yml", "admin_bottle_detail")
        menus["admin_players"] = loader.load("admin/players.yml", "admin_players")
        menus["admin_confirm"] = loader.load("admin/confirm.yml", "admin_confirm")
    }

    fun get(key: String): GuiMenuConfig = menus[key] ?: error("Unknown gui menu: $key")
}
