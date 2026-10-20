package cn.bidcheck.document.word;

import cn.bidcheck.document.DocumentLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.stream.*;

final class WordRenderSupport {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String POLICY="word-render-2-legacy-preflight";
    static final long MAX_PDF_BYTES=128L*1024*1024;
    static final int MAX_RESULT_BYTES=16*1024*1024,MAX_SPANS=50_000,MAX_TEXT=2_000_000;
    private static final java.util.regex.Pattern LEGACY_ACTIVE_FIELD=java.util.regex.Pattern.compile("(?i)(?:^|[^A-Z0-9_])(?:FORMTEXT|FORMCHECKBOX|FORMDROPDOWN|MACROBUTTON)(?:$|[^A-Z0-9_])");
    static Map<String,Object> map(Object... kv){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    static String str(Map<?,?> m,String k){return Objects.toString(m.get(k),"");}
    static String hash(String value){return hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static String hash(Path path,long maximum)throws IOException{try(InputStream in=Files.newInputStream(path)){return digest(in,null,maximum);}}
    static String digest(InputStream in,OutputStream out,long maximum)throws IOException{
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){interrupted();if((count+=n)>maximum)throw new IOException("WORD_FILE_LIMIT");digest.update(b,0,n);if(out!=null)out.write(b,0,n);}return HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static void interrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("WORD_RENDER_INTERRUPTED");}
    static Map<String,Object> failure(String code){return map("status",code,"errorCode",code,"usableForEvidence",false,"pageCount",0,"pages",List.of(),"spans",List.of(),"renderedSpans",List.of(),"limitations",List.of(message(code)));}
    static String message(String code){return switch(code){case "LICENSE_REQUIRED"->"尚未配置 Aspose.Words 许可证，继续使用原生 Word 解析；评估版内容不进入正式证据。";case "LICENSE_INVALID"->"Word 渲染许可证未通过校验，未生成正式派生证据。";case "FONT_SUBSTITUTION"->"渲染发生字体替代，可能改变分页和字形；派生内容未用于正式证据。";case "RESOURCE_BLOCKED"->"文档依赖外部资源，已禁止读取；需嵌入完整资源后再渲染。";case "REVISIONS_PRESENT"->"文档含未确定的修订，不能自动选择最终正文并投入正式渲染证据。";case "TIMEOUT"->"Word 渲染超过时限，工作进程已终止。";case "BUSY"->"Word 渲染当前繁忙，请稍后重试。";case "DISABLED"->"Word 分页渲染未启用，原生解析仍可使用。";default->"Word 渲染未完成，继续保留原生解析与原件；不能据此判断原文缺失。";};}
    static void validateDocx(Path source)throws IOException{validateDocx(source,false);}
    /** Older SDKs lack OLE-ignore and form-script controls. Reject those features before SDK load.
     * Ordinary links remain inert and external resource loads are still blocked by the worker callback. */
    static void validateLegacyDocx(Path source)throws IOException{validateDocx(source,true);}
    private static void validateDocx(Path source,boolean legacy)throws IOException{
        long total=0;int count=0;boolean main=false;Set<String> names=new HashSet<>();
        try(ZipFile zip=new ZipFile(source.toFile())){var entries=zip.entries();while(entries.hasMoreElements()){
            interrupted();ZipEntry entry=entries.nextElement();String name=entry.getName();if(++count>2000||!names.add(name)||name.contains("..")||name.startsWith("/")||name.contains("\\"))throw new IOException("WORD_PACKAGE_LIMIT");
            if(entry.isDirectory())continue;if(name.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin"))throw new IOException("WORD_MACROS_UNSUPPORTED");
            if(legacy)validateLegacyPart(name);
            if(entry.getSize()>32L*1024*1024||(entry.getSize()>1_000_000&&entry.getCompressedSize()>0&&entry.getSize()/entry.getCompressedSize()>200))throw new IOException("WORD_PACKAGE_LIMIT");
            try(InputStream in=zip.getInputStream(entry)){byte[] bytes=in.readNBytes(32*1024*1024+1);total+=bytes.length;if(bytes.length>32*1024*1024||total>80L*1024*1024)throw new IOException("WORD_PACKAGE_LIMIT");
                if(name.equals("word/document.xml"))main=true;
                String xmlName=legacy?name.toLowerCase(Locale.ROOT):name;
                if(xmlName.endsWith(".xml")||xmlName.endsWith(".rels"))validateXml(bytes,xmlName.endsWith(".rels"),legacy);
                if(name.startsWith("word/media/"))imageBudget(bytes);
            }
        }}if(!main)throw new IOException("WORD_MAIN_PART_MISSING");
    }
    private static void imageBudget(byte[] bytes)throws IOException{
        try(var input=javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){if(input==null)return;var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())return;var reader=readers.next();try{reader.setInput(input,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>32768||h>32768||(long)w*h>16_000_000)throw new IOException("WORD_IMAGE_LIMIT");}finally{reader.dispose();}}
    }
    static void validateXml(byte[] bytes,boolean relationships)throws IOException{validateXml(bytes,relationships,false);}
    private static void validateXml(byte[] bytes,boolean relationships,boolean legacy)throws IOException{
        XMLStreamReader reader=null;try{XMLInputFactory f=XMLInputFactory.newFactory();f.setProperty(XMLInputFactory.SUPPORT_DTD,false);f.setProperty("javax.xml.stream.isSupportingExternalEntities",false);reader=f.createXMLStreamReader(new ByteArrayInputStream(bytes));int depth=0,nodes=0,chars=0;
            StringBuilder instruction=new StringBuilder();int instructionDepth=-1;
            while(reader.hasNext()){interrupted();int event=reader.next();if(event==XMLStreamConstants.DTD||event==XMLStreamConstants.ENTITY_REFERENCE)throw new IOException("WORD_XML_UNSAFE");if(event==XMLStreamConstants.START_ELEMENT){if(++depth>64||++nodes>200_000||reader.getAttributeCount()>100)throw new IOException("WORD_XML_LIMIT");if(relationships&&reader.getLocalName().equals("Relationship")&&"External".equalsIgnoreCase(reader.getAttributeValue(null,"TargetMode"))){String type=reader.getAttributeValue(null,"Type");if(type==null||!type.endsWith("/hyperlink"))throw new IOException("RESOURCE_BLOCKED");}
                    if(legacy){validateLegacyElement(reader,relationships);if(reader.getLocalName().equals("fldChar")){validateLegacyInstruction(instruction.toString());instruction.setLength(0);}if(reader.getLocalName().equals("instrText"))instructionDepth=depth;}
                }
                if(event==XMLStreamConstants.CHARACTERS||event==XMLStreamConstants.CDATA){if((chars+=reader.getTextLength())>MAX_TEXT)throw new IOException("WORD_XML_LIMIT");if(legacy&&instructionDepth>=0)instruction.append(reader.getText());}
                if(event==XMLStreamConstants.END_ELEMENT){if(depth==instructionDepth)instructionDepth=-1;depth--;}
            }
            if(legacy)validateLegacyInstruction(instruction.toString());
        }catch(XMLStreamException e){throw new IOException("WORD_XML_UNSAFE");}finally{if(reader!=null)try{reader.close();}catch(XMLStreamException ignored){}}
    }
    private static void validateLegacyPart(String name)throws IOException{
        String lower=name.toLowerCase(Locale.ROOT);
        // Encoded/ambiguous OPC names can conceal a part from a path-based security check.
        if(lower.indexOf('%')>=0||lower.indexOf(':')>=0||lower.indexOf('?')>=0||lower.indexOf('#')>=0||lower.chars().anyMatch(c->c<32))throw new IOException("WORD_LEGACY_PACKAGE_UNSUPPORTED");
        if(lower.contains("vba")||lower.contains("/embeddings/")||lower.startsWith("embeddings/")||lower.contains("/activex/")||lower.startsWith("activex/")
                ||lower.startsWith("customui/")||lower.contains("/customui/")||lower.contains("/webextensions/")||lower.endsWith(".ocx"))throw new IOException("WORD_LEGACY_ACTIVE_CONTENT_UNSUPPORTED");
        // Printer settings are inert package metadata; other opaque binary payloads are not verified.
        if(lower.endsWith(".bin")&&!lower.startsWith("word/printersettings/"))throw new IOException("WORD_LEGACY_EMBEDDED_CONTENT_UNSUPPORTED");
    }
    private static void validateLegacyElement(XMLStreamReader reader,boolean relationships)throws IOException{
        String local=reader.getLocalName().toLowerCase(Locale.ROOT);
        if(Set.of("object","oleobject","control","controls","ocx","customui","webextension","altchunk","ffdata").contains(local))throw new IOException("WORD_LEGACY_ACTIVE_CONTENT_UNSUPPORTED");
        if(relationships&&local.equals("relationship")){
            String type=Objects.toString(reader.getAttributeValue(null,"Type"),"").toLowerCase(Locale.ROOT);
            if(activeType(type))throw new IOException("WORD_LEGACY_ACTIVE_CONTENT_UNSUPPORTED");
        }
        for(int i=0;i<reader.getAttributeCount();i++){
            String attribute=reader.getAttributeLocalName(i),value=reader.getAttributeValue(i);
            if(attribute.equalsIgnoreCase("ContentType")&&activeType(value.toLowerCase(Locale.ROOT)))throw new IOException("WORD_LEGACY_ACTIVE_CONTENT_UNSUPPORTED");
            if(local.equals("fldsimple")&&attribute.equals("instr"))validateLegacyInstruction(value);
        }
    }
    private static boolean activeType(String value){return value.contains("vba")||value.contains("macroenabled")||value.contains("activex")||value.contains("oleobject")
        ||value.contains("customui")||value.contains("webextension")||value.contains("afchunk")||value.endsWith("/control")||value.endsWith("/package")
        ||value.contains("officedocument.package")||value.endsWith("/extensibility")||value.endsWith("/attachedtoolbars");}
    private static void validateLegacyInstruction(String instruction)throws IOException{
        if(LEGACY_ACTIVE_FIELD.matcher(instruction).find())throw new IOException("WORD_LEGACY_FORM_UNSUPPORTED");
    }
    static final class LimitedOutput extends FilterOutputStream{private final long max;private long count;LimitedOutput(OutputStream out,long max){super(out);this.max=max;}@Override public void write(int b)throws IOException{if(++count>max)throw new IOException("WORD_OUTPUT_LIMIT");out.write(b);}@Override public void write(byte[] b,int off,int len)throws IOException{if((count+=len)>max)throw new IOException("WORD_OUTPUT_LIMIT");out.write(b,off,len);}}
    static void clear(Path directory,Path root)throws IOException{Path dir=directory.toAbsolutePath().normalize(),base=root.toAbsolutePath().normalize();if(dir.equals(base)||!dir.startsWith(base))throw new IOException("WORD_CACHE_PATH_INVALID");if(!Files.exists(dir))return;try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
}
