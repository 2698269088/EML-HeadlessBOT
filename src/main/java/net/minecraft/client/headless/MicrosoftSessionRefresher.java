package net.minecraft.client.headless;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Renews the cached Microsoft/Minecraft session on a timer while the bot runs.
 *
 * <p>Startup already reuses or refreshes {@code session.yml}, but a long-lived process can outlive the
 * Minecraft access token (~24 hours). If the token expires and the bot then reconnects, the next login
 * handshake would present a stale token and fail with "invalid session". This task closes that gap: it
 * silently refreshes the session on a fixed schedule, writes the result back to {@code session.yml}, and
 * applies the fresh access token to the running client.
 *
 * <p>The refresh token is the only credential needed, so a successful renewal never asks the user for
 * anything. If the refresh token itself is rejected (revoked, password changed), the task keeps the old
 * session and logs a warning; the user has to run {@code login} again.
 *
 * <p>The task is tied to the account the client started with: if {@code session.yml} is replaced with a
 * different account (the {@code login} command), renewal pauses instead of overwriting the new file, because
 * the running client cannot swap identities until a restart.
 */
public final class MicrosoftSessionRefresher {
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

   /** First renewal happens soon after start so a near-expiry cached token is topped up without delay. */
   private static final long INITIAL_DELAY_MINUTES = 5L;

   private final HeadlessSession session;
   private final File sessionFile;
   private final long periodMinutes;
   private final String identityUuid;
   private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "Microsoft session refresh");
      thread.setDaemon(true);
      return thread;
   });

   private volatile MicrosoftSession current;
   private volatile boolean running;

   public MicrosoftSessionRefresher(final HeadlessSession session, final MicrosoftSession initial, final long periodMinutes) {
      this.session = session;
      this.sessionFile = new File(session.gameDirectory(), "session.yml");
      this.periodMinutes = periodMinutes;
      this.identityUuid = initial.uuid();
      this.current = initial;
   }

   /** Arms the periodic renewal. A non-positive period means the feature is disabled and nothing runs. */
   public void start() {
      if (this.current == null || this.periodMinutes <= 0L) {
         return;
      }

      this.running = true;
      this.executor.scheduleWithFixedDelay(this::refresh, INITIAL_DELAY_MINUTES, this.periodMinutes, TimeUnit.MINUTES);
   }

   public void stop() {
      this.running = false;
      this.executor.shutdownNow();
   }

   private void refresh() {
      if (!this.running) {
         return;
      }

      // Re-read the file so a manual 'login' (which saves a fresh session) is honoured, and pause if the
      // account changed - the running client keeps the old identity until a restart.
      MicrosoftSession base = MicrosoftSession.load(this.sessionFile);
      if (base != null && !base.uuid().equals(this.identityUuid)) {
         LOGGER.info("[session] account in session.yml changed; pausing runtime renewal until restart");
         this.running = false;
         this.executor.shutdown();
         return;
      }

      if (base == null) {
         base = this.current;
      }

      if (base == null || base.refreshToken() == null || base.refreshToken().isBlank()) {
         LOGGER.warn("[session] cannot renew the Microsoft session: no refresh token");
         return;
      }

      try {
         MicrosoftSession updated = MicrosoftAuth.refresh(base);
         updated.save(this.sessionFile);
         this.current = updated;
         this.session.applyRefreshedSession(updated);
         LOGGER.info("[session] renewed Microsoft session for '{}'", updated.name());
      } catch (MicrosoftAuthException e) {
         LOGGER.warn("[session] Microsoft session renewal failed; keeping the current session (run 'login' if it stays expired): {}", e.getMessage());
      } catch (RuntimeException e) {
         LOGGER.warn("[session] could not apply the renewed session to the running client", e);
      }
   }
}
