package eml.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Standalone bootstrap tool: builds a compile-only "open" copy of the vanilla client jar where every
 * class/member is public and non-final, so javac can compile our donor templates against internals.
 *
 * <p>Only the access flags are touched and method code is skipped: this jar NEVER runs anything. It
 * is a compile artifact for {@code eml.template} / {@code eml.action} sources only; the runtime
 * always uses the untouched jar, and the agent relies on instruction copying (which turns
 * private access into self-access inside the patched class) rather than on widened members.
 *
 * <p>Usage: {@code java -cp asm:asm-tree:gson eml.engine.OpenJarTool <vanilla.jar> <out.jar> [<gameDir> <version> [force]]}
 * With a game directory and version given, a missing client jar is fetched from the official endpoints
 * into that directory first - the same downloader the bot uses, so the build needs no pre-existing file.
 */
public final class OpenJarTool {
   private static final int STRIP = Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL;

   public static void main(final String[] args) throws IOException, InterruptedException {
      if (args.length < 2) {
         System.err.println("usage: OpenJarTool <vanilla-client.jar> <open.jar> [<gameDir> <version> [force]]");
         System.exit(2);
      }

      Path in = Paths.get(args[0]);
      Path out = Paths.get(args[1]);
      String forceFlag = args.length > 2 && args.length < 4 ? args[2] : null; // legacy two-slot form

      if (args.length >= 4 && !Files.isRegularFile(in)) {
         // The client jar is the one file this build may fetch: it is the input of this very step, and the
         // downloader writes it inside the project (./versions/<version>/), never anywhere else.
         System.out.println("OpenJarTool: no client jar at " + in.toAbsolutePath() + " -> fetching it into " + args[2]);
         in = new eml.bootstrap.MojangRuntime(new java.io.File(args[2]), args[3]).ensureClientJar().toPath();
      }

      if (!Files.isRegularFile(in)) {
         System.err.println("client jar not found: " + in.toAbsolutePath());
         System.err.println("run the bot once (it downloads the client jar into ./versions/<version>/), pass <gameDir> and <version> to this tool, or give a jar path as the first argument.");
         System.exit(1);
      }

      Files.createDirectories(out.getParent());

      // Rewriting ~30k classes is the slowest step of a build; skip when the copy is fresh.
      boolean force = "true".equalsIgnoreCase(System.getProperty("eml.openjar.force", "false"))
         || (forceFlag != null && Boolean.parseBoolean(forceFlag))
         || (args.length > 4 && Boolean.parseBoolean(args[4]));
      boolean fresh = Files.isRegularFile(out) && Files.getLastModifiedTime(out).toMillis() > Files.getLastModifiedTime(in).toMillis();
      if (fresh && !force) {
         System.out.println("OpenJarTool: up to date, reusing " + out.toAbsolutePath() + " (force with -Dopenjar.force=true)");
         return;
      }

      int classes = 0;
      int copied = 0;

      try (JarFile jar = new JarFile(in.toFile())) {
         Manifest manifest = jar.getManifest();
         try (OutputStream os = Files.newOutputStream(out); JarOutputStream jos = manifest != null ? new JarOutputStream(os, manifest) : new JarOutputStream(os)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
               JarEntry entry = entries.nextElement();
               String name = entry.getName();
               if (name.equals("META-INF/MANIFEST.MF") || name.equals("META-INF/") || name.startsWith("META-INF/SIG-") || name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA")) {
                  continue;
               }

               try (InputStream is = jar.getInputStream(entry)) {
                  byte[] data = is.readAllBytes();
                  if (name.endsWith(".class")) {
                     byte[] opened = openClass(data);
                     if (opened != null) {
                        jos.putNextEntry(new JarEntry(name));
                        jos.write(opened);
                        jos.closeEntry();
                        classes++;
                     } else {
                        // Module descriptors and unparseable entries are copied verbatim.
                        writeRaw(jos, name, data);
                        copied++;
                     }
                  } else {
                     writeRaw(jos, name, data);
                     copied++;
                  }
               }
            }
         }
      }

      System.out.println("OpenJarTool: widened " + classes + " classes, copied " + copied + " entries -> " + out.toAbsolutePath());
   }

   private static void writeRaw(final JarOutputStream jos, final String name, final byte[] data) throws IOException {
      if (name.endsWith("/")) {
         return;
      }

      jos.putNextEntry(new JarEntry(name));
      jos.write(data);
      jos.closeEntry();
   }

   /** @return the widened class bytes, or null when the entry is not a readable class file. */
   private static byte[] openClass(final byte[] source) {
      try {
         ClassReader reader = new ClassReader(source);
         ClassNode node = new ClassNode();
         // SKIP_CODE: javac only needs signatures, flags and the type hierarchy.
         reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
         node.access = widen(node.access);

         for (FieldNode field : node.fields) {
            field.access = widen(field.access);
         }

         for (MethodNode method : node.methods) {
            method.access = widen(method.access);
         }

         for (InnerClassNode inner : node.innerClasses) {
            inner.access = widen(inner.access);
         }

         ClassWriter writer = new ClassWriter(0);
         node.accept(writer);
         return writer.toByteArray();
      } catch (IllegalArgumentException e) {
         return null;
      }
   }

   private static int widen(final int access) {
      int result = access & ~STRIP;
      if ((access & Opcodes.ACC_PROTECTED) != 0 || (access & Opcodes.ACC_PRIVATE) != 0 || (access & Opcodes.ACC_PUBLIC) == 0) {
         result |= Opcodes.ACC_PUBLIC;
      }

      return result;
   }
}
