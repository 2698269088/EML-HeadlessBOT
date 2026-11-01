package top.mcocet.hlbot.plugins;

import java.io.File;
import org.slf4j.Logger;

/**
 * Base class for hlbot plugins, modelled after Bukkit's {@code JavaPlugin}. A plugin jar
 * must contain a {@code plugin.yml} with at least:
 *
 * <pre>
 * name: MyPlugin
 * main: com.example.myplugin.MyPlugin
 * version: 1.0.0
 * </pre>
 *
 * <p>The {@code main} class must extend this class and have a public no-arg constructor.
 * The manager calls {@link #onLoad()} once after instantiation, then {@link #onEnable()}
 * when the plugin becomes active, and {@link #onDisable()} during shutdown or when the
 * plugin is disabled explicitly.
 */
public abstract class Plugin {
   private PluginDescriptionFile description;
   private PluginClassLoader classLoader;
   private File dataFolder;
   private Logger logger;
   private BotApi api;
   private Config config;
   private PluginState state = PluginState.LOADED;

   /** Internal wiring done by {@link PluginManager}; never call this from a plugin. */
   final void init(final PluginDescriptionFile description, final PluginClassLoader classLoader, final File dataFolder, final BotApi api) {
      this.description = description;
      this.classLoader = classLoader;
      this.dataFolder = dataFolder;
      this.api = api;
      this.logger = org.slf4j.LoggerFactory.getLogger(description.getName());
   }

   /** Called once right after the plugin has been instantiated, while it is only LOADED. */
   public void onLoad() {
   }

   /**
    * Called when the plugin becomes active, i.e. once the client has entered a world (see
    * {@link PluginState}). Register listeners and console commands here, not in {@link #onLoad()},
    * so nothing touches game state before a connection exists.
    */
   public void onEnable() {
   }

   /**
    * Called when the plugin is taken out of service: on every disconnect, on {@code plugins disable},
    * before {@link #onUnload()} and during shutdown. Withdraw background work here; the manager
    * unregisters listeners and commands for you afterwards.
    */
   public void onDisable() {
   }

   /**
    * Final hook, called when the plugin is unloaded (reload or shutdown) after {@link #onDisable()}.
    * Its class loader is closed right after, so static caches that hold plugin classes must be
    * released here or they leak across reloads. Default no-op.
    */
   public void onUnload() {
   }

   public final String getName() {
      return this.description.getName();
   }

   public final PluginDescriptionFile getDescription() {
      return this.description;
   }

   /** Folder ({@code plugins/<name>/}) for the plugin's own configuration files. */
   public final File getDataFolder() {
      return this.dataFolder;
   }

   public final Logger getLogger() {
      return this.logger;
   }

   /** The API facade exposing the bot's session, client and extension points. */
   public final BotApi getApi() {
      return this.api;
   }

   /**
    * The tick-driven task scheduler. Prefer this over raw threads: sync tasks run on the client thread
    * (safe to touch the world), async tasks run on a worker, and every task is cancelled automatically
    * when this plugin disables or unloads.
    */
   public final BotScheduler scheduler() {
      return BotScheduler.INSTANCE;
   }

   /**
    * This plugin's {@code config.yml} (under {@link #getDataFolder()}), loaded lazily on first access.
    * Reads never fail: a missing or malformed file simply yields defaults.
    */
   public final Config getConfig() {
      if (this.config == null) {
         Config loaded = new Config(new File(this.dataFolder, "config.yml"));
         loaded.load();
         this.config = loaded;
      }

      return this.config;
   }

   /** Writes the in-memory config to {@code dataFolder/config.yml}. */
   public final void saveConfig() {
      getConfig().save();
   }

   /** Re-reads {@code config.yml} from disk, discarding unsaved changes. */
   public final void reloadConfig() {
      if (this.config != null) {
         this.config.reload();
      }
   }

   /**
    * Seeds {@code config.yml} from a {@code config.yml} bundled in the plugin jar, only when none exists
    * on disk yet, so first-run users get a documented default without edits being overwritten. Call in
    * {@link #onEnable()} before {@link #getConfig()}. {@code true} when a fresh copy was written.
    */
   public final boolean saveDefaultConfig() {
      return getConfig().saveDefaultFromJar(this);
   }

   public final PluginClassLoader getClassLoader() {
      return this.classLoader;
   }

   public final boolean isEnabled() {
      return this.state == PluginState.ENABLED;
   }

   /** Current lifecycle state; the bot owns every transition. */
   public final PluginState getState() {
      return this.state;
   }

   final void setState(final PluginState state) {
      this.state = state;
   }
}
