package top.mcocet.hlbot.plugins;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.minecraft.client.headless.Commands;
import net.minecraft.client.headless.ConsoleIO;
import net.minecraft.client.headless.HeadlessSession;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;
import top.mcocet.hlbot.plugins.event.Cancellable;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.EventHandler;
import top.mcocet.hlbot.plugins.event.HandlerList;
import top.mcocet.hlbot.plugins.event.Listener;
import top.mcocet.hlbot.plugins.event.RegisteredListener;

/**
 * Loads plugin jars from the {@code plugins/} directory and owns their lifecycle, the event bus and
 * the plugin-registered console commands.
 *
 * <p>A plugin jar must contain a {@code plugin.yml} (see {@link PluginDescriptionFile}). Jars are
 * instantiated in dependency order: a plugin that declares {@code depend: [A]} is guaranteed to load
 * after {@code A} and sees its classes.
 *
 * <p>Lifecycle is split by the connection, so the phases are driven from outside (see {@link BotPlugins}):
 * <ol>
 *   <li>{@link #loadPlugins()} at startup: scan, instantiate, {@code onLoad()}. Every plugin is
 *       {@link PluginState#LOADED} and touches no game state.</li>
 *   <li>{@link #setConnected setConnected(true)} when the client enters a world: {@code onEnable()} for
 *       each, which is where listeners and commands register.</li>
 *   <li>{@link #setConnected setConnected(false)} on disconnect: {@code onDisable()} and withdrawal of
 *       every listener/command, while the plugin stays loaded.</li>
 *   <li>{@link #unloadAll()} on shutdown: {@code onUnload()} then the class loader is closed.</li>
 * </ol>
 *
 * <p>{@code reload}/{@code load} perform the same enable/disable dance so a hot-reloaded plugin ends up
 * in whatever phase the connection is in. All mutating entry points are {@code synchronized}: enabling
 * runs on the client thread (the connection edge) while {@code plugins load} may run from the console,
 * and the coarse lock keeps the maps and the {@link Commands} registry consistent across the two.
 *
 * <p>Event handling follows Bukkit's model: listeners are plain objects implementing {@link Listener}
 * with methods annotated {@link EventHandler}; {@link #callEvent(Event)} dispatches synchronously by
 * priority.
 *
 * <p>Failure handling is Paper-style: a plugin that throws while loading/enabling, inside an event
 * handler, a scheduled task or its console command is quarantined - disabled, marked
 * {@link PluginState#FAILED} and skipped by the automatic enable-all - so one broken plugin cannot
 * take the bot down with it.
 */
public class PluginManager {
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

   private final File pluginsDirectory;
   private final BotApi api;
   private final Map<String, Plugin> plugins = new LinkedHashMap<>();
   private final Map<String, PluginClassLoader> loaders = new HashMap<>();
   private final Map<String, File> pluginFiles = new HashMap<>();
   private final Map<Plugin, List<RegisteredListener>> listenersByPlugin = new HashMap<>();
   private final Map<Plugin, List<String>> commandsByPlugin = new HashMap<>();
   private final Map<Plugin, List<ProtocolBridge>> bridgesByPlugin = new HashMap<>();
   private boolean connected;

   // ---- Paper-style fault isolation ----
   private boolean disableOnError = true;
   private int failThreshold = 1;
   private final Map<String, Fault> faults = new HashMap<>();
   private final Map<String, Integer> failureCounts = new HashMap<>();

   /** One recorded failure: where, why and when a plugin was taken out of service. */
   public record Fault(String phase, String message, long timeMillis, int occurrences) {
      public String summary() {
         return this.phase + ": " + this.message + (this.occurrences > 1 ? " (" + this.occurrences + "x)" : "");
      }
   }

   PluginManager(final File pluginsDirectory, final BotApi api) {
      this.pluginsDirectory = pluginsDirectory;
      this.api = api;
   }

   // ---------- load ----------

