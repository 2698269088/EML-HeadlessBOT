package top.mcocet.hlbot.plugins.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an event handler. The method must be public, return void and take
 * exactly one parameter whose type is a concrete subclass of {@link Event}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EventHandler {
   EventPriority priority() default EventPriority.NORMAL;

   /** When true, this handler is skipped for cancelled {@link Cancellable} events. */
   boolean ignoreCancelled() default false;
}
