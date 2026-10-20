package cn.bidcheck.persistence;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Internal storage only: the prefix cannot be the beginning of a valid JSON value. */
final class StoredBodyCodec {
    static final String MAGIC="BIDCHECK-STORED-BODY:";
    static final String HEADER=MAGIC+"1:GZIP\n";
    static final int COMPRESSION_THRESHOLD=1024*1024;
    static final int MAX_UNCOMPRESSED_BYTES=128*1024*1024;
    private static final int MAX_COMPRESSED_BYTES=MAX_UNCOMPRESSED_BYTES+1024*1024;
    private static final Set<String> KINDS=Set.of("fact_snapshot","consistency_report","evidence_graph_snapshot");
    private StoredBodyCodec() {}

    static String encode(String kind,String json) {
        if(!KINDS.contains(kind)||json.length()<COMPRESSION_THRESHOLD/3)return json;
        if(json.length()>MAX_UNCOMPRESSED_BYTES)throw tooLarge();
        byte[] raw;
        try {
            ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(json));
            raw=new byte[encoded.remaining()];encoded.get(raw);
        } catch(CharacterCodingException failure){throw invalid();}
        if(raw.length<COMPRESSION_THRESHOLD)return json;
        if(raw.length>MAX_UNCOMPRESSED_BYTES)throw tooLarge();
        try {
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            try(GZIPOutputStream gzip=new GZIPOutputStream(output)){gzip.write(raw);}
            byte[] packed=output.toByteArray();
            return HEADER+raw.length+"\n"+sha(raw)+"\n"+sha(packed)+"\n"+Base64.getEncoder().encodeToString(packed);
        } catch(IOException failure){throw invalid();}
    }

    static String decode(String stored) {
        if(stored==null||!stored.startsWith(MAGIC))return stored;
        if(!stored.startsWith(HEADER))throw invalid();
        int lengthEnd=stored.indexOf('\n',HEADER.length());
        if(lengthEnd<0||lengthEnd-HEADER.length()>10)throw invalid();
        String declared=stored.substring(HEADER.length(),lengthEnd);
        if(!declared.matches("[1-9][0-9]*"))throw invalid();
        long expected;
        try{expected=Long.parseLong(declared);}catch(NumberFormatException failure){throw invalid();}
        if(expected>MAX_UNCOMPRESSED_BYTES)throw tooLarge();
        int rawHashStart=lengthEnd+1,packedHashStart=rawHashStart+65,payloadStart=packedHashStart+65;
        if(stored.length()<=payloadStart||stored.charAt(rawHashStart+64)!='\n'||stored.charAt(packedHashStart+64)!='\n')throw invalid();
        String rawHash=stored.substring(rawHashStart,rawHashStart+64),packedHash=stored.substring(packedHashStart,packedHashStart+64);
        if(!rawHash.matches("[0-9a-f]{64}")||!packedHash.matches("[0-9a-f]{64}"))throw invalid();
        if(stored.length()-payloadStart>4L*((MAX_COMPRESSED_BYTES+2L)/3L))throw tooLarge();
        try {
            String payload=stored.substring(payloadStart);
            byte[] packed=Base64.getDecoder().decode(payload);
            if(packed.length>MAX_COMPRESSED_BYTES)throw tooLarge();
            if(!sha(packed).equals(packedHash)||!Base64.getEncoder().encodeToString(packed).equals(payload))throw invalid();
            byte[] raw=unpack(packed,(int)expected);
            if(!sha(raw).equals(rawHash))throw invalid();
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch(IOException|IllegalArgumentException failure){throw invalid();}
    }
    /** One envelope contains exactly the single gzip member emitted above, without trailing data. */
    private static byte[] unpack(byte[] packed,int expected) {
        if(packed.length<18||(packed[0]&255)!=31||(packed[1]&255)!=139||packed[2]!=8||packed[3]!=0)throw invalid();
        Inflater inflater=new Inflater(true);CRC32 crc=new CRC32();
        ByteArrayOutputStream output=new ByteArrayOutputStream(Math.min(expected,8192));
        try {
            inflater.setInput(packed,10,packed.length-18);
            byte[] buffer=new byte[8192];int total=0;
            while(!inflater.finished()) {
                int count=inflater.inflate(buffer);
                if((long)total+count>expected)throw invalid();
                if(count==0&&!inflater.finished())throw invalid();
                output.write(buffer,0,count);crc.update(buffer,0,count);total+=count;
            }
            if(inflater.getRemaining()!=0||total!=expected||littleEndian(packed,packed.length-8)!=crc.getValue()
                    ||littleEndian(packed,packed.length-4)!=expected)throw invalid();
            return output.toByteArray();
        } catch(DataFormatException failure){throw invalid();}
        finally{inflater.end();}
    }
    private static long littleEndian(byte[] value,int at) {
        return (value[at]&255L)|((value[at+1]&255L)<<8)|((value[at+2]&255L)<<16)|((value[at+3]&255L)<<24);
    }
    private static String sha(byte[] value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("STORED_BODY_CODEC_UNAVAILABLE");}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("STORED_BODY_INVALID");}
    private static IllegalStateException tooLarge(){return new IllegalStateException("STORED_BODY_TOO_LARGE");}
}
