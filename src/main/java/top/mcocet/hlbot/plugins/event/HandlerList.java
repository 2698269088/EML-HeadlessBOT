package top.mcocet.hlbot.plugins.event;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-event-type listener registry, ordered by {@link EventPriority}. Backed by
 * {@link CopyOnWriteArrayList} so events may be fired while listeners register or
 * unregister concurrently.
 */
public class HandlerList {
   private final EnumMap<EventPriority, CopyOnWriteArrayList<RegisteredListener>> handlers = new EnumMap<>(EventPriority.class);
   private volatile RegisteredListener[] snapshot = new RegisteredListener[0];

   public HandlerList() {
      for (EventPriority priority : EventPriority.values()) {
         this.handlers.put(priority, new CopyOnWriteArrayList<>());
      }
   }

   public void register(final RegisteredListener listener) {
      this.handlers.get(listener.priority()).add(listener);
      this.rebuildSnapshot();
   }

   public void unregister(final RegisteredListener listener) {
      this.handlers.get(listener.priority()).remove(listener);
      this.rebuildSnapshot();
   }

   private void rebuildSnapshot() {
      List<RegisteredListener> all = new ArrayList<>();
      for (EventPriority priority : EventPriority.values()) {
         all.addAll(this.handlers.get(priority));
      }

      this.snapshot = all.toArray(new RegisteredListener[0]);
   }

   /** Ordered listener view. Cached on change so firing an event allocates nothing. */
   public RegisteredListener[] getRegisteredListeners() {
      return this.snapshot;
   }
}
