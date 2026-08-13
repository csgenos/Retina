/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.spirv;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads decorations straight out of a SPIR-V module.
 *
 * <p>Reflection is what turns "the shader compiled" into "the shader will actually bind". The
 * translator assigns sets and bindings on paper; this reads back what the compiler really
 * emitted, so a mismatch between the two is caught at load time rather than as a validation
 * error or a black screen at runtime.
 *
 * <p>The module is parsed directly rather than through SPIRV-Cross. Only the decoration and
 * name instructions are needed, the binary layout for those is fixed and small, and parsing
 * them here avoids a second native library on the shader-compilation hot path.
 */
public final class SpirvReflection {

    /** A resource the module declares. */
    public record Resource(String name, int set, int binding, StorageClass storageClass) {
    }

    /** The SPIR-V storage class of a declared variable, limited to the ones that matter. */
    public enum StorageClass {
        UNIFORM_CONSTANT, INPUT, UNIFORM, OUTPUT, PUSH_CONSTANT, STORAGE_BUFFER, OTHER;

        static StorageClass of(int value) {
            return switch (value) {
                case 0 -> UNIFORM_CONSTANT;
                case 1 -> INPUT;
                case 2 -> UNIFORM;
                case 3 -> OUTPUT;
                case 9 -> PUSH_CONSTANT;
                case 12 -> STORAGE_BUFFER;
                default -> OTHER;
            };
        }
    }

    /** An input or output interface variable with an explicit location. */
    public record InterfaceVariable(String name, int location, StorageClass storageClass) {
    }

    private final List<Resource> resources;
    private final List<InterfaceVariable> inputs;
    private final List<InterfaceVariable> outputs;
    private final boolean hasPushConstants;
    private final Set<String> capabilities;

    private SpirvReflection(List<Resource> resources, List<InterfaceVariable> inputs,
                            List<InterfaceVariable> outputs, boolean hasPushConstants,
                            Set<String> capabilities) {
        this.resources = List.copyOf(resources);
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.hasPushConstants = hasPushConstants;
        this.capabilities = Set.copyOf(capabilities);
    }

    /** Descriptor-bound resources, i.e. those carrying both a set and a binding. */
    public List<Resource> resources() {
        return resources;
    }

    /** Interface inputs with explicit locations. */
    public List<InterfaceVariable> inputs() {
        return inputs;
    }

    /** Interface outputs with explicit locations. */
    public List<InterfaceVariable> outputs() {
        return outputs;
    }

    /** Whether the module declares a push-constant block. */
    public boolean hasPushConstants() {
        return hasPushConstants;
    }

    /** SPIR-V capabilities the module requires, as their numeric ids rendered as names. */
    public Set<String> capabilities() {
        return capabilities;
    }

    /** The fragment output locations the module writes. */
    public List<Integer> fragmentOutputLocations() {
        return outputs.stream()
            .filter(v -> v.storageClass() == StorageClass.OUTPUT)
            .map(InterfaceVariable::location)
            .sorted()
            .toList();
    }

    /** The resource bound at {@code set}/{@code binding}, if any. */
    public Optional<Resource> at(int set, int binding) {
        return resources.stream()
            .filter(r -> r.set() == set && r.binding() == binding)
            .findFirst();
    }

    // ---- SPIR-V opcodes and decorations used here ----
    private static final int OP_NAME = 5;
    private static final int OP_DECORATE = 71;
    private static final int OP_VARIABLE = 59;
    private static final int OP_CAPABILITY = 17;
    private static final int DECORATION_BINDING = 33;
    private static final int DECORATION_DESCRIPTOR_SET = 34;
    private static final int DECORATION_LOCATION = 30;

