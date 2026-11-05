package top.mcocet.hlbot.promo.campaign;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import top.mcocet.hlbot.promo.EmlPgaPlugin;
import top.mcocet.hlbot.promo.config.PgaSettings;
import top.mcocet.hlbot.promo.support.AdText;
import top.mcocet.hlbot.plugins.BotScheduler;

/**
 * The advertisement loop: one tick-driven state machine that owns every timing decision.
 *
 * <h2>Why a state machine instead of a scheduler task per action</h2>
 *
 * <p>The original plugin mixed three timing mechanisms: a {@code ScheduledExecutorService} for rounds,
 * {@code new Thread(...)} + {@code Thread.sleep} loops for messages and bursts, and a suspend flag checked in
 * six scattered places. Stopping it required interrupting threads and then sleeping "to make sure" they were
 * gone - which is not a guarantee, only a hope.
 *
 * <p>Here there is exactly one clock. {@link #pump()} is called by the framework scheduler on the client
 * thread a few times per second and simply asks "is anything due?":
 *
 * <ul>
 *   <li>a round is due -> pick the recipients and the message list, queue it;</li>
 *   <li>a queued message is due -> send it, then schedule the next one or the next round.</li>
 * </ul>
 *
 * <p>Consequences that matter in practice: {@code stop} takes effect on the next pump with no thread to
 * interrupt, a suspend is one boolean read at two points, and nothing can outlive the plugin because the
 * framework cancels every task it owns on disable.
 *
 * <h2>Connection handling</h2>
 *
 * <p>Rounds are not attempted while the bot is offline, and a dropped connection does not consume a round:
 * the machine parks until the next check. That is the difference between "advertising paused because the
 * bot is reconnecting" and "the schedule fell behind by twenty rounds".
 */
public final class AdCampaign {
   /** Pump period in ticks: 4 ticks = 200 ms, fine enough for 1-second gaps without spinning. */
   private static final long PUMP_PERIOD_TICKS = 4;

   /** How long to wait before re-checking while offline or with no messages, in milliseconds. */
   private static final long IDLE_RECHECK_MILLIS = 2_000L;

   private final EmlPgaPlugin plugin;

   /** Message texts of the round in flight, and how far it got. */
   private final List<String> round = new ArrayList<>();

   private volatile boolean running;

   private int index;
   private long nextRoundAt;
   private long nextSendAt;

   /** Private-mode recipient of the round in flight; {@code null} in public mode. */
   private String target;

   /** Last text actually sent, so {@code skipRepeats} has something to compare against. */
   private String lastText = "";

   /** Last reason the loop did nothing, surfaced by {@code status}. */
   private String note = "";

   private BotScheduler.ScheduledTask pump;

   public AdCampaign(final EmlPgaPlugin plugin) {
      this.plugin = plugin;
   }

   // ==================== lifecycle ====================

   /**
    * Creates the pump timer if it is not running.
    *
    * <p>Called on enable and on start: the framework withdraws a plugin's tasks on disable, so the timer has
    * to be re-created after every disconnect even though the campaign's own state survives.
    */
   public void arm() {
      // The framework cancels a plugin's tasks on disable, but the handle stays non-null - so a re-arm after
      // a reconnect must check isCancelled(), or the pumped clock would silently stay dead.
      if (this.pump == null || this.pump.isCancelled()) {
         this.pump = this.plugin.scheduler().runTimer(this.plugin, this::pump, 1L, PUMP_PERIOD_TICKS);
      }
   }

   /** Begins advertising. Rounds start one interval from now, never immediately after a join. */
   public synchronized void start() {
      this.arm();
      this.running = true;
      this.round.clear();
      this.index = 0;
      this.target = null;
      this.note = "";
      this.nextRoundAt = System.currentTimeMillis() + this.interval();
      this.plugin.log().info(this.plugin.lang().get("campaign.started", this.plugin.settings().mode()));
   }

   /** Stops advertising and cancels the round in flight. Safe to call when already stopped. */
   public synchronized void stop() {
      if (!this.running) {
         return;
      }

      this.running = false;
      this.round.clear();
      this.index = 0;
      this.target = null;
      this.note = "";
      this.plugin.log().info(this.plugin.lang().get("campaign.stopped"));
   }

   public boolean isRunning() {
      return this.running;
   }

   // ==================== the clock ====================

   /** Called by the scheduler; the only place that decides whether something is due. */
   public void pump() {
      if (!this.running) {
         return;
      }

      long now = System.currentTimeMillis();

      if (!this.plugin.api().isConnected()) {
         this.note = this.plugin.lang().get("campaign.offline");
         this.nextRoundAt = now + IDLE_RECHECK_MILLIS;
         return;
      }

      if (this.index >= this.round.size()) {
         if (now >= this.nextRoundAt) {
            this.beginRound(now);
         }

         return;
      }

      if (now >= this.nextSendAt) {
         this.emitNext(now);
      }
   }

