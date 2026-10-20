package cn.bidcheck.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoredBodyCodecTest {
    static final String LARGE="正文😀𠀀\t表格第一列\n第二列\r\n".repeat(40000);
    static String hash(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    static byte[] gzip(byte[] raw)throws Exception {var out=new ByteArrayOutputStream();try(var zipped=new GZIPOutputStream(out)){zipped.write(raw);}return out.toByteArray();}
    static String envelope(byte[] raw,byte[] packed)throws Exception{return StoredBodyCodec.HEADER+raw.length+"\n"+hash(raw)+"\n"+hash(packed)+"\n"+Base64.getEncoder().encodeToString(packed);}
    static void invalid(String value){var failure=assertThrows(IllegalStateException.class,()->StoredBodyCodec.decode(value));assertEquals("STORED_BODY_INVALID",failure.getMessage());assertNull(failure.getCause());}

    @Test void unicodeAndWhitespaceRoundtripPreservesExactJsonAndPublicEncoding() {
        Store store=new Store(mock(RecordMapper.class),new ObjectMapper());String json=store.encode(Map.of("text",LARGE,"number",13.25));
        String stored=StoredBodyCodec.encode("consistency_report",json);
        assertTrue(stored.startsWith(StoredBodyCodec.HEADER));assertEquals(json,StoredBodyCodec.decode(stored));
        assertEquals(store.map(json),store.map(stored));assertEquals(store.decode(json,Map.class),store.decode(stored,Map.class));
        assertTrue(store.encode(Map.of("text",LARGE)).startsWith("{"));
    }
    @Test void thirteenMegabyteReportRoundtripIsLossless() {
        Store store=new Store(mock(RecordMapper.class),new ObjectMapper());
        String json=store.encode(Map.of("text","项目表格原文\n".repeat(650000),"occurrences",List.of(Map.of("page",581,"scope","UNRESOLVED"))));
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length>13_000_000);
        String stored=StoredBodyCodec.encode("consistency_report",json);assertEquals(json,StoredBodyCodec.decode(stored));
        assertTrue(stored.length()<json.getBytes(StandardCharsets.UTF_8).length/10);
    }
    @Test void supportedLargeSnapshotKindsAboveUtf8ThresholdAreCompressed() {
        String json="\""+"a".repeat(StoredBodyCodec.COMPRESSION_THRESHOLD-2)+"\"";
        for(String kind:List.of("fact_snapshot","consistency_report","evidence_graph_snapshot"))assertTrue(StoredBodyCodec.encode(kind,json).startsWith(StoredBodyCodec.HEADER));
        String smaller=json.substring(1);assertSame(smaller,StoredBodyCodec.encode("fact_snapshot",smaller));
        for(String kind:List.of("document","fact_usage","consistency_publication_batch","finding","entity","fact_occurrence"))assertSame(json,StoredBodyCodec.encode(kind,json));
        assertTrue(StoredBodyCodec.encode("fact_snapshot","中".repeat(400000)).startsWith(StoredBodyCodec.HEADER));
    }
    @Test void legacyJsonAndMagicInsideUserFieldsRemainPlain() {
        String legacy="{\"body\":\"BIDCHECK-STORED-BODY:1:GZIP\\nvalue\",\"version\":1}";
        assertSame(legacy,StoredBodyCodec.decode(legacy));Store store=new Store(mock(RecordMapper.class),new ObjectMapper());
        assertEquals(1,store.map(legacy).get("version"));assertSame("[]",StoredBodyCodec.decode("[]"));
    }
    @Test void unsupportedVersionTruncationHashAndLengthDamageFailWithoutContent()throws Exception {
        byte[] raw="{\"value\":\"secret\"}".getBytes(StandardCharsets.UTF_8);String correct=envelope(raw,gzip(raw));
        invalid(correct.replace(":1:GZIP",":2:GZIP"));invalid(correct.substring(0,correct.length()-1));invalid(StoredBodyCodec.HEADER);
        invalid(correct.replaceFirst("\n"+raw.length+"\n","\n"+(raw.length-1)+"\n"));
        invalid(correct.replaceFirst("\n"+raw.length+"\n","\n"+(raw.length+1)+"\n"));
        String[] parts=correct.split("\n",-1);parts[2]="0".repeat(64);invalid(String.join("\n",parts));
        parts=correct.split("\n",-1);parts[3]="0".repeat(64);invalid(String.join("\n",parts));
        parts=correct.split("\n",-1);parts[4]="!"+parts[4].substring(1);invalid(String.join("\n",parts));
    }
    @Test void corruptGzipTrailerAndConcatenatedOrTrailingPayloadAreRejected()throws Exception {
        byte[] raw="a".repeat(10000).getBytes(StandardCharsets.UTF_8),packed=gzip(raw);byte[] corrupt=packed.clone();corrupt[corrupt.length-8]^=1;
        invalid(envelope(raw,corrupt));
        byte[] tail=Arrays.copyOf(packed,packed.length+1);tail[tail.length-1]=1;invalid(envelope(raw,tail));
        var output=new ByteArrayOutputStream();output.write(packed);output.write(gzip(new byte[0]));invalid(envelope(raw,output.toByteArray()));
        invalid(envelope(raw,Arrays.copyOf(packed,packed.length-1)));
    }
    @Test void declaredLimitIsRejectedBeforePayloadDecodeAndActualOutputCannotExceedDeclaration()throws Exception {
        var failure=assertThrows(IllegalStateException.class,()->StoredBodyCodec.decode(StoredBodyCodec.HEADER+(StoredBodyCodec.MAX_UNCOMPRESSED_BYTES+1L)+"\n"));
        assertEquals("STORED_BODY_TOO_LARGE",failure.getMessage());
        byte[] raw="x".repeat(1024*1024).getBytes(StandardCharsets.UTF_8);String tiny=envelope(raw,gzip(raw)).replaceFirst("\n1048576\n","\n1\n");invalid(tiny);
    }
    @Test void oversizedWriteAndMalformedUtf16AreRejectedWithoutReplacement() {
        String oversized="x".repeat(StoredBodyCodec.MAX_UNCOMPRESSED_BYTES+1);
        assertEquals("STORED_BODY_TOO_LARGE",assertThrows(IllegalStateException.class,()->StoredBodyCodec.encode("fact_snapshot",oversized)).getMessage());
        for(char surrogate:new char[]{'\uD800','\uDC00'})assertEquals("STORED_BODY_INVALID",assertThrows(IllegalStateException.class,
            ()->StoredBodyCodec.encode("consistency_report",LARGE+surrogate)).getMessage());
    }
    @Test void invalidUtf8IsRejectedEvenWhenLengthAndHashesAreConsistent()throws Exception {
        byte[] invalid={(byte)0xc3,0x28};invalid(envelope(invalid,gzip(invalid)));
    }
    @Test void normalSaveAndBulkInsertKeepLargeReviewUpdatesCompressed() {
        RecordMapper mapper=mock(RecordMapper.class);Store store=new Store(mapper,new ObjectMapper());var value=Map.of("text",LARGE,"targetedReview",Map.of("status","REVIEW_REQUIRED"));
        store.save("consistency_report","report","project","run",value);
        ArgumentCaptor<Stored> captured=ArgumentCaptor.forClass(Stored.class);verify(mapper).save(captured.capture());
        Stored prior=captured.getValue();assertTrue(prior.body.startsWith(StoredBodyCodec.HEADER));when(mapper.get("report")).thenReturn(prior);
        store.save("consistency_report","report","project","run",Map.of("text",LARGE,"targetedReview",Map.of("status","COMPLETED")));
        verify(mapper,times(2)).save(captured.capture());assertTrue(captured.getValue().body.startsWith(StoredBodyCodec.HEADER));assertEquals(prior.createdAt,captured.getValue().createdAt);
        assertEquals("COMPLETED",((Map<?,?>)store.map(captured.getValue().body).get("targetedReview")).get("status"));
        doAnswer(call->{List<Stored> rows=call.getArgument(0);assertTrue(rows.get(0).body.startsWith(StoredBodyCodec.HEADER));assertEquals(value,store.map(rows.get(0).body));return null;}).when(mapper).insertRecords(anyList());
        store.insertNew("fact_snapshot","project","run",List.of(value),unused->"snapshot");verify(mapper).insertRecords(anyList());
    }
    @Test void compressedPreparedRecordWorksThroughProxyAndNeverReencodesInFinalWrite() {
        RecordMapper mapper=mock(RecordMapper.class);Store target=spy(new Store(mapper,new ObjectMapper()));ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);Store proxy=(Store)factory.getProxy();
        var input=new LinkedHashMap<String,Object>(Map.of("text",LARGE));var prepared=proxy.prepareRecord("fact_snapshot","snapshot","project","run",input);input.put("text","mutated");
        doThrow(new AssertionError("No encoding under final fence")).when(target).encode(any());proxy.savePrepared(prepared);
        ArgumentCaptor<Stored> saved=ArgumentCaptor.forClass(Stored.class);verify(mapper).save(saved.capture());assertTrue(saved.getValue().body.startsWith(StoredBodyCodec.HEADER));assertEquals(LARGE,proxy.map(saved.getValue().body).get("text"));
        Store other=new Store(mapper,new ObjectMapper());assertThrows(IllegalArgumentException.class,()->other.savePrepared(prepared));
    }
}
