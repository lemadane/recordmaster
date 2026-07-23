package io.lemadane.recordmaster.core;

import io.lemadane.recordmaster.Record;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.*;

public final class BinaryCodec {
    private static final byte TAG_NULL = 0;
    private static final byte TAG_STRING = 1;
    private static final byte TAG_UUID = 2;
    private static final byte TAG_INSTANT = 3;
    private static final byte TAG_INTEGER = 4;
    private static final byte TAG_LONG = 5;
    private static final byte TAG_DOUBLE = 6;
    private static final byte TAG_BOOLEAN = 7;
    private static final byte TAG_ENUM = 8;
    private static final byte TAG_RECORD = 9;

    public static byte[] serialize(Record record) {
        if (record == null) return new byte[0];
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            
            RecordComponent[] components = record.getClass().getRecordComponents();
            dos.writeInt(components.length);
            for (RecordComponent comp : components) {
                dos.writeUTF(comp.getName());
                Object val = comp.getAccessor().invoke(record);
                writeValue(dos, val);
            }
            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed for " + record.getClass().getName(), e);
        }
    }

    public static <T extends Record> T deserialize(byte[] bytes, Class<T> recordClass) {
        if (bytes == null || bytes.length == 0) return null;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             DataInputStream dis = new DataInputStream(bais)) {
            
            int count = dis.readInt();
            Map<String, Object> values = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String name = dis.readUTF();
                Object val = readValue(dis);
                values.put(name, val);
            }

            RecordComponent[] components = recordClass.getRecordComponents();
            Object[] args = new Object[components.length];
            Class<?>[] paramTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent comp = components[i];
                Object val = values.get(comp.getName());
                paramTypes[i] = comp.getType();
                args[i] = castValue(val, comp.getType());
            }

            Constructor<T> canonicalConstructor = recordClass.getDeclaredConstructor(paramTypes);
            canonicalConstructor.setAccessible(true);
            return canonicalConstructor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("Deserialization failed for " + recordClass.getName(), e);
        }
    }

    public static void writeValue(DataOutputStream dos, Object val) throws IOException {
        if (val == null) {
            dos.writeByte(TAG_NULL);
        } else if (val instanceof String s) {
            dos.writeByte(TAG_STRING);
            dos.writeUTF(s);
        } else if (val instanceof UUID uuid) {
            dos.writeByte(TAG_UUID);
            dos.writeLong(uuid.getMostSignificantBits());
            dos.writeLong(uuid.getLeastSignificantBits());
        } else if (val instanceof Instant inst) {
            dos.writeByte(TAG_INSTANT);
            dos.writeLong(inst.getEpochSecond());
            dos.writeInt(inst.getNano());
        } else if (val instanceof Integer i) {
            dos.writeByte(TAG_INTEGER);
            dos.writeInt(i);
        } else if (val instanceof Long l) {
            dos.writeByte(TAG_LONG);
            dos.writeLong(l);
        } else if (val instanceof Double d) {
            dos.writeByte(TAG_DOUBLE);
            dos.writeDouble(d);
        } else if (val instanceof Boolean b) {
            dos.writeByte(TAG_BOOLEAN);
            dos.writeBoolean(b);
        } else if (val.getClass().isEnum()) {
            dos.writeByte(TAG_ENUM);
            dos.writeUTF(val.getClass().getName());
            dos.writeUTF(((Enum<?>) val).name());
        } else if (val.getClass().isRecord()) {
            dos.writeByte(TAG_RECORD);
            dos.writeUTF(val.getClass().getName());
            
            RecordComponent[] subComponents = val.getClass().getRecordComponents();
            dos.writeInt(subComponents.length);
            for (RecordComponent comp : subComponents) {
                dos.writeUTF(comp.getName());
                try {
                    Object subVal = comp.getAccessor().invoke(val);
                    writeValue(dos, subVal);
                } catch (Exception e) {
                    throw new IOException("Failed to serialize sub-component " + comp.getName(), e);
                }
            }
        } else {
            throw new IllegalArgumentException("Unsupported field type: " + val.getClass().getName());
        }
    }

    public static Object readValue(DataInputStream dis) throws IOException {
        byte tag = dis.readByte();
        return switch (tag) {
            case TAG_NULL -> null;
            case TAG_STRING -> dis.readUTF();
            case TAG_UUID -> new UUID(dis.readLong(), dis.readLong());
            case TAG_INSTANT -> Instant.ofEpochSecond(dis.readLong(), dis.readInt());
            case TAG_INTEGER -> dis.readInt();
            case TAG_LONG -> dis.readLong();
            case TAG_DOUBLE -> dis.readDouble();
            case TAG_BOOLEAN -> dis.readBoolean();
            case TAG_ENUM -> {
                String className = dis.readUTF();
                String name = dis.readUTF();
                yield new EnumPlaceholder(className, name);
            }
            case TAG_RECORD -> {
                String className = dis.readUTF();
                int count = dis.readInt();
                Map<String, Object> values = new HashMap<>();
                for (int i = 0; i < count; i++) {
                    String name = dis.readUTF();
                    Object subVal = readValue(dis);
                    values.put(name, subVal);
                }
                yield new RecordPlaceholder(className, values);
            }
            default -> throw new IOException("Unknown type tag: " + tag);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object castValue(Object val, Class<?> targetType) {
        if (val == null) {
            if (targetType == boolean.class) return false;
            if (targetType == int.class) return 0;
            if (targetType == long.class) return 0L;
            if (targetType == double.class) return 0.0;
            return null;
        }
        if (val instanceof EnumPlaceholder placeholder) {
            if (targetType.isEnum()) {
                return Enum.valueOf((Class<Enum>) targetType, placeholder.name());
            }
        }
        if (val instanceof RecordPlaceholder placeholder) {
            if (targetType.isRecord()) {
                try {
                    RecordComponent[] components = targetType.getRecordComponents();
                    Object[] args = new Object[components.length];
                    Class<?>[] paramTypes = new Class<?>[components.length];
                    for (int i = 0; i < components.length; i++) {
                        RecordComponent comp = components[i];
                        Object subVal = placeholder.values().get(comp.getName());
                        paramTypes[i] = comp.getType();
                        args[i] = castValue(subVal, comp.getType());
                    }
                    Constructor<?> constructor = targetType.getDeclaredConstructor(paramTypes);
                    constructor.setAccessible(true);
                    return constructor.newInstance(args);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to construct nested record of type " + targetType.getName(), e);
                }
            }
        }
        return val;
    }

    private record EnumPlaceholder(String className, String name) {}
    private record RecordPlaceholder(String className, Map<String, Object> values) {}
}
