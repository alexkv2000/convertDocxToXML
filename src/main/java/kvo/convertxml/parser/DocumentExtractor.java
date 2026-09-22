package kvo.convertxml.parser;

import java.nio.charset.StandardCharsets;

public final class DocumentExtractor {
    public enum SourceFormat { DOC, DOCX, PDF, RTF, HTML, UNKNOWN }
    private DocumentExtractor() {}
    /** Определение формата по содержимому (magic bytes), не по расширению. */
    public static SourceFormat sniff(byte[] d) {
        if (d == null || d.length == 0) return SourceFormat.UNKNOWN;
        if (startsWith(d, new byte[]{(byte)0xD0,(byte)0xCF,0x11,(byte)0xE0,
                (byte)0xA1,(byte)0xB1,0x1A,(byte)0xE1})) return SourceFormat.DOC;
        if (startsWith(d, "PK"))     return SourceFormat.DOCX;
        if (startsWith(d, "%PDF"))   return SourceFormat.PDF;
        if (startsWith(d, "{\\rtf")) return SourceFormat.RTF;
        if (startsWith(d, "<html") || startsWith(d, "<!DOCTYPE")) return SourceFormat.HTML;
        return SourceFormat.UNKNOWN;
    }
    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
    private static boolean startsWith(byte[] data, String prefix) {
        return startsWith(data, prefix.getBytes(StandardCharsets.US_ASCII));
    }
}