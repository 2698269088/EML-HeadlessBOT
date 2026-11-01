package top.mcocet.hlbot.plugins;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

/**
 * Tick-driven task scheduler — the framework primitive plugins otherwise reinvent badly.
 *
 * <p>Every task is scheduled against {@link eml.hooks.BotRuntime#tickCount()}: {@link #pump()} runs once
 * per client tick and fires whatever has come due. Two execution modes:
 * <ul>
 *   <li><b>sync</b> (the default) - the task body runs on the client game thread, so it may read the level,
 *       the player and the connection directly. This is the safe choice and matches the bot's threading
 *       contract.</li>
 *   <li><b>async</b> ({@code runAsync*}) - the task is dispatched to a daemon worker pool, for blocking
 *       work (network, disk). It must NOT touch game state; hop back with
 *       {@link BotApi#runOnClient(Runnable)} when it needs to.</li>
 * </ul>
 *
 * <p>{@code schedule}/{@code cancel} are safe to call from any thread (the task list is guarded), but the
 * due-check and execution always happen under {@link #pump()} on the client thread, so timers tick in
 * whole 50 ms steps and never race the world.
 *
 * <p>Every task is owned by a {@link Plugin}; disabling or unloading a plugin cancels its tasks
 * automatically, so a plugin cannot leave a timer running after it is gone.
 */
public final class BotScheduler {
   /** Process-wide instance, pumped by the tick loop. */
   public static final BotScheduler INSTANCE = new BotScheduler();
   private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

   private final List<Task> tasks = new ArrayList<>();
   private final ExecutorService worker = Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "Bot-Async-Task");
      thread.setDaemon(true);
      return thread;
   });

   private BotScheduler() {
   }

   // ---------- public scheduling API (delay/period are in ticks; 1 tick = 50 ms) ----------

   /** Runs on the next client tick, on the game thread. */
   public ScheduledTask run(final Plugin plugin, final Runnable task) {
      return schedule(plugin, task, 1, 0, false);
   }

   /** Runs after {@code delayTicks} ticks, on the game thread. */
   public ScheduledTask runLater(final Plugin plugin, final Runnable task, final long delayTicks) {
      return schedule(plugin, task, delayTicks, 0, false);
   }

   /** Repeats every {@code periodTicks} ticks after an initial delay, on the game thread. */
   public ScheduledTask runTimer(final Plugin plugin, final Runnable task, final long delayTicks, final long periodTicks) {
      return schedule(plugin, task, delayTicks, Math.max(1, periodTicks), false);
   }

   /** Runs immediately on a worker thread. Must not touch game state. */
   public ScheduledTask runAsync(final Plugin plugin, final Runnable task) {
      return schedule(plugin, task, 1, 0, true);
   }

   /** Runs after {@code delayTicks} ticks on a worker thread. Must not touch game state. */
   public ScheduledTask runAsyncLater(final Plugin plugin, final Runnable task, final long delayTicks) {
      return schedule(plugin, task, delayTicks, 0, true);
   }

   /** Repeats every {@code periodTicks} ticks on a worker thread. Must not touch game state. */
   public ScheduledTask runAsyncTimer(final Plugin plugin, final Runnable task, final long delayTicks, final long periodTicks) {
      return schedule(plugin, task, delayTicks, Math.max(1, periodTicks), true);
   }

   private ScheduledTask schedule(final Plugin plugin, final Runnable task, final long delayTicks, final long periodTicks, final boolean async) {
      long startTick = eml.hooks.BotRuntime.tickCount() + Math.max(0, delayTicks);
      Task entry = new Task(plugin, task, startTick, periodTicks, async);
      synchronized (this.tasks) {
         this.tasks.add(entry);
      }
      return entry;
   }

   /** Cancels every task owned by {@code plugin}; called on disable/unload so nothing outlives its owner. */
   public void cancelAll(final Plugin plugin) {
      synchronized (this.tasks) {
         for (Task task : this.tasks) {
            if (task.plugin == plugin) {
               task.cancelled = true;
            }
         }
         this.tasks.removeIf(task -> task.plugin == plugin);
      }
   }

   /** Advances the clock and fires due tasks. Called exactly once per client tick, on the client thread. */
   public void pump() {
      long tick = eml.hooks.BotRuntime.tickCount();
      List<Task> due = null;

      synchronized (this.tasks) {
         if (this.tasks.isEmpty()) {
            return;
         }

         Iterator<Task> iterator = this.tasks.iterator();
         while (iterator.hasNext()) {
            Task task = iterator.next();
            if (task.cancelled) {
               iterator.remove();
               continue;
            }

            if (tick >= task.nextTick) {
               (due == null ? due = new ArrayList<>() : due).add(task);
               if (task.periodTicks > 0) {
                  task.nextTick = tick + task.periodTicks;
               } else {
                  task.cancelled = true;
                  iterator.remove();
               }
            }
         }
      }

      if (due == null) {
         return;
      }

      for (Task task : due) {
         if (task.async) {
            this.worker.execute(() -> guard(task));
         } else {
            guard(task);
         }
      }
   }

   private void guard(final Task task) {
      if (task.cancelled) {
         return;
      }

      try {
         task.runnable.run();
      } catch (Throwable t) {
         LOGGER.error("Scheduled task of plugin '{}' failed", task.plugin == null ? "?" : task.plugin.getName(), t);
         // Paper-style isolation: a task that keeps throwing must not keep damaging the tick loop.
         BotPlugins.reportFailure(task.plugin, "scheduler", t);
      }
   }

   /** Stops the worker pool and drops all tasks. Called once at bot shutdown, after plugins unload. */
   public void shutdown() {
      synchronized (this.tasks) {
         for (Task task : this.tasks) {
            task.cancelled = true;
         }
         this.tasks.clear();
      }

      this.worker.shutdownNow();
   }

   /** Handle for a scheduled task; lets its owner cancel it independently of the repeat schedule. */
   public interface ScheduledTask {
      void cancel();

      boolean isCancelled();
   }

   private static final class Task implements ScheduledTask {
      final Plugin plugin;
      final Runnable runnable;
      final long periodTicks;
      final boolean async;
      long nextTick;
      volatile boolean cancelled;

      Task(final Plugin plugin, final Runnable runnable, final long nextTick, final long periodTicks, final boolean async) {
         this.plugin = plugin;
         this.runnable = runnable;
         this.nextTick = nextTick;
         this.periodTicks = periodTicks;
         this.async = async;
      }

      @Override
      public void cancel() {
         this.cancelled = true;
      }

      @Override
      public boolean isCancelled() {
         return this.cancelled;
      }
   }
}
