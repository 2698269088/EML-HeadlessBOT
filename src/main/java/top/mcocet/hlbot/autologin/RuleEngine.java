package top.mcocet.hlbot.autologin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.hlbot.plugins.BotApi;

/**
 * The AutoLogin rule engine. It evaluates rules in {@code priority} order for each {@link Signal},
 * honours {@code once}/{@code cooldownMs}/{@code stopOnMatch}, and hands matching actions to the
 * executor. The engine never reaches back into the event layer; signals are produced by
 * {@link RuleListener}.
 */
final class RuleEngine {
   private static final Logger LOGGER = LoggerFactory.getLogger("AutoLogin");

   private final ActionExecutor executor;
   private volatile List<Rule> rules = List.of();
   private volatile Map<String, String> seedVariables = Map.of();
   private volatile boolean debug;
   private volatile boolean enabled = true;
   private volatile RuleContext context = new RuleContext(Map.of());

   RuleEngine(final BotApi api) {
      this.executor = new ActionExecutor(api);
   }

   /** Installs a fresh rule set and session variables, resetting the connection state. */
   synchronized void reload(final List<Rule> newRules, final Map<String, String> seedVars, final boolean debug, final boolean enabled) {
      List<Rule> sorted = new ArrayList<>(newRules == null ? List.of() : newRules);
      sorted.sort(Comparator.comparingInt(Rule::priority));
      this.rules = sorted;
      this.seedVariables = seedVars == null ? new HashMap<>() : new HashMap<>(seedVars);
      this.debug = debug;
      this.enabled = enabled;
      this.context = new RuleContext(this.seedVariables);
      LOGGER.info("[AutoLogin] {} rule(s) loaded, stage={}, enabled={}, debug={}", sorted.size(), this.context.getStage(), enabled, debug);
   }

   /** Runtime console override; a later profile reload keeps this value through AutoLogin.debugOverride. */
   void setDebug(final boolean debug) {
      this.debug = debug;
   }

   /** Resets the per-connection state, so a reconnect starts the login flow from the beginning. */
   void reset() {
      this.context = new RuleContext(this.seedVariables);
   }

   /**
    * Feeds one signal through every rule. Synchronised so console debugging ({@code autologin try}) cannot
    * race a chat event on the client thread over the same session context. Never throws: a broken action
    * must not break a chat path.
    */
   synchronized void handle(final Signal signal) {
      if (signal == null || !this.enabled || this.rules.isEmpty()) {
         return;
      }

      if (this.debug) {
         LOGGER.info("[AutoLogin][debug] signal {}", signal);
      }

      RuleContext ctx = this.context;
      for (Rule rule : this.rules) {
         Map<String, String> captures = new HashMap<>();
         if (!rule.condition().matches(signal, ctx, captures)) {
            continue;
         }

         if (rule.isOnce() && ctx.isExecutedOnce(rule.id())) {
            if (this.debug) {
               LOGGER.info("[AutoLogin][debug] rule '{}' already fired once, skipping", rule.id());
            }
            continue;
         }

         if (ctx.isOnCooldown(rule.id(), rule.cooldownMs())) {
            if (this.debug) {
               LOGGER.info("[AutoLogin][debug] rule '{}' is on cooldown, skipping", rule.id());
            }
            continue;
         }

         if (this.debug) {
            LOGGER.info("[AutoLogin][debug] rule '{}' matched <- {}", rule.id(), signal);
         }

         Map<String, String> vars = new HashMap<>(ctx.getVariables());
         vars.putAll(captures);
         vars.put("text", signal.text());
         vars.put("type", signal.type().name());
         vars.put("stage", ctx.getStage() == null ? "" : ctx.getStage());
         if (signal.sender() != null) {
            vars.put("sender", signal.sender());
         }
         vars.put("overlay", String.valueOf(signal.overlay()));

         if (signal.containerId() != null) {
            String containerId = String.valueOf(signal.containerId());
            ctx.setVariable("containerId", containerId);
            vars.put("containerId", containerId);
         }

         if (signal.slot() != null) {
            String slot = String.valueOf(signal.slot());
            ctx.setVariable("slot", slot);
            vars.put("slot", slot);
         }

         ctx.markExecutedOnce(rule.id());
         ctx.touchCooldown(rule.id());

         for (Action action : rule.actions()) {
            try {
               this.executor.execute(action, vars, ctx, this.debug);
            } catch (RuntimeException failed) {
               LOGGER.error("[AutoLogin] rule '{}' action failed", rule.id(), failed);
            }
         }

         if (rule.isStopOnMatch()) {
            break;
         }
      }
   }

   /** Used by {@code autologin try}: simulates a system chat line without a live connection. */
   void simulateSystemChat(final String text) {
      this.handle(new Signal(Signal.Type.SYSTEM_CHAT, text, null, false));
   }

   String currentStage() {
      return this.context.getStage();
   }

   int ruleCount() {
      return this.rules.size();
   }

   boolean isEnabled() {
      return this.enabled;
   }

   boolean isDebug() {
      return this.debug;
   }
}
