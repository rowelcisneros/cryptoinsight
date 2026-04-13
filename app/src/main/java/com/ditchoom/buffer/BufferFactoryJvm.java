package com.ditchoom.buffer;

public final class BufferFactoryJvm {
    private BufferFactoryJvm() {
    }

    public static PlatformBuffer allocate(
        PlatformBuffer.Companion companion,
        int size,
        AllocationZone zone,
        ByteOrder byteOrder
    ) {
        return BufferFactoryAndroid.allocate(companion, size, zone, byteOrder);
    }

    public static PlatformBuffer allocate$default(
        PlatformBuffer.Companion companion,
        int size,
        AllocationZone zone,
        ByteOrder byteOrder,
        int mask,
        Object unused
    ) {
        if ((mask & 0x4) != 0) {
            byteOrder = null;
        }
        return BufferFactoryAndroid.allocate(companion, size, zone, byteOrder);
    }

    public static PlatformBuffer wrap(
        PlatformBuffer.Companion companion,
        byte[] bytes,
        ByteOrder byteOrder
    ) {
        return BufferFactoryAndroid.wrap(companion, bytes, byteOrder);
    }

    public static PlatformBuffer wrap$default(
        PlatformBuffer.Companion companion,
        byte[] bytes,
        ByteOrder byteOrder,
        int mask,
        Object unused
    ) {
        if ((mask & 0x2) != 0) {
            byteOrder = null;
        }
        return BufferFactoryAndroid.wrap(companion, bytes, byteOrder);
    }
}
