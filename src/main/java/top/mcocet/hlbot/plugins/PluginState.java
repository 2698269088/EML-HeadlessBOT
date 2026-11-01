package top.mcocet.hlbot.plugins;

/**
 * Lifecycle state of a managed plugin. The bot drives every transition, never the plugin:
 *
 * <pre>
 *   (jar scanned)          onLoad()              (server connected)   onEnable()
 *        LOADED  ─────────────────────────────►   ENABLED  ◄──────────────────────
 *          ▲                                          │  (server disconnected)
 *          │        (reload: unload then load)        │  onDisable()
 *          └──────────── UNLOADED ◄── onUnload() ── DISABLED
 * </pre>
 *
 * <ul>
 *   <li>{@link #LOADED} - instantiated and {@code onLoad()} done; classes and config are reachable,
 *       but no listeners/commands are registered and no game state is touched. Plugins sit here from
 *       startup until the client first enters a world.</li>
 *   <li>{@link #ENABLED} - {@code onEnable()} ran on the client thread after a successful server
 *       connection; the plugin may register events/commands and use the world.</li>
 *   <li>{@link #DISABLED} - {@code onDisable()} ran (disconnected, or an explicit disable); listeners
 *       and commands were withdrawn. Still loaded, so it can be re-enabled on the next connection.</li>
 *   <li>{@link #FAILED} - quarantined after throwing in {@code onLoad()}/{@code onEnable()}, an event,
 *       a scheduled task or a console command. Left out by the automatic enable-all; an explicit
 *       {@code plugins enable <name>} clears the fault and retries.</li>
 *   <li>{@link #UNLOADED} - {@code onUnload()} ran and the class loader is closed; the plugin is gone
 *       until reloaded from disk.</li>
 * </ul>
 */
public enum PluginState {
   LOADED,
   ENABLED,
   DISABLED,
   /** Quarantined by the fault policy: listeners, commands and timers withdrawn, never auto-enabled. */
   FAILED,
   UNLOADED
}
