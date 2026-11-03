package top.mcocet.hlbot.promo.campaign;

import java.util.List;
import top.mcocet.hlbot.promo.EmlPgaPlugin;
import top.mcocet.hlbot.promo.support.AdText;
import top.mcocet.hlbot.plugins.BotScheduler;

/**
 * Public greeting for players who newly appear in the tab list.
 *
 * <p>The original plugin hung its greeting off the private-message path, so it only ever appeared when a
 * private round happened to reach that player - which made the feature look broken on a quiet server. A
 * greeting belongs to the moment someone joins, so it is driven by the roster instead: every second the
 * roster reports names that were not there before, and each one is greeted once.
 *
 * <p>Arrivals are consumed even while the feature is off. Otherwise turning it on would dump a greeting for
 * everybody who joined during the quiet period, which reads as a bug to the person receiving them.
 */
public final class Greeter {
   /** One second: joins are not urgent enough to poll per tick, and this keeps the tab-list read cheap. */
   private static final long PUMP_PERIOD_TICKS = 20;

   private final EmlPgaPlugin plugin;
   private BotScheduler.ScheduledTask pump;

   public Greeter(final EmlPgaPlugin plugin) {
      this.plugin = plugin;
   }

   public void arm() {
      // Cancelled handles stay non-null after a disable, so the check has to include isCancelled().
      if (this.pump == null || this.pump.isCancelled()) {
         this.pump = this.plugin.scheduler().runTimer(this.plugin, this::pump, 20L, PUMP_PERIOD_TICKS);
      }
   }

   public void pump() {
      List<String> arrivals = this.plugin.roster().arrivals();

      if (arrivals.isEmpty() || !this.plugin.settings().greetingEnabled()) {
         return;
      }

      if (!this.plugin.api().isConnected()) {
         return;
      }

      String format = this.plugin.settings().greetingFormat();
      int online = this.plugin.roster().online().size();

      for (String name : arrivals) {
         this.plugin.api().sendChat(AdText.fill(format, name, this.plugin.api().username(), online));
         this.plugin.log().info(this.plugin.lang().get("greeting.sent", name));
      }
   }
}
