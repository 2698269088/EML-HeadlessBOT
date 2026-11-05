package top.mcocet.hlbot.promo.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import top.mcocet.hlbot.promo.EmlPgaPlugin;
import top.mcocet.hlbot.promo.config.PgaSettings;
import top.mcocet.hlbot.plugins.BotCommand;

/**
 * {@code /promo <sub>} - the command entry point.
 *
 * <h2>Structure</h2>
 *
 * <p>Subcommands live in a table of {@link SubCommand}s rather than in a switch. Adding one is a single line
 * in {@link #build()}, the help text is generated from the table (so it cannot drift), and the dispatch is one
 * method that takes its output as a parameter - which is where the original implementation ended up with two
 * 500-line copies of the same logic.
 *
 * <p>Every body follows the same shape: validate with the context helpers (which report and return null),
 * mutate settings, persist once, report. No subcommand touches the network directly; sending goes through
 * {@code AdCampaign}, so manual sends and scheduled sends obey identical text and target rules.
 */
public final class EpaCommand implements BotCommand {
   /** A subcommand defined by a lambda; the record's accessors satisfy the interface. */
   private record Simple(String name, String usage, String summary, Consumer<CommandContext> body) implements SubCommand {
      @Override
      public void run(final CommandContext context) {
         this.body.accept(context);
      }
   }

   private final EmlPgaPlugin plugin;
   private final Map<String, SubCommand> table = new LinkedHashMap<>();

   public EpaCommand(final EmlPgaPlugin plugin) {
      this.plugin = plugin;
      this.build();
   }

   @Override
   public String name() {
      return "promo";
   }

   @Override
   public String usage() {
      return "promo <subcommand> [args]";
   }

   @Override
   public String description() {
      return this.plugin.lang().get("cmd.description");
   }

   // ==================== dispatch ====================

   @Override
   public void execute(final List<String> args) {
      Output output = Output.console(this.plugin.api());

      if (args.isEmpty()) {
         this.help(output);
         return;
      }

      // `promo <sub>` is the short form; a legacy `promo <sub>` still works, the token is just skipped.
      if (!args.isEmpty() && "epa".equalsIgnoreCase(args.get(0))) {
         this.run(args.subList(1, args.size()), output);
         return;
      }

      this.run(args, output);
   }

   /**
    * Runs one subcommand from a token list, no {@code epa} prefix.
    *
    * <p>Output-agnostic on purpose: the console passes a printer, and anything else (a test, a dashboard) can
    * pass a collector without the table knowing.
    */
   public void run(final List<String> tokens, final Output output) {
      if (tokens.isEmpty()) {
         this.help(output);
         return;
      }

      String name = tokens.get(0).toLowerCase(Locale.ROOT);
      SubCommand sub = this.table.get(name);

      if (sub == null) {
         output.error(this.plugin.lang().get("cmd.unknown", name));
         this.help(output);
         return;
      }

      sub.run(new CommandContext(this.plugin, tokens.subList(1, tokens.size()), output));
   }

   private void help(final Output output) {
      output.line(this.plugin.lang().get("cmd.help.title"));

      for (SubCommand sub : this.table.values()) {
         output.line("  " + sub.usage() + "  -  " + sub.summary());
      }
   }

   private void define(final String name, final String usage, final String summary, final Consumer<CommandContext> body) {
      this.table.put(name, new Simple(name, usage, summary, body));
   }

   // ==================== the table ====================

