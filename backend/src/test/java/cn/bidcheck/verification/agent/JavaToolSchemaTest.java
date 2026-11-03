package cn.bidcheck.verification.agent;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaToolSchemaTest {
    private static Map<String,Object> m(Object... pairs){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    private static Map<String,Object> object(Map<String,Object> fields,String... required){return m("type","object","properties",fields,"required",Arrays.asList(required),"additionalProperties",false);}
    @Test void validatesNestedToolContractAndReportsOnlySafePaths(){
        Map<String,Object> schema=object(m("targetObligationId",m("type","string","enum",new HashSet<>(Arrays.asList("A","B"))),"fact",object(m("sourceIds",m("type","array","minItems",1,"maxItems",2,"items",m("type","string")),"value",m("type","integer","minimum",0)),"sourceIds","value")),"targetObligationId","fact");
        assertNull(JavaToolSchema.validate(schema,m("targetObligationId","A","fact",m("sourceIds",Arrays.asList("S1"),"value",3))));
        JavaToolSchema.ValidationFailure missing=JavaToolSchema.validate(schema,m("targetObligationId","A","fact",m("value",3)));assertEquals("required",missing.keyword);assertEquals("/fact/sourceIds",missing.path);
        JavaToolSchema.ValidationFailure extra=JavaToolSchema.validate(schema,m("targetObligationId","A","fact",m("sourceIds",Arrays.asList("S1"),"value",3,"secret-body","untrusted")));assertEquals("additionalProperties",extra.keyword);assertEquals("/fact/secret-body",extra.path);
        assertEquals("minimum",JavaToolSchema.validate(schema,m("targetObligationId","A","fact",m("sourceIds",Arrays.asList("S1"),"value",-1))).keyword);
        assertEquals("enum",JavaToolSchema.validate(schema,m("targetObligationId","C","fact",m("sourceIds",Arrays.asList("S1"),"value",0))).keyword);
    }
    @Test void enforcesBranchingAndNumericJsonEquality(){
        Map<String,Object> number=m("oneOf",Arrays.asList(m("type","number","minimum",0,"maximum",10),m("const","empty")));
        Map<String,Object> schema=object(m("value",number,"step",m("type","number","multipleOf",new BigDecimal("0.1")),"items",m("type","array","uniqueItems",true,"contains",m("type","integer"),"minContains",1,"maxContains",2)),"value");
        assertNull(JavaToolSchema.validate(schema,m("value","empty","step",new BigDecimal("0.3"),"items",Arrays.asList(1,"x"))));
        assertEquals("oneOf",JavaToolSchema.validate(schema,m("value",11)).keyword);
        assertEquals("multipleOf",JavaToolSchema.validate(schema,m("value",1,"step",new BigDecimal("0.25"))).keyword);
        assertEquals("uniqueItems",JavaToolSchema.validate(schema,m("value",1,"items",Arrays.asList(1,1.0))).keyword);
        assertEquals("contains",JavaToolSchema.validate(schema,m("value",1,"items",Arrays.asList("x"))).keyword);
        Map<String,Object> overlapping=object(m("value",m("oneOf",Arrays.asList(m("type","number"),m("type","integer")))));assertEquals("oneOf",JavaToolSchema.validate(overlapping,m("value",1)).keyword);
    }
    @Test void supportsLocalRefsRecursionAndStringCodePoints(){
        Map<String,Object> node=object(m("name",m("type","string","minLength",1,"maxLength",1),"children",m("type","array","items",m("$ref","#/$defs/node"))),"name");
        Map<String,Object> schema=object(m("root",m("$ref","#/$defs/node")),"root");schema.put("$defs",m("node",node));
        assertNull(JavaToolSchema.validate(schema,m("root",m("name","\ud83d\ude00","children",Arrays.asList(m("name","x"))))));
        assertEquals("maxLength",JavaToolSchema.validate(schema,m("root",m("name","xy"))).keyword);
        Map<String,Object> escaped=object(m("/tilde~",m("type","string")),"/tilde~");JavaToolSchema.ValidationFailure missing=JavaToolSchema.validate(escaped,m());assertEquals("/~1tilde~0",missing.path);
    }
    @Test void supportsDependenciesPatternsAndConditionalSchemas(){
        Map<String,Object> schema=m("type","object","properties",m("kind",m("enum",Arrays.asList("A","B")),"name",m("type","string")),"patternProperties",m("^x-",m("type","integer")),"additionalProperties",false,"dependentRequired",m("name",Arrays.asList("kind")),"if",m("required",Arrays.asList("kind"),"properties",m("kind",m("const","A"))),"then",m("required",Arrays.asList("name")),"else",m("not",m("required",Arrays.asList("name"))));
        assertNull(JavaToolSchema.validate(schema,m("kind","A","name","yes","x-one",1)));
        assertEquals("required",JavaToolSchema.validate(schema,m("kind","A")).keyword);
        assertEquals("dependentRequired",JavaToolSchema.validate(m("type","object","dependentRequired",m("name",Arrays.asList("kind"))),m("name","yes")).keyword);
        assertEquals("type",JavaToolSchema.validate(schema,m("kind","B","x-one","wrong")).keyword);
        assertEquals("not",JavaToolSchema.validate(schema,m("kind","B","name","yes")).keyword);
    }
    @Test void rejectsRemoteRefsMalformedSchemasAndUnsupportedAssertions(){
        assertThrows(IllegalArgumentException.class,()->JavaToolSchema.validateSchema(object(m("x",m("$ref","https://example.test/schema")))));
        assertThrows(IllegalArgumentException.class,()->JavaToolSchema.validateSchema(object(m("x",m("$ref","#/$defs/missing")))));
        assertThrows(IllegalArgumentException.class,()->JavaToolSchema.validateSchema(object(m("x",m("type","integer","minimum",true)))));
        assertThrows(IllegalArgumentException.class,()->JavaToolSchema.validateSchema(object(m("x",m("type","string","pattern","[")))));
        assertThrows(IllegalArgumentException.class,()->JavaToolSchema.validateSchema(m("type","object","unevaluatedProperties",false)));
        assertThrows(IllegalArgumentException.class,()->JavaToolSchema.validateSchema(m("type","object","enum",Arrays.asList(1,1.0))));
        assertEquals("type",JavaToolSchema.validate(object(m("x",m("type","number"))),m("x",Double.NaN)).keyword);
    }
    @Test void allCurrentProductionToolSchemasAreSupported()throws Exception{
        int validated=0;
        for(String owner:Arrays.asList("EvidenceInvestigation","AgentLedReview")){
            java.lang.reflect.Method tools=Class.forName("cn.bidcheck.verification.engine."+owner).getDeclaredMethod("tools");tools.setAccessible(true);
            for(Object raw:(List<?>)tools.invoke(null)){
                @SuppressWarnings("unchecked") Map<String,Object> schema=(Map<String,Object>)((Map<?,?>)raw).get("parameters");JavaToolSchema.validateSchema(schema);validated++;
            }
        }
        assertTrue(validated>=16);
    }
}
