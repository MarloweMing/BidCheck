package cn.bidcheck.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class Java8ApiTest {
    @TempDir Path directory;

    @Test void unicodeWhitespaceMatchesTheOriginalStringMethods() {
        assertTrue(Java8Api.isBlank("\u2003\t\r\n"));
        assertFalse(Java8Api.isBlank("\u00a0"));
        assertEquals("投标",Java8Api.strip("\u2003投标\u2003"));
        assertEquals("投标\u2003",Java8Api.stripLeading("\u2003投标\u2003"));
        assertEquals("\u2003投标",Java8Api.stripTrailing("\u2003投标\u2003"));
    }

    @Test void factoriesRejectNullAndDuplicatesAndRemainImmutable() {
        List<String> source=new ArrayList<String>(Arrays.asList("甲"));
        List<String> snapshot=Java8Api.listCopyOf(source);source.add("乙");
        assertEquals(Arrays.asList("甲"),snapshot);
        assertThrows(UnsupportedOperationException.class,()->snapshot.add("丙"));
        assertThrows(NullPointerException.class,()->Java8Api.listOf("甲",null));
        assertThrows(IllegalArgumentException.class,()->Java8Api.setOf("甲","甲"));
        assertThrows(IllegalArgumentException.class,()->Java8Api.mapOf("键",1,"键",2));
        assertEquals(1,Java8Api.setCopyOf(Arrays.asList("甲","甲")).size());
        List<String> streamed=Java8Api.toList(Stream.of("甲",null));
        assertNull(streamed.get(1));
        assertThrows(UnsupportedOperationException.class,()->streamed.clear());
    }

    @Test void boundedReadDoesNotConsumeBytesBeyondTheLimit() throws Exception {
        ByteArrayInputStream input=new ByteArrayInputStream(new byte[]{1,2,3,4});
        assertArrayEquals(new byte[]{1,2},Java8Api.readNBytes(input,2));
        assertEquals(3,input.read());
        assertArrayEquals(new byte[]{4},Java8Api.readNBytes(input,100));
        assertThrows(IllegalArgumentException.class,()->Java8Api.readNBytes(input,-1));
    }

    @Test void textFilesUseUtf8AndRejectMalformedData() throws Exception {
        Path path=directory.resolve("文本.txt");
        Java8Api.writeString(path,"投标复核");
        assertEquals("投标复核",Java8Api.readString(path));
        Files.write(path,new byte[]{(byte)0xc3,0x28});
        assertThrows(java.nio.charset.CharacterCodingException.class,()->Java8Api.readString(path));
        assertEquals("000f80ff",HexFormat.of().formatHex(new byte[]{0,15,(byte)128,(byte)255}));
        assertArrayEquals(new byte[]{0,15,(byte)128,(byte)255},HexFormat.of().parseHex("000F80ff"));
        assertThrows(IllegalArgumentException.class,()->HexFormat.of().parseHex("abc"));
        assertThrows(IllegalArgumentException.class,()->HexFormat.of().parseHex("\uFF11\uFF12"));
    }
}