   private void build() {
      this.define("help", "promo help", this.plugin.lang().get("summary.help"), ctx -> this.help(ctx.output()));
      this.define("status", "promo status", this.plugin.lang().get("summary.status"), this::status);

      // ---- campaign ----
      this.define("start", "promo start", this.plugin.lang().get("summary.start"), this::start);
      this.define("stop", "promo stop", this.plugin.lang().get("summary.stop"), this::stop);
      this.define("forcestop", "promo forcestop", this.plugin.lang().get("summary.forcestop"), this::forceStop);
      this.define("reload", "promo reload", this.plugin.lang().get("summary.reload"), this::reload);

      // ---- message list ----
      this.define("list", "promo list", this.plugin.lang().get("summary.list"), this::list);
      this.define("add", "promo add <text>", this.plugin.lang().get("summary.add"), this::add);
      this.define("remove", "promo remove <index|text>", this.plugin.lang().get("summary.remove"), this::remove);
      this.define("set", "promo set <index> <text>", this.plugin.lang().get("summary.set"), this::set);
      this.define("clear", "promo clear", this.plugin.lang().get("summary.clear"), this::clear);

      // ---- schedule ----
      this.define("mode", "promo mode <public|private>", this.plugin.lang().get("summary.mode"), this::mode);
      this.define("interval", "promo interval <seconds>", this.plugin.lang().get("summary.interval"), this::interval);
      this.define("publicinterval", "promo publicinterval <seconds>", this.plugin.lang().get("summary.publicinterval"), this::publicInterval);
      this.define("privateinterval", "promo privateinterval <seconds>", this.plugin.lang().get("summary.privateinterval"), this::privateInterval);
      this.define("gap", "promo gap <seconds>", this.plugin.lang().get("summary.gap"), this::gap);
      this.define("jitter", "promo jitter <seconds>", this.plugin.lang().get("summary.jitter"), this::jitter);
      this.define("gapjitter", "promo gapjitter <seconds>", this.plugin.lang().get("summary.gapjitter"), this::gapJitter);
      this.define("random", "promo random <on|off>", this.plugin.lang().get("summary.random"), this::random);
      this.define("skiprepeats", "promo skiprepeats <on|off>", this.plugin.lang().get("summary.skiprepeats"), this::skipRepeats);

      // ---- text ----
      this.define("suffix", "promo suffix <on|off>", this.plugin.lang().get("summary.suffix"), this::suffix);
      this.define("suffixlen", "promo suffixlen <length>", this.plugin.lang().get("summary.suffixlen"), this::suffixLength);
      this.define("mathfont", "promo mathfont <on|off>", this.plugin.lang().get("summary.mathfont"), this::mathFont);
      this.define("mathmin", "promo mathmin <digits>", this.plugin.lang().get("summary.mathmin"), this::mathMin);

      // ---- greeting ----
      this.define("greeting", "promo greeting <on|off>", this.plugin.lang().get("summary.greeting"), this::greeting);
      this.define("greetingformat", "promo greetingformat <format>", this.plugin.lang().get("summary.greetingformat"), this::greetingFormat);

      // ---- manual send ----
      this.define("say", "promo say <index>", this.plugin.lang().get("summary.say"), this::say);
      this.define("pm", "promo pm <player> [index]", this.plugin.lang().get("summary.pm"), this::pm);
      this.define("players", "promo players", this.plugin.lang().get("summary.players"), this::players);

      // ---- lists ----
      this.define("blacklist", "promo blacklist <add|remove|list> [name]", this.plugin.lang().get("summary.blacklist"), this::blacklist);

      // ---- burst ----
      this.define("burst", "promo burst <start|stop|status>", this.plugin.lang().get("summary.burst"), this::burst);
      this.define("burstlist", "promo burstlist", this.plugin.lang().get("summary.burstlist"), this::burstList);
      this.define("burstadd", "promo burstadd <text>", this.plugin.lang().get("summary.burstadd"), this::burstAdd);
      this.define("burstremove", "promo burstremove <index|text>", this.plugin.lang().get("summary.burstremove"), this::burstRemove);
      this.define("burstset", "promo burstset <index> <text>", this.plugin.lang().get("summary.burstset"), this::burstSet);
      this.define("sync", "promo sync <on|off>", this.plugin.lang().get("summary.sync"), this::sync);
      this.define("burstinterval", "promo burstinterval <seconds>", this.plugin.lang().get("summary.burstinterval"), this::burstInterval);
      this.define("burstduration", "promo burstduration <seconds>", this.plugin.lang().get("summary.burstduration"), this::burstDuration);

      // ---- diagnostics ----
      this.define("lang", "promo lang", this.plugin.lang().get("summary.lang"), this::language);
   }

   // ==================== bodies: reporting ====================

