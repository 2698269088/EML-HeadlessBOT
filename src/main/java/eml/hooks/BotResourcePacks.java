package eml.hooks;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resource-pack answers for the headless client.
 *
 * <p>Vanilla's flow cannot run here: a push without a stored server-list preference pops a confirm screen
 * (which a headless client never answers), and the download path ends in a resource reload that needs the
 * renderer and the resource manager. So the answer is produced directly - see
 * {@code eml.template.ResourcePackHandling}.
 *
 * <p>Policy: {@code accept} (default) accepts every pack and reports it loaded; {@code decline} refuses
 * optional packs and still accepts required ones, because a required pack that is declined disconnects the
 * player. Configure with {@code bot.yml}'s {@code resourcePacks} or {@code -Deml.resourcePacks=decline}.
 */
public final class BotResourcePacks {
   public enum Policy {
      ACCEPT,
      DECLINE
   }

   private static final Set<UUID> SEEN = ConcurrentHashMap.newKeySet();
   private static volatile Policy policy = initial();

   private BotResourcePacks() {
   }

   /** {@code -Deml.resourcePacks} wins over {@code bot.yml}, so a single launch can override the file. */
   public static void configureFromConfig(final String value) {
      if (System.getProperty("eml.resourcePacks") != null) {
         return;
      }

      setPolicy(value);
   }

   public static void setPolicy(final String value) {
      policy = "decline".equalsIgnoreCase(String.valueOf(value).trim()) ? Policy.DECLINE : Policy.ACCEPT;
   }

   public static Policy policy() {
      return policy;
   }

   /**
    * Records that this pack id has already been answered on this connection.
    *
    * @return true when it was seen before, i.e. the reply can skip straight to the terminal status
    */
   public static boolean markSeen(final UUID id) {
      return !SEEN.add(id);
   }

   public static void forget(final UUID id) {
      SEEN.remove(id);
   }

   public static void clear() {
      SEEN.clear();
   }

   public static int seen() {
      return SEEN.size();
   }

   private static Policy initial() {
      return "decline".equalsIgnoreCase(System.getProperty("eml.resourcePacks", "")) ? Policy.DECLINE : Policy.ACCEPT;
   }
}
