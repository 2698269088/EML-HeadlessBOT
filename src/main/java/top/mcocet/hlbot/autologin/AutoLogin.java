package top.mcocet.hlbot.autologin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.headless.BotConfig;
import top.mcocet.hlbot.plugins.BotApi;
import top.mcocet.hlbot.plugins.Plugin;

/**
 * Built-in AutoLogin feature: a rule-action engine that walks the server's register / login / queue flow
 * without any per-server code.
 *
 * <p>Its config lives in {@code autologin.yml} next to {@code bot.yml}; the server password lives in
 * {@code bot.yml} as {@code password} and is exposed to rules as {@code {{password}}}. The listener is
 * registered once at startup and the engine resets itself on every connect/disconnect, so it stays active
 * across reconnects without the plugin manager having to own its lifecycle.
 */
public final class AutoLogin extends Plugin {
   private File file;
   private AutoLoginConfig config;
   private RuleEngine engine;
   private RuleListener listener;
   private boolean listenerRegistered;
   private volatile String currentHost = "";
   private volatile int currentPort;
   private volatile String currentProfile = "default";
   private Boolean debugOverride;

   public void install(final File gameDirectory, final BotApi api) {
      this.file = new File(gameDirectory, "autologin.yml");
      this.engine = new RuleEngine(api);
      // Attach so getApi()/getLogger() are wired; autologin.yml itself is parsed by AutoLoginConfig.
      attach("AutoLogin", this.file, api);
      this.seedDefault();
      this.reload();
   }

   @Override
   public void onLoad() {
      // Everything is wired in install(); onLoad exists for the lifecycle shape only.
   }

   @Override
   public void onEnable() {
      this.registerListener();
   }

   private void registerListener() {
      if (this.listenerRegistered || this.getApi() == null) {
         return;
      }

      this.listener = new RuleListener(this);
      this.getApi().registerEvents(this.listener, this);
      this.listenerRegistered = true;
   }

   /** Re-reads {@code autologin.yml} and reapplies the current server's profile without a restart. */
   public synchronized void reload() {
      try {
         this.config = AutoLoginConfig.load(this.file);
         this.applySelection();
      } catch (Exception failed) {
         this.getLogger().error("Could not read {}", this.file.getName(), failed);
         this.config = AutoLoginConfig.empty();
         this.applySelection();
      }
   }

   /** Selects rules before the login event's SERVER_CHANGE signal is evaluated. */
   synchronized void selectServer(final String host, final int port) {
      String wantedHost = host == null ? "" : host;
      if (this.currentPort == port && this.currentHost.equalsIgnoreCase(wantedHost)) {
         return;
      }

      this.currentHost = wantedHost;
      this.currentPort = port;
      if (this.config != null) {
         this.applySelection();
      } else {
         this.engine.reset();
      }
   }

   /** Early titles and system chat can arrive before LoginSuccessEvent; use the recorded dial target for those. */
   synchronized void selectConnectionTarget() {
      this.selectServer(eml.hooks.BotRuntime.targetHost(), eml.hooks.BotRuntime.targetPort());
   }

   synchronized void clearServer() {
      if (this.currentHost.isEmpty() && this.currentPort == 0) {
         this.engine.reset();
         return;
      }

      this.selectServer("", 0);
   }

   private void applySelection() {
      AutoLoginConfig.Selection selection = this.config.select(this.currentHost, this.currentPort);
      this.currentProfile = selection.profile();
      boolean debug = this.debugOverride == null ? selection.debug() : this.debugOverride;
      this.engine.reload(selection.rules(), this.seedVariables(selection), debug, selection.enabled());
      this.getLogger().info("[AutoLogin] {} rule(s) loaded from {}, server={}, profile={}",
         selection.rules().size(), this.file.getName(), this.serverAddress(), selection.profile());
   }

   synchronized void setDebug(final boolean debug) {
      this.debugOverride = debug;
      this.engine.setDebug(debug);
   }

   private Map<String, String> seedVariables(final AutoLoginConfig.Selection selection) {
      Map<String, String> seed = new LinkedHashMap<>();
      seed.putAll(selection.credentials());
      seed.putAll(selection.variables());

      String password = seed.get("password");
      if (password == null || password.isBlank()) {
         BotConfig botConfig = BotConfig.instance();
         seed.put("password", botConfig == null ? "" : botConfig.password());
      }

      String username = this.getApi() == null ? null : this.getApi().username();
      if (username != null && !username.isBlank()) {
         seed.put("username", username);
         seed.put("bot", username);
      }

      seed.put("host", this.currentHost);
      seed.put("port", this.currentPort > 0 ? String.valueOf(this.currentPort) : "");
      seed.put("server", this.serverAddress());

      return seed;
   }

   private void seedDefault() {
      if (this.file.isFile()) {
         return;
      }

      try (InputStream template = AutoLogin.class.getResourceAsStream("/autologin/config.yml")) {
         if (template == null) {
            return;
         }

         Files.createDirectories(this.file.getParentFile().toPath());
         Files.copy(template, this.file.toPath());
      } catch (Exception failed) {
         this.getLogger().warn("Could not seed {}", this.file.getName(), failed);
      }
   }

   RuleEngine engine() {
      return this.engine;
   }

   AutoLoginConfig config() {
      return this.config;
   }

   String serverProfile() {
      return this.currentProfile;
   }

   String serverAddress() {
      return this.currentHost.isBlank() ? "default" : this.currentHost + ":" + this.currentPort;
   }

   File file() {
      return this.file;
   }
}