   private void status(final CommandContext context) {
      context.output().line(context.lang().get("status.header", context.api().username()));
      context.campaign().describe().forEach(context.output()::line);
      context.burst().describe().forEach(context.output()::line);

      List<String> messages = context.settings().messages();
      context.output().line(context.lang().get("status.messages", messages.size(), context.settings().burstMessages().size()));
      context.output().line(context.lang().get("status.text",
         context.settings().suffixEnabled() ? context.lang().get("status.on") : context.lang().get("status.off"),
         context.settings().mathFont() ? context.lang().get("status.on") : context.lang().get("status.off"),
         context.settings().greetingEnabled() ? context.lang().get("status.on") : context.lang().get("status.off")));
   }

   // ==================== bodies: campaign ====================

   private void start(final CommandContext context) {
      context.settings().enabled(true);
      context.settings().save();
      context.campaign().start();
      context.output().line(context.lang().get("cmd.start.ok"));
   }

   private void stop(final CommandContext context) {
      context.campaign().stop();
      context.burst().stop();
      context.output().line(context.lang().get("cmd.stop.ok"));
   }

   private void forceStop(final CommandContext context) {
      context.campaign().stop();
      context.burst().stop();
      context.settings().enabled(false);
      context.settings().save();
      context.output().line(context.lang().get("cmd.forcestop.ok"));
   }

   private void reload(final CommandContext context) {
      context.settings().reload();
      context.plugin().reloadLanguage();
      context.campaign().start();
      context.output().line(context.lang().get("cmd.reload.ok", context.lang().language(), context.lang().size()));
   }

   // ==================== bodies: message list ====================

   private void list(final CommandContext context) {
      List<String> messages = context.settings().messages();

      if (messages.isEmpty()) {
         context.output().line(context.lang().get("cmd.list.empty"));
         return;
      }

      context.output().line(context.lang().get("cmd.list.header", messages.size()));

      for (int index = 0; index < messages.size(); index++) {
         context.output().line("  " + (index + 1) + ". " + messages.get(index));
      }
   }

   private void add(final CommandContext context) {
      String text = context.tail(0);

      if (text.isEmpty()) {
         context.output().error(context.lang().get("cmd.usage", "promo add <text>"));
         return;
      }

      List<String> messages = context.settings().messages();

      if (messages.contains(text)) {
         context.output().error(context.lang().get("cmd.list.duplicate", text));
         return;
      }

      messages.add(text);
      context.settings().messages(messages);
      context.saved("cmd.list.added", messages.size(), text);
   }

   private void remove(final CommandContext context) {
      String target = context.tail(0);

      if (target.isEmpty()) {
         context.output().error(context.lang().get("cmd.usage", "promo remove <index|text>"));
         return;
      }

      List<String> messages = context.settings().messages();
      int index = this.parseIndex(target);

      if (index >= 0) {
         if (index >= messages.size()) {
            context.output().error(context.lang().get("cmd.list.index", messages.size()));
            return;
         }

         String removed = messages.remove(index);
         context.settings().messages(messages);
         context.saved("cmd.list.removed", removed);
         return;
      }

      if (!messages.remove(target)) {
         context.output().error(context.lang().get("cmd.list.missing", target));
         return;
      }

      context.settings().messages(messages);
      context.saved("cmd.list.removed", target);
   }

   private void set(final CommandContext context) {
      Integer index = context.requireInt(0, 1, "promo set <index> <text>");
      String text = index == null ? "" : context.tail(1);

      if (index == null) {
         return;
      }

      if (text.isEmpty()) {
         context.output().error(context.lang().get("cmd.usage", "promo set <index> <text>"));
         return;
      }

      List<String> messages = context.settings().messages();

      if (index - 1 > messages.size()) {
         context.output().error(context.lang().get("cmd.list.index", messages.size()));
         return;
      }

      context.settings().setMessage(index - 1, text);
      context.saved("cmd.list.updated", index, text);
   }

   private void clear(final CommandContext context) {
      context.settings().messages(List.of());
      context.saved("cmd.list.cleared");
   }

   // ==================== bodies: schedule ====================

   private void mode(final CommandContext context) {
      PgaSettings.Mode mode = PgaSettings.Mode.parse(context.arg(0));

      if (mode == null) {
         context.output().error(context.lang().get("cmd.usage", "promo mode <public|private>"));
         return;
      }

      context.settings().mode(mode);
      // A mode switch invalidates the rotation: the next round should start from the first eligible player.
      context.roster().resetCursor();
      context.saved("cmd.mode.ok", mode);
   }

