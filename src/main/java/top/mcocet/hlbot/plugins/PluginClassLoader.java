package top.mcocet.hlbot.plugins;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-plugin {@link URLClassLoader}. Lookup order: parent (the bot itself, so plugins see
 * the API and the whole Minecraft runtime), then the plugin jar, then any loaders of
 * plugins declared in {@code depend}. This mirrors Bukkit's isolation model: plugins share
 * the bot classes but never each other's classes unless a dependency is declared.
 */
public class PluginClassLoader extends URLClassLoader {
   private final List<PluginClassLoader> dependencies = new CopyOnWriteArrayList<>();

   public PluginClassLoader(final URL[] urls, final ClassLoader parent) {
      super(urls, parent);
   }

   public void addDependency(final PluginClassLoader loader) {
      if (loader != null && loader != this && !this.dependencies.contains(loader)) {
         this.dependencies.add(loader);
      }
   }

   @Override
   protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
         Class<?> loaded = findLoadedClass(name);
         if (loaded == null) {
            loaded = tryLoadFromParent(name, resolve);
         }

         if (loaded == null) {
            loaded = tryFindInOwnJar(name, resolve);
         }

         if (loaded == null) {
            loaded = tryFindInDependencies(name, resolve);
         }

         if (loaded == null) {
            throw new ClassNotFoundException(name);
         }

         return loaded;
      }
   }

   private Class<?> tryLoadFromParent(final String name, final boolean resolve) {
      try {
         Class<?> clazz = getParent().loadClass(name);
         if (resolve) {
            resolveClass(clazz);
         }

         return clazz;
      } catch (ClassNotFoundException e) {
         return null;
      }
   }

   private Class<?> tryFindInOwnJar(final String name, final boolean resolve) {
      try {
         Class<?> clazz = findClass(name);
         if (resolve) {
            resolveClass(clazz);
         }

         return clazz;
      } catch (ClassNotFoundException e) {
         return null;
      }
   }

   private Class<?> tryFindInDependencies(final String name, final boolean resolve) {
      for (PluginClassLoader dependency : this.dependencies) {
         try {
            return dependency.loadClass(name, resolve);
         } catch (ClassNotFoundException e) {
            // try the next dependency
         }
      }

      return null;
   }
}