   /** Scans the plugins directory and loads every valid jar. Nothing is enabled here. */
   public synchronized void loadPlugins() {
      if (!this.pluginsDirectory.exists() && !this.pluginsDirectory.mkdirs()) {
         LOGGER.warn("Could not create plugins directory {}", this.pluginsDirectory);
         return;
      }

      File[] jars = this.pluginsDirectory.listFiles((dir, name) -> isPluginCandidate(name));
      if (jars == null || jars.length == 0) {
         return;
      }

      Map<String, PendingPlugin> pending = new LinkedHashMap<>();
      for (File jar : jars) {
         try {
            PluginDescriptionFile description = readDescription(jar);
            PendingPlugin existing = pending.get(description.getName());
            if (existing != null || this.plugins.containsKey(description.getName())) {
               LOGGER.warn(
                  "Duplicate plugin name '{}' in {}; already provided by {}",
                  description.getName(),
                  jar.getName(),
                  existing != null ? existing.file().getName() : "an already loaded plugin"
               );
               continue;
            }

            pending.put(description.getName(), new PendingPlugin(jar, description));
         } catch (Exception e) {
            LOGGER.error("Cannot load plugin from {}: {}", jar.getName(), e.getMessage());
         }
      }

      for (PendingPlugin candidate : sortByDependencies(pending)) {
         try {
            instantiate(candidate);
         } catch (Exception e) {
            LOGGER.error("Failed to load plugin {}", candidate.description.getName(), e);
         }
      }
   }

   /**
    * Loads a single jar at runtime ({@code plugins load}). The (soft)dependency class loader chain is
    * wired the same way as the startup batch. If the client is currently connected the new plugin is
    * enabled immediately, matching the ones already active.
    */
   public synchronized void loadPlugin(final File jar) throws Exception {
      PluginDescriptionFile description = readDescription(jar);
      if (this.plugins.containsKey(description.getName())) {
         throw new InvalidPluginException("a plugin named '" + description.getName() + "' is already loaded");
      }

      for (String dependency : description.getDepend()) {
         if (!this.plugins.containsKey(dependency)) {
            throw new InvalidPluginException("plugin '" + description.getName() + "' depends on missing plugin '" + dependency + "'");
         }
      }

      instantiate(new PendingPlugin(jar, description));
      if (this.connected) {
         enablePlugin(this.plugins.get(description.getName()));
      }
   }

   // ---------- enable / disable ----------

   /** Enables every loaded plugin in load order; called when the client enters a world. */
   public synchronized void setConnected(final boolean connected) {
      this.connected = connected;

      if (connected) {
         enableAll();
      } else {
         disableAll();
      }
   }

   public synchronized void enableAll() {
      for (Plugin plugin : new ArrayList<>(this.plugins.values())) {
         // A quarantined plugin stays out until an operator clears it with 'plugins enable <name>';
         // silently retrying it on every reconnect is exactly what the fault policy avoids.
         if (plugin.getState() != PluginState.ENABLED && plugin.getState() != PluginState.FAILED) {
            enablePlugin(plugin, false);
         }
      }
   }

   public synchronized void disableAll() {
      List<Plugin> reverse = new ArrayList<>(this.plugins.values());
      Collections.reverse(reverse);
      for (Plugin plugin : reverse) {
         if (plugin.getState() == PluginState.ENABLED) {
            disablePlugin(plugin);
         }
      }
   }

   public synchronized void enablePlugin(final Plugin plugin) {
      enablePlugin(plugin, true);
   }

   /**
    * @param clearFault an explicit enable (console/API) is the operator's retry, so it lifts a
    *                   quarantine; the automatic enable-all on connect passes false and skips FAILED
    */
   private synchronized void enablePlugin(final Plugin plugin, final boolean clearFault) {
      if (plugin == null || plugin.getState() == PluginState.ENABLED || plugin.getState() == PluginState.UNLOADED) {
         return;
      }

      if (clearFault) {
         clearFault(plugin.getName());
      }

      try {
         plugin.onEnable();
         plugin.setState(PluginState.ENABLED);
         LOGGER.info("Enabled {}", plugin.getDescription().getFullName());
      } catch (Throwable t) {
         LOGGER.error("Error while enabling {}", plugin.getName(), t);
         // Half-enabled is worse than not enabled: withdraw whatever got registered and quarantine it.
         rollbackEnable(plugin);
         plugin.setState(PluginState.DISABLED);
         handleFatalFailure(plugin, "onEnable", t);
      }
   }

