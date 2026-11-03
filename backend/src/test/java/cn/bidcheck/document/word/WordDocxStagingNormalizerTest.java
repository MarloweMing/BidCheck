package cn.bidcheck.document.word;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static cn.bidcheck.document.word.WordRenderSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class WordDocxStagingNormalizerTest {
    @TempDir Path temp;
    private static final String URI="http://schemas.openxmlformats.org/officeDocument/2006/bibliography";
    private static final String DECL="xmlns:b=\""+URI+"\"";
    private static final String BODY="<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>正文完全保留</w:t></w:r></w:p></w:body></w:document>";

    @Test void knownMetadataRepairChangesOnlyTheDuplicateDeclarationAndKeepsOriginalHash()throws Exception{
        String xml="\ufeff<?xml version='1.0' encoding='UTF-8'?><b:Sources "+DECL+" "+DECL+" Version='6'><b:Source>中文与标点、原样保留</b:Source></b:Sources>";
        Path source=archive(cn.bidcheck.compat.Java8Api.mapOf("customXml/item2.xml", xml));byte[] original=Files.readAllBytes(source);
        Path target=temp.resolve("normalized.docx");cn.bidcheck.document.word.WordDocxStagingNormalizer.Result result=WordDocxStagingNormalizer.normalize(source,target);
        assertArrayEquals(original,Files.readAllBytes(source));assertEquals(hash(original),result.sourceSha256());
        assertEquals(hash(Files.readAllBytes(target)),result.normalizedSha256());assertEquals(target.toAbsolutePath(),result.normalizedPath());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("customXml/item2.xml"),result.normalizedParts());
        assertEquals(xml.replace(" "+DECL+" "+DECL," "+DECL),read(target,"customXml/item2.xml"));
        assertEquals(BODY,read(target,"word/document.xml"));assertDoesNotThrow(()->validateLegacyDocx(target));
    }

    @Test void mixedQuoteStyleIsAllowedOnlyForExactlyEqualNamespaceUris()throws Exception{
        Path source=archive(cn.bidcheck.compat.Java8Api.mapOf("customXml/item2.xml", "<b:Sources "+DECL+" xmlns:b='"+URI+"'/>"));
        cn.bidcheck.document.word.WordDocxStagingNormalizer.Result result=WordDocxStagingNormalizer.normalize(source,temp.resolve("normalized.docx"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("customXml/item2.xml"),result.normalizedParts());
        assertEquals("<b:Sources "+DECL+"/>",read(result.normalizedPath(),"customXml/item2.xml"));
    }

    @ParameterizedTest @ValueSource(strings={
        "<b:Sources xmlns:b='URI' xmlns:b='urn:conflict'/>",
        "<b:Sources xmlns:b='URI' xmlns:b='URI' Version='1' Version='1'/>",
        "<x:Sources xmlns:x='URI' xmlns:x='URI'/>",
        "<b:Other xmlns:b='URI' xmlns:b='URI'/>",
        "<b:Sources xmlns:b='URI' xmlns:b='URI'><broken></b:Sources>"})
    void conflictingNamespacesOtherDuplicatesAndUnreviewedRepairsRemainErrors(String value)throws Exception{
        Path source=archive(cn.bidcheck.compat.Java8Api.mapOf("customXml/item2.xml", value.replace("URI",URI)));byte[] before=Files.readAllBytes(source);Path target=temp.resolve("normalized.docx");
        assertThrows(IOException.class,()->WordDocxStagingNormalizer.normalize(source,target));
        assertFalse(Files.exists(target));assertArrayEquals(before,Files.readAllBytes(source));assertNoTemporaryOutput();
    }

    @ParameterizedTest @ValueSource(strings={"word/document.xml","customXml/not-an-item.xml","customXml/item0.xml"})
    void sameTextOutsideTheExistingMetadataAllowlistIsNeverRepaired(String part)throws Exception{
        Path source=archive(cn.bidcheck.compat.Java8Api.mapOf(part, "<b:Sources "+DECL+" "+DECL+"/>"));Path target=temp.resolve("normalized.docx");
        assertThrows(IOException.class,()->WordDocxStagingNormalizer.normalize(source,target));assertFalse(Files.exists(target));
    }

    @Test void namespaceLookingTextInsideAnOrdinaryAttributeIsNotRemoved()throws Exception{
        String xml="<b:Sources "+DECL+" Note=' "+DECL+" "+DECL+" '/>";
        Path source=archive(cn.bidcheck.compat.Java8Api.mapOf("customXml/item2.xml", xml));
        cn.bidcheck.document.word.WordDocxStagingNormalizer.Result result=WordDocxStagingNormalizer.normalize(source,temp.resolve("normalized.docx"));
        assertTrue(result.normalizedParts().isEmpty());assertEquals(xml,read(result.normalizedPath(),"customXml/item2.xml"));
    }

    @Test void dtdAndExternalEntitiesCannotBeMadeSafeByNamespaceRepair()throws Exception{
        String xml="<!DOCTYPE b:Sources [<!ENTITY x SYSTEM 'file:///private'>]><b:Sources "+DECL+" "+DECL+">&x;</b:Sources>";
        Path source=archive(cn.bidcheck.compat.Java8Api.mapOf("customXml/item2.xml", xml));Path target=temp.resolve("normalized.docx");
        assertEquals("WORD_XML_UNSAFE",assertThrows(IOException.class,()->WordDocxStagingNormalizer.normalize(source,target)).getMessage());
        assertFalse(Files.exists(target));assertNoTemporaryOutput();
    }

    @Test void sourceAndAnyExistingOutputAreNeverOverwritten()throws Exception{
        Path source=archive(cn.bidcheck.compat.Java8Api.mapOf());byte[] before=Files.readAllBytes(source);
        assertEquals("WORD_STAGING_PATH_INVALID",assertThrows(IOException.class,()->WordDocxStagingNormalizer.normalize(source,source)).getMessage());
        Path existing=temp.resolve("existing.docx");cn.bidcheck.compat.Java8Api.writeString(existing, "keep existing");
        assertThrows(IOException.class,()->WordDocxStagingNormalizer.normalize(source,existing));
        assertEquals("keep existing",cn.bidcheck.compat.Java8Api.readString(existing));assertArrayEquals(before,Files.readAllBytes(source));
    }

    @Test void packageTraversalAndCompressionBombAreStillRejected()throws Exception{
        Path traversal=archive(cn.bidcheck.compat.Java8Api.mapOf("../outside.xml", "<data/>"));Path target=temp.resolve("normalized.docx");
        assertEquals("WORD_PACKAGE_LIMIT",assertThrows(IOException.class,()->WordDocxStagingNormalizer.normalize(traversal,target)).getMessage());
        Path bomb=archive(cn.bidcheck.compat.Java8Api.mapOf("word/large.xml", "<data>"+cn.bidcheck.compat.Java8Api.repeat("x", 2_000_000)+"</data>"));
        assertEquals("WORD_PACKAGE_LIMIT",assertThrows(IOException.class,()->WordDocxStagingNormalizer.normalize(bomb,target)).getMessage());
        assertFalse(Files.exists(target));assertNoTemporaryOutput();
    }

    @Test void cancellationIsPropagatedWithoutPublishingAnOutput()throws Exception{
        Path source=archive(cn.bidcheck.compat.Java8Api.mapOf());Path target=temp.resolve("normalized.docx");
        try{Thread.currentThread().interrupt();assertThrows(InterruptedIOException.class,()->WordDocxStagingNormalizer.normalize(source,target));assertTrue(Thread.currentThread().isInterrupted());}
        finally{Thread.interrupted();}
        assertFalse(Files.exists(target));
    }

    private void assertNoTemporaryOutput()throws IOException{try(java.util.stream.Stream<java.nio.file.Path> files=Files.list(temp)){assertTrue(files.noneMatch(p->p.getFileName().toString().startsWith("word-normalized-")));}}
    private static String read(Path path,String part)throws IOException{try(java.util.zip.ZipFile zip=new ZipFile(path.toFile());java.io.InputStream input=zip.getInputStream(zip.getEntry(part))){return new String(cn.bidcheck.compat.Java8Api.readAllBytes(input),StandardCharsets.UTF_8);}}
    private Path archive(Map<String,String> additions)throws IOException{
        Map<String,String> entries=new LinkedHashMap<>();entries.put("[Content_Types].xml","<Types/>");entries.put("word/document.xml",BODY);entries.putAll(additions);
        Path source=Files.createTempFile(temp,"original-",".docx");
        try(java.util.zip.ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(source))){for(java.util.Map.Entry<java.lang.String, java.lang.String> entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
        return source;
    }
}