   private void interval(final CommandContext context) {
      Integer seconds = context.requireInt(0, 1, "promo interval <seconds>");

      if (seconds == null) {
         return;
      }

      if (context.settings().mode() == PgaSettings.Mode.PRIVATE) {
         context.settings().privateIntervalSeconds(seconds);
      } else {
         context.settings().publicIntervalSeconds(seconds);
      }

      context.saved("cmd.interval.ok", context.settings().mode(), seconds);
   }

   private void publicInterval(final CommandContext context) {
      Integer seconds = context.requireInt(0, 1, "promo publicinterval <seconds>");

      if (seconds != null) {
         context.settings().publicIntervalSeconds(seconds);
         context.saved("cmd.interval.ok", PgaSettings.Mode.PUBLIC, seconds);
      }
   }

   private void privateInterval(final CommandContext context) {
      Integer seconds = context.requireInt(0, 1, "promo privateinterval <seconds>");

      if (seconds != null) {
         context.settings().privateIntervalSeconds(seconds);
         context.saved("cmd.interval.ok", PgaSettings.Mode.PRIVATE, seconds);
      }
   }

   private void gap(final CommandContext context) {
      Integer seconds = context.requireInt(0, 1, "promo gap <seconds>");

      if (seconds != null) {
         context.settings().gapSeconds(seconds);
         context.saved("cmd.gap.ok", seconds);
      }
   }

   private void jitter(final CommandContext context) {
      Integer seconds = context.requireInt(0, 0, "promo jitter <seconds>");

      if (seconds != null) {
         context.settings().jitterSeconds(seconds);
         context.saved("cmd.jitter.ok", seconds);
      }
   }

   private void gapJitter(final CommandContext context) {
      Integer seconds = context.requireInt(0, 0, "promo gapjitter <seconds>");

      if (seconds != null) {
         context.settings().gapJitterSeconds(seconds);
         context.saved("cmd.jitter.ok", seconds);
      }
   }

   private void random(final CommandContext context) {
      Boolean value = context.requireToggle(0, "promo random <on|off>");

      if (value != null) {
         context.settings().randomOne(value);
         context.saved("cmd.random.ok", this.onOff(context, value));
      }
   }

   private void skipRepeats(final CommandContext context) {
      Boolean value = context.requireToggle(0, "promo skiprepeats <on|off>");

      if (value != null) {
         context.settings().skipRepeats(value);
         context.saved("cmd.generic.ok", "skipRepeats", this.onOff(context, value));
      }
   }

   // ==================== bodies: text ====================

   private void suffix(final CommandContext context) {
      Boolean value = context.requireToggle(0, "promo suffix <on|off>");

      if (value != null) {
         context.settings().suffixEnabled(value);
         context.saved("cmd.generic.ok", "suffix", this.onOff(context, value));
      }
   }

   private void suffixLength(final CommandContext context) {
      Integer length = context.requireInt(0, 0, "promo suffixlen <length>");

      if (length != null) {
         context.settings().suffixLength(length);
         context.saved("cmd.generic.ok", "suffixLength", length);
      }
   }

   private void mathFont(final CommandContext context) {
      Boolean value = context.requireToggle(0, "promo mathfont <on|off>");

      if (value != null) {
         context.settings().mathFont(value);
         context.saved("cmd.generic.ok", "mathFont", this.onOff(context, value));
      }
   }

   private void mathMin(final CommandContext context) {
      Integer digits = context.requireInt(0, 1, "promo mathmin <digits>");

      if (digits != null) {
         context.settings().minDigits(digits);
         context.saved("cmd.generic.ok", "mathMinDigits", digits);
      }
   }

   // ==================== bodies: greeting ====================

   private void greeting(final CommandContext context) {
      Boolean value = context.requireToggle(0, "promo greeting <on|off>");

      if (value != null) {
         context.settings().greetingEnabled(value);
         context.saved("cmd.greeting.ok", this.onOff(context, value));
      }
   }

