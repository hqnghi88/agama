import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches ParallelAgentRunner:
 * 1. execute(ForkJoinTask) -> calls task.invoke() instead of AGENT_PARALLEL_EXECUTOR.invoke(task)
 * 2. compute() -> always calls executeOn() directly (no fork/join splitting)
 *    ForkJoinTask.invoke() from non-ForkJoinWorkerThread causes externalAwaitDone()
 *    which parks the thread; on Android the commonPool work-stealing is unreliable.
 */
public class ParallelRunnerPatcher {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ParallelRunnerPatcher <gama.core.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        String targetClass = "gama/core/runtime/concurrent/ParallelAgentRunner.class";

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
                    if (mn.name.equals("execute") && mn.desc.equals("(Ljava/util/concurrent/ForkJoinTask;)Ljava/lang/Object;")) {
                        InsnList newInsns = new InsnList();
                        LabelNode nonnull = new LabelNode();
                        newInsns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        newInsns.add(new JumpInsnNode(Opcodes.IFNONNULL, nonnull));
                        newInsns.add(new InsnNode(Opcodes.ACONST_NULL));
                        newInsns.add(new InsnNode(Opcodes.ARETURN));
                        newInsns.add(nonnull);
                        newInsns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        newInsns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                "java/util/concurrent/ForkJoinTask", "invoke",
                                "()Ljava/lang/Object;", false));
                        newInsns.add(new InsnNode(Opcodes.ARETURN));

                        mn.instructions.clear();
                        mn.tryCatchBlocks.clear();
                        mn.localVariables.clear();
                        mn.maxStack = 2;
                        mn.maxLocals = 1;
                        mn.instructions.insert(newInsns);
                        patched = true;
                        System.out.println("Patched: " + targetClass + " -> execute() now calls task.invoke()");
                    }

                    if (mn.name.equals("compute") && mn.desc.equals("()Ljava/lang/Object;")) {
                        InsnList newInsns = new InsnList();
                        newInsns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        newInsns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        newInsns.add(new FieldInsnNode(Opcodes.GETFIELD,
                                "gama/core/runtime/concurrent/ParallelAgentRunner",
                                "originalScope",
                                "Lgama/core/runtime/IScope;"));
                        newInsns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                "gama/core/runtime/concurrent/ParallelAgentRunner",
                                "executeOn",
                                "(Lgama/core/runtime/IScope;)Ljava/lang/Object;", false));
                        newInsns.add(new InsnNode(Opcodes.ARETURN));

                        mn.instructions.clear();
                        mn.tryCatchBlocks.clear();
                        mn.localVariables.clear();
                        mn.maxStack = 2;
                        mn.maxLocals = 1;
                        mn.instructions.insert(newInsns);
                        System.out.println("Patched: " + targetClass + " -> compute() now calls executeOn() directly (no fork/join)");
                    }
                }

                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                cn.accept(cw);
                data = cw.toByteArray();
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
            System.out.println("Target not found");
        }
    }
}
