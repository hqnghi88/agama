import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches LayeredDisplayOutput.createSurface() to remove the3D early-return.
 *
 * Source:
 *   else if (getData().is3D()) return;
 *
 * We NOP out the is3D check + RETURN so that createDisplaySurfaceFor() is always called,
 * even for 3D displays. On Android we render everything through the 2D Canvas surface.
 */
public class Display3DPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: Display3DPatcher <gama.core.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        String targetClass = "gama/core/outputs/LayeredDisplayOutput.class";

        ZipFile zipIn = new ZipFile(jarFile);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));
        boolean patched = false;

        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) { data = is.readAllBytes(); }

            if (entry.getName().equals(targetClass)) {
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);

                for (MethodNode mn : cn.methods) {
                    if (!mn.name.equals("createSurface") || !mn.desc.equals("(Lgama/core/runtime/IScope;)V")) continue;

                    System.out.println("Found createSurface(IScope) in LayeredDisplayOutput");

                    // Pattern to find:
                    //   INVOKEVIRTUAL DisplayData.is3D ()Z
                    //   IFEQ <skip>       -- branch if false (skip the return)
                    //   RETURN            -- early return if is3D() == true
                    //
                    // We NOP out both IFEQ and RETURN so execution always falls through
                    // to createDisplaySurfaceFor(this).

                    for (int i = 0; i < mn.instructions.size(); i++) {
                        AbstractInsnNode insn = mn.instructions.get(i);
                        if (insn instanceof MethodInsnNode mni
                                && mni.name.equals("is3D")
                                && mni.desc.equals("()Z")) {
                            System.out.println("Found is3D() call at instruction " + i);

                            // Scan forward for IFEQ + RETURN
                            for (int j = i + 1; j < Math.min(mn.instructions.size(), i + 6); j++) {
                                AbstractInsnNode next = mn.instructions.get(j);
                                if (next instanceof JumpInsnNode jni && jni.getOpcode() == Opcodes.IFEQ) {
                                    System.out.println("Found IFEQ at instruction " + j);
                                    // NOP the IFEQ
                                    mn.instructions.set(jni, new InsnNode(Opcodes.NOP));

                                    // Find and NOP the RETURN that follows
                                    for (int k = j + 1; k < Math.min(mn.instructions.size(), j + 4); k++) {
                                        AbstractInsnNode ret = mn.instructions.get(k);
                                        if (ret.getOpcode() == Opcodes.RETURN) {
                                            System.out.println("Found RETURN at instruction " + k + " — NOP'ing");
                                            mn.instructions.set(ret, new InsnNode(Opcodes.NOP));
                                            patched = true;
                                            break;
                                        }
                                        if (ret.getOpcode() == Opcodes.GOTO) break;
                                    }
                                    break;
                                }
                            }
                            break;
                        }
                    }

                    if (patched) {
                        // Recompute frames/maxs
                        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                        cn.accept(cw);
                        data = cw.toByteArray();
                        System.out.println("Patched createSurface: is3D() early-return removed");
                    } else {
                        System.out.println("WARNING: is3D() pattern not found in createSurface");
                    }
                }
            }

            zipOut.putNextEntry(new ZipEntry(entry.getName()));
            zipOut.write(data);
            zipOut.closeEntry();
        }

        zipIn.close();
        zipOut.close();

        if (patched) {
            jarFile.delete();
            tmpJar.renameTo(jarFile);
            System.out.println("JAR updated: " + jarFile.getName());
        } else {
            tmpJar.delete();
            System.out.println("No targets found");
        }
    }
}