   private void greetingFormat(final CommandContext context) {
      String format = context.tail(0);

      if (format.isEmpty()) {
         context.output().error(context.lang().get("cmd.usage", "promo greetingformat <format>"));
         return;
      }

      context.settings().greetingFormat(format);
      context.saved("cmd.greeting.format.ok", format);
   }

   // ==================== bodies: manual send ====================

   private void say(final CommandContext context) {
      Integer index = context.requireInt(0, 1, "promo say <index>");
      List<String> messages = context.settings().messages();

      if (index == null) {
         return;
      }

      if (index > messages.size()) {
         context.output().error(context.lang().get("cmd.list.index", messages.size()));
         return;
      }

      String text = messages.get(index - 1);

      if (!context.campaign().sendOnce(text, null)) {
         context.output().error(context.lang().get("cmd.offline"));
         return;
      }

      context.output().line(context.lang().get("cmd.say.ok", text));
   }

   private void pm(final CommandContext context) {
      if (!context.requireArgs(1, "promo pm <player> [index]")) {
         return;
      }

      String player = context.arg(0);
      String indexArg = context.arg(1);
      List<String> messages = context.settings().messages();

      if (messages.isEmpty()) {
         context.output().error(context.lang().get("cmd.list.empty"));
         return;
      }

      int index = indexArg == null ? 1 : this.parseIndex(indexArg) + 1;

      if (index < 1 || index > messages.size()) {
         context.output().error(context.lang().get("cmd.list.index", messages.size()));
         return;
      }

      if (!context.campaign().sendOnce(messages.get(index - 1), player)) {
         context.output().error(context.lang().get("cmd.offline"));
         return;
      }

      context.output().line(context.lang().get("cmd.pm.ok", player));
   }

   private void players(final CommandContext context) {
      List<String> online = context.roster().online();
      List<String> targets = context.roster().targets();

      if (online.isEmpty()) {
         context.output().line(context.lang().get("cmd.players.empty"));
         return;
      }

      context.output().line(context.lang().get("cmd.players.header", online.size(), targets.size()));
      context.output().line("  " + String.join(", ", online));
   }

   // ==================== bodies: lists ====================

   private void blacklist(final CommandContext context) {
      if (!context.requireArgs(1, "promo blacklist <add|remove|list> [name]")) {
         return;
      }

      String action = context.arg(0).toLowerCase(Locale.ROOT);
      List<String> current = context.settings().blacklist();

      switch (action) {
         case "list" -> {
            context.output().line(current.isEmpty()
               ? context.lang().get("cmd.blacklist.empty")
               : context.lang().get("cmd.blacklist.header", String.join(", ", current)));
         }
         case "add" -> {
            if (!context.requireArgs(2, "promo blacklist add <name>")) {
               return;
            }

            String name = context.arg(1);

            if (current.stream().anyMatch(entry -> entry.equalsIgnoreCase(name))) {
               context.output().error(context.lang().get("cmd.blacklist.present", name));
               return;
            }

            current.add(name);
            context.settings().blacklist(current);
            context.roster().resetCursor();
            context.saved("cmd.blacklist.added", name);
         }
         case "remove" -> {
            if (!context.requireArgs(2, "promo blacklist remove <name>")) {
               return;
            }

            String name = context.arg(1);
            boolean removed = current.removeIf(entry -> entry.equalsIgnoreCase(name));

            if (!removed) {
               context.output().error(context.lang().get("cmd.blacklist.missing", name));
               return;
            }

            context.settings().blacklist(current);
            context.saved("cmd.blacklist.removed", name);
         }
         default -> context.output().error(context.lang().get("cmd.usage", "promo blacklist <add|remove|list> [name]"));
      }
   }

   // ==================== bodies: burst ====================

   private void burst(final CommandContext context) {
      String action = context.arg(0) == null ? "status" : context.arg(0).toLowerCase(Locale.ROOT);

      switch (action) {
         case "start" -> {
            if (context.burst().start()) {
               context.settings().burstEnabled(true);
               context.settings().save();
               context.output().line(context.lang().get("cmd.burst.started"));
            } else {
               context.output().error(context.lang().get("cmd.burst.refused"));
            }
         }
         case "stop" -> {
            context.burst().stop();
            context.settings().burstEnabled(false);
            context.settings().save();
            context.output().line(context.lang().get("cmd.burst.stopped"));
         }
         case "status" -> context.burst().describe().forEach(context.output()::line);
         default -> context.output().error(context.lang().get("cmd.usage", "promo burst <start|stop|status>"));
      }
   }

