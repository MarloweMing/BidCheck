package cn.bidcheck.compat;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class RecordCompatibilityTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void savedDocumentsKeepTheirJsonFieldsAndNestedValueTypes() throws Exception {
        String saved="{\"id\":\"d\",\"projectId\":\"p\",\"role\":\"BID\",\"name\":\"投标.txt\",\"version\":1,\"sha256\":\"hash\",\"createdAt\":\"time\",\"pages\":[{\"number\":2,\"text\":\"正文\",\"locationType\":\"PAGE\",\"parser\":\"TEXT\",\"warning\":\"\",\"layout\":null}],\"warnings\":[]}";
        Document document=json.readValue(saved,Document.class);
        assertEquals(new Page(2,"正文","PAGE","TEXT",""),document.pages().get(0));
        assertEquals(Collections.emptyList(),document.warnings());
        assertEquals(json.readTree(saved),json.valueToTree(document));
        assertEquals(document,json.readValue(json.writeValueAsString(document),Document.class));
    }

    @Test void valueComparisonAndDisplayKeepRecordSemantics() throws Exception {
        Evidence positive=new Evidence("d","文件",1,"PAGE","原文","a",0.0);
        Evidence negative=new Evidence("d","文件",1,"PAGE","原文","a",-0.0);
        Evidence nan=new Evidence("d","文件",1,"PAGE","原文","a",Double.NaN);
        assertNotEquals(positive,negative);
        assertEquals(nan,new Evidence("d","文件",1,"PAGE","原文","a",Double.NaN));
        assertEquals("Evidence[documentId=d, documentName=文件, page=1, locationType=PAGE, quote=原文, anchor=a, confidence=0.0]",positive.toString());
        assertEquals(positive,json.readValue(json.writeValueAsString(positive),Evidence.class));
        assertEquals(positive.hashCode(),json.readValue(json.writeValueAsString(positive),Evidence.class).hashCode());
    }
}
