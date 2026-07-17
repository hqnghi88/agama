import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.nio.file.*;

/**
 * Simple patch: replace ColorBrewer.instance() call in Colors.<clinit> with ACONST_NULL
 * to avoid circular class initialization issue with Android's XML parser.
 */
public class ColorsPatcher {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: ColorsPatcher <jar>"); System.exit(1); }
        java.nio.file.Path jarFile = java.nio.file.Paths.get(args[0]);
        java.nio.file.Path backup = java.nio.file.Paths.get(args[0] + ".bak4");
        if (!java.nio.file.Files.exists(backup)) { java.nio.file.Files.copy(jarFile, backup); System.out.println("Backup: " + backup); }
        File tmpDir = java.nio.file.Files.createTempDirectory("colors_patch").toFile();
        try {
            new ProcessBuilder(new String[]{"jar", "xf", jarFile.toAbsolutePath().toString()}).directory(tmpDir).start().waitFor();
            File f = new File(tmpDir, "gama/gaml/operators/Colors.class");
            if (!f.exists()) { System.err.println("Colors.class not found!"); System.exit(1); }
            byte[] patched = patchColors(java.nio.file.Files.readAllBytes(f.toPath()));
            java.nio.file.Files.write(f.toPath(), patched);
            System.out.println("Colors.class patched");
            new ProcessBuilder(new String[]{"jar", "cf", jarFile.toAbsolutePath().toString(), "-C", tmpDir.getAbsolutePath(), "."}).start().waitFor();
            System.out.println("JAR updated: " + args[0]);
        } finally { deleteDir(tmpDir); }
    }
    static void deleteDir(File d) { File[] f = d.listFiles(); if (f != null) for (File c : f) { if (c.isDirectory()) deleteDir(c); c.delete(); } d.delete(); }

    static byte[] patchColors(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode(Opcodes.ASM9);
        cr.accept(cn, 0);

        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name)) {
                // Find INVOKESTATIC ColorBrewer.instance() and replace with ACONST_NULL
                int count = 0;
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode min) {
                        if (min.getOpcode() == Opcodes.INVOKESTATIC
                            && "org/geotools/brewer/color/ColorBrewer".equals(min.owner)
                            && "instance".equals(min.name)) {
                            mn.instructions.set(insn, new InsnNode(Opcodes.ACONST_NULL));
                            count++;
                            System.out.println("Replaced ColorBrewer.instance() with null");
                        }
                    }
                }
                if (count == 0) System.out.println("WARNING: No ColorBrewer.instance() call found!");
                break;
            }
        }

        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
