package top.mcocet.hlbot.plugins.event;

@FunctionalInterface
public interface EventExecutor {
   void execute(Listener listener, Event event) throws Exception;
}
