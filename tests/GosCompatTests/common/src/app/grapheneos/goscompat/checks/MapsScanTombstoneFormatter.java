package app.grapheneos.goscompat.checks;

import com.android.server.os.nano.TombstoneProtos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

public final class MapsScanTombstoneFormatter {
    private static final int MAP_CONTEXT_LINES = 5;

    private MapsScanTombstoneFormatter() {
    }

    public static String format(byte[] proto) throws IOException {
        return format(TombstoneProtos.Tombstone.parseFrom(proto));
    }

    public static byte[] readFully(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String format(TombstoneProtos.Tombstone tombstone) {
        TombstoneProtos.Thread thread = tombstone.threads != null
                ? tombstone.threads.get(tombstone.tid) : null;
        if (thread == null) {
            TombstoneProtos.Signal signal = tombstone.signalInfo;
            return "pid=" + tombstone.pid
                    + ", tid=" + tombstone.tid
                    + ", signal=" + (signal != null ? signal.name : "<none>")
                    + ", commandLine=" + Arrays.toString(tombstone.commandLine)
                    + "\nNo crashing thread in tombstone";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("pid=").append(tombstone.pid)
                .append(", tid=").append(tombstone.tid)
                .append(", thread=").append(thread.name)
                .append(", commandLine=").append(Arrays.toString(tombstone.commandLine))
                .append('\n')
                .append(formatSignalLine(tombstone, thread))
                .append("\n\nmaps around fault address:\n")
                .append(formatFaultMapContext(tombstone))
                .append("\nbacktrace:\n");
        TombstoneProtos.BacktraceFrame[] frames = thread.currentBacktrace;
        if (frames != null) {
            for (int i = 0; i < frames.length; i++) {
                TombstoneProtos.BacktraceFrame frame = frames[i];
                builder.append('#').append(i)
                        .append(' ')
                        .append(frame.fileName)
                        .append(" (")
                        .append(frame.functionName)
                        .append(")\n");
            }
        }
        return builder.toString();
    }

    private static String formatSignalLine(TombstoneProtos.Tombstone tombstone,
            TombstoneProtos.Thread thread) {
        TombstoneProtos.Signal signal = tombstone.signalInfo;
        if (signal == null) {
            return "signal <none>";
        }
        String faultAddress = "--------";
        if (signal.hasFaultAddress) {
            faultAddress = "0x" + formatPlainAddress(tombstone, signal.faultAddress)
                    + getReadWriteDesc(tombstone.arch, thread);
        }
        return "signal " + signal.number
                + " (" + signal.name + ")"
                + ", code " + signal.code
                + " (" + signal.codeName + ")"
                + ", fault addr " + faultAddress;
    }

    private static String formatFaultMapContext(TombstoneProtos.Tombstone tombstone) {
        TombstoneProtos.MemoryMapping[] mappings = tombstone.memoryMappings;
        if (mappings == null || mappings.length == 0) {
            return "    <no memory mappings>\n";
        }

        TombstoneProtos.Signal signal = tombstone.signalInfo;
        if (signal == null || !signal.hasFaultAddress) {
            return "    <no fault address>\n";
        }

        long faultAddress = untagAddress(tombstone.arch, signal.faultAddress);
        int faultIndex = -1;
        int insertionIndex = mappings.length;
        for (int i = 0; i < mappings.length; i++) {
            TombstoneProtos.MemoryMapping mapping = mappings[i];
            if (faultAddress < mapping.beginAddress) {
                insertionIndex = i;
                break;
            }
            if (faultAddress >= mapping.beginAddress && faultAddress < mapping.endAddress) {
                faultIndex = i;
                insertionIndex = i;
                break;
            }
        }

        int center = faultIndex >= 0
                ? faultIndex
                : Math.min(insertionIndex, mappings.length - 1);
        int start = Math.max(0, center - MAP_CONTEXT_LINES);
        int end = Math.min(mappings.length, center + MAP_CONTEXT_LINES + 1);

        StringBuilder builder = new StringBuilder();
        boolean markerPrinted = false;
        for (int i = start; i < end; i++) {
            if (faultIndex < 0 && i == insertionIndex) {
                builder.append("--->Fault address falls at 0x")
                        .append(formatPlainAddress(tombstone, faultAddress))
                        .append(" between mapped regions\n");
                markerPrinted = true;
            }
            builder.append(formatMapLine(tombstone, mappings[i], i == faultIndex));
        }
        if (faultIndex < 0 && !markerPrinted) {
            builder.append("--->Fault address falls at 0x")
                    .append(formatPlainAddress(tombstone, faultAddress));
            if (insertionIndex == 0) {
                builder.append(" before any mapped regions");
            } else {
                builder.append(" after any mapped regions");
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private static String formatMapLine(TombstoneProtos.Tombstone tombstone,
            TombstoneProtos.MemoryMapping mapping, boolean containsFault) {
        StringBuilder builder = new StringBuilder(containsFault ? "--->" : "    ");
        builder.append(formatMapAddress(tombstone, mapping.beginAddress))
                .append('-')
                .append(formatMapAddress(tombstone, mapping.endAddress - 1))
                .append(' ')
                .append(mapping.read ? 'r' : '-')
                .append(mapping.write ? 'w' : '-')
                .append(mapping.execute ? 'x' : '-')
                .append(String.format(Locale.US, "  %8x  %8x",
                        mapping.offset, mapping.endAddress - mapping.beginAddress));
        if (mapping.mappingName != null && !mapping.mappingName.isEmpty()) {
            builder.append("  ").append(mapping.mappingName);
        }
        builder.append('\n');
        return builder.toString();
    }

    private static String getReadWriteDesc(int arch, TombstoneProtos.Thread thread) {
        String registerName;
        long writeMask;
        switch (arch) {
            case TombstoneProtos.ARM32:
                registerName = "error_code";
                writeMask = 1L << 11;
                break;
            case TombstoneProtos.ARM64:
                registerName = "esr";
                writeMask = 1L << 6;
                break;
            case TombstoneProtos.X86:
            case TombstoneProtos.X86_64:
                registerName = "err";
                writeMask = 1L << 1;
                break;
            default:
                return "";
        }
        if (thread.registers == null) {
            return "";
        }
        for (TombstoneProtos.Register register : thread.registers) {
            if (!registerName.equals(register.name)) {
                continue;
            }
            return (register.u64 & writeMask) != 0 ? " (write)" : " (read)";
        }
        return "";
    }

    private static long untagAddress(int arch, long address) {
        if (arch == TombstoneProtos.ARM64) {
            return address & ((1L << 56) - 1);
        }
        return address;
    }

    private static String formatPlainAddress(TombstoneProtos.Tombstone tombstone, long address) {
        return String.format(Locale.US, "%0" + (pointerWidth(tombstone) * 2) + "x", address);
    }

    private static String formatMapAddress(TombstoneProtos.Tombstone tombstone, long address) {
        String formatted = formatPlainAddress(tombstone, address);
        return formatted.length() == 16
                ? formatted.substring(0, 8) + "'" + formatted.substring(8)
                : formatted;
    }

    private static int pointerWidth(TombstoneProtos.Tombstone tombstone) {
        switch (tombstone.arch) {
            case TombstoneProtos.ARM32:
            case TombstoneProtos.X86:
                return 4;
            case TombstoneProtos.ARM64:
            case TombstoneProtos.RISCV64:
            case TombstoneProtos.X86_64:
            default:
                return 8;
        }
    }
}
