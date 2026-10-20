package io.github.izakyl.folkways.build;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Synthetic signatures prove discovery without putting any game classes on the tooling classpath. */
public final class ApiClosureTest {
    private static final String PREFIX = "io/github/izakyl/folkways/";
    private static final String ROOT = PREFIX + "core/api/Fixture";

    private ApiClosureTest() {
    }

    public static void main(String[] args) {
        Map<String, byte[]> classes = fixture();
        Set<String> selected = ApiClosure.select(classes);
        for (String extra : new String[] {"Base", "Inherited", "Bound", "Array", "Generic", "Marker",
                "AnnotationValue", "Mode", "TypeMarker", "ParameterMarker", "DefaultValue", "Exception",
                "Outer", "Outer$Inner", "Argument", "Contract", "Permitted", "RecordComponent", "RecordMarker"}) {
            String part = extra.equals("Base") ? "base/persist/" : "effect/";
            require(selected.contains(PREFIX + part + extra), "missing recursive signature type " + extra);
        }
        require(selected.contains(ROOT + "$Nested"), "public member types are exported");
        require(selected.contains(ROOT + "$Protected"), "protected member types are exported");
        require(!selected.contains(ROOT + "$Private"), "private member types are excluded");
        require(!selected.contains(ROOT + "$1"), "anonymous implementations are excluded");
        require(!selected.contains(PREFIX + "core/api/PackagePrivate"), "package-private roots are excluded");
        require(!selected.contains(PREFIX + "core/engine/Implementation"), "method bodies are not a signature");
        require(!selected.contains(PREFIX + "effect/PrivateDetail"), "private signatures are not exported");
        require(ApiClosure.select(classes).equals(selected), "selection is deterministic");
        Map<String, byte[]> attachment = new LinkedHashMap<>();
        for (String name : selected) {
            attachment.put(name, classes.get(name));
        }
        require(ApiClosure.select(attachment).equals(selected), "closure is complete without the implementation");

        attachment.remove(PREFIX + "effect/Generic");
        rejects(attachment, "Missing project type", "a type used only in a generic signature is mandatory");
        classes = fixture();
        ClassWriter leaked = type(ROOT, "java/lang/Object", null, Opcodes.ACC_PUBLIC);
        leaked.visitMethod(Opcodes.ACC_PUBLIC, "leak", "()L" + PREFIX + "core/engine/Implementation;", null, null);
        classes.put(ROOT, finish(leaked));
        rejects(classes, "implementation type", "implementation leaks fail instead of expanding the API");
        System.out.println("API closure tests passed: signatures, generics, annotations, nested types and isolation");
    }

