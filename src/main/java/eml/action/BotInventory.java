package eml.action;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.jspecify.annotations.Nullable;

/**
 * Container and inventory control - the {@code clickWindow} layer.
 *
 * <p>This is the gap that mattered most: without it the bot could walk and mine but could not open a chest,
 * move a stack, or craft anything, which rules out most things a bot is actually for. Every operation here
 * is one vanilla packet, sent through the same public entry point the vanilla GUI uses
 * ({@link MultiPlayerGameMode#handleContainerInput}), so the server sees traffic it cannot distinguish from
 * a real player clicking. Nothing is reimplemented: the local menu mutation, the changed-slot hashes the
 * 26.x desync check requires, and the packet are all vanilla's own work.
 *
 * <h2>Threading</h2>
 *
 * <p>Client thread only. Every method reads {@code client.player.containerMenu} and mutates it, exactly like
 * a GUI would; call through {@code BotApi.runOnClient} or from an event handler.
 *
 * <h2>Slot numbering</h2>
 *
 * <p>Indices are the menu's own ({@code 0..menu.slots.size()-1}), which is also what the protocol uses. They
 * are <em>not</em> inventory indices: for a chest menu the chest slots come first, then the player's main
 * inventory, then the hotbar. Use {@link #slotCount()} and {@link #slot(int)} to inspect rather than
 * hardcoding, and {@link #playerInventorySlot(int)} to convert a hotbar/main-inventory index when a menu has
 * no such slot.
 */
public final class BotInventory {
   /** Vanilla's "click outside the window" slot id, which drops the cursor stack into the world. */
   public static final int OUTSIDE = -999;

   /** Buttons for {@link ContainerInput#SWAP}: the hotbar index the cursor stack is swapped with. */
   public static final int SWAP_OFFHAND = 40;

   private final Supplier<Minecraft> clients;

   public BotInventory(final Supplier<Minecraft> clients) {
      this.clients = clients;
   }

   // ==================== state ====================

   /** The menu the player currently has open; never null once a player exists (it defaults to the inventory). */
   public @Nullable AbstractContainerMenu menu() {
      LocalPlayer player = player();
      return player == null ? null : player.containerMenu;
   }

   /** {@code containerId} of the open menu, as sent to the server. */
   public int containerId() {
      AbstractContainerMenu menu = menu();
      return menu == null ? -1 : menu.containerId;
   }

   /** Monotonic desync-guard counter the server compares against; useful when a click is rejected. */
   public int stateId() {
      AbstractContainerMenu menu = menu();
      return menu == null ? -1 : menu.getStateId();
   }

   public int slotCount() {
      AbstractContainerMenu menu = menu();
      return menu == null ? 0 : menu.slots.size();
   }

   public boolean isOpen() {
      AbstractContainerMenu menu = menu();
      return menu != null && menu != playerInventoryMenu();
   }

   /** True when the server owns the inventory (chests, crafting tables, ...), false for the plain inventory. */
   public boolean isServerControlled() {
      MultiPlayerGameMode gameMode = gameMode();
      return gameMode != null && gameMode.isServerControlledInventory();
   }

   /** A copy of every slot's stack, in menu order. */
   public List<ItemStack> contents() {
      List<ItemStack> stacks = new ArrayList<>();
      AbstractContainerMenu menu = menu();

      if (menu != null) {
         for (Slot slot : menu.slots) {
            stacks.add(slot.getItem());
         }
      }

      return stacks;
   }

   /** The stack held by the cursor. */
   public ItemStack carried() {
      AbstractContainerMenu menu = menu();
      return menu == null ? ItemStack.EMPTY : menu.getCarried();
   }

   /** The stack in one slot, or {@link ItemStack#EMPTY} when the index is out of range. */
   public ItemStack slot(final int index) {
      AbstractContainerMenu menu = menu();

      if (menu == null || index < 0 || index >= menu.slots.size()) {
         return ItemStack.EMPTY;
      }

      return menu.getSlot(index).getItem();
   }

