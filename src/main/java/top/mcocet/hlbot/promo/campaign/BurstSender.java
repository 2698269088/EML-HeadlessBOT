package top.mcocet.hlbot.promo.campaign;

import java.util.ArrayList;
import java.util.List;
import top.mcocet.hlbot.promo.EmlPgaPlugin;
import top.mcocet.hlbot.promo.config.PgaSettings;
import top.mcocet.hlbot.plugins.BotScheduler;

/**
 * The fast public loop that runs <em>alongside</em> private rounds.
 *
 * <p>This is the original plugin's "multi-thread" feature, kept because it is genuinely useful: while the
 * main schedule whispers to one player at a time, the public chat is kept busy by a second, faster loop. The
 * name was misleading - it was never multithreaded in any meaningful sense, it was a second schedule with a
 * different cadence - so here it is called what it is: a burst.
 *
 * <p>Two things are preserved on purpose:
 * <ul>
 *   <li>it only runs while the main mode is private (otherwise the two loops would talk over each other in
 *       the same channel);</li>
 *   <li>it works in windows - {@code durationSeconds} of sending, then a pause of {@code checkSeconds} -
 *       which is what stops a 2-second cadence from looking like a spambot to the server.</li>
 * </ul>
 *
 * <p>Unlike the original it holds no thread: a window is two timestamps, so {@code stop} is immediate and
 * cannot leave a sleep loop behind.
 */
public final class BurstSender {
   private static final long PUMP_PERIOD_TICKS = 4;

   private final EmlPgaPlugin plugin;

   /** Whether the user asked for bursts (independent of the current window state). */
   private volatile boolean enabled;

   private boolean windowActive;
   private long windowEndsAt;
   private long nextWindowAt;
   private long nextSendAt;
   private int index;

   private BotScheduler.ScheduledTask pump;

   public BurstSender(final EmlPgaPlugin plugin) {
      this.plugin = plugin;
   }

   public void arm() {
      // Cancelled handles stay non-null after a disable, so the check has to include isCancelled().
      if (this.pump == null || this.pump.isCancelled()) {
         this.pump = this.plugin.scheduler().runTimer(this.plugin, this::pump, 1L, PUMP_PERIOD_TICKS);
      }
   }

   /**
    * Turns bursts on.
    *
    * @return false with a logged reason when the preconditions are not met, so the command can report it
    *         instead of leaving the user with a silent no-op
    */
   public synchronized boolean start() {
      if (this.enabled) {
         this.plugin.log().warn(this.plugin.lang().get("burst.already"));
         return false;
      }

      if (this.plugin.settings().mode() != PgaSettings.Mode.PRIVATE) {
         this.plugin.log().warn(this.plugin.lang().get("burst.needs.private"));
         return false;
      }

      if (!this.plugin.campaign().isRunning()) {
         this.plugin.log().warn(this.plugin.lang().get("burst.needs.running"));
         return false;
      }

      this.arm();
      this.enabled = true;
      this.windowActive = false;
      this.nextWindowAt = System.currentTimeMillis();
      this.plugin.log().info(this.plugin.lang().get("burst.started",
         this.plugin.settings().burstIntervalSeconds(),
         this.plugin.settings().burstDurationSeconds()));
      return true;
   }

   public synchronized void stop() {
      if (!this.enabled) {
         return;
      }

      this.enabled = false;
      this.windowActive = false;
      this.plugin.log().info(this.plugin.lang().get("burst.stopped"));
   }

   public boolean isEnabled() {
      return this.enabled;
   }

   /** True while a sending window is open, as opposed to the pause between windows. */
   public boolean isSending() {
      return this.enabled && this.windowActive;
   }

   public void pump() {
      if (!this.enabled) {
         return;
      }

      // Idle, not stopped: switching the main mode back to private resumes bursts without a re-start.
      if (this.plugin.settings().mode() != PgaSettings.Mode.PRIVATE || !this.plugin.api().isConnected()) {
         return;
      }

      long now = System.currentTimeMillis();

      if (this.windowActive) {
         if (now >= this.windowEndsAt) {
            this.windowActive = false;
            this.nextWindowAt = now + this.plugin.settings().burstCheckSeconds() * 1000L;
            return;
         }

         if (now >= this.nextSendAt) {
            this.sendOne();
            this.nextSendAt = now + this.plugin.settings().burstIntervalSeconds() * 1000L;
         }

         return;
      }

      if (now >= this.nextWindowAt) {
         this.windowActive = true;
         this.windowEndsAt = now + this.plugin.settings().burstDurationSeconds() * 1000L;
         this.nextSendAt = now;
         this.index = 0;
      }
   }

   private void sendOne() {
      List<String> texts = this.plugin.settings().burstMessages();

      if (texts.isEmpty()) {
         return;
      }

      String raw = texts.get(this.index++ % texts.size());
      String text = this.plugin.campaign().composeFor(raw, null, this.plugin.settings().burstMathFont());
      this.plugin.api().sendChat(text);
   }

   public List<String> describe() {
      List<String> lines = new ArrayList<>();
      String state;

      if (!this.enabled) {
         state = this.plugin.lang().get("status.stopped");
      } else if (this.windowActive) {
         state = this.plugin.lang().get("status.running");
      } else {
         // Distinct from the campaign's "waiting for the next round" key: this one takes no arguments.
         state = this.plugin.lang().get("status.paused");
      }

      lines.add(this.plugin.lang().get("status.burst", state,
         this.plugin.settings().burstIntervalSeconds(),
         this.plugin.settings().burstDurationSeconds()));
      return lines;
   }
}
