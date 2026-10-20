package cn.bidcheck.document.word;

import cn.bidcheck.document.DocumentLimits;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Creates a separate, validated rendering input. The original/staged bytes are never overwritten. */
public final class WordDocxStagingNormalizer {
    public static final String POLICY="bibliography-namespace-staging-1";
    private static final int ENTRY_LIMIT=32*1024*1024, METADATA_LIMIT=8*1024*1024;
    private static final long TOTAL_LIMIT=80L*1024*1024;
    private static final String BIBLIOGRAPHY="http://schemas.openxmlformats.org/officeDocument/2006/bibliography";
    private static final Pattern ROOT=Pattern.compile("(?s)\\A(?:\u00ef\u00bb\u00bf)?\\s*(?:<\\?xml[^?]*\\?>\\s*)?(<b:Sources\\b[^<>]*>)");
    private static final Pattern ATTRIBUTE=Pattern.compile("(?s)([A-Za-z_][A-Za-z0-9_.:-]*)\\s*=\\s*(['\"])(.*?)\\2");
    private WordDocxStagingNormalizer(){}

    public record Result(Path normalizedPath,String sourceSha256,String normalizedSha256,List<String> normalizedParts){
        public Result{normalizedParts=List.copyOf(normalizedParts);}
    }

    /** Caller supplies its disposable source copy and a new, absent output path.
     * Security preflight (modern or legacy) must still be selected by the worker before SDK load. */
    public static Result normalize(Path stagedSource,Path newNormalizedPath)throws IOException{
        WordRenderSupport.interrupted();
        Path source=stagedSource.toAbsolutePath().normalize(),target=newNormalizedPath.toAbsolutePath().normalize();
        if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||Files.size(source)>DocumentLimits.MAX_FILE_BYTES
                ||Files.exists(target,LinkOption.NOFOLLOW_LINKS)||source.equals(target)||target.getParent()==null)throw new IOException("WORD_STAGING_PATH_INVALID");
        Path parent=target.getParent().toRealPath();target=parent.resolve(target.getFileName());
        if(source.toRealPath().equals(target)||Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("WORD_STAGING_PATH_INVALID");
        String originalHash=WordRenderSupport.hash(source,DocumentLimits.MAX_FILE_BYTES);
        Path temporary=Files.createTempFile(parent,"word-normalized-",".tmp");
        List<String> changed=new ArrayList<>();
        try{
            copyValidated(source,temporary,changed);
            WordRenderSupport.validateDocx(temporary);
            if(!originalHash.equals(WordRenderSupport.hash(source,DocumentLimits.MAX_FILE_BYTES)))throw new IOException("SOURCE_HASH_MISMATCH");
            String normalizedHash=WordRenderSupport.hash(temporary,DocumentLimits.MAX_FILE_BYTES);
            // Deliberately no REPLACE_EXISTING, including when another process creates the target meanwhile.
            Files.move(temporary,target);
            return new Result(target,originalHash,normalizedHash,changed);
        }finally{Files.deleteIfExists(temporary);}
    }

    private static void copyValidated(Path source,Path target,List<String> changed)throws IOException{
        int count=0;long total=0;Set<String> names=new HashSet<>();boolean main=false,contentTypes=false;
        try(ZipFile zip=new ZipFile(source.toFile());ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(target))){
            var entries=zip.entries();
            while(entries.hasMoreElements()){
                WordRenderSupport.interrupted();ZipEntry entry=entries.nextElement();String name=entry.getName();
                if(++count>2000||!names.add(name)||name.contains("..")||name.startsWith("/")||name.contains("\\"))throw new IOException("WORD_PACKAGE_LIMIT");
                if(entry.isDirectory()){out.putNextEntry(new ZipEntry(name));out.closeEntry();continue;}
                if(entry.getSize()>ENTRY_LIMIT||(entry.getSize()>1_000_000&&entry.getCompressedSize()>0&&entry.getSize()/entry.getCompressedSize()>200))throw new IOException("WORD_PACKAGE_LIMIT");
                byte[] bytes;try(InputStream input=zip.getInputStream(entry)){bytes=input.readNBytes(ENTRY_LIMIT+1);}
                total+=bytes.length;if(bytes.length>ENTRY_LIMIT||total>TOTAL_LIMIT)throw new IOException("WORD_PACKAGE_LIMIT");
                if(name.equals("word/document.xml"))main=true;if(name.equals("[Content_Types].xml"))contentTypes=true;
                if(name.matches("customXml/item[1-9][0-9]*\\.xml")){
                    if(bytes.length>METADATA_LIMIT)throw new IOException("WORD_XML_LIMIT");
                    byte[] repaired=bibliographyView(bytes);
                    if(!java.util.Arrays.equals(bytes,repaired)){changed.add(name);bytes=repaired;}
                }
                String lower=name.toLowerCase(Locale.ROOT);
                if(lower.endsWith(".xml")||lower.endsWith(".rels"))WordRenderSupport.validateXml(bytes,lower.endsWith(".rels"));
                ZipEntry copied=new ZipEntry(name);if(entry.getTime()>=0)copied.setTime(entry.getTime());
                out.putNextEntry(copied);out.write(bytes);out.closeEntry();
            }
        }
        if(!main||!contentTypes)throw new IOException("WORD_MAIN_PART_MISSING");
    }

    /** Same deliberately narrow allowlist as FileParser.bibliographyValidationView:
     * only b:Sources in customXml/itemN.xml, and every duplicate xmlns:b has the exact known URI.
     * Lexical attribute boundaries additionally prevent matching xmlns text inside another attribute. */
    private static byte[] bibliographyView(byte[] bytes){
        String xml=new String(bytes,StandardCharsets.ISO_8859_1);var root=ROOT.matcher(xml);
        if(!root.find())return bytes;
        String tag=root.group(1);int cursor="<b:Sources".length();List<int[]> duplicateRanges=new ArrayList<>();int declarations=0;
        while(cursor<tag.length()){
            int whitespaceStart=cursor;while(cursor<tag.length()&&xmlSpace(tag.charAt(cursor)))cursor++;
            if(tag.substring(cursor).equals(">")||tag.substring(cursor).equals("/>"))break;
            if(cursor==whitespaceStart)return bytes;
            var attribute=ATTRIBUTE.matcher(tag);attribute.region(cursor,tag.length());if(!attribute.lookingAt())return bytes;
            if(attribute.group(1).equals("xmlns:b")){
                if(!attribute.group(3).equals(BIBLIOGRAPHY))return bytes;
                if(++declarations>1)duplicateRanges.add(new int[]{whitespaceStart,attribute.end()});
            }
            cursor=attribute.end();
        }
        if(declarations<2)return bytes;
        StringBuilder repaired=new StringBuilder();int kept=0;
        for(int[] range:duplicateRanges){repaired.append(tag,kept,range[0]);kept=range[1];}
        repaired.append(tag,kept,tag.length());
        return (xml.substring(0,root.start(1))+repaired+xml.substring(root.end(1))).getBytes(StandardCharsets.ISO_8859_1);
    }
    private static boolean xmlSpace(char c){return c==' '||c=='\t'||c=='\r'||c=='\n';}
}
