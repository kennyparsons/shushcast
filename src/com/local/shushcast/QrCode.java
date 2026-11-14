package com.local.shushcast;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class QrCode {
    private static final int[] GF_EXP = new int[512];
    private static final int[] GF_LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            GF_EXP[i] = x;
            GF_LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) {
            GF_EXP[i] = GF_EXP[i - 255];
        }
    }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return GF_EXP[GF_LOG[a] + GF_LOG[b]];
    }

    private static int[] rsGenerator(int degree) {
        int[] gen = new int[degree + 1];
        gen[0] = 1;
        for (int i = 0; i < degree; i++) {
            int root = GF_EXP[i];
            for (int j = i + 1; j >= 1; j--) {
                gen[j] = gen[j] ^ gfMul(gen[j - 1], root);
            }
        }
        return gen;
    }

    private static byte[] rsRemainder(byte[] data, int ecCount) {
        int[] gen = rsGenerator(ecCount);
        int[] rem = new int[ecCount];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ rem[0];
            System.arraycopy(rem, 1, rem, 0, ecCount - 1);
            rem[ecCount - 1] = 0;
            for (int j = 0; j < ecCount; j++) {
                rem[j] ^= gfMul(gen[j + 1], factor);
            }
        }
        byte[] out = new byte[ecCount];
        for (int i = 0; i < ecCount; i++) out[i] = (byte) rem[i];
        return out;
    }

    public static Bitmap encodeBitmap(String text, int pxSize) {
        boolean[][] m = encodeMatrix(text);
        int n = m.length;
        int quiet = 4;
        int total = n + quiet * 2;
        int scale = Math.max(1, pxSize / total);
        int imgSize = total * scale;
        int[] pixels = new int[imgSize * imgSize];
        Arrays.fill(pixels, Color.WHITE);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (m[y][x]) {
                    int py0 = (y + quiet) * scale;
                    int px0 = (x + quiet) * scale;
                    for (int dy = 0; dy < scale; dy++) {
                        int row = (py0 + dy) * imgSize + px0;
                        for (int dx = 0; dx < scale; dx++) {
                            pixels[row + dx] = Color.BLACK;
                        }
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, imgSize, imgSize, Bitmap.Config.ARGB_8888);
    }

    public static boolean[][] encodeMatrix(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int version;
        int ecLevelBits; // M = 0, L = 1
        int dataCodewords;
        int ecCodewords;

        if (utf8.length <= 26) {
            version = 2; ecLevelBits = 0; dataCodewords = 28; ecCodewords = 16; // V2-M
        } else if (utf8.length <= 42) {
            version = 3; ecLevelBits = 0; dataCodewords = 44; ecCodewords = 26; // V3-M
        } else if (utf8.length <= 78) {
            version = 4; ecLevelBits = 1; dataCodewords = 80; ecCodewords = 20; // V4-L
        } else {
            throw new IllegalArgumentException("URL too long for QR V4-L");
        }

        byte[] data = new byte[dataCodewords];
        int bitPos = 0;
        bitPos = appendBits(data, bitPos, 0b0100, 4); // Byte mode
        bitPos = appendBits(data, bitPos, utf8.length, 8);
        for (byte b : utf8) {
            bitPos = appendBits(data, bitPos, b & 0xFF, 8);
        }
        int remBits = dataCodewords * 8 - bitPos;
        bitPos = appendBits(data, bitPos, 0, Math.min(4, remBits));
        if ((bitPos & 7) != 0) {
            bitPos += 8 - (bitPos & 7);
        }
        int byteIdx = bitPos / 8;
        boolean alt = true;
        while (byteIdx < dataCodewords) {
            data[byteIdx++] = (byte) (alt ? 0xEC : 0x11);
            alt = !alt;
        }

        byte[] ec = rsRemainder(data, ecCodewords);
        byte[] all = new byte[dataCodewords + ecCodewords];
        System.arraycopy(data, 0, all, 0, dataCodewords);
        System.arraycopy(ec, 0, all, dataCodewords, ecCodewords);

        int size = version * 4 + 17;
        boolean[][] isFunc = new boolean[size][size];
        boolean[][] modules = new boolean[size][size];

        drawFinder(modules, isFunc, 0, 0);
        drawFinder(modules, isFunc, size - 7, 0);
        drawFinder(modules, isFunc, 0, size - 7);
        drawAlignment(modules, isFunc, size - 7, size - 7);

        for (int i = 8; i < size - 8; i++) {
            setFunc(modules, isFunc, 6, i, i % 2 == 0);
            setFunc(modules, isFunc, i, 6, i % 2 == 0);
        }
        setFunc(modules, isFunc, 8, size - 8, true); // dark module
        // Reserve format info cells
        for (int i = 0; i <= 8; i++) {
            if (i != 6) {
                isFunc[8][i] = true;
                isFunc[i][8] = true;
            }
        }
        for (int i = size - 8; i < size; i++) isFunc[8][i] = true;
        for (int i = size - 7; i < size; i++) isFunc[i][8] = true;

        // Place data bits (up/down 2-column zig-zag)
        int totalBits = all.length * 8;
        int bitIdx = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunc[y][x] && bitIdx < totalBits) {
                        boolean bit = ((all[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1) != 0;
                        modules[y][x] = bit;
                        bitIdx++;
                    }
                }
            }
        }

        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(modules, isFunc, mask);
            drawFormatBits(modules, isFunc, ecLevelBits, mask);
            int p = penaltyScore(modules);
            if (p < bestPenalty) {
                bestPenalty = p;
                bestMask = mask;
            }
            applyMask(modules, isFunc, mask);
        }
        applyMask(modules, isFunc, bestMask);
        drawFormatBits(modules, isFunc, ecLevelBits, bestMask);
        return modules;
    }

    private static int appendBits(byte[] buf, int bitPos, int val, int len) {
        for (int i = len - 1; i >= 0; i--) {
            if (((val >>> i) & 1) != 0) {
                buf[bitPos >>> 3] |= (byte) (1 << (7 - (bitPos & 7)));
            }
            bitPos++;
        }
        return bitPos;
    }

    private static void setFunc(boolean[][] m, boolean[][] f, int x, int y, boolean dark) {
        m[y][x] = dark;
        f[y][x] = true;
    }

    private static void drawFinder(boolean[][] m, boolean[][] f, int x0, int y0) {
        int size = m.length;
        for (int dy = -1; dy <= 7; dy++) {
            for (int dx = -1; dx <= 7; dx++) {
                int x = x0 + dx, y = y0 + dy;
                if (x < 0 || x >= size || y < 0 || y >= size) continue;
                int dist = Math.max(Math.abs(dx - 3), Math.abs(dy - 3));
                boolean dark = dist != 2 && dist != 4;
                setFunc(m, f, x, y, dark);
            }
        }
    }

    private static void drawAlignment(boolean[][] m, boolean[][] f, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFunc(m, f, cx + dx, cy + dy, dist != 1);
            }
        }
    }

    private static void drawFormatBits(boolean[][] m, boolean[][] f, int ecLevelBits, int mask) {
        int data = (ecLevelBits << 3) | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        int size = m.length;
        for (int i = 0; i <= 5; i++) setFunc(m, f, 8, i, ((bits >>> i) & 1) != 0);
        setFunc(m, f, 8, 7, ((bits >>> 6) & 1) != 0);
        setFunc(m, f, 8, 8, ((bits >>> 7) & 1) != 0);
        setFunc(m, f, 7, 8, ((bits >>> 8) & 1) != 0);
        for (int i = 9; i < 15; i++) setFunc(m, f, 14 - i, 8, ((bits >>> i) & 1) != 0);

        for (int i = 0; i < 8; i++) setFunc(m, f, size - 1 - i, 8, ((bits >>> i) & 1) != 0);
        for (int i = 8; i < 15; i++) setFunc(m, f, 8, size - 15 + i, ((bits >>> i) & 1) != 0);
        setFunc(m, f, 8, size - 8, true);
    }

    private static void applyMask(boolean[][] m, boolean[][] f, int mask) {
        int size = m.length;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (f[y][x]) continue;
                boolean inv;
                switch (mask) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv) m[y][x] = !m[y][x];
            }
        }
    }

    private static int penaltyScore(boolean[][] m) {
        int size = m.length;
        int pen = 0;
        for (int y = 0; y < size; y++) {
            int run = 1;
            for (int x = 1; x < size; x++) {
                if (m[y][x] == m[y][x - 1]) {
                    if (++run == 5) pen += 3;
                    else if (run > 5) pen += 1;
                } else run = 1;
            }
        }
        for (int x = 0; x < size; x++) {
            int run = 1;
            for (int y = 1; y < size; y++) {
                if (m[y][x] == m[y - 1][x]) {
                    if (++run == 5) pen += 3;
                    else if (run > 5) pen += 1;
                } else run = 1;
            }
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = m[y][x];
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) pen += 3;
            }
        }
        return pen;
    }
}
