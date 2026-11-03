package top.mcocet.hlbot.promo;

import org.slf4j.Logger;
import top.mcocet.hlbot.promo.campaign.AdCampaign;
import top.mcocet.hlbot.promo.campaign.BurstSender;
import top.mcocet.hlbot.promo.campaign.Greeter;
import top.mcocet.hlbot.promo.command.EpaCommand;
import top.mcocet.hlbot.promo.config.PgaSettings;
import top.mcocet.hlbot.promo.support.PlayerRoster;
import top.mcocet.hlbot.promo.support.Translator;
import top.mcocet.hlbot.plugins.BotApi;
import top.mcocet.hlbot.plugins.Plugin;

/**
 * EMLPga - scheduled advertising for a headless bot.
 *
 * <p>This class is wiring only: it builds the parts, moves them along the plugin lifecycle, and exposes them
 * to each other. Every behaviour lives in a component that can be read on its own:
 *
 * <ul>
 *   <li>{@link PgaSettings} - the typed config, the only place a key string appears;</li>
 *   <li>{@link PlayerRoster} - who is online, who is blacklisted, who is new;</li>
 *   <li>{@link AdCampaign} - the round/pause clock and the send pipeline;</li>
 *   <li>{@link BurstSender} - the fast public loop that runs beside private rounds;</li>
 *   <li>{@link Greeter} - join greetings;</li>
 *   <li>{@link EpaCommand} - the {@code /promo epa} table.</li>
 * </ul>
 *
 * <h2>Lifecycle notes that are easy to get wrong</h2>
 *
 * <p>{@code onEnable} fires when the bot enters a world and {@code onDisable} on every disconnect, so the
 * campaign is stopped on disable (there is nobody to talk to) and restarted from {@code config.enabled} on
 * the next enable. The framework also cancels a plugin's scheduled tasks when it disables, which is why each
 * component's {@code arm()} re-creates its timer when the old handle reports cancelled - a stale handle that
 * merely looked non-null is how a timer "disappears" after a reconnect.
 *
 * <p>Nothing here registers chat listeners. The whisper console was removed deliberately: a bot that accepts
 * commands from strangers in chat is a much larger risk than the convenience is worth, and every setting is
 * already reachable from the bot console.
 */
public final class EmlPgaPlugin extends Plugin {
   private PgaSettings settings;
   private volatile Translator lang;
   private PlayerRoster roster;
   private AdCampaign campaign;
   private BurstSender burst;
   private Greeter greeter;
   private EpaCommand command;

   // ==================== lifecycle ====================

   @Override
   public void onLoad() {
      // Seeds plugins/EMLPga/config.yml from the jar on first run, then reads it.
      this.saveDefaultConfig();
      this.settings = new PgaSettings(this.getConfig());
      this.lang = new Translator(this.getClass().getClassLoader(), this.settings.language());
      this.roster = new PlayerRoster(this.getApi(), this.settings);
      this.campaign = new AdCampaign(this);
      this.burst = new BurstSender(this);
      this.greeter = new Greeter(this);
      this.command = new EpaCommand(this);
   }

   @Override
   public void onEnable() {
      this.getApi().registerCommand(this.command, this);
      this.campaign.arm();
      this.greeter.arm();

      if (this.settings.enabled()) {
         this.campaign.start();

         if (this.settings.burstEnabled()) {
            this.burst.start();
         }
      } else {
         this.log().info(this.lang.get("plugin.idle"));
      }

      // A built-in feature has no plugin.yml, so there is no description to read the version from.
      this.log().info(this.lang.get("plugin.enabled", "1.0.0", this.lang.language()));
   }

   @Override
   public void onDisable() {
      // A disconnect means there is no audience; keeping the schedule armed would just burn rounds.
      this.campaign.stop();
      this.burst.stop();
   }

   @Override
   public void onUnload() {
      this.campaign.stop();
      this.burst.stop();
   }

   // ==================== components (used by the subcommands) ====================

   public BotApi api() {
      return this.getApi();
   }

   public PgaSettings settings() {
      return this.settings;
   }

   public Translator lang() {
      return this.lang;
   }

   public PlayerRoster roster() {
      return this.roster;
   }

   public AdCampaign campaign() {
      return this.campaign;
   }

   public BurstSender burst() {
      return this.burst;
   }

   /** Donor-visible logger accessor; the components log through the plugin so every line shares one name. */
   public Logger log() {
      return this.getLogger();
   }

   /**
    * Re-reads the language file and rebuilds the command table.
    *
    * <p>The table is rebuilt because its help summaries are resolved once at construction: keeping the old
    * instance would leave {@code help} printing the previous language until the next restart.
    */
   public void reloadLanguage() {
      this.lang = new Translator(this.getClass().getClassLoader(), this.settings.language());
      this.command = new EpaCommand(this);

      if (this.isEnabled()) {
         this.getApi().registerCommand(this.command, this);
      }
   }
}
