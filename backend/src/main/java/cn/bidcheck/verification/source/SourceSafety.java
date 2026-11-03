package cn.bidcheck.verification.source;
import cn.bidcheck.compat.HexFormat;

import cn.bidcheck.document.DocumentLimits;
import cn.bidcheck.domain.Domain.Document;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.w3c.dom.*;

/** Shared limits for sidecar extraction; never trusts a caller supplied document hash without reading the source. */
final class SourceSafety {
    static void interrupted() { if(Thread.currentThread().isInterrupted()) throw new CancellationException("来源核验已中断"); }
    static String verify(Document doc, Path path) throws IOException {
        interrupted();
        if(doc==null || path==null || !Files.isRegularFile(path) || Files.size(path)<1 || Files.size(path)>DocumentLimits.MAX_FILE_BYTES)
            throw new IOException("SOURCE_SIZE_OR_PATH_INVALID");
        String hash;
        try(InputStream in=Files.newInputStream(path)){hash=digest(in);}
        if(doc.sha256()==null || !doc.sha256().matches("[a-fA-F0-9]{64}") || !hash.equalsIgnoreCase(doc.sha256()))
            throw new IOException("SOURCE_HASH_MISMATCH");
        return hash;
    }
    static String digest(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
    static String digest(InputStream in) throws IOException {
        try { MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];long count=0;int n;
            while((n=in.read(buffer))!=-1){interrupted();count+=n;if(count>DocumentLimits.MAX_FILE_BYTES)throw new IOException("SOURCE_LIMIT");md.update(buffer,0,n);}return HexFormat.of().formatHex(md.digest());
        } catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static Map<String,Object> map(Object... kv){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)out.put((String)kv[i],kv[i+1]);return out;}
    static void pdf(PDDocument doc) throws IOException {
        if(doc.isEncrypted() || doc.getNumberOfPages()<1 || doc.getNumberOfPages()>1000)throw new IOException("PDF_PAGE_OR_ENCRYPTION_LIMIT");
        Set<COSBase> visited=Collections.newSetFromMap(new IdentityHashMap<>());long[] total={0};
        for(PDPage page:doc.getPages()){
            interrupted();double w=page.getCropBox().getWidth(),h=page.getCropBox().getHeight();
            if(!Double.isFinite(w)||!Double.isFinite(h)||w<1||h<1||w>20000||h>20000)throw new IOException("PDF_PAGE_SIZE_LIMIT");
            Iterator<PDStream> streams=page.getContentStreams();while(streams.hasNext())boundedStream(streams.next().getCOSObject(),4_000_000,total,visited);
            resources(page.getResources(),0,total,visited);
        }
    }
    /** Same resource bounds, limited to the explicitly requested page for lazy sidecars. */
    static void pdfPage(PDDocument doc,int pageNumber)throws IOException{
        if(doc.isEncrypted()||doc.getNumberOfPages()<1||doc.getNumberOfPages()>1000||pageNumber<1||pageNumber>doc.getNumberOfPages())throw new IOException("PDF_PAGE_OR_ENCRYPTION_LIMIT");
        interrupted();PDPage page=doc.getPage(pageNumber-1);double w=page.getCropBox().getWidth(),h=page.getCropBox().getHeight();
        if(!Double.isFinite(w)||!Double.isFinite(h)||w<1||h<1||w>20000||h>20000)throw new IOException("PDF_PAGE_SIZE_LIMIT");
        Set<COSBase> visited=Collections.newSetFromMap(new IdentityHashMap<>());long[] total={0};Iterator<PDStream> streams=page.getContentStreams();
        while(streams.hasNext())boundedStream(streams.next().getCOSObject(),4_000_000,total,visited);resources(page.getResources(),0,total,visited);
    }
    private static void resources(PDResources resources,int depth,long[] total,Set<COSBase> visited)throws IOException{
        if(resources==null || visited.contains(resources.getCOSObject()))return;
        if(depth>20||visited.size()>12000)throw new IOException("PDF_RESOURCE_LIMIT");visited.add(resources.getCOSObject());
        // Inspect font streams before PDFBox constructs font objects; images are not decoded by the text stripper.
        COSBase fonts=resources.getCOSObject().getDictionaryObject(COSName.FONT);
        if(fonts instanceof COSDictionary)for(COSName name:((COSDictionary) (fonts)).keySet())scanFonts(((COSDictionary) (fonts)).getDictionaryObject(name),0,total,visited);
        for(COSName name:resources.getXObjectNames()){
            interrupted();if(resources.isImageXObject(name))continue;
            org.apache.pdfbox.pdmodel.graphics.PDXObject object=resources.getXObject(name);if(object instanceof PDFormXObject){boundedStream(((PDFormXObject) (object)).getCOSObject(),4_000_000,total,visited);resources(((PDFormXObject) (object)).getResources(),depth+1,total,visited);}
        }
    }
    private static void scanFonts(COSBase base,int depth,long[] total,Set<COSBase> visited)throws IOException{
        if(base==null||visited.contains(base))return;if(depth>12)throw new IOException("PDF_FONT_LIMIT");
        if(base instanceof COSStream){boundedStream(((COSStream) (base)),16_000_000,total,visited);return;}
        visited.add(base);
        if(base instanceof COSDictionary)for(COSName key:cn.bidcheck.compat.Java8Api.listOf(COSName.FONT_DESC, COSName.DESCENDANT_FONTS, COSName.FONT_FILE, COSName.FONT_FILE2, COSName.FONT_FILE3, COSName.TO_UNICODE))scanFonts(((COSDictionary) (base)).getDictionaryObject(key),depth+1,total,visited);
        if(base instanceof COSArray)for(int i=0;i<((COSArray) (base)).size();i++)scanFonts(((COSArray) (base)).getObject(i),depth+1,total,visited);
    }
    private static void boundedStream(COSStream stream,int max,long[] total,Set<COSBase> visited)throws IOException{
        if(!visited.add(stream))return;try(InputStream in=stream.createInputStream()){byte[] b=new byte[8192];int n,count=0;while((n=in.read(b))!=-1){interrupted();count+=n;total[0]+=n;if(count>max||total[0]>128_000_000)throw new IOException("PDF_EXPANSION_LIMIT");}}
    }
    static Map<String,byte[]> wordXml(Path path)throws IOException{
        Map<String,byte[]> parts=new TreeMap<>();long total=0;int count=0;
        try(ZipFile zip=new ZipFile(path.toFile())){java.util.Enumeration<? extends java.util.zip.ZipEntry> entries=zip.entries();Set<String> names=new HashSet<>();
            while(entries.hasMoreElements()){interrupted();ZipEntry e=entries.nextElement();if(++count>4096||!names.add(e.getName()))throw new IOException("WORD_ZIP_LIMIT");
                if(e.isDirectory())continue;if(e.getSize()>64_000_000 || (e.getSize()>1_000_000&&e.getCompressedSize()>0&&e.getSize()/e.getCompressedSize()>150))throw new IOException("WORD_ZIP_LIMIT");
                if(!e.getName().matches("word/(document|header[0-9]+|footer[0-9]+)\\.xml"))continue;
                try(InputStream in=zip.getInputStream(e)){byte[] bytes=cn.bidcheck.compat.Java8Api.readNBytes(in, 16_000_001);total+=bytes.length;if(bytes.length>16_000_000||total>32_000_000)throw new IOException("WORD_XML_LIMIT");parts.put(e.getName(),bytes);}
            }
        }
        if(!parts.containsKey("word/document.xml"))throw new IOException("WORD_MAIN_PART_MISSING");return parts;
    }
    static org.w3c.dom.Document xml(byte[] bytes)throws IOException{
        try{
            javax.xml.stream.XMLInputFactory streamFactory=javax.xml.stream.XMLInputFactory.newFactory();streamFactory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD,false);streamFactory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
            javax.xml.stream.XMLStreamReader reader=streamFactory.createXMLStreamReader(new ByteArrayInputStream(bytes));int nodes=0,depth=0,chars=0;
            try{while(reader.hasNext()){interrupted();int type=reader.next();if(type==javax.xml.stream.XMLStreamConstants.DTD||type==javax.xml.stream.XMLStreamConstants.ENTITY_REFERENCE)throw new IOException("WORD_XML_UNSAFE_OR_INVALID");
                if(type==javax.xml.stream.XMLStreamConstants.START_ELEMENT){if(++nodes>100_000||++depth>40||reader.getAttributeCount()>100)throw new IOException("WORD_XML_STRUCTURE_LIMIT");}
                if(type==javax.xml.stream.XMLStreamConstants.END_ELEMENT)depth--;
                if(type==javax.xml.stream.XMLStreamConstants.CHARACTERS){chars+=reader.getTextLength();if(chars>2_000_000)throw new IOException("WORD_XML_TEXT_LIMIT");}
            }}finally{reader.close();}
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setXIncludeAware(false);f.setExpandEntityReferences(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            javax.xml.parsers.DocumentBuilder b=f.newDocumentBuilder();b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}});
            return b.parse(new ByteArrayInputStream(bytes));
        }catch(CancellationException e){throw e;}catch(IOException e){throw e;}catch(Exception e){throw new IOException("WORD_XML_UNSAFE_OR_INVALID");}
    }
    static String extension(Document doc){String name=doc.name()==null?"":doc.name().toLowerCase(Locale.ROOT);return name.substring(Math.max(0,name.lastIndexOf('.')+1));}
}