   /** Size of the part of the menu that belongs to the container itself, excluding the player inventory. */
   public int containerSlotCount() {
      AbstractContainerMenu menu = menu();

      if (menu == null) {
         return 0;
      }

      int containerSlots = 0;

      for (Slot slot : menu.slots) {
         // The player's own slots are the ones backed by its inventory; everything else is the container.
         if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory)) {
            containerSlots++;
         }
      }

      return containerSlots;
   }

   // ==================== clicks ====================

   /**
    * One raw click. This is the whole API: everything below composes it.
    *
    * @param slot  menu slot index, or {@link #OUTSIDE}
    * @param input the vanilla container action
    * @param button the vanilla button argument (0 left / 1 right for {@code PICKUP}, hotbar index for
    *               {@code SWAP}, 0 one / 1 all for {@code THROW})
    * @return true when a packet was sent
    */
   public boolean click(final int slot, final ContainerInput input, final int button) {
      LocalPlayer player = player();
      MultiPlayerGameMode gameMode = gameMode();
      AbstractContainerMenu menu = menu();

      if (player == null || gameMode == null || menu == null) {
         return false;
      }

      if (slot != OUTSIDE && (slot < 0 || slot >= menu.slots.size())) {
         return false;
      }

      // handleContainerInput snapshots the slots, applies the click locally, hashes the changed slots and
      // sends ServerboundContainerClickPacket - the same path a mouse button takes. It refuses a mismatched
      // containerId itself, so a menu that was replaced mid-call degrades to a warning, not a desync.
      gameMode.handleContainerInput(menu.containerId, slot, button, input, player);
      return true;
   }

   /** Left click: pick up / put down / merge with the cursor stack. */
   public boolean leftClick(final int slot) {
      return click(slot, ContainerInput.PICKUP, 0);
   }

   /** Right click: take half, or place one. */
   public boolean rightClick(final int slot) {
      return click(slot, ContainerInput.PICKUP, 1);
   }

   /** Shift + left click: the container decides where the stack goes (chest to inventory and back). */
   public boolean shiftClick(final int slot) {
      return click(slot, ContainerInput.QUICK_MOVE, 0);
   }

   /** Q / Ctrl+Q on a slot: drop one, or the whole stack. */
   public boolean drop(final int slot, final boolean wholeStack) {
      return click(slot, ContainerInput.THROW, wholeStack ? 1 : 0);
   }

   /** Number key while hovering a slot: swaps it with that hotbar slot. */
   public boolean swapWithHotbar(final int slot, final int hotbarIndex) {
      return click(slot, ContainerInput.SWAP, hotbarIndex);
   }

   /** F while hovering a slot: swap with the offhand. */
   public boolean swapWithOffhand(final int slot) {
      return click(slot, ContainerInput.SWAP, SWAP_OFFHAND);
   }

   /** Double click: collect every matching stack from the container onto the cursor. */
   public boolean collect(final int slot) {
      return click(slot, ContainerInput.PICKUP_ALL, 0);
   }

   /** Middle click in creative: clone the hovered stack onto the cursor. */
   public boolean cloneSlot(final int slot) {
      return click(slot, ContainerInput.CLONE, 0);
   }

   /** Click outside the window to drop the cursor stack into the world. */
   public boolean dropCarried() {
      return click(OUTSIDE, ContainerInput.PICKUP, 0);
   }

   /**
    * Moves a stack without shift-click, as two pickups: pick it up, then place it on the target. Fails (and
    * leaves the cursor as it was) when the target already holds a different item, because vanilla would
    * refuse the merge anyway and a silent half-move is worse than a false.
    */
   public boolean move(final int from, final int to) {
      ItemStack source = slot(from);
      ItemStack target = slot(to);

      if (source.isEmpty()) {
         return false;
      }

      if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(source, target)) {
         return false;
      }

      if (!leftClick(from)) {
         return false;
      }

      if (!leftClick(to)) {
         // Put it back rather than stranding it on the cursor.
         leftClick(from);
         return false;
      }

      return true;
   }

   /** Empties one slot into the player inventory using shift-click (chest, furnace output, crafting result). */
   public boolean intoInventory(final int slot) {
      return shiftClick(slot);
   }

   // ==================== container buttons & creative ====================

   /** Enchanting/stonecutter/anvil-style button, e.g. the furnace recipe-book button or an enchant option. */
   public boolean button(final int buttonId) {
      MultiPlayerGameMode gameMode = gameMode();
      AbstractContainerMenu menu = menu();

      if (gameMode == null || menu == null) {
         return false;
      }

      gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
      return true;
   }

   /** Creative-only: put a stack straight into a slot, bypassing the click protocol. */
   public boolean creativeGive(final ItemStack stack, final int slot) {
      MultiPlayerGameMode gameMode = gameMode();

      if (gameMode == null) {
         return false;
      }

      gameMode.handleCreativeModeItemAdd(stack, slot);
      return true;
   }

   /** Enables or disables one slot of a menu the server marked as state-controlled (e.g. a recipe book slot). */
   public boolean slotState(final int slot, final boolean enabled) {
      MultiPlayerGameMode gameMode = gameMode();
      AbstractContainerMenu menu = menu();

      if (gameMode == null || menu == null) {
         return false;
      }

      gameMode.handleSlotStateChanged(slot, menu.containerId, enabled);
      return true;
   }

   // ==================== crafting ====================

   /**
    * Every recipe the recipe book currently reports as craftable with the items on hand.
    *
    * <p>This is the honest way to answer "what can I make": the client's recipe book is exactly what the
    * vanilla recipe screen shows, already filtered by the server's recipe list and by inventory contents.
    * Matching a desired product is the caller's job via {@link RecipeDisplayEntry#craftingRequirements()},
    * which lists the ingredients in grid order - no result-stack resolution needed, so nothing here depends
    * on a registry context being built correctly.
    */
   public List<RecipeDisplayEntry> craftable() {
      List<RecipeDisplayEntry> entries = new ArrayList<>();
      LocalPlayer player = player();

      if (player == null) {
         return entries;
      }

      for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
         for (RecipeDisplayEntry entry : collection.getRecipes()) {
            if (collection.isCraftable(entry.id())) {
               entries.add(entry);
            }
         }
      }

      return entries;
   }

   /**
    * Asks the server to lay a recipe out in the open menu's grid (the vanilla recipe-book click).
    *
    * <p>The placement happens server-side from the recipe id, which is why this works for a crafting table
    * the bot never sees a GUI for. {@code shiftDown} makes the result go straight into the inventory, which
    * is what an automation loop wants; leaving it false leaves the result on the crafting slot.
    */
   public boolean placeRecipe(final RecipeDisplayId recipe, final boolean shiftDown) {
      MultiPlayerGameMode gameMode = gameMode();
      AbstractContainerMenu menu = menu();

      if (gameMode == null || menu == null) {
         return false;
      }

      gameMode.handlePlaceRecipe(menu.containerId, recipe, shiftDown);
      return true;
   }

   /** Convenience overload for {@link #placeRecipe(RecipeDisplayId, boolean)}. */
   public boolean craft(final RecipeDisplayEntry recipe, final boolean shiftDown) {
      return placeRecipe(recipe.id(), shiftDown);
   }

   // ==================== closing ====================

   /**
    * Closes the open container, which the server needs to know before it will accept a click in the next
    * one. Sending the packet is enough; {@code ClientPacketListener} clears the menu when the server agrees.
    */
   public boolean close() {
      Minecraft client = clients.get();
      AbstractContainerMenu menu = menu();

      if (client == null || client.getConnection() == null || menu == null || menu == playerInventoryMenu()) {
         return false;
      }

      client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(menu.containerId));
      return true;
   }

   // ==================== helpers ====================

   /** Converts a player-inventory index (0-35, hotbar last) to the menu slot that holds it. */
   public int playerInventorySlot(final int inventoryIndex) {
      AbstractContainerMenu menu = menu();

      if (menu == null) {
         return -1;
      }

      for (int index = 0; index < menu.slots.size(); index++) {
         Slot slot = menu.getSlot(index);

         if (slot.container instanceof net.minecraft.world.entity.player.Inventory inventory
            && inventoryIndex == slot.getContainerSlot()) {
            return index;
         }
      }

      return -1;
   }

   private @Nullable LocalPlayer player() {
      Minecraft client = this.clients.get();
      return client == null ? null : client.player;
   }

   private @Nullable MultiPlayerGameMode gameMode() {
      Minecraft client = this.clients.get();
      return client == null ? null : client.gameMode;
   }

   private @Nullable AbstractContainerMenu playerInventoryMenu() {
      LocalPlayer player = player();
      return player == null ? null : player.inventoryMenu;
   }
}
