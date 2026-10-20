package cn.bidcheck.verification.source;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructuredCompactResponseTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode page()throws Exception{return (ObjectNode)json.readTree("""
        {"page":9,"q":["张三　 项目经理 Ａ-01"],"r":[["张三","PERSON","本页第１行"]],
        "f":[["岗位","项目经理","CLEAR",0,0]],"limitations":[]}
        """);}
    @Test void expandsNineExplicitCanonicalFieldsWithoutNormalizingSourceOrInventingCoordinates()throws Exception{
        JsonNode canonical=StructuredCompactResponse.page(page()),item=canonical.path("observations").get(0);
        assertEquals(9,item.size());assertEquals("FIELD",item.path("type").asText());assertTrue(item.path("bbox").isNull());
        assertEquals("张三　 项目经理 Ａ-01",item.path("sourceQuote").asText());assertEquals("本页第１行",item.path("recordKey").asText());
        assertEquals("CLEAR",item.path("readability").asText());assertEquals("张三",item.path("subject").asText());
        assertEquals(9,canonical.path("page").asInt());assertFalse(canonical.has("q"));
    }
    @Test void everyIndexIsAnExplicitPageLocalIntegerAndNeverGuessed()throws Exception{
        for(JsonNode invalid:List.of(json.readTree("-1"),json.readTree("1"),json.readTree("0.0"),json.readTree("\"0\""),json.readTree("null"),json.readTree("2147483648"))){
            for(int position:List.of(3,4)){ObjectNode page=page();((ArrayNode)page.path("f").get(0)).set(position,invalid);
                assertEquals("VISUAL_COMPACT_SCHEMA_INVALID",assertThrows(IOException.class,()->StructuredCompactResponse.page(page)).getMessage());}
        }
    }
    @Test void omittedReadabilityOrTupleValuesAndUnsupportedEnumsCannotBecomeDefaults()throws Exception{
        for(String field:List.of("[\"岗位\",\"项目经理\",0,0]","[\"岗位\",\"项目经理\",\"clear\",0,0]",
            "[\"岗位\",null,\"CLEAR\",0,0]","[\"岗位\",\"项目经理\",\"CLEAR\",0,0,\"extra\"]")){
            ObjectNode page=page();page.putArray("f").add(json.readTree(field));assertThrows(IOException.class,()->StructuredCompactResponse.page(page));
        }
        ObjectNode page=page();((ArrayNode)page.path("r").get(0)).set(1,TextNode.valueOf("IMPLIED_PERSON"));
        assertThrows(IOException.class,()->StructuredCompactResponse.page(page));
    }
    @Test void sameFortyFieldAndScalarBoundsRemainEnforcedWithoutSilentTruncation()throws Exception{
        ObjectNode page=page();JsonNode field=page.path("f").get(0);ArrayNode fields=page.putArray("f");
        for(int i=0;i<40;i++)fields.add(field.deepCopy());assertEquals(40,StructuredCompactResponse.page(page).path("observations").size());
        fields.add(field.deepCopy());assertThrows(IOException.class,()->StructuredCompactResponse.page(page));
        for(String key:List.of("q","r")){ObjectNode overflow=page();JsonNode item=overflow.path(key).get(0);for(int i=1;i<41;i++)((ArrayNode)overflow.get(key)).add(item.deepCopy());
            assertThrows(IOException.class,()->StructuredCompactResponse.page(overflow));}
        ObjectNode quote=page();((ArrayNode)quote.get("q")).set(0,TextNode.valueOf("字".repeat(4001)));assertThrows(IOException.class,()->StructuredCompactResponse.page(quote));
        ObjectNode value=page();((ArrayNode)value.path("f").get(0)).set(1,TextNode.valueOf("值".repeat(2049)));assertThrows(IOException.class,()->StructuredCompactResponse.page(value));
    }
    @Test void emptyPageMustStillHaveExplicitLocalArraysAndCannotUseLegacyOrExtraKeys()throws Exception{
        ObjectNode empty=page();empty.putArray("q");empty.putArray("r");empty.putArray("f");assertTrue(StructuredCompactResponse.page(empty).path("observations").isEmpty());
        empty.remove("q");assertThrows(IOException.class,()->StructuredCompactResponse.page(empty));
        ObjectNode extra=page();extra.put("subject","inherited");assertThrows(IOException.class,()->StructuredCompactResponse.page(extra));
        assertThrows(IOException.class,()->StructuredCompactResponse.page(json.readTree("{\"page\":9,\"observations\":[]}")));
    }
    @Test void envelopeRequiresVersionAndBoundedArrayButDelegatesPageIsolationToExistingMapping()throws Exception{
        ObjectNode envelope=json.createObjectNode().put("wire",StructuredCompactResponse.VERSION);ArrayNode pages=envelope.putArray("pages");pages.add(page());pages.add(page().put("page",999));
        JsonNode canonical=StructuredCompactResponse.envelope(envelope,List.of(9));assertEquals(2,canonical.path("pages").size());assertFalse(canonical.has("wire"));
        envelope.put("wire","SOURCE_FACTS_COMPACT_V2");assertThrows(IOException.class,()->StructuredCompactResponse.envelope(envelope,List.of(9)));
        envelope.put("wire",StructuredCompactResponse.VERSION);for(int i=0;i<3;i++)pages.add(page());
        assertEquals("VISUAL_PAGE_MAPPING_INVALID",assertThrows(IOException.class,()->StructuredCompactResponse.envelope(envelope,List.of(9))).getMessage());
    }
}
