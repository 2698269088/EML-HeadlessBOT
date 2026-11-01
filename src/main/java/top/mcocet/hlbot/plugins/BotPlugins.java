package top.mcocet.hlbot.plugins;

import java.io.File;
import net.minecraft.client.Minecraft;
import net.minecraft.client.headless.ConsoleIO;
import net.minecraft.client.headless.HeadlessSession;
import top.mcocet.hlbot.plugins.event.Event;

/**
 * Static entry point of the plugin system. {@link #init} is called once from {@code BotApplication},
 * then the connection drives the lifecycle through {@link #onServerConnected()} and
 * {@link #onServerDisconnected()} (fired from the tick loop, i.e. on the client thread).
 * {@link #fire(Event)} is the single hook used by every engine call site, mirroring NeoForge's
 * {@code EVENT_BUS.post(...)}:
 *
 * <pre>{@code
 * if (BotPlugins.fire(new SendChatEvent(message)).isCancelled()) {
 *    return;
 * }
 * }</pre>
 *
 * <p>The lifecycle mutators that a plugin or the console can call ({@code reload}, {@code enable},
 * {@code disable}, {@code load}, {@code unload}) are posted onto the client thread so {@code onEnable}
 * and {@code onDisable} always run where touching the world is legal; {@link #shutdown()} is the
 * exception and runs after the client loop has already stopped.
 */
public final class BotPlugins {
   /**
    * The plugin-facing API version. A plugin declaring {@code apiVersion} in its {@code plugin.yml} loads
    * only when the major matches; bump the major on any breaking change to {@link BotApi}/{@link Plugin}/
    * the event types so old plugins are refused up front instead of failing at runtime.
    */
   public static final String API_VERSION = "1.0";

   private static PluginManager manager;
   private static BotApi api;
   private static HeadlessSession session;

   private BotPlugins() {
   }

   /** Scans {@code pluginsDirectory} and loads every plugin, but does not enable any (see the phases). */
   public static void init(final File pluginsDirectory, final HeadlessSession session, final ConsoleIO console) {
      if (manager != null) {
         return;
      }

      BotPlugins.session = session;
      api = new BotApi(session, console);
      manager = new PluginManager(pluginsDirectory, api);
      api.attach(manager);
      manager.loadPlugins();
   }

   /**
    * Applies the fault policy from {@code bot.yml}: with {@code disableOnError} on, a plugin that
    * throws is taken out of service instead of being left half-working (see
    * {@link PluginManager#handleRuntimeFailure}).
    */
   public static void configureFaultPolicy(final boolean disableOnError, final int failThreshold) {
      PluginManager m = manager;
      if (m != null) {
         m.configureFaultPolicy(disableOnError, failThreshold);
      }
   }

   /**
    * Reports a failure raised by plugin code outside the manager's own call sites (scheduled task
    * bodies, for example). Swallows nothing and never throws into the caller.
    */
   public static void reportFailure(final Plugin plugin, final String phase, final Throwable error) {
      PluginManager m = manager;
      if (m != null) {
         m.handleRuntimeFailure(plugin, phase, error);
      } else if (plugin != null) {
         org.slf4j.LoggerFactory.getLogger("Plugins")
            .error("Plugin '{}' failed in {} (no manager active)", plugin.getName(), phase, error);
      }
   }

   // ---------- connection-driven lifecycle (called on the client thread) ----------

   /** The client entered a world: enable every loaded plugin so its listeners/commands activate. */
   public static void onServerConnected() {
      PluginManager m = manager;
      if (m != null) {
         m.setConnected(true);
      }
   }

   /** The connection is gone: disable every enabled plugin (they stay loaded for a possible reconnect). */
   public static void onServerDisconnected() {
      PluginManager m = manager;
      if (m != null) {
         m.setConnected(false);
      }
   }

   // ---------- hot management (posted to the client thread) ----------

   public static void reloadAll() {
      onClient(() -> {
         PluginManager m = manager;
         if (m != null) {
            m.reloadAll();
         }
      });
   }

   public static void reloadPlugin(final String name) {
      onClient(() -> {
         PluginManager m = manager;
         if (m != null) {
            m.reloadPlugin(name);
         }
      });
   }

   public static void loadPlugin(final File jar) {
      onClient(() -> {
         PluginManager m = manager;
         if (m != null) {
            try {
               m.loadPlugin(jar);
            } catch (Exception e) {
               org.slf4j.LoggerFactory.getLogger("Plugins").error("Failed to load {}", jar.getName(), e);
            }
         }
      });
   }

   public static void unloadPlugin(final String name) {
      onClient(() -> {
         PluginManager m = manager;
         if (m != null) {
            m.unloadPlugin(name);
         }
      });
   }

   public static void enablePlugin(final String name) {
      onClient(() -> {
         PluginManager m = manager;
         if (m != null) {
            m.enablePlugin(m.getPlugin(name));
         }
      });
   }

   public static void disablePlugin(final String name) {
      onClient(() -> {
         PluginManager m = manager;
         if (m != null) {
            m.disablePlugin(m.getPlugin(name));
         }
      });
   }

   /**
    * Unloads every plugin. Runs directly (not on the client thread): it is called from
    * {@code BotApplication} after {@code awaitTermination()}, when the tick loop has already ended,
    * so there is no client thread left to post to and no concurrent ticking to guard against.
    */
   public static void shutdown() {
      PluginManager m = manager;
      if (m != null) {
         m.unloadAll();
         manager = null;
         api = null;
         session = null;
      }

      // Stop the worker pool last, once every plugin (and its tasks) has been torn down.
      BotScheduler.INSTANCE.shutdown();
   }

   private static void onClient(final Runnable task) {
      HeadlessSession s = session;
      if (s == null) {
         return;
      }

      Minecraft client = s.isStarted() ? s.clientNow() : null;
      if (client != null) {
         client.execute(task);
      } else {
         // No live client (e.g. before start or during teardown): run inline; the manager lock keeps it safe.
         task.run();
      }
   }

   public static PluginManager manager() {
      return manager;
   }

   public static BotApi api() {
      return api;
   }

   /**
    * Posts {@code event} to all registered listeners, synchronously and in priority
    * order. Returns the same event so call sites can inspect cancellations:
    * {@code fire(new SendChatEvent(msg)).isCancelled()}. Safe to call from any thread;
    * listeners run on the calling thread.
    */
   public static <E extends Event> E fire(final E event) {
      PluginManager m = manager;
      if (m != null) {
         m.callEvent(event);
      }

      return event;
   }
}