    /**
     * Parses {@code module}.
     *
     * @throws IllegalArgumentException when the module is not well-formed SPIR-V
     */
    public static SpirvReflection of(ByteBuffer module) {
        ByteBuffer buffer = module.duplicate().order(ByteOrder.nativeOrder());
        if (buffer.remaining() < 20 || buffer.remaining() % 4 != 0) {
            throw new IllegalArgumentException("SPIR-V module is "
                + buffer.remaining() + " bytes, which is not a whole number of words");
        }
        IntBuffer words = buffer.asIntBuffer();
        int magic = words.get(0);
        if (magic != SpirvCompiler.SPIRV_MAGIC) {
            throw new IllegalArgumentException(String.format(
                "SPIR-V magic number is 0x%08X, expected 0x%08X", magic,
                SpirvCompiler.SPIRV_MAGIC));
        }

        Map<Integer, String> names = new LinkedHashMap<>();
        Map<Integer, Integer> descriptorSets = new LinkedHashMap<>();
        Map<Integer, Integer> bindings = new LinkedHashMap<>();
        Map<Integer, Integer> locations = new LinkedHashMap<>();
        Map<Integer, StorageClass> storageClasses = new LinkedHashMap<>();
        Set<String> capabilities = new LinkedHashSet<>();

        int index = 5; // skip the 5-word header
        int limit = words.limit();
        while (index < limit) {
            int instruction = words.get(index);
            int wordCount = instruction >>> 16;
            int opcode = instruction & 0xFFFF;
            if (wordCount == 0 || index + wordCount > limit) {
                throw new IllegalArgumentException(
                    "SPIR-V module is truncated at word " + index);
            }
            switch (opcode) {
                case OP_CAPABILITY -> {
                    if (wordCount >= 2) {
                        capabilities.add(capabilityName(words.get(index + 1)));
                    }
                }
                case OP_NAME -> {
                    if (wordCount >= 3) {
                        names.put(words.get(index + 1),
                            readString(words, index + 2, index + wordCount));
                    }
                }
                case OP_DECORATE -> {
                    if (wordCount >= 4) {
                        int target = words.get(index + 1);
                        int decoration = words.get(index + 2);
                        int operand = words.get(index + 3);
                        switch (decoration) {
                            case DECORATION_DESCRIPTOR_SET ->
                                descriptorSets.put(target, operand);
                            case DECORATION_BINDING -> bindings.put(target, operand);
                            case DECORATION_LOCATION -> locations.put(target, operand);
                            default -> {
                                // Other decorations do not affect binding compatibility.
                            }
                        }
                    }
                }
                case OP_VARIABLE -> {
                    if (wordCount >= 4) {
                        storageClasses.put(words.get(index + 2),
                            StorageClass.of(words.get(index + 3)));
                    }
                }
                default -> {
                    // Not needed for binding reflection.
                }
            }
            index += wordCount;
        }

        List<Resource> resources = new ArrayList<>();
        descriptorSets.forEach((id, set) -> {
            Integer binding = bindings.get(id);
            if (binding != null) {
                resources.add(new Resource(names.getOrDefault(id, "<unnamed#" + id + ">"),
                    set, binding, storageClasses.getOrDefault(id, StorageClass.OTHER)));
            }
        });
        resources.sort((a, b) -> a.set() != b.set()
            ? Integer.compare(a.set(), b.set())
            : Integer.compare(a.binding(), b.binding()));

        List<InterfaceVariable> inputs = new ArrayList<>();
        List<InterfaceVariable> outputs = new ArrayList<>();
        locations.forEach((id, location) -> {
            StorageClass storage = storageClasses.getOrDefault(id, StorageClass.OTHER);
            InterfaceVariable variable = new InterfaceVariable(
                names.getOrDefault(id, "<unnamed#" + id + ">"), location, storage);
            if (storage == StorageClass.INPUT) {
                inputs.add(variable);
            } else if (storage == StorageClass.OUTPUT) {
                outputs.add(variable);
            }
        });
        inputs.sort((a, b) -> Integer.compare(a.location(), b.location()));
        outputs.sort((a, b) -> Integer.compare(a.location(), b.location()));

        boolean pushConstants = storageClasses.containsValue(StorageClass.PUSH_CONSTANT);
        return new SpirvReflection(resources, inputs, outputs, pushConstants, capabilities);
    }

    /** Reads a NUL-terminated UTF-8 literal packed four bytes per word. */
    private static String readString(IntBuffer words, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            int word = words.get(i);
            for (int shift = 0; shift < 32; shift += 8) {
                int b = (word >>> shift) & 0xFF;
                if (b == 0) {
                    return out.toString();
                }
                out.append((char) b);
            }
        }
        return out.toString();
    }

    /** Names the handful of capabilities Retina reasons about; others render as an id. */
    private static String capabilityName(int id) {
        return switch (id) {
            case 0 -> "Matrix";
            case 1 -> "Shader";
            case 2 -> "Geometry";
            case 3 -> "Tessellation";
            case 5 -> "Linkage";
            case 6 -> "Float16";
            case 10 -> "Float64";
            case 11 -> "Int64";
            case 22 -> "Int16";
            case 32 -> "SampledCubeArray";
            case 39 -> "ImageQuery";
            case 45 -> "ClipDistance";
            case 46 -> "CullDistance";
            case 61 -> "DerivativeControl";
            case 4427 -> "DrawParameters";
            case 4437 -> "PhysicalStorageBufferAddresses";
            case 5301 -> "GroupNonUniform";
            case 5345 -> "StorageBuffer16BitAccess";
            case 5348 -> "UniformAndStorageBuffer8BitAccess";
            default -> "Capability" + id;
        };
    }
}