   private void burstList(final CommandContext context) {
      List<String> messages = context.settings().burstMessages();

      if (messages.isEmpty()) {
         context.output().line(context.lang().get("cmd.list.empty"));
         return;
      }

      context.output().line(context.lang().get("cmd.burstlist.header", messages.size()));

      for (int index = 0; index < messages.size(); index++) {
         context.output().line("  " + (index + 1) + ". " + messages.get(index));
      }
   }

   private void burstAdd(final CommandContext context) {
      String text = context.tail(0);

      if (text.isEmpty()) {
         context.output().error(context.lang().get("cmd.usage", "promo burstadd <text>"));
         return;
      }

      List<String> messages = context.settings().burstMessages();
      messages.add(text);
      context.settings().burstMessages(messages);
      context.saved("cmd.burstlist.added", messages.size(), text);
   }

   private void burstRemove(final CommandContext context) {
      String target = context.tail(0);

      if (target.isEmpty()) {
         context.output().error(context.lang().get("cmd.usage", "promo burstremove <index|text>"));
         return;
      }

      List<String> messages = context.settings().burstMessages();
      int index = this.parseIndex(target);

      if (index >= 0) {
         if (index >= messages.size()) {
            context.output().error(context.lang().get("cmd.list.index", messages.size()));
            return;
         }

         String removed = messages.remove(index);
         context.settings().burstMessages(messages);
         context.saved("cmd.list.removed", removed);
         return;
      }

      if (!messages.remove(target)) {
         context.output().error(context.lang().get("cmd.list.missing", target));
         return;
      }

      context.settings().burstMessages(messages);
      context.saved("cmd.list.removed", target);
   }

   private void burstSet(final CommandContext context) {
      Integer index = context.requireInt(0, 1, "promo burstset <index> <text>");
      String text = index == null ? "" : context.tail(1);

      if (index == null) {
         return;
      }

      if (text.isEmpty()) {
         context.output().error(context.lang().get("cmd.usage", "promo burstset <index> <text>"));
         return;
      }

      List<String> messages = context.settings().burstMessages();

      if (index - 1 >= messages.size()) {
         context.output().error(context.lang().get("cmd.list.index", messages.size()));
         return;
      }

      messages.set(index - 1, text);
      context.settings().burstMessages(messages);
      context.saved("cmd.list.updated", index, text);
   }

   private void sync(final CommandContext context) {
      Boolean value = context.requireToggle(0, "promo sync <on|off>");

      if (value != null) {
         context.settings().burstSync(value);
         context.saved("cmd.generic.ok", "burst.syncWithMain", this.onOff(context, value));
      }
   }

   private void burstInterval(final CommandContext context) {
      Integer seconds = context.requireInt(0, 1, "promo burstinterval <seconds>");

      if (seconds != null) {
         context.settings().burstIntervalSeconds(seconds);
         context.saved("cmd.generic.ok", "burst.intervalSeconds", seconds);
      }
   }

   private void burstDuration(final CommandContext context) {
      Integer seconds = context.requireInt(0, 1, "promo burstduration <seconds>");

      if (seconds != null) {
         context.settings().burstDurationSeconds(seconds);
         context.saved("cmd.generic.ok", "burst.durationSeconds", seconds);
      }
   }

   // ==================== bodies: diagnostics ====================

   private void language(final CommandContext context) {
      context.output().line(context.lang().get("cmd.lang.ok", context.lang().language(), context.lang().size()));
   }

   // ==================== helpers ====================

   private String onOff(final CommandContext context, final boolean value) {
      return context.lang().get(value ? "status.on" : "status.off");
   }

   /** Parses a 1-based index; returns the 0-based value, or -1 when the text is not an index. */
   private int parseIndex(final String raw) {
      try {
         int value = Integer.parseInt(raw.trim());
         return value < 1 ? -1 : value - 1;
      } catch (NumberFormatException notAnIndex) {
         return -1;
      }
   }
}