   public synchronized void disablePlugin(final Plugin plugin) {
      if (plugin == null || plugin.getState() != PluginState.ENABLED) {
         return;
      }

      this.unregisterEvents(plugin);
      this.unregisterCommands(plugin);
      BotScheduler.INSTANCE.cancelAll(plugin);
      try {
         plugin.onDisable();
      } catch (Throwable t) {
         LOGGER.error("Error while disabling {}", plugin.getName(), t);
      } finally {
         plugin.setState(PluginState.DISABLED);
         LOGGER.info("Disabled {}", plugin.getDescription().getFullName());
      }
   }

   private void rollbackEnable(final Plugin plugin) {
      this.unregisterEvents(plugin);
      this.unregisterCommands(plugin);
      try {
         plugin.onDisable();
      } catch (Throwable ignored) {
         // onEnable already failed; onDisable cleanup is best-effort.
      }
   }

   /**
    * Registers a protocol bridge for a plugin. Bridges are deliberately not touched by enable/disable:
    * they must already be active when the next connection is dialed, and a reconnect happens while the
    * plugin is in the DISABLED phase.
    */
   public synchronized void registerProtocolBridge(final ProtocolBridge bridge, final Plugin plugin) {
      if (bridge == null || plugin == null) {
         return;
      }

      eml.hooks.BotProtocol.register(bridge);
      this.bridgesByPlugin.computeIfAbsent(plugin, key -> new ArrayList<>()).add(bridge);
   }

   private void unregisterBridges(final Plugin plugin) {
      List<ProtocolBridge> bridges = this.bridgesByPlugin.remove(plugin);
      if (bridges != null) {
         for (ProtocolBridge bridge : bridges) {
            eml.hooks.BotProtocol.unregister(bridge);
         }
      }
   }

   // ---------- fault isolation (Paper-style) ----------

   /** Applies the policy from {@code bot.yml}; the threshold is clamped to at least one failure. */
   public synchronized void configureFaultPolicy(final boolean disableOnError, final int failThreshold) {
      this.disableOnError = disableOnError;
      this.failThreshold = Math.max(1, failThreshold);
   }

   /** The recorded failure for {@code name}, or null when it never failed. */
   public synchronized Fault fault(final String name) {
      return this.faults.get(name);
   }

   /** Lifts a quarantine so the next explicit enable (or reload) may retry the plugin. */
   public synchronized void clearFault(final String name) {
      this.faults.remove(name);
      this.failureCounts.remove(name);
   }

   /**
    * Entry point for every place plugin code can throw: events, scheduled tasks and console commands.
    * Below the threshold the failure is only logged; at/above it the plugin is disabled and marked
    * {@link PluginState#FAILED} so the rest of the bot keeps running.
    */
   public synchronized void handleRuntimeFailure(final Plugin plugin, final String phase, final Throwable error) {
      if (plugin == null) {
         return;
      }

      if (!this.disableOnError) {
         LOGGER.error("Plugin '{}' failed in {} (isolation disabled)", plugin.getName(), phase, error);
         return;
      }

      int occurrences = this.failureCounts.merge(plugin.getName(), 1, Integer::sum);
      if (occurrences < this.failThreshold) {
         LOGGER.warn("Plugin '{}' failed in {} ({}/{})", plugin.getName(), phase, occurrences, this.failThreshold, error);
         return;
      }

      quarantine(plugin, phase, error, occurrences);
   }

