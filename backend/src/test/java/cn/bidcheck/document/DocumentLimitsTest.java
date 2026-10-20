package cn.bidcheck.document;

import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentLimitsTest {
    @Test void streamsExactlyOneGigabyteAndRejectsOneAdditionalByteWithoutAllocatingTheFile() throws Exception {
        assertEquals(1024L * 1024 * 1024, DocumentLimits.MAX_FILE_BYTES);
        assertEquals(DocumentLimits.MAX_FILE_BYTES,DocumentLimits.copy(countingInput(DocumentLimits.MAX_FILE_BYTES),
                OutputStream.nullOutputStream(),DocumentLimits.MAX_FILE_BYTES));
        assertThrows(IOException.class,()->DocumentLimits.copy(countingInput(DocumentLimits.MAX_FILE_BYTES+1),
                OutputStream.nullOutputStream(),DocumentLimits.MAX_FILE_BYTES));
    }
    private static InputStream countingInput(long bytes) {
        return new InputStream() {
            private long remaining=bytes;
            @Override public int read() { if(remaining==0)return -1;remaining--;return 0; }
            @Override public int read(byte[] buffer,int offset,int length) {
                if(remaining==0)return -1;int count=(int)Math.min(remaining,length);remaining-=count;return count;
            }
        };
    }
    @Test void aStreamCannotBypassTheAdvertisedLimit() throws Exception {
        byte[] data = new byte[64 * 1024 + 1];
        var out = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> DocumentLimits.copy(new ByteArrayInputStream(data), out, 64 * 1024));
        assertEquals(64 * 1024, out.size());
        assertEquals(64 * 1024, DocumentLimits.copy(new ByteArrayInputStream(new byte[64 * 1024]), OutputStream.nullOutputStream(), 64 * 1024));
    }
}
