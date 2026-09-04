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

/**
 * Puente opcional con nLogin sin dependencia de compilación.
 *
 * nLogin usa una infraestructura compartida para varios eventos Bukkit. En
 * algunas versiones, un listener registrado dinámicamente para
 * AuthenticateEvent también puede ser despachado al ejecutar otros eventos de
 * nLogin que comparten el mismo HandlerList (por ejemplo LoginRequestEvent o
 * PremiumLoginEvent). Por eso SIEMPRE validamos la clase concreta recibida
 * antes de invocar métodos reflectivos obtenidos desde AuthenticateEvent.
 *
 * La finalización no depende exclusivamente del evento: LogoutBodyManager
 * mantiene un fallback liviano que consulta isAuthenticated(...) únicamente
 * para jugadores con una sesión pendiente de logout-body.
 */
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
        if (player == null)
            return false;

        try {
            Class<?> apiClass = Class.forName("com.nickuc.login.api.nLoginAPI");
            Method getApi = apiClass.getMethod("getApi");
            Object api = getApi.invoke(null);
            if (api == null)
                return false;

            // Las versiones actuales de la API exponen Identity y String. Se
            // conserva el intento con Player por compatibilidad con builds
            // antiguas/forks, y se cae al método String si no existe.
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
                // CRÍTICO: nLogin comparte HandlerList entre parte de sus eventos.
                // Bukkit puede llamar este executor con LoginRequestEvent,
                // PremiumLoginEvent, etc. Nunca debemos invocar un Method de
                // AuthenticateEvent sobre una instancia de otra clase.
                if (!eventClass.isInstance(event))
                    return;

                try {
                    Object result = getPlayer.invoke(event);
                    if (result instanceof Player player)
                        authenticatedHandler.accept(player);
                } catch (ReflectiveOperationException | IllegalArgumentException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "No se pudo leer el jugador del AuthenticateEvent de nLogin.", ex);
                }
            };

            Bukkit.getPluginManager().registerEvent(
                    eventClass,
                    new org.bukkit.event.Listener() {},
                    EventPriority.MONITOR,
                    executor,
                    plugin,
                    true);

            registered = true;
            plugin.getLogger().info(
                    "Integración nLogin activa: castigos/restauraciones se aplicarán tras autenticar.");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "nLogin está instalado pero no se pudo registrar AuthenticateEvent. "
                            + "Se usará el fallback de autenticación para sesiones pendientes.", ex);
        }
    }
}
