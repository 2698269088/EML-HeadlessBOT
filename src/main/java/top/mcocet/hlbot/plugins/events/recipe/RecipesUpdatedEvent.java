package top.mcocet.hlbot.plugins.events.recipe;

import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the server (re)sends the full recipe set — once during login and again on
 * datapack reload. The recipe container has already been swapped when this fires.
 */
public class RecipesUpdatedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final int itemSetCount;
   private final int stonecutterRecipeCount;

   public RecipesUpdatedEvent(final int itemSetCount, final int stonecutterRecipeCount) {
      this.itemSetCount = itemSetCount;
      this.stonecutterRecipeCount = stonecutterRecipeCount;
   }

   /** Number of crafting item sets in the new recipe container. */
   public int getItemSetCount() {
      return this.itemSetCount;
   }

   /** Number of stonecutter recipes in the new recipe container. */
   public int getStonecutterRecipeCount() {
      return this.stonecutterRecipeCount;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
