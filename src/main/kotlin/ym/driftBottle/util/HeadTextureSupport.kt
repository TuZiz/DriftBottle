package ym.driftBottle.util

import org.bukkit.Bukkit
import org.bukkit.inventory.meta.SkullMeta
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

object HeadTextureSupport {

    fun applyTexture(meta: SkullMeta, texture: String?): Boolean {
        val skinUrl = resolveTextureUrl(texture) ?: return false
        return runCatching {
            val profile = Bukkit.createPlayerProfile(UUID.randomUUID(), null)
            profile.textures.skin = skinUrl
            meta.setOwnerProfile(profile)
        }.isSuccess
    }

    private fun resolveTextureUrl(texture: String?): URL? {
        val normalized = texture?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        extractTextureUrl(normalized)?.let { return runCatching { URL(it) }.getOrNull() }
        if (normalized.matches(Regex("^[A-Za-z0-9]{20,}$"))) {
            return runCatching { URL("http://textures.minecraft.net/texture/$normalized") }.getOrNull()
        }
        val decoded = runCatching {
            String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        val decodedUrl = extractTextureUrl(decoded) ?: return null
        return runCatching { URL(decodedUrl) }.getOrNull()
    }

    private fun extractTextureUrl(raw: String): String? {
        return Regex("""https?://textures\.minecraft\.net/texture/[A-Za-z0-9]+""")
            .find(raw)
            ?.value
    }
}
