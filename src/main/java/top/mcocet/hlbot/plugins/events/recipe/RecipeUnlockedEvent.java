package top.mcocet.hlbot.plugins.events.recipe;

import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when the server notifies the client that a new recipe became available (the
 * toast slot in vanilla). Drive auto-crafting or recipe tracking from here.
 */
public class RecipeUnlockedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final RecipeDisplayId recipeId;

   public RecipeUnlockedEvent(final RecipeDisplayId recipeId) {
      this.recipeId = recipeId;
   }

   public RecipeDisplayId getRecipeId() {
      return this.recipeId;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