   /** Load/enable failures are fatal immediately: the plugin never reached a usable state. */
   public synchronized void handleFatalFailure(final Plugin plugin, final String phase, final Throwable error) {
      if (plugin == null) {
         return;
      }

      int occurrences = this.failureCounts.merge(plugin.getName(), 1, Integer::sum);
      quarantine(plugin, phase, error, occurrences);
   }

   private void quarantine(final Plugin plugin, final String phase, final Throwable error, final int occurrences) {
      String name = plugin.getName();
      String reason = describe(error);

      if (plugin.getState() == PluginState.ENABLED) {
         // Withdraw listeners, commands and timers first, so nothing from it can run again.
         disablePlugin(plugin);
      }

      this.faults.put(name, new Fault(phase, reason, System.currentTimeMillis(), occurrences));
      plugin.setState(PluginState.FAILED);
      LOGGER.error("Plugin '{}' is now disabled (failed in {}): {}", name, phase, reason, error);
   }

   /** Root cause only: reflective event calls wrap the real exception, and that is what matters here. */
   private static String describe(final Throwable error) {
      Throwable root = error;
      while (root.getCause() != null && root.getCause() != root) {
         root = root.getCause();
      }

      String message = root.getMessage();
      return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
   }

   // ---------- unload / reload (hot) ----------

   /** Disables (if needed), runs {@code onUnload()}, closes the class loader and forgets the plugin. */
   public synchronized void unloadPlugin(final String name) {
      Plugin plugin = this.plugins.get(name);
      if (plugin == null) {
         return;
      }

      // Closing a loader the moment another loaded plugin still links against it would break that plugin,
      // so refuse; the dependent must be unloaded first (reloadAll/unloadAll already go in reverse).
      List<String> dependents = loadedDependents(name);
      if (!dependents.isEmpty()) {
         LOGGER.warn("Cannot unload '{}': still required by {}", name, dependents);
         return;
      }

      disablePlugin(plugin);
      BotScheduler.INSTANCE.cancelAll(plugin);
      try {
         plugin.onUnload();
      } catch (Throwable t) {
         LOGGER.error("Error while unloading {}", name, t);
      } finally {
         this.unregisterBridges(plugin);
         this.plugins.remove(name);
         this.pluginFiles.remove(name);
         closeLoader(name);
         clearFault(name);
         LOGGER.info("Unloaded {}", name);
      }
   }

   public synchronized void unloadAll() {
      disableAll();
      // Reverse load order so a plugin is gone before anything it depended on is torn down.
      List<String> names = new ArrayList<>(this.plugins.keySet());
      Collections.reverse(names);
      for (String name : names) {
         unloadPlugin(name);
      }
   }

   /** Names of loaded plugins that hard-depend on {@code name}. */
   private List<String> loadedDependents(final String name) {
      List<String> dependents = new ArrayList<>();
      for (Plugin plugin : this.plugins.values()) {
         if (plugin.getDescription().getDepend().contains(name)) {
            dependents.add(plugin.getName());
         }
      }
      return dependents;
   }

   /** Re-reads a plugin's jar from disk: unload, then load again, re-enabling if the client is connected. */
   public synchronized void reloadPlugin(final String name) {
      File jar = this.pluginFiles.get(name);
      if (jar == null || !jar.isFile()) {
         LOGGER.warn("Cannot reload {}: its jar is gone from {}", name, this.pluginsDirectory);
         return;
      }

      unloadPlugin(name);
      if (this.plugins.containsKey(name)) {
         // Unload was refused (active dependents); reloading on top of them would double-load classes.
         return;
      }

      try {
         loadPlugin(jar);
      } catch (Exception e) {
         LOGGER.error("Failed to reload {}", name, e);
      }
   }

   /** Unloads every plugin and re-scans the whole {@code plugins/} directory from disk. */
   public synchronized void reloadAll() {
      boolean wasConnected = this.connected;
      unloadAll();
      loadPlugins();
      if (wasConnected) {
         enableAll();
      }
   }

