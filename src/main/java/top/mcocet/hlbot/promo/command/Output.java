package top.mcocet.hlbot.promo.command;

import top.mcocet.hlbot.plugins.BotApi;

/**
 * Where a command's output goes.
 *
 * <p>This single abstraction is what removes the largest piece of duplication in the original plugin: it had
 * one 585-line executor for the console and a second, hand-written copy of the same switch for the whisper
 * console. Both were "run a subcommand and report the result"; only the destination differed.
 *
 * <p>It exists so the command layer never talks to {@link BotApi} directly: a caller can collect the lines
 * instead of printing them (a test, or a future dashboard), and no subcommand has to know where its output
 * ends up.
 */
public interface Output {
   void line(String message);

   void error(String message);

   /** Sink writing to the bot console. */
   static Output console(final BotApi api) {
      return new Output() {
         @Override
         public void line(final String message) {
            api.print(message);
         }

         @Override
         public void error(final String message) {
            api.printError(message);
         }
      };
   }
}
