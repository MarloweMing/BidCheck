package cn.bidcheck.service;
import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FocusedRequirementArtifactScopeTest {
    Document tender(){return new Document("t","p","TENDER","招标.docx",2,"hash","",List.of(),List.of());}
    Map<String,Object> request(){return new LinkedHashMap<>(Map.of("projectId","p","documentId","t","documentHash","hash","documentVersion",2,
        "artifactId","a","compilerPolicyVersion","c","modelIdentity","m","inputFingerprint","i"));}
    @Test void onlyExactFrozenTenderScopeCanBeReadOrWritten(){
        assertTrue(FocusedCheckContext.validRequirementArtifactScope(request(),"p",tender()));
        for(String key:List.of("projectId","documentId","documentHash","artifactId","compilerPolicyVersion","modelIdentity","inputFingerprint")){
            var bad=request();bad.put(key,"");assertFalse(FocusedCheckContext.validRequirementArtifactScope(bad,"p",tender()),key);
        }
        var stale=request();stale.put("documentVersion",1);assertFalse(FocusedCheckContext.validRequirementArtifactScope(stale,"p",tender()));
        assertFalse(FocusedCheckContext.validRequirementArtifactScope(request(),"other",tender()));
        assertFalse(FocusedCheckContext.validRequirementArtifactScope(request(),"p",null));
    }
}
