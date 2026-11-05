package top.mcocet.hlbot.autologin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-connection rule state: the current stage, session variables, already-fired {@code once} rules and
 * cooldown timestamps. A fresh instance is created on reload and on every (re)connect.
 */
final class RuleContext {
   static final String INITIAL_STAGE = "INIT";

   private String stage = INITIAL_STAGE;
   private final Map<String, String> variables = new HashMap<>();
   private final Set<String> executedOnce = new HashSet<>();
   private final Map<String, Long> cooldown = new HashMap<>();

   RuleContext(final Map<String, String> seedVariables) {
      if (seedVariables != null) {
         this.variables.putAll(seedVariables);
      }
   }

   String getStage() {
      return this.stage;
   }

   void setStage(final String stage) {
      this.stage = stage;
   }

   Map<String, String> getVariables() {
      return this.variables;
   }

   void setVariable(final String key, final String value) {
      this.variables.put(key, value);
   }

   boolean isExecutedOnce(final String ruleId) {
      return this.executedOnce.contains(ruleId);
   }

   void markExecutedOnce(final String ruleId) {
      this.executedOnce.add(ruleId);
   }

   boolean isOnCooldown(final String ruleId, final long cooldownMs) {
      if (cooldownMs <= 0L) {
         return false;
      }

      Long last = this.cooldown.get(ruleId);
      return last != null && (System.currentTimeMillis() - last) < cooldownMs;
   }

   void touchCooldown(final String ruleId) {
      this.cooldown.put(ruleId, System.currentTimeMillis());
   }
}
