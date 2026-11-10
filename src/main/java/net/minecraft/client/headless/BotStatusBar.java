package net.minecraft.client.headless;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.Status;

/** A one-line status display pinned above the terminal's scrolling region. */
public final class BotStatusBar {
   private static final String WIDGET = "eml-status-bar-redraw";
   private static final long REFRESH_NANOS = 1_000_000_000L;
   private static final Object RENDER_LOCK = new Object();

   private static volatile boolean enabled = true;
   private static volatile LineReader activeReader;
   private static volatile String currentText = " EML-HeadlessBOT | STARTING ";
   private static Status reservation;
   private static String lastSignature;
   private static long lastRefresh;

   private BotStatusBar() {
   }

   public static void setEnabled(final boolean value) {
      enabled = value;
      if (!value) {
         close();
      }
   }

   /** Reserves one JLine status row, then moves that reserved row from the bottom to the top. */
   public static void install(final LineReader reader) {
      close();
      if (!enabled || reader == null || !supports(reader.getTerminal())) {
         return;
      }

      Terminal terminal = reader.getTerminal();
      Size size = terminal.getSize();
      if (size.getRows() < 4 || size.getColumns() < 16) {
         return;
      }

      Status status = Status.getStatus(terminal);
      if (status == null) {
         return;
      }

      synchronized (RENDER_LOCK) {
         // JLine subtracts one row from its editable display while this status exists. Suspending its own
         // redraw leaves that accounting in place without letting it restore its usual bottom scroll region.
         status.update(List.of(new AttributedString("")), false);
         status.suspend();
         reservation = status;
         activeReader = reader;
         lastSignature = null;
         lastRefresh = 0L;
         reader.getWidgets().put(WIDGET, BotStatusBar::redrawFromWidget);

         terminal.puts(Capability.change_scroll_region, 1, size.getRows() - 1);
         terminal.puts(Capability.cursor_address, 1, 0);
         render();
      }
   }

   /** Called on the client thread; terminal output itself is serialised through a JLine widget. */
   public static void tick(final Minecraft client) {
      LineReader reader = activeReader;
      if (reader == null) {
         return;
      }

      long now = System.nanoTime();
      if (now - lastRefresh < REFRESH_NANOS) {
         return;
      }

      lastRefresh = now;
      currentText = snapshot(client);
      try {
         reader.callWidget(WIDGET);
      } catch (IllegalStateException ignored) {
         // A command is between readLine calls. The next tick will redraw it.
      }
   }

   private static boolean redrawFromWidget() {
      synchronized (RENDER_LOCK) {
         return render();
      }
   }

   private static boolean render() {
      LineReader reader = activeReader;
      if (reader == null) {
         return false;
      }

      Terminal terminal = reader.getTerminal();
      Size size = terminal.getSize();
      if (size.getRows() < 4 || size.getColumns() < 16) {
         return true;
      }

      String signature = size.getRows() + ":" + size.getColumns() + ":" + currentText;
      if (signature.equals(lastSignature)) {
         return true;
      }

      AttributedString line = fit(currentText, size.getColumns());
      terminal.puts(Capability.save_cursor);
      terminal.puts(Capability.change_scroll_region, 1, size.getRows() - 1);
      terminal.puts(Capability.cursor_address, 0, 0);
      terminal.puts(Capability.clr_eol);
      terminal.writer().print(line.toAnsi(terminal));
      terminal.puts(Capability.restore_cursor);
      terminal.flush();
      lastSignature = signature;
      return true;
   }

   private static AttributedString fit(final String text, final int width) {
      AttributedString source = new AttributedString(text);
      AttributedStringBuilder line = new AttributedStringBuilder(width);
      if (source.columnLength() > width) {
         line.append(source.columnSubSequence(0, Math.max(0, width - 1)), AttributedStyle.INVERSE);
         line.append("…", AttributedStyle.INVERSE);
      } else {
         line.append(source, AttributedStyle.INVERSE);
         line.append(" ".repeat(Math.max(0, width - source.columnLength())), AttributedStyle.INVERSE);
      }

      return line.toAttributedString();
   }

   private static String snapshot(final Minecraft client) {
      StringBuilder text = new StringBuilder(" EML | ").append(client.getUser().getName());
      String host = eml.hooks.BotRuntime.targetHost();
      if (!host.isEmpty()) {
         text.append(" | ").append(host).append(':').append(eml.hooks.BotRuntime.targetPort());
      }

      boolean connected = client.getConnection() != null;
      text.append(" | ");
      if (connected) {
         text.append("ONLINE");
         long ping = eml.hooks.BotRuntime.lastPingMs();
         if (ping >= 0L) {
            text.append(' ').append(ping).append("ms");
         }
      } else if (eml.action.BotActions.pendingConnection(client) != null) {
         text.append("LOGIN");
      } else {
         text.append("OFFLINE");
      }

      if (client.player != null) {
         text.append(" | ").append(client.player.getBlockX()).append(',')
            .append(client.player.getBlockY()).append(',').append(client.player.getBlockZ());
         text.append(String.format(Locale.ROOT, " | HP %.1f/%.1f", client.player.getHealth(), client.player.getMaxHealth()));
      }

      top.mcocet.hlbot.nav.Navigator navigation = top.mcocet.hlbot.nav.Navigator.INSTANCE;
      text.append(" | ").append(navigation.mode());
      if (navigation.navigating()) {
         text.append('>').append(navigation.targetX()).append(',')
            .append(navigation.targetY()).append(',').append(navigation.targetZ());
      }

      return text.append(' ').toString();
   }

   private static boolean supports(final Terminal terminal) {
      return terminal != null && !(terminal instanceof DumbTerminal)
         && terminal.getStringCapability(Capability.change_scroll_region) != null
         && terminal.getStringCapability(Capability.save_cursor) != null
         && terminal.getStringCapability(Capability.restore_cursor) != null
         && terminal.getStringCapability(Capability.cursor_address) != null
         && terminal.getStringCapability(Capability.clr_eol) != null;
   }

   /** Restores the normal full-screen scrolling region. Safe to call repeatedly. */
   public static void close() {
      synchronized (RENDER_LOCK) {
         LineReader reader = activeReader;
         activeReader = null;
         lastSignature = null;
         lastRefresh = 0L;
         if (reader != null) {
            reader.getWidgets().remove(WIDGET);
            Terminal terminal = reader.getTerminal();
            if (supports(terminal)) {
               Size size = terminal.getSize();
               terminal.puts(Capability.save_cursor);
               terminal.puts(Capability.change_scroll_region, 0, Math.max(0, size.getRows() - 1));
               terminal.puts(Capability.cursor_address, 0, 0);
               terminal.puts(Capability.clr_eol);
               terminal.puts(Capability.restore_cursor);
               terminal.flush();
            }
         }

         if (reservation != null) {
            reservation.close();
            reservation = null;
         }
      }
   }
}
