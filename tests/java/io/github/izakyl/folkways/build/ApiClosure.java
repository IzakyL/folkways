package io.github.izakyl.folkways.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Build tooling only. Finds a compilation API's types without loading or executing game classes. */
public final class ApiClosure {
    private static final String PREFIX = "io/github/izakyl/folkways/";
    private static final List<String> ROOTS = List.of("core/api/", "front/api/", "base/task/");
    private static final List<String> IMPLEMENTATIONS = List.of(
        "core/engine/", "core/shell/", "front/engine/", "front/ui/", "front/client/");

    private ApiClosure() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: index <output> <classes-dir>... | verify <api.jar>");
        }
        Map<String, byte[]> classes = new LinkedHashMap<>();
        if (args[0].equals("index") && args.length >= 3) {
            for (int i = 2; i < args.length; i++) {
                Path directory = Path.of(args[i]);
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                        add(classes, Files.readAllBytes(path));
                    }
                }
            }
            Set<String> selected = select(classes);
            Path output = Path.of(args[1]);
            Files.createDirectories(output.getParent());
            Files.write(output, selected.stream().map(name -> name + ".class").toList(), StandardCharsets.UTF_8);
            System.out.println("API signature closure: " + selected.size() + " classes");
        } else if (args[0].equals("verify") && args.length == 2) {
            try (ZipFile jar = new ZipFile(args[1])) {
                for (var entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                    try (var input = jar.getInputStream(entry)) {
                        add(classes, input.readAllBytes());
                    }
                }
            }
            Set<String> selected = select(classes);
            if (!selected.equals(classes.keySet())) {
                throw new IllegalStateException("API attachment contains types outside its public signature closure");
            }
            System.out.println("Packaged API signature closure verified: " + selected.size() + " classes");
        } else {
            throw new IllegalArgumentException("usage: index <output> <classes-dir>... | verify <api.jar>");
        }
    }

    private static void add(Map<String, byte[]> classes, byte[] bytes) {
        String name = new ClassReader(bytes).getClassName();
        if (classes.putIfAbsent(name, bytes) != null) {
            throw new IllegalStateException("Duplicate project class " + name);
        }
    }

    static Set<String> select(Map<String, byte[]> classes) {
        Map<String, Info> info = new LinkedHashMap<>();
        classes.forEach((name, bytes) -> info.put(name, describe(bytes)));
        ArrayDeque<String> pending = new ArrayDeque<>();
        info.forEach((name, type) -> {
            if (ROOTS.stream().anyMatch(root -> name.startsWith(PREFIX + root)) && accessible(name, info)) {
                pending.add(name);
            }
        });
        if (pending.isEmpty()) {
            throw new IllegalStateException("No public Folkways API roots found");
        }
        Set<String> selected = new TreeSet<>();
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!name.startsWith(PREFIX) || !selected.add(name)) {
                continue;
            }
            if (IMPLEMENTATIONS.stream().anyMatch(part -> name.startsWith(PREFIX + part))) {
                throw new IllegalStateException("Public API exposes an implementation type: " + name);
            }
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new IllegalStateException("Missing project type in API signature closure: " + name);
            }
            pending.addAll(references(bytes));
        }
        return selected;
    }

    private static boolean accessible(String name, Map<String, Info> info) {
        Info type = info.get(name);
        return type != null && visible(type.access) && (type.outer == null || accessible(type.outer, info));
    }

    private static boolean visible(int access) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
    }

    private record Info(int access, String outer) {
    }

    private static Info describe(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        int[] access = {reader.getAccess()};
        String[] outer = {null};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visitInnerClass(String name, String parent, String simple, int flags) {
                if (name.equals(reader.getClassName())) {
                    access[0] = flags;
                    outer[0] = parent;
                    if (simple == null) {
                        access[0] = 0; // Anonymous implementation, never a public root.
                    }
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Info(access[0], outer[0]);
    }

    static Set<String> references(byte[] bytes) {
        Set<String> references = new LinkedHashSet<>();
        // ASM handles descriptors, generic bounds/inner types, exceptions, annotation values/defaults,
        // type/parameter annotations, records and sealed types. The sink never emits or executes bytecode.
        ClassVisitor signatures = new ClassRemapper(new ClassWriter(0), new Remapper() {
            @Override
            public String map(String name) {
                if (name.startsWith(PREFIX)) {
                    references.add(name);
                }
                return name;
            }
        });
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9, signatures) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                return visible(access) ? super.visitField(access, name, descriptor, signature, value) : null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return visible(access) ? super.visitMethod(access, name, descriptor, signature, exceptions) : null;
            }

            @Override
            public void visitInnerClass(String name, String outer, String simple, int access) {
                if (name.equals(reader.getClassName()) || (reader.getClassName().equals(outer) && visible(access))) {
                    super.visitInnerClass(name, outer, simple, access);
                }
            }

            @Override
            public void visitNestMember(String member) {
                // The nest also lists private/anonymous implementations, unrelated to compilation signatures.
            }

            @Override
            public void visitNestHost(String host) {
                // A named nested type's enclosing class is covered by InnerClasses above.
            }

            @Override
            public void visitOuterClass(String owner, String name, String descriptor) {
                references.add(owner);
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return references;
    }
}
