package top.mcocet.hlbot.autologin;

import java.util.regex.Pattern;
import top.mcocet.hlbot.plugins.event.EventHandler;
import top.mcocet.hlbot.plugins.event.EventPriority;
import top.mcocet.hlbot.plugins.event.Listener;
import top.mcocet.hlbot.plugins.events.chat.ChatMessageEvent;
import top.mcocet.hlbot.plugins.events.connection.DisconnectEvent;
import top.mcocet.hlbot.plugins.events.connection.LoginSuccessEvent;
import top.mcocet.hlbot.plugins.events.player.ContainerOpenedEvent;

/**
 * Event → signal adapter. Chat, login, disconnect and container-open events become {@link Signal}s and are
 * handed to the engine. Handlers run at {@link EventPriority#MONITOR} so AutoLogin only observes, and every
 * handler swallows exceptions: a login rule must never break the bot's own chat or connection path.
 */
public final class RuleListener implements Listener {
   private static final Pattern COLOR_CODE = Pattern.compile("(?i)§[0-9a-fk-or]");

   private final AutoLogin autoLogin;
   private final RuleEngine engine;

   public RuleListener(final AutoLogin autoLogin) {
      this.autoLogin = autoLogin;
      this.engine = autoLogin.engine();
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onChat(final ChatMessageEvent event) {
      try {
         this.autoLogin.selectConnectionTarget();
         String text = strip(event.getMessage());
         if (event.isFromServer()) {
            if ("title".equalsIgnoreCase(event.getSender())) {
               this.engine.handle(new Signal(Signal.Type.TITLE, text, "title", true));
            } else if ("overlay".equalsIgnoreCase(event.getSender())) {
               this.engine.handle(new Signal(Signal.Type.OVERLAY, text, null, true));
            } else {
               this.engine.handle(new Signal(Signal.Type.SYSTEM_CHAT, text, null, false));
            }
         } else {
            String sender = "player".equals(event.getSender()) ? null : event.getSender();
            this.engine.handle(new Signal(Signal.Type.PUBLIC_CHAT, text, sender, false));
         }
      } catch (RuntimeException ignored) {
         // AutoLogin must never break the chat path.
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onLogin(final LoginSuccessEvent event) {
      try {
         this.autoLogin.selectServer(event.getHost(), event.getPort());
         this.engine.handle(new Signal(Signal.Type.SERVER_CHANGE, "GAME", null, false));
      } catch (RuntimeException ignored) {
         // Server selection and reset are best-effort.
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onDisconnect(final DisconnectEvent event) {
      try {
         this.autoLogin.clearServer();
      } catch (RuntimeException ignored) {
         // Reset is best-effort.
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onContainerOpened(final ContainerOpenedEvent event) {
      try {
         if (event.getMenu() == null) {
            return;
         }

         this.autoLogin.selectConnectionTarget();
         String title = event.getTitle() == null ? "" : event.getTitle().getString();
         this.engine.handle(new Signal(Signal.Type.WINDOW_OPEN, title, null, false, event.getMenu().containerId));
      } catch (RuntimeException ignored) {
         // Container tracking is best-effort.
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onContainerSlotUpdated(
      final top.mcocet.hlbot.plugins.events.player.ContainerSlotUpdatedEvent event
   ) {
      try {
         if (event.getMenu() == null || event.getItem() == null || event.getItem().isEmpty()) {
            return;
         }

         this.autoLogin.selectConnectionTarget();
         String text = strip(event.getItem().getDisplayName().getString());
         this.engine.handle(new Signal(Signal.Type.WINDOW_SLOT, text, null, false,
            event.getMenu().containerId, event.getSlot()));
      } catch (RuntimeException ignored) {
         // Container tracking is best-effort.
      }
   }

   private static String strip(final String text) {
      return text == null ? "" : COLOR_CODE.matcher(text).replaceAll("");
   }
}
