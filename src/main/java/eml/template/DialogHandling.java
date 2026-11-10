package eml.template;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogConnectionAccess;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.core.Holder;
import net.minecraft.server.dialog.Dialog;

/** Routes server dialogs to the headless model instead of constructing GUI screens. */
public final class DialogHandling {
   private DialogHandling() {
   }

   public static void showDialog(final ClientCommonPacketListenerImpl self, final Holder<Dialog> dialog,
                                 final DialogConnectionAccess access, final Screen previous) {
      eml.action.BotDialogs.INSTANCE.open(dialog, access);
   }

   public static void clearDialog(final ClientCommonPacketListenerImpl self) {
      eml.action.BotDialogs.INSTANCE.close("server");
   }
}