   private void closeLoader(final String name) {
      PluginClassLoader loader = this.loaders.remove(name);
      if (loader != null) {
         try {
            loader.close();
         } catch (IOException ignored) {
            // A closed jar file handle on Windows is best-effort; the loader is already forgotten.
         }
      }
   }

   // ---------- lookup ----------

   public Plugin getPlugin(final String name) {
      return this.plugins.get(name);
   }

   public Collection<Plugin> getPlugins() {
      return Collections.unmodifiableCollection(this.plugins.values());
   }

   public boolean isPluginLoaded(final String name) {
      return this.plugins.containsKey(name);
   }

   public boolean isPluginEnabled(final String name) {
      Plugin plugin = this.plugins.get(name);
      return plugin != null && plugin.isEnabled();
   }

   public boolean isConnected() {
      return this.connected;
   }

   // ---------- events ----------

   /**
    * Registers every {@link EventHandler}-annotated method of {@code listener}. The
    * methods are invoked on the thread firing the event (usually the client thread).
    */
   public synchronized void registerEvents(final Listener listener, final Plugin plugin) {
      for (Method method : listener.getClass().getMethods()) {
         EventHandler annotation = method.getAnnotation(EventHandler.class);
         if (annotation == null) {
            continue;
         }

         Class<?>[] parameters = method.getParameterTypes();
         if (parameters.length != 1 || !Event.class.isAssignableFrom(parameters[0])) {
            LOGGER.warn(
               "Ignoring @EventHandler {}.{}: handler must take exactly one Event parameter",
               listener.getClass().getName(),
               method.getName()
            );
            continue;
         }

         HandlerList handlerList = handlerListFor(parameters[0].asSubclass(Event.class));
         RegisteredListener registration = new RegisteredListener(
            plugin,
            listener,
            annotation.priority(),
            annotation.ignoreCancelled(),
            (instance, event) -> method.invoke(instance, event),
            handlerList
         );
         handlerList.register(registration);
         this.listenersByPlugin.computeIfAbsent(plugin, key -> new ArrayList<>()).add(registration);
      }
   }

   /** Removes every listener previously registered by {@code plugin}. */
   public synchronized void unregisterEvents(final Plugin plugin) {
      List<RegisteredListener> registrations = this.listenersByPlugin.remove(plugin);
      if (registrations == null) {
         return;
      }

      for (RegisteredListener registration : registrations) {
         registration.handlerList().unregister(registration);
      }
   }

   /** Fires {@code event} synchronously, in priority order. */
   public void callEvent(final Event event) {
      Plugin failedPlugin = null;
      Throwable failure = null;

      for (RegisteredListener registration : event.getHandlers().getRegisteredListeners()) {
         if (event instanceof Cancellable cancellable && cancellable.isCancelled() && registration.ignoreCancelled()) {
            continue;
         }

         try {
            registration.executor().execute(registration.listener(), event);
         } catch (Throwable t) {
            LOGGER.error("Error passing {} to {}", event.getClass().getSimpleName(), registration.plugin().getName(), t);
            // Collect, then isolate AFTER the dispatch loop: disabling a plugin mutates the handler
            // list, and a half-iterated notification would be worse than the exception itself.
            if (failedPlugin == null) {
               failedPlugin = registration.plugin();
               failure = t;
            }
         }
      }

      if (failedPlugin != null) {
         handleRuntimeFailure(failedPlugin, "event " + event.getClass().getSimpleName(), failure);
      }
   }

   private static HandlerList handlerListFor(final Class<? extends Event> eventClass) {
      try {
         return (HandlerList) eventClass.getMethod("getHandlerList").invoke(null);
      } catch (Exception e) {
         throw new IllegalStateException("Event class must provide public static HandlerList getHandlerList(): " + eventClass.getName(), e);
      }
   }

   // ---------- console commands ----------

