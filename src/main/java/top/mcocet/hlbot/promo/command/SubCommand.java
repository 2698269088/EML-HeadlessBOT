package top.mcocet.hlbot.promo.command;

/**
 * One subcommand of {@code /promo epa}.
 *
 * <p>Metadata travels with the behaviour so the help text is generated from the same table that executes the
 * command. In the original project the help was a separate 35-line list of language keys that had to be kept
 * in sync with the dispatcher by hand - and drifted, which is why some subcommands were unlisted.
 */
public interface SubCommand {
   /** Token the user types, lower case. */
   String name();

   /** One-line argument sketch, shown by {@code help}. */
   String usage();

   /** What it does, shown by {@code help}. */
   String summary();

   void run(CommandContext context);
}
