package eml.template;

import com.mojang.authlib.GameProfile;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.client.multiplayer.chat.ChatTrustLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.ChatType.Bound;
import net.minecraft.util.Util;

/**
 * Donor bodies for {@code ChatListener}: keep the vanilla decoration, trust evaluation, filtering and
 * reporting logic, but replace "show it in the GUI" with the console sink plus the plugin event.
 *
 * <p>The message-delay queue ({@code willDelayMessages} / {@code delayedMessageQueue}) is deliberately
 * not used by these donors: it exists to pace human reading, and a bot wants lines immediately. The
 * queue itself stays intact for the unpatched acknowledgement paths.
 */
public final class ChatListenerRouting {
   private ChatListenerRouting() {
   }

   /** {@code handlePlayerChatMessage(PlayerChatMessage, GameProfile, ChatType.Bound)}. */
   public static void playerMessage(final ChatListener self, final PlayerChatMessage message, final GameProfile sender, final Bound boundChatType) {
      boolean onlyShowSecure = self.minecraft.options.onlyShowSecureChat().get();
      PlayerChatMessage displayed = onlyShowSecure ? message.removeUnsignedContent() : message;
      Component decorated = boundChatType.decorate(displayed.decoratedContent());
      Instant received = Instant.now();
      ChatTrustLevel trust = self.evaluateTrustLevel(message, decorated, received);

      if (onlyShowSecure && trust.isNotSecure()) {
         return;
      }

      if (self.minecraft.isBlocked(message.sender()) || message.isFullyFiltered()) {
         return;
      }

      LocalPlayer receiver = self.minecraft.player;
      if (receiver == null || !receiver.chatAbilities().canReceivePlayerMessages()) {
         return;
      }

      boolean cancelled = eml.hooks.BotChat.handle(self.minecraft, false, sender.name(), decorated);
      self.previousMessageTime = Util.getMillis();
      ClientPacketListener connection = self.minecraft.getConnection();
      if (connection != null && message.signature() != null) {
         connection.markMessageAsProcessed(message.signature(), !cancelled);
      }

      // Signed-message reporting stays on the vanilla path even without a GUI.
      self.logPlayerMessage(message, sender, trust);
   }

   /** {@code handleDisguisedChatMessage(Component, ChatType.Bound)}. */
   public static void disguisedMessage(final ChatListener self, final Component message, final Bound boundChatType) {
      LocalPlayer receiver = self.minecraft.player;
      if (receiver == null || !receiver.chatAbilities().canReceivePlayerMessages()) {
         return;
      }

      Component decorated = boundChatType.decorate(message);
      eml.hooks.BotChat.handle(self.minecraft, false, "player", decorated);
      self.previousMessageTime = Util.getMillis();
   }

   /** {@code handleSystemMessage(Component, boolean)}. */
   public static void systemMessage(final ChatListener self, final Component message, final boolean remote) {
      if (self.minecraft.options.hideMatchedNames().get() && self.minecraft.isBlocked(self.guessChatUUID(message))) {
         return;
      }

      LocalPlayer receiver = self.minecraft.player;
      if (receiver == null || !receiver.chatAbilities().canReceiveSystemMessages()) {
         return;
      }

      eml.hooks.BotChat.handle(self.minecraft, remote, remote ? "server" : "client", message);
      if (remote) {
         self.logSystemMessage(message, Instant.now());
      }
   }

   /** {@code handleOverlay(Component)} - the action bar line, printed like any other message. */
   public static void overlayMessage(final ChatListener self, final Component message) {
      eml.hooks.BotChat.handle(self.minecraft, true, "overlay", Component.literal("[overlay] ").append(message));
   }

   /** {@code handleChatMessageError(UUID, MessageSignature, ChatType.Bound)}. */
   public static void messageError(final ChatListener self, final UUID senderId, final MessageSignature invalidSignature, final Bound boundChatType) {
      ClientPacketListener connection = self.minecraft.getConnection();
      if (connection != null && invalidSignature != null) {
         connection.markMessageAsProcessed(invalidSignature, false);
      }

      if (self.minecraft.isBlocked(senderId)) {
         return;
      }

      LocalPlayer receiver = self.minecraft.player;
      if (receiver == null || !receiver.chatAbilities().canReceivePlayerMessages()) {
         return;
      }

      Component decorated = boundChatType.decorate(ChatValidationProbe.error());
      eml.hooks.BotChat.handle(self.minecraft, false, "player", decorated);
      self.previousMessageTime = Util.getMillis();
   }

   /**
    * Small helper class so donors never build a lambda or touch a private constant across classes.
    *
    * <p>It must be PUBLIC: the donor body below is copied into {@code ChatListener}, and the copied call
    * site is resolved against that class - a package-private helper would throw IllegalAccessError.
    */
   public static final class ChatValidationProbe {
      private ChatValidationProbe() {
      }

      public static Component error() {
         return Component.translatable("chat.validation_error").withStyle(ChatFormatting.RED, ChatFormatting.ITALIC);
      }
   }
}
