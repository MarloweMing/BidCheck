package cn.bidcheck.compat;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Java8ExtrasTest {
    @Test void linesKeepsUnicodeSeparatorsAndOmitsOnlyFinalEmptyLine() {
        assertEquals(Collections.emptyList(), Java8Extras.lines("").collect(Collectors.toList()));
        assertEquals(Arrays.asList("", "", ""), Java8Extras.lines("\r\n\n\r").collect(Collectors.toList()));
        assertEquals(Arrays.asList("first", "second", "third\u2028fourth\u0085fifth"), Java8Extras.lines("first\r\nsecond\rthird\u2028fourth\u0085fifth\n").collect(Collectors.toList()));
    }

    @Test void nullStreamsValidateBoundsAndRespectClose() throws Exception {
        OutputStream output = Java8Extras.nullOutputStream();
        output.write(new byte[3]);
        assertThrows(IndexOutOfBoundsException.class, () -> output.write(new byte[3], -1, 0));
        output.close();
        assertThrows(IOException.class, () -> output.write(0));
        InputStream input = Java8Extras.nullInputStream();
        assertEquals(-1, input.read());
        assertEquals(0, input.read(new byte[0]));
        input.close();
        assertThrows(IOException.class, input::read);
        java.io.Writer writer = Java8Extras.nullWriter();
        writer.write("discard");
        writer.close();
        assertThrows(IOException.class, () -> writer.write("closed"));
    }
}
