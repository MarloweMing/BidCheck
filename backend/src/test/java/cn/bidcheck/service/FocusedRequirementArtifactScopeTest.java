package cn.bidcheck.service;
import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FocusedRequirementArtifactScopeTest {
    Document tender(){return new Document("t","p","TENDER","招标.docx",2,"hash","",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());}
    Map<String,Object> request(){return new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("projectId", "p", "documentId", "t", "documentHash", "hash", "documentVersion", 2, "artifactId", "a", "compilerPolicyVersion", "c", "modelIdentity", "m", "inputFingerprint", "i"));}
    @Test void onlyExactFrozenTenderScopeCanBeReadOrWritten(){
        assertTrue(FocusedCheckContext.validRequirementArtifactScope(request(),"p",tender()));
        for(String key:cn.bidcheck.compat.Java8Api.listOf("projectId", "documentId", "documentHash", "artifactId", "compilerPolicyVersion", "modelIdentity", "inputFingerprint")){
            java.util.Map<java.lang.String, java.lang.Object> bad=request();bad.put(key,"");assertFalse(FocusedCheckContext.validRequirementArtifactScope(bad,"p",tender()),key);
        }
        java.util.Map<java.lang.String, java.lang.Object> stale=request();stale.put("documentVersion",1);assertFalse(FocusedCheckContext.validRequirementArtifactScope(stale,"p",tender()));
        assertFalse(FocusedCheckContext.validRequirementArtifactScope(request(),"other",tender()));
        assertFalse(FocusedCheckContext.validRequirementArtifactScope(request(),"p",null));
    }
}
