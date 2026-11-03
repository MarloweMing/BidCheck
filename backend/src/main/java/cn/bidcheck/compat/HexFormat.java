package cn.bidcheck.compat;

/** Lowercase hexadecimal encoding, compatible with HexFormat.of(). */
public final class HexFormat {
    private static final HexFormat INSTANCE = new HexFormat();
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();
    private HexFormat() { }
    public static HexFormat of() { return INSTANCE; }
    public byte[] parseHex(String value) {
        if ((value.length() & 1) != 0) throw new IllegalArgumentException("odd hexadecimal length");
        byte[] output = new byte[value.length() / 2];
        for (int i=0;i<output.length;i++) {
            int high = digit(value.charAt(i*2)), low = digit(value.charAt(i*2+1));
            if (high<0 || low<0) throw new IllegalArgumentException("invalid hexadecimal digit");
            output[i] = (byte)((high << 4) | low);
        }
        return output;
    }
    private static int digit(char value) {
        if (value>='0' && value<='9') return value-'0';
        if (value>='a' && value<='f') return value-'a'+10;
        if (value>='A' && value<='F') return value-'A'+10;
        return -1;
    }
    public String formatHex(byte[] value) {
        char[] output = new char[value.length * 2];
        for (int i=0;i<value.length;i++) { output[i*2]=DIGITS[(value[i] & 255) >>> 4];output[i*2+1]=DIGITS[value[i] & 15]; }
        return new String(output);
    }
}
