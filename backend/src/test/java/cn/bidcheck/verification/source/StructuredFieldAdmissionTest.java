package cn.bidcheck.verification.source;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StructuredFieldAdmissionTest {
    private final String quote="张甲 证书编号 A12345";
    private Document document(){return new Document("bid","project","BID","fixture.pdf",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,quote,"PAGE","PDF","")),cn.bidcheck.compat.Java8Api.listOf());}
    private Map<String,Object> result(String note){java.util.LinkedHashMap<java.lang.String, java.lang.Object> item=new LinkedHashMap<String,Object>();item.putAll(cn.bidcheck.compat.Java8Api.mapOf("type", "FIELD", "label", "证书编号", "value", "A12345", "readability", "CLEAR", "sourcePage", 1, "sourceQuote", quote, "subject", "张甲", "entityType", "PERSON", "recordKey", "row1"));item.put("modelLimitations",cn.bidcheck.compat.Java8Api.listOf(note));item.put("source",cn.bidcheck.compat.Java8Api.mapOf("documentId", "bid", "projectId", "project", "documentHash", "hash", "documentVersion", 1, "page", 1));return StructuredContextFacts.batch(document(),cn.bidcheck.compat.Java8Api.listOf(1),cn.bidcheck.compat.Java8Api.mapOf("observations", cn.bidcheck.compat.Java8Api.listOf(item)),"fixture-model");}
    @Test void pageScopeAllowsLiteralSourceFieldAndPreservesBoundary(){java.util.Map<java.lang.String, java.lang.Object> batch=result("仅基于单页图像内容进行转录，未验证证书真实性或有效性");List<?> facts=(List<?>)batch.get("facts");assertEquals(1,facts.size());Map<?,?> fact=(Map<?,?>)facts.get(0);assertEquals("UNRESOLVED",fact.get("scope"));assertEquals("张甲",fact.get("subject"));assertEquals("SAME_SOURCE_ROW",((Map<?,?>)fact.get("attributes")).get("subjectBinding"));}
    @Test void ownershipUncertaintyDoesNotDeleteVisibleNumberOrInventItsOwner(){java.util.Map<java.lang.String, java.lang.Object> batch=result("无法确认张甲与该证书是否属于同一人");Map<?,?> fact=(Map<?,?>)((List<?>)batch.get("facts")).get(0);assertEquals("",fact.get("subject"));assertEquals("UNKNOWN",((Map<?,?>)fact.get("attributes")).get("entityType"));assertEquals("A12345",fact.get("value"));}
    @Test void actualNumberBlurStillRejectsField(){java.util.Map<java.lang.String, java.lang.Object> batch=result("证书编号模糊不清");assertTrue(((List<?>)batch.get("facts")).isEmpty());assertFalse(((List<?>)batch.get("rejected")).isEmpty());}
}