   synchronized void registerCommand(final Commands.Command command, final Plugin plugin) {
      // Wrapped so a throw inside the command body is attributed to its owner and isolated, exactly
      // like an event-handler failure. The registry still sees the plugin's own name and docs.
      Commands.register(new PluginCommand(command, plugin));
      this.commandsByPlugin.computeIfAbsent(plugin, key -> new ArrayList<>()).add(command.name());
   }

   /** One plugin command plus the fault policy: a throw disables the owner instead of the bot. */
   private final class PluginCommand implements Commands.Command {
      private final Commands.Command delegate;
      private final Plugin plugin;

      private PluginCommand(final Commands.Command delegate, final Plugin plugin) {
         this.delegate = delegate;
         this.plugin = plugin;
      }

      @Override
      public String name() {
         return this.delegate.name();
      }

      @Override
      public String usage() {
         return this.delegate.usage();
      }

      @Override
      public String description() {
         return this.delegate.description();
      }

      @Override
      public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
         try {
            this.delegate.execute(io, session, args);
         } catch (Throwable t) {
            io.printError("[plugins] '" + this.plugin.getName() + "' failed in '" + this.delegate.name() + "': " + t);
            handleRuntimeFailure(this.plugin, "command " + this.delegate.name(), t);
         }
      }
   }

   private void unregisterCommands(final Plugin plugin) {
      List<String> names = this.commandsByPlugin.remove(plugin);
      if (names == null) {
         return;
      }

      for (String name : names) {
         Commands.unregister(name);
      }
   }

   // ---------- jar loading internals ----------

   private record PendingPlugin(File file, PluginDescriptionFile description) {
   }

   /**
    * A dropped Minecraft client jar is not a plugin: {@code eml.bootstrap} moves a recognised one out
    * of {@code plugins/}, but a renamed or unexpected copy must still not be reported as
    * "missing plugin.yml".
    */
   private static boolean isPluginCandidate(final String name) {
      if (!name.endsWith(".jar")) {
         return false;
      }

      String lower = name.toLowerCase(java.util.Locale.ROOT);
      return !lower.contains("minecraft") && !lower.matches("\\d+\\.\\d+(\\.\\d+)?(-snapshot)?\\.jar");
   }

   private static PluginDescriptionFile readDescription(final File jar) throws Exception {
      try (JarFile jarFile = new JarFile(jar)) {
         JarEntry entry = jarFile.getJarEntry("plugin.yml");
         if (entry == null) {
            throw new InvalidPluginException("missing plugin.yml");
         }

         try (InputStream stream = jarFile.getInputStream(entry)) {
            Map<String, Object> yaml = new Yaml().load(stream);
            return PluginDescriptionFile.fromYaml(yaml);
         }
      }
   }

   private List<PendingPlugin> sortByDependencies(final Map<String, PendingPlugin> pending) {
      Map<String, Integer> inDegree = new HashMap<>();
      Map<String, List<String>> dependents = new HashMap<>();
      for (String name : pending.keySet()) {
         inDegree.put(name, 0);
         dependents.put(name, new ArrayList<>());
      }

      for (PendingPlugin candidate : pending.values()) {
         for (String dependency : candidate.description.getDepend()) {
            if (pending.containsKey(dependency)) {
               dependents.get(dependency).add(candidate.description.getName());
               inDegree.merge(candidate.description.getName(), 1, Integer::sum);
            } else if (!this.plugins.containsKey(dependency)) {
               LOGGER.warn("Plugin '{}' depends on missing plugin '{}'; loading anyway", candidate.description.getName(), dependency);
            }
         }

         // Soft dependencies only order loading when they are actually present; missing ones are ignored.
         for (String soft : candidate.description.getSoftDepend()) {
            if (pending.containsKey(soft)) {
               dependents.get(soft).add(candidate.description.getName());
               inDegree.merge(candidate.description.getName(), 1, Integer::sum);
            }
         }
      }

      Queue<String> queue = new ArrayDeque<>();
      for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
         if (entry.getValue() == 0) {
            queue.add(entry.getKey());
         }
      }

      List<PendingPlugin> sorted = new ArrayList<>();
      while (!queue.isEmpty()) {
         String name = queue.poll();
         sorted.add(pending.get(name));
         for (String dependent : dependents.get(name)) {
            if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
               queue.add(dependent);
            }
         }
      }

      if (sorted.size() != pending.size()) {
         LOGGER.error("Dependency cycle detected among plugins; some will not load");
      }

      return sorted;
   }

   /** Instantiates one plugin and leaves it LOADED; enabling is a separate, connection-driven step. */
   private void instantiate(final PendingPlugin pending) throws Exception {
      PluginDescriptionFile description = pending.description;

      // The apiVersion gate is the one compatibility contract the bot can enforce up front, so an
      // incompatible plugin fails loudly here instead of at some deep reflective call months later.
      String api = description.getApiVersion();
      if (api != null && !api.isBlank() && !isApiCompatible(api)) {
         throw new InvalidPluginException(
            "apiVersion '" + api + "' is incompatible with this bot (API " + BotPlugins.API_VERSION + ")");
      }

      ClassLoader parent = PluginManager.class.getClassLoader();
      List<PluginClassLoader> dependencyLoaders = new ArrayList<>();
      for (String dependency : description.getDepend()) {
         PluginClassLoader dependencyLoader = this.loaders.get(dependency);
         if (dependencyLoader != null) {
            dependencyLoaders.add(dependencyLoader);
         }
      }
      // Soft dependencies that happen to be loaded join the visible-class chain, but never a missing one.
      for (String soft : description.getSoftDepend()) {
         PluginClassLoader softLoader = this.loaders.get(soft);
         if (softLoader != null && !dependencyLoaders.contains(softLoader)) {
            dependencyLoaders.add(softLoader);
         }
      }

      // The first declared dependency becomes the parent so its classes are visible.
      if (!dependencyLoaders.isEmpty()) {
         parent = dependencyLoaders.get(0);
      }

      URL url = pending.file.toURI().toURL();
      PluginClassLoader loader = new PluginClassLoader(new URL[]{url}, parent);
      for (int i = 1; i < dependencyLoaders.size(); i++) {
         loader.addDependency(dependencyLoaders.get(i));
      }

      Class<?> mainClass;
      try {
         mainClass = Class.forName(description.getMain(), true, loader);
      } catch (ClassNotFoundException | LinkageError e) {
         // Do not leave the just-created loader holding the jar open on Windows, or reload cannot replace it.
         try {
            loader.close();
         } catch (IOException ignored) {
         }
         throw e;
      }

      if (!Plugin.class.isAssignableFrom(mainClass)) {
         try {
            loader.close();
         } catch (IOException ignored) {
         }
         throw new InvalidPluginException("main class " + description.getMain() + " does not extend " + Plugin.class.getName());
      }

      Plugin plugin = (Plugin) mainClass.getDeclaredConstructor().newInstance();
      File dataFolder = new File(this.pluginsDirectory, description.getName());
      plugin.init(description, loader, dataFolder, this.api);

      this.plugins.put(description.getName(), plugin);
      this.loaders.put(description.getName(), loader);
      this.pluginFiles.put(description.getName(), pending.file);

      try {
         plugin.onLoad();
      } catch (Throwable t) {
         LOGGER.error("Error in onLoad of {}", description.getFullName(), t);
         // A plugin that cannot finish onLoad has no business being enabled later.
         handleFatalFailure(plugin, "onLoad", t);
      }

      LOGGER.info("Loaded {}", description.getFullName());
   }

   /**
    * Major-only compatibility: same major version means the plugin's expected API still exists, a
    * different major is a hard refusal. A blank {@code apiVersion} (plugin predates the field) is allowed.
    */
   private static boolean isApiCompatible(final String requested) {
      return majorOf(requested).equals(majorOf(BotPlugins.API_VERSION));
   }

   private static String majorOf(final String version) {
      int dot = version.indexOf('.');
      return (dot >= 0 ? version.substring(0, dot) : version).trim();
   }
}
