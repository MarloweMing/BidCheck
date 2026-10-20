package cn.bidcheck.document.word;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static cn.bidcheck.document.word.WordRenderSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class WordRenderSupportTest {
    @TempDir Path temp;
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test void ordinaryDocxPassesBothModesWithoutChangingSource() throws Exception {
        Path source=temp.resolve("ordinary.docx");
        try(var doc=new XWPFDocument()) {
            doc.createParagraph().createRun().setText("合成正文，含安全的普通表格和文字。");
            doc.createTable(2,2).getRow(0).getCell(0).setText("姓名");
            try(OutputStream out=Files.newOutputStream(source)){doc.write(out);}
        }
        byte[] before=Files.readAllBytes(source);
        validateDocx(source);validateLegacyDocx(source);
        assertArrayEquals(before,Files.readAllBytes(source));
    }

    @Test void ordinaryHyperlinkAndAutomaticStyleSettingRemainSupported() throws Exception {
        Path source=packageWith(Map.of(
            "word/_rels/document.xml.rels",relationships("hyperlink","TargetMode='External' Target='https://example.test/reference'"),
            "word/settings.xml","<w:settings xmlns:w='"+W+"'><w:updateStyles w:val='true'/></w:settings>"));
        assertDoesNotThrow(()->validateDocx(source));
        assertDoesNotThrow(()->validateLegacyDocx(source));
    }

    @ParameterizedTest @ValueSource(strings={"image","attachedTemplate","oleObject"})
    void actualExternalResourcesStillBlocked(String type) throws Exception {
        Path source=packageWith(Map.of("word/_rels/document.xml.rels",relationships(type,"TargetMode='External' Target='file:///private'")));
        assertEquals("RESOURCE_BLOCKED",assertThrows(IOException.class,()->validateLegacyDocx(source)).getMessage());
    }

    @ParameterizedTest @ValueSource(strings={"word/embeddings/renamed.xml","word/activeX/activeX1.xml","customUI/customUI.xml","word/webExtensions/extension.xml"})
    void unsupportedActivePartsAreRejectedBeforeLegacySdkLoadOnly(String part) throws Exception {
        Path source=packageWith(Map.of(part,"<data/>"));
        assertDoesNotThrow(()->validateDocx(source),"Modern mode retains its existing SDK protection contract.");
        assertEquals("WORD_LEGACY_ACTIVE_CONTENT_UNSUPPORTED",assertThrows(IOException.class,()->validateLegacyDocx(source)).getMessage());
    }

    @ParameterizedTest @ValueSource(strings={"oleObject","control","activeXControl","package","aFChunk","ui/extensibility"})
    void renamedActivePayloadCannotBypassRelationshipCheck(String type) throws Exception {
        Path source=packageWith(Map.of("word/_rels/document.xml.rels",relationships(type,"Target='unremarkable.xml'"),"word/unremarkable.xml","<data/>"));
        assertEquals("WORD_LEGACY_ACTIVE_CONTENT_UNSUPPORTED",assertThrows(IOException.class,()->validateLegacyDocx(source)).getMessage());
    }

    @ParameterizedTest @ValueSource(strings={"application/vnd.ms-office.oleObject","application/vnd.ms-office.activeX+xml","application/vnd.ms-word.document.macroEnabled.main+xml"})
    void activeContentTypeCannotHideBehindXmlPartName(String contentType) throws Exception {
        Path source=packageWith(Map.of("[Content_Types].xml","<Types><Override PartName='/word/innocent.xml' ContentType='"+contentType+"'/></Types>","word/innocent.xml","<data/>"));
        assertEquals("WORD_LEGACY_ACTIVE_CONTENT_UNSUPPORTED",assertThrows(IOException.class,()->validateLegacyDocx(source)).getMessage());
    }

    @ParameterizedTest @ValueSource(strings={"object","OLEObject","control","controls","altChunk","ffData"})
    void activeObjectMarkupWithoutConventionalPackagePartIsRejected(String element) throws Exception {
        Path source=packageWith(Map.of("word/document.xml",document("<w:"+element+"/>")));
        assertEquals("WORD_LEGACY_ACTIVE_CONTENT_UNSUPPORTED",assertThrows(IOException.class,()->validateLegacyDocx(source)).getMessage());
    }

    @ParameterizedTest @ValueSource(strings={"FORMTEXT","FORMCHECKBOX","FORMDROPDOWN","MACROBUTTON"})
    void simpleInteractiveFieldIsRejected(String field) throws Exception {
        Path source=packageWith(Map.of("word/document.xml",document("<w:fldSimple w:instr=' "+field+" '/>")));
        assertEquals("WORD_LEGACY_FORM_UNSUPPORTED",assertThrows(IOException.class,()->validateLegacyDocx(source)).getMessage());
    }

    @Test void splitComplexFieldInstructionCannotBypassProtection() throws Exception {
        String fields="<w:r><w:fldChar w:fldCharType='begin'/></w:r><w:r><w:instrText> FORM</w:instrText></w:r>"
            +"<w:r><w:instrText>TEXT </w:instrText></w:r><w:r><w:fldChar w:fldCharType='separate'/></w:r>"
            +"<w:r><w:t>cached field result</w:t></w:r><w:r><w:fldChar w:fldCharType='end'/></w:r>";
        Path source=packageWith(Map.of("word/document.xml",document(fields)));
        assertEquals("WORD_LEGACY_FORM_UNSUPPORTED",assertThrows(IOException.class,()->validateLegacyDocx(source)).getMessage());
    }

    @Test void ordinaryTextMentioningFieldsIsNotActiveContent() throws Exception {
        Path source=packageWith(Map.of("word/document.xml",document("<w:r><w:t>FORMTEXT 和 OLEObject 是说明文字，不是字段。</w:t></w:r><w:fldSimple w:instr=' PAGE '/>")));
        assertDoesNotThrow(()->validateLegacyDocx(source));
    }

    @Test void ordinaryPrinterMetadataAllowedButUnknownBinaryPayloadRejected() throws Exception {
        Path safe=packageWith(Map.of("word/printerSettings/printerSettings1.bin","inert printer settings"));
        assertDoesNotThrow(()->validateLegacyDocx(safe));
        Path opaque=packageWith(Map.of("word/innocent.bin","opaque payload"));
        assertEquals("WORD_LEGACY_EMBEDDED_CONTENT_UNSUPPORTED",assertThrows(IOException.class,()->validateLegacyDocx(opaque)).getMessage());
    }

    @Test void encodedPartAliasAndUnsafeXmlStillFailClosed() throws Exception {
        Path encoded=packageWith(Map.of("word/%65mbeddings/payload.xml","<data/>"));
        assertEquals("WORD_LEGACY_PACKAGE_UNSUPPORTED",assertThrows(IOException.class,()->validateLegacyDocx(encoded)).getMessage());
        Path xxe=packageWith(Map.of("word/document.xml","<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///private'>]><a>&x;</a>"));
        assertEquals("WORD_XML_UNSAFE",assertThrows(IOException.class,()->validateLegacyDocx(xxe)).getMessage());
    }

    private static String document(String body){return "<w:document xmlns:w='"+W+"'><w:body><w:p>"+body+"</w:p></w:body></w:document>";}
    private static String relationships(String type,String target){return "<Relationships><Relationship Id='rId1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/"+type+"' "+target+"/></Relationships>";}
    private Path packageWith(Map<String,String> additions)throws IOException{
        var entries=new LinkedHashMap<String,String>();entries.put("word/document.xml",document("<w:r><w:t>合成正文</w:t></w:r>"));entries.putAll(additions);
        Path source=Files.createTempFile(temp,"package-",".docx");
        try(var zip=new ZipOutputStream(Files.newOutputStream(source))){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
        return source;
    }
}
