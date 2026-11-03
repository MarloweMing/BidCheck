package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlobalReviewCompilerTest {
    @Test void configuredModelCannotCompileGlobalReviewIntoAnAtomicPass() throws Exception {
        ModelGateway model=new ModelGateway() {
            public boolean enabled() { return true; }
            public String identity() { return "must-not-compile-global"; }
            public JsonNode request(String operation,Map<String,Object> input) { throw new AssertionError("global review must not be compiled from model excerpts"); }
        };
        Checkpoint cp=new Checkpoint("c","全文证件有效期覆盖检查","效期检查","HIGH","FULL_TEXT_REVIEW","certificateCoverage","REVIEW","2026-10-20","DATE",true,true,true,null);
        cn.bidcheck.verification.engine.VerificationTypes.Plan plan=new RequirementCompiler().compile(cp,new RunSession(model,cn.bidcheck.compat.Java8Api.mapOf(),"scope"));
        assertTrue(plan.rules().isEmpty());
        assertFalse(plan.issues().isEmpty());
        Document doc=new Document("b","p","BID","投标.pdf",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"证书有效期至2027年10月20日","PAGE","PDFBOX",null)),cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("UNDETERMINED",new VerificationEngine(model,null).evaluate(cp,cn.bidcheck.compat.Java8Api.listOf(doc),null,cn.bidcheck.compat.Java8Api.mapOf()).get("businessDecision"));
    }
}
