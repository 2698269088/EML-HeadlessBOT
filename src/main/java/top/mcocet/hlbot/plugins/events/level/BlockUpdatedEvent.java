package top.mcocet.hlbot.plugins.events.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import top.mcocet.hlbot.plugins.event.Event;
import top.mcocet.hlbot.plugins.event.HandlerList;

/**
 * Fired when a single block changes at a position (pistons, growth, other players
 * building, ...). Batched section updates are deliberately not reported — at that rate a
 * listener would be a hot spot; poll with {@code ClientTickEvent} instead if you need
 * area diffs.
 */
public class BlockUpdatedEvent extends Event {
   private static final HandlerList HANDLERS = new HandlerList();

   private final BlockPos pos;
   private final BlockState newState;

   public BlockUpdatedEvent(final BlockPos pos, final BlockState newState) {
      this.pos = pos;
      this.newState = newState;
   }

   public BlockPos getPos() {
      return this.pos;
   }

   public BlockState getNewState() {
      return this.newState;
   }

   @Override
   public HandlerList getHandlers() {
      return HANDLERS;
   }

   public static HandlerList getHandlerList() {
      return HANDLERS;
   }
}
