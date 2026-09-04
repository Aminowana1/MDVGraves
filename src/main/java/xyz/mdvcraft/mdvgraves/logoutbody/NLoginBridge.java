package xyz.mdvcraft.mdvgraves.logoutbody;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import xyz.mdvcraft.mdvgraves.MDVGravesPlugin;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class NLoginBridge {
    private static final String AUTH_EVENT = "com.nickuc.login.api.event.bukkit.auth.AuthenticateEvent";

    private final MDVGravesPlugin plugin;
    private boolean registered;

    public NLoginBridge(MDVGravesPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRegistered() {
        return registered;
    }

    public boolean isNLoginEnabled() {
        Plugin nlogin = Bukkit.getPluginManager().getPlugin("nLogin");
        return nlogin != null && nlogin.isEnabled();
    }


    public boolean isAuthenticated(Player player) {
        if (!isNLoginEnabled())
            return true;
        try {
            Class<?> apiClass = Class.forName("com.nickuc.login.api.nLoginAPI");
            Method getApi = apiClass.getMethod("getApi");
            Object api = getApi.invoke(null);
            try {
                Method isAuthenticated = apiClass.getMethod("isAuthenticated", Player.class);
                return Boolean.TRUE.equals(isAuthenticated.invoke(api, player));
            } catch (NoSuchMethodException ignored) {
                Method isAuthenticated = apiClass.getMethod("isAuthenticated", String.class);
                return Boolean.TRUE.equals(isAuthenticated.invoke(api, player.getName()));
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public void register(Consumer<Player> authenticatedHandler) {
        if (registered || !isNLoginEnabled())
            return;
        try {
            Class<?> rawEvent = Class.forName(AUTH_EVENT);
            if (!Event.class.isAssignableFrom(rawEvent))
                throw new IllegalStateException(AUTH_EVENT + " no es un Bukkit Event");
            Class<? extends Event> eventClass = (Class<? extends Event>) rawEvent;
            Method getPlayer = rawEvent.getMethod("getPlayer");

            EventExecutor executor = (listener, event) -> {
                try {
                    Object result = getPlayer.invoke(event);
                    if (result instanceof Player player)
                        authenticatedHandler.accept(player);
                } catch (ReflectiveOperationException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "No se pudo leer el jugador del AuthenticateEvent de nLogin.", ex);
                }
            };
            Bukkit.getPluginManager().registerEvent(eventClass, new org.bukkit.event.Listener() {},
                    EventPriority.MONITOR, executor, plugin, true);
            registered = true;
            plugin.getLogger().info("Integración nLogin activa: castigos/restauraciones se aplicarán tras autenticar.");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "nLogin está instalado pero no se pudo registrar AuthenticateEvent. Se usará fallback por PlayerJoin.", ex);
        }
    }
}