    private static Map<String, byte[]> fixture() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (String name : new String[] {"Inherited", "Bound", "Array", "Generic", "Marker", "AnnotationValue",
                "Mode", "TypeMarker", "ParameterMarker", "DefaultValue", "Exception", "Outer", "Outer$Inner",
                "Argument", "Contract", "Permitted", "PrivateDetail", "RecordComponent", "RecordMarker"}) {
            put(classes, type(PREFIX + "effect/" + name, "java/lang/Object", null, Opcodes.ACC_PUBLIC));
        }
        ClassWriter base = type(PREFIX + "base/persist/Base", "java/lang/Object", null, Opcodes.ACC_PUBLIC);
        base.visitMethod(Opcodes.ACC_PROTECTED, "inherited", "()L" + PREFIX + "effect/Inherited;", null, null);
        put(classes, base);
        ClassWriter root = type(ROOT, PREFIX + "base/persist/Base",
            "<T:L" + PREFIX + "effect/Bound;>L" + PREFIX + "base/persist/Base;", Opcodes.ACC_PUBLIC);
        root.visitPermittedSubclass(PREFIX + "effect/Permitted");
        root.visitRecordComponent("component", "L" + PREFIX + "effect/RecordComponent;", null)
            .visitAnnotation("L" + PREFIX + "effect/RecordMarker;", false).visitEnd();
        root.visitInnerClass(ROOT + "$Nested", ROOT, "Nested", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        root.visitInnerClass(ROOT + "$Protected", ROOT, "Protected", Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC);
        root.visitInnerClass(ROOT + "$Private", ROOT, "Private", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC);
        root.visitNestMember(ROOT + "$Private");
        root.visitField(Opcodes.ACC_PUBLIC, "array", "[L" + PREFIX + "effect/Array;", null, null);
        root.visitField(Opcodes.ACC_PRIVATE, "detail", "L" + PREFIX + "effect/PrivateDetail;", null, null);
        root.visitField(Opcodes.ACC_PROTECTED, "nested", "L" + PREFIX + "effect/Outer$Inner;",
            "L" + PREFIX + "effect/Outer<Ljava/lang/String;>.Inner<L" + PREFIX + "effect/Argument;>;", null);
        var annotation = root.visitAnnotation("L" + PREFIX + "effect/Marker;", false);
        annotation.visit("value", Type.getObjectType(PREFIX + "effect/AnnotationValue"));
        annotation.visitEnum("mode", "L" + PREFIX + "effect/Mode;", "ON");
        annotation.visitEnd();
        root.visitTypeAnnotation(0x10000000, null, "L" + PREFIX + "effect/TypeMarker;", false).visitEnd();
        var method = root.visitMethod(Opcodes.ACC_PUBLIC, "generic", "(Ljava/lang/String;)Ljava/util/List;",
            "(Ljava/lang/String;)Ljava/util/List<+L" + PREFIX + "effect/Generic;>;",
            new String[] {PREFIX + "effect/Exception"});
        method.visitParameterAnnotation(0, "L" + PREFIX + "effect/ParameterMarker;", false).visitEnd();
        method.visitAnnotationDefault().visit(null, Type.getObjectType(PREFIX + "effect/DefaultValue"));
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, PREFIX + "core/engine/Implementation");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 2);
        method.visitEnd();
        put(classes, root);
        for (String name : new String[] {"Nested", "Protected", "Private"}) {
            int access = name.equals("Private") ? Opcodes.ACC_PRIVATE
                : name.equals("Protected") ? Opcodes.ACC_PROTECTED : Opcodes.ACC_PUBLIC;
            ClassWriter inner = type(ROOT + "$" + name, "java/lang/Object", null, Opcodes.ACC_PUBLIC);
            inner.visitInnerClass(ROOT + "$" + name, ROOT, name, access | Opcodes.ACC_STATIC);
            inner.visitNestHost(ROOT);
            if (name.equals("Nested")) {
                inner.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, ROOT + "$Nested", null,
                    "java/lang/Object", new String[] {PREFIX + "effect/Contract"});
            }
            put(classes, inner);
        }
        ClassWriter anonymous = type(ROOT + "$1", "java/lang/Object", null, 0);
        anonymous.visitOuterClass(ROOT, "generic", "()V");
        anonymous.visitInnerClass(ROOT + "$1", null, null, 0);
        put(classes, anonymous);
        put(classes, type(PREFIX + "core/api/PackagePrivate", "java/lang/Object", null, 0));
        put(classes, type(PREFIX + "core/engine/Implementation", "java/lang/Object", null, Opcodes.ACC_PUBLIC));
        return classes;
    }

    private static ClassWriter type(String name, String parent, String signature, int access) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, access, name, signature, parent, null);
        return writer;
    }

    private static byte[] finish(ClassWriter writer) {
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void put(Map<String, byte[]> classes, ClassWriter writer) {
        byte[] bytes = finish(writer);
        classes.put(new org.objectweb.asm.ClassReader(bytes).getClassName(), bytes);
    }

    private static void rejects(Map<String, byte[]> classes, String message, String reason) {
        try {
            ApiClosure.select(classes);
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains(message), reason + ": " + expected.getMessage());
            return;
        }
        throw new AssertionError(reason);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
