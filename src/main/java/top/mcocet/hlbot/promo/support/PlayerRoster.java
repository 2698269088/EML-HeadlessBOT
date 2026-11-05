package top.mcocet.hlbot.promo.support;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import top.mcocet.hlbot.promo.config.PgaSettings;
import top.mcocet.hlbot.plugins.BotApi;
import top.mcocet.hlbot.plugins.PlayerSnapshot;

/**
 * The bot's view of who is online, plus the two name lists that decide what may be sent to whom.
 *
 * <p>One instance owns all of it: the original plugin kept a static cached name list, a static round-robin
 * index and a static blacklist check in a sender class, so a plugin reload or a second bot in the same JVM
 * shared state it should never have shared. Here the roster is an ordinary object created with the plugin.
 *
 * <p>Reads go through {@link BotApi#onlinePlayers()}, which already hops to the client thread and hands back
 * plain snapshots - so every method here is safe from the command console, a web request or a scheduler
 * task.
 */
public final class PlayerRoster {
   private final BotApi api;
   private final PgaSettings settings;

   /** Round-robin cursor over {@link #targets()}; reset whenever the list shape changes. */
   private int cursor;

   /** Lower-cased names already seen, for join detection. */
   private final Set<String> seen = new HashSet<>();

   public PlayerRoster(final BotApi api, final PgaSettings settings) {
      this.api = api;
      this.settings = settings;
   }

   /** Every online player except the bot itself, in the order the server lists them. */
   public List<String> online() {
      List<String> names = new ArrayList<>();

      for (PlayerSnapshot player : this.api.onlinePlayers()) {
         if (!player.self() && player.name() != null && !player.name().isBlank()) {
            names.add(player.name());
         }
      }

      return names;
   }

   /** Online players that private rounds may message: everything except the blacklist. */
   public List<String> targets() {
      Set<String> blocked = this.blockedKeys();
      List<String> targets = new ArrayList<>();

      for (String name : this.online()) {
         if (!blocked.contains(AdText.key(name))) {
            targets.add(name);
         }
      }

      return targets;
   }

   /**
    * The next private-round target, rotating through {@link #targets()}.
    *
    * @return the name to message, or {@code null} when nobody is eligible - which the caller must treat as
    *         "skip this round" rather than "send to everyone"
    */
   public String nextTarget() {
      List<String> targets = this.targets();

      if (targets.isEmpty()) {
         this.cursor = 0;
         return null;
      }

      if (this.cursor >= targets.size()) {
         this.cursor = 0;
      }

      return targets.get(this.cursor++ % targets.size());
   }

   /**
    * Names that appeared since the previous call, and remembers them.
    *
    * <p>Consumed unconditionally, even when greetings are disabled: otherwise the first tick after enabling
    * them would greet every player who joined while the feature was off.
    */
   public List<String> arrivals() {
      List<String> fresh = new ArrayList<>();
      Set<String> blocked = this.blockedKeys();
      Set<String> current = new LinkedHashSet<>();

      for (String name : this.online()) {
         String key = AdText.key(name);
         current.add(key);

         if (!this.seen.contains(key) && !blocked.contains(key)) {
            fresh.add(name);
         }
      }

      this.seen.retainAll(current);
      this.seen.addAll(current);
      return fresh;
   }

   /** Forgets join detection state, e.g. after a reconnect where the whole tab list is new. */
   public void resetSeen() {
      this.seen.clear();
      this.cursor = 0;
   }

   /** Drops the round-robin cursor so the next round starts with the first eligible player. */
   public void resetCursor() {
      this.cursor = 0;
   }

   private Set<String> blockedKeys() {
      Set<String> keys = new HashSet<>();

      for (String name : this.settings.blacklist()) {
         keys.add(AdText.key(name));
      }

      return keys;
   }
}
