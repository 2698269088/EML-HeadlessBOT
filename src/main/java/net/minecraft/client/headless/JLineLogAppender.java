package net.minecraft.client.headless;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.jline.reader.LineReader;

/**
 * log4j2 appender that prints every event through {@link LineReader#printAbove(String)}:
 * log lines are rendered above the input line and the user's partially typed command is
 * redrawn intact underneath, so engine logs can never splice into console input. This is
 * the xinbot approach, adapted from logback to log4j2.
 *
 * <p>Before the JLine terminal is up (and after it closes) events go to the raw stdout, which this class
 * opens on {@code FileDescriptor.out} itself: reaching for {@code Bootstrap.STDOUT} would initialise the
 * vanilla {@code Bootstrap} class from inside the first log line - including its stream redirection and
 * its {@code com.mojang.logging} dependency - long before the game bootstrap asks for it.
 * Registered in log4j2.xml via the {@code packages} attribute on {@code <Configuration>}.
 */
@Plugin(name = "JLineLog", category = "Core", elementType = Appender.ELEMENT_TYPE, printObject = true)
public final class JLineLogAppender extends AbstractAppender {
   private static final PrintStream RAW_OUT = openRawOut();
   private static volatile LineReader lineReader;

   /**
    * Raw stdout in the CONSOLE's encoding, not UTF-8: a GBK codepage turns UTF-8 bytes into mojibake
    * ("再见" became "鍐嶈" once the terminal was closed and this path took over). {@code stdout.encoding}
    * is what Java 18+ reports for the attached console, mirroring the charset the log4j2 pattern uses.
    */
   private static PrintStream openRawOut() {
      String charset = System.getProperty("stdout.encoding", System.getProperty("native.encoding", "UTF-8"));

      try {
         return new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset);
      } catch (UnsupportedEncodingException e) {
         return new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
      }
   }

   public static void setLineReader(final LineReader reader) {
      lineReader = reader;
   }

   protected JLineLogAppender(final String name, final Filter filter, final Layout<? extends Serializable> layout) {
      super(name, filter, layout, true, null);
   }

   @PluginFactory
   public static JLineLogAppender createAppender(
      @PluginAttribute("name") final String name,
      @PluginElement("Filter") final Filter filter,
      @PluginElement("Layout") final Layout<? extends Serializable> layout
   ) {
      return new JLineLogAppender(name, filter, layout);
   }

   @Override
   public void append(final LogEvent event) {
      Layout<? extends Serializable> layout = this.getLayout();
      if (layout == null) {
         return;
      }

      Serializable rendered = layout.toSerializable(event);
      if (rendered == null) {
         return;
      }

      String line = rendered.toString();
      LineReader reader = lineReader;
      if (reader != null) {
         reader.printAbove(line);
      } else {
         RAW_OUT.print(line);
      }
   }
}
