package com.resqai.service;

/** Converts raw PCM16 frames into a WAV container for the STT provider. */
public final class AudioWavUtil {

    private AudioWavUtil() {}

    /** PCM16 signed little-endian mono samples -> complete WAV file bytes. */
    public static byte[] pcm16ToWav(byte[] pcm, int sampleRate, int channels) {
        int dataLen = pcm.length;
        int totalLen = 36 + dataLen;
        byte[] out = new byte[44 + dataLen];
        // RIFF header
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        writeInt(out, 4, totalLen);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        writeInt(out, 16, 16);                       // PCM chunk size
        out[20] = 1; out[21] = 0;                    // PCM format
        out[22] = (byte) channels; out[23] = 0;
        writeInt(out, 24, sampleRate);
        writeInt(out, 28, sampleRate * channels * 2); // byte rate
        out[32] = (byte) (channels * 2); out[33] = 0; // block align
        out[34] = 16; out[35] = 0;                    // bits per sample
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';
        writeInt(out, 40, dataLen);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    private static void writeInt(byte[] b, int off, int value) {
        b[off] = (byte) value;
        b[off + 1] = (byte) (value >> 8);
        b[off + 2] = (byte) (value >> 16);
        b[off + 3] = (byte) (value >> 24);
    }
}
