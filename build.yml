package io.github.yt2160hook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Minimal protobuf wire-format editor for the eight ClientAbrState quality fields we use. */
final class ProtoWire {
    private ProtoWire() { }

    static byte[] overrideQualityFields(byte[] input, int resolution) {
        if (input == null || resolution <= 0) return null;
        try {
            ByteArrayOutputStream preserved = new ByteArrayOutputStream(input.length + 40);
            int cursor = 0;
            boolean hasLastManualResolution = false;
            Integer viewportWidth = null;
            Integer viewportHeight = null;

            while (cursor < input.length) {
                int fieldStart = cursor;
                Varint key = readVarint(input, cursor);
                cursor = key.next;
                int fieldNumber = (int) (key.value >>> 3);
                int wireType = (int) (key.value & 7);
                if (fieldNumber <= 0) throw new IOException("Invalid protobuf field number");

                long varintValue = 0;
                boolean hasVarint = false;
                switch (wireType) {
                    case 0: {
                        Varint value = readVarint(input, cursor);
                        cursor = value.next;
                        varintValue = value.value;
                        hasVarint = true;
                        break;
                    }
                    case 1:
                        cursor = checkedAdvance(cursor, 8, input.length);
                        break;
                    case 2: {
                        Varint length = readVarint(input, cursor);
                        cursor = length.next;
                        if (length.value < 0 || length.value > Integer.MAX_VALUE) throw new IOException("Bad length");
                        cursor = checkedAdvance(cursor, (int) length.value, input.length);
                        break;
                    }
                    case 5:
                        cursor = checkedAdvance(cursor, 4, input.length);
                        break;
                    default:
                        // Group wire types are not expected in ClientAbrState. Do not risk
                        // corrupting an unfamiliar state: leave it untouched instead.
                        return null;
                }

                if (fieldNumber == 16) hasLastManualResolution = true;
                if (fieldNumber == 18 && hasVarint) viewportWidth = clampToInt(varintValue);
                if (fieldNumber == 19 && hasVarint) viewportHeight = clampToInt(varintValue);

                // Replace these known fields once at the end. Everything else is preserved byte-for-byte.
                if (!isReplacedQualityField(fieldNumber)) {
                    preserved.write(input, fieldStart, cursor - fieldStart);
                }
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream(preserved.size() + 48);
            preserved.writeTo(output);

            // If there was no previous menu selection, tell YouTube this is a fresh manual selection.
            if (!hasLastManualResolution) {
                writeVarintField(output, 13, 0); // timeSinceLastManualFormatSelectionMs
                writeVarintField(output, 14, 0); // lastManualDirection (sint32 zig-zag zero)
            }

            if (viewportWidth != null && viewportHeight != null) {
                int shortSide = Math.min(viewportWidth, viewportHeight);
                if (shortSide > 0 && shortSide < resolution) {
                    int newWidth = (int) Math.ceil((double) viewportWidth * resolution / shortSide);
                    int newHeight = (int) Math.ceil((double) viewportHeight * resolution / shortSide);
                    writeVarintField(output, 18, newWidth);
                    writeVarintField(output, 19, newHeight);
                } else {
                    writeVarintField(output, 18, viewportWidth);
                    writeVarintField(output, 19, viewportHeight);
                }
            }

            writeVarintField(output, 16, resolution); // lastManualSelectedResolution
            writeVarintField(output, 21, resolution); // stickyResolution
            writeVarintField(output, 26, 3);          // VIDEO_QUALITY_SETTING_ADVANCED_MENU
            writeVarintField(output, 30, 0);          // powerSaveMode=false
            return output.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isReplacedQualityField(int number) {
        return number == 16 || number == 21 || number == 26 || number == 30;
    }

    private static int checkedAdvance(int cursor, int count, int length) throws IOException {
        if (count < 0 || cursor > length - count) throw new IOException("Truncated protobuf field");
        return cursor + count;
    }

    private static int clampToInt(long value) {
        if (value < 0) return 0;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static Varint readVarint(byte[] data, int start) throws IOException {
        long result = 0;
        int shift = 0;
        int cursor = start;
        while (cursor < data.length && shift < 64) {
            int value = data[cursor++] & 0xff;
            result |= (long) (value & 0x7f) << shift;
            if ((value & 0x80) == 0) return new Varint(result, cursor);
            shift += 7;
        }
        throw new IOException("Malformed/truncated varint");
    }

    private static void writeVarintField(ByteArrayOutputStream out, int fieldNumber, long value) throws IOException {
        writeVarint(out, ((long) fieldNumber << 3));
        writeVarint(out, value);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) throws IOException {
        while ((value & ~0x7fL) != 0) {
            out.write((int) (value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static final class Varint {
        final long value;
        final int next;
        Varint(long value, int next) { this.value = value; this.next = next; }
    }
}
