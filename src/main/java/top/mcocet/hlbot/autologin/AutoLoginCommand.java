package top.mcocet.hlbot.autologin;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.headless.Commands;
import net.minecraft.client.headless.ConsoleIO;
import net.minecraft.client.headless.HeadlessSession;

/**
 * Console command {@code autologin}. Subcommands:
 *
 * <pre>
 *   autologin status             show enabled/debug/stage/rule count
 *   autologin reload             re-read autologin.yml
 *   autologin templates          list built-in templates
 *   autologin debug [on|off]     toggle signal/action tracing without editing the file
 *   autologin try &lt;text&gt;        simulate a system chat line for rule debugging
 * </pre>
 */
public final class AutoLoginCommand implements Commands.Command {
   private final AutoLogin autoLogin;

   public AutoLoginCommand(final AutoLogin autoLogin) {
      this.autoLogin = autoLogin;
   }

   @Override
   public String name() {
      return "autologin";
   }

   @Override
   public String usage() {
      return "autologin [status|reload|templates|debug [on|off]|try <text>]";
   }

   @Override
   public String description() {
      return "rule-driven auto login from autologin.yml";
   }

   @Override
   public void execute(final ConsoleIO io, final HeadlessSession session, final List<String> args) {
      String sub = args.isEmpty() ? "status" : args.get(0).toLowerCase(Locale.ROOT);
      switch (sub) {
         case "status" -> this.status(io);
         case "reload" -> {
            this.autoLogin.reload();
            io.print("[autologin] reloaded");
         }
         case "templates" -> io.print("[autologin] templates: " + TemplateLibrary.names());
         case "debug" -> {
            boolean enabled;
            if (args.size() == 1) {
               enabled = !this.autoLogin.engine().isDebug();
            } else if (args.size() == 2 && "on".equalsIgnoreCase(args.get(1))) {
               enabled = true;
            } else if (args.size() == 2 && "off".equalsIgnoreCase(args.get(1))) {
               enabled = false;
            } else {
               io.printError("[autologin] usage: autologin debug [on|off]");
               return;
            }

            this.autoLogin.setDebug(enabled);
            io.print("[autologin] debug=" + enabled);
         }
         case "try" -> {
            if (args.size() < 2) {
               io.printError("[autologin] usage: autologin try <text>");
               return;
            }

            String text = String.join(" ", args.subList(1, args.size()));
            this.autoLogin.engine().simulateSystemChat(text);
            io.print("[autologin] simulated; stage=" + this.autoLogin.engine().currentStage());
         }
         default -> io.printError("[autologin] usage: " + this.usage());
      }
   }

   private void status(final ConsoleIO io) {
      AutoLoginConfig config = this.autoLogin.config();
      RuleEngine engine = this.autoLogin.engine();

      if (config == null) {
         io.print("[autologin] not configured");
         return;
      }

      io.print("[autologin] enabled=" + engine.isEnabled() + ", debug=" + engine.isDebug()
         + ", stage=" + engine.currentStage() + ", rules=" + engine.ruleCount()
         + ", server=" + this.autoLogin.serverAddress() + ", profile=" + this.autoLogin.serverProfile()
         + ", file=" + this.autoLogin.file().getName());
   }
}