   /** Picks recipients and the message list for one round. */
   private void beginRound(final long now) {
      List<String> texts = this.plugin.settings().messages();

      if (texts.isEmpty()) {
         this.note = this.plugin.lang().get("campaign.no.messages");
         this.nextRoundAt = now + IDLE_RECHECK_MILLIS;
         return;
      }

      String recipient = null;

      if (this.plugin.settings().mode() == PgaSettings.Mode.PRIVATE) {
         recipient = this.plugin.roster().nextTarget();

         if (recipient == null) {
            // Nobody eligible: keep the schedule, do not burn a round on an empty audience.
            this.note = this.plugin.lang().get("campaign.no.targets");
            this.nextRoundAt = now + this.interval();
            return;
         }
      }

      this.round.clear();

      if (this.plugin.settings().randomOne()) {
         this.round.add(texts.get(ThreadLocalRandom.current().nextInt(texts.size())));
      } else {
         this.round.addAll(texts);
      }

      this.index = 0;
      this.target = recipient;
      this.nextSendAt = now;
      this.note = "";
   }

   /** Sends the next queued message, then schedules whatever comes after it. */
   private void emitNext(final long now) {
      String raw = this.round.get(this.index++);
      String text = this.compose(raw, this.target, this.plugin.settings().mathFont());

      if (this.plugin.settings().skipRepeats() && text.equals(this.lastText)) {
         // Nothing to send: advance the schedule as if it had been, so the loop cannot stall here.
         this.afterSend(now);
         return;
      }

      this.dispatch(text, this.target);
      this.lastText = text;
      this.afterSend(now);
   }

   private void afterSend(final long now) {
      if (this.index >= this.round.size()) {
         this.nextRoundAt = now + this.interval();
      } else {
         this.nextSendAt = now + this.gap();
      }
   }

   // ==================== manual sends ====================

   /**
    * Sends one line outside the schedule, for {@code say} and {@code pm}.
    *
    * @param raw    the configured message text (placeholders still apply)
    * @param toName player name for {@code /msg}, or {@code null} for public chat
    * @return false when the bot is not connected
    */
   public boolean sendOnce(final String raw, final String toName) {
      if (!this.plugin.api().isConnected()) {
         return false;
      }

      this.dispatch(this.compose(raw, toName, this.plugin.settings().mathFont()), toName);
      return true;
   }

   /**
    * Builds the final line: placeholders, optional digit styling, optional unique suffix.
    *
    * <p>Public because the burst sender composes through exactly the same pipeline. That is the point of
    * keeping the three transforms in one place: two senders that format differently is how a plugin ends up
    * with lines that are unique in one channel and blocked as spam in the other.
    */
   public String composeFor(final String raw, final String name, final boolean styleDigits) {
      return this.compose(raw, name, styleDigits);
   }

   /** Builds the final line: placeholders, optional digit styling, optional unique suffix. */
   private String compose(final String raw, final String name, final boolean styleDigits) {
      String text = AdText.fill(raw, name, this.plugin.api().username(), this.plugin.roster().online().size());

      if (styleDigits) {
         text = AdText.mathFont(text, this.plugin.settings().minDigits());
      }

      if (this.plugin.settings().suffixEnabled()) {
         int length = this.plugin.settings().suffixLength();

         if (length > 0) {
            text = text + " " + AdText.suffix(length);
         }
      }

      return text;
   }

   private void dispatch(final String text, final String toName) {
      if (toName == null) {
         this.plugin.api().sendChat(text);
      } else {
         // Private delivery goes through the server's own /msg command, so it looks exactly like a player
         // doing it - no client-side packet crafting to get wrong.
         this.plugin.api().sendCommand("msg " + toName + " " + text);
      }
   }

   // ==================== timing helpers ====================

   /** Round interval with the configured jitter applied. */
   private long interval() {
      return jittered(this.plugin.settings().roundIntervalSeconds(), this.plugin.settings().jitterSeconds());
   }

   /** Gap between two messages of a round, with its own (usually smaller) jitter. */
   private long gap() {
      return jittered(this.plugin.settings().gapSeconds(), this.plugin.settings().gapJitterSeconds());
   }

   private static long jittered(final int baseSeconds, final int deviationSeconds) {
      if (deviationSeconds <= 0) {
         return baseSeconds * 1000L;
      }

      int min = Math.max(1, baseSeconds - deviationSeconds);
      int max = baseSeconds + deviationSeconds;
      return (min + ThreadLocalRandom.current().nextInt(max - min + 1)) * 1000L;
   }

   // ==================== reporting ====================

   /** Lines describing the current state, for {@code /promo epa status}. */
   public List<String> describe() {
      List<String> lines = new ArrayList<>();
      long now = System.currentTimeMillis();

      lines.add(this.plugin.lang().get("status.campaign",
         this.running ? this.plugin.lang().get("status.running") : this.plugin.lang().get("status.stopped")));
      lines.add(this.plugin.lang().get("status.mode", this.plugin.settings().mode()));
      lines.add(this.plugin.lang().get("status.interval",
         this.plugin.settings().roundIntervalSeconds(),
         this.plugin.settings().gapSeconds(),
         this.plugin.settings().jitterSeconds()));

      if (this.running) {
         if (this.index < this.round.size()) {
            lines.add(this.plugin.lang().get("status.round",
               this.index + "/" + this.round.size(),
               this.target == null ? "-" : this.target,
               Math.max(0L, (this.nextSendAt - now) / 1000L)));
         } else {
            lines.add(this.plugin.lang().get("status.waiting",
               Math.max(0L, (this.nextRoundAt - now) / 1000L)));
         }
      }

      if (!this.note.isEmpty()) {
         lines.add(this.plugin.lang().get("status.note", this.note));
      }

      lines.add(this.plugin.lang().get("status.roster",
         this.plugin.roster().online().size(),
         this.plugin.roster().targets().size(),
         this.plugin.settings().blacklist().size()));
      return lines;
   }
}
