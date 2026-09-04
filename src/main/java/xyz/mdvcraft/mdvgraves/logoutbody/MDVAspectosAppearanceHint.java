package xyz.mdvcraft.mdvgraves.logoutbody;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import xyz.mdvcraft.mdvgraves.MDVGravesPlugin;

import java.io.File;

public final class MDVAspectosAppearanceHint {
    private final MDVGravesPlugin plugin;

    public MDVAspectosAppearanceHint(MDVGravesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Devuelve el nombre de skin que MDVAspectos recuerda. Se usa solo como fallback:
     * la vía principal de LibsDisguises clona el perfil vivo del Player al salir,
     * que ya refleja el resultado final de MDVAspectos/SkinsRestorer.
     */
    public String resolveSkinName(Player player) {
        Plugin mdvAspectos = Bukkit.getPluginManager().getPlugin("MDVAspectos");
        if (mdvAspectos == null || !mdvAspectos.isEnabled())
            return null;
        try {
            File file = new File(mdvAspectos.getDataFolder(), "skin-memory.yml");
            if (!file.isFile())
                return null;
            YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = data.getConfigurationSection(
                    "players." + player.getUniqueId());
            if (section == null)
                return null;

            String state = section.getString("state", "skin");
            if (state != null && (state.equalsIgnoreCase("native")
                    || state.equalsIgnoreCase("clear")
                    || state.equalsIgnoreCase("cleared")))
                return player.getName();

            String skin = section.getString("skin", "");
            return skin == null || skin.isBlank() ? null : skin.trim();
        } catch (Throwable ex) {
            plugin.getLogger().fine("No se pudo leer skin-memory.yml de MDVAspectos: " + ex.getMessage());
            return null;
        }
    }
}
