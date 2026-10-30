package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.engine.CheckpointExtractor;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequirementSourceRepairTest {
    private Document doc(String... blocks){List<Page> pages=new ArrayList<>();for(int i=0;i<blocks.length;i++)pages.add(new Page(i+1,blocks[i],"BLOCK","NATIVE",""));return new Document("tender","project","TENDER","招标",1,"frozen-hash","",pages,List.of());}
    private Checkpoint checkpoint(String title,String field,String expected,String source){return new Checkpoint("test",title,"响应检查","MEDIUM","SEMANTIC_RESPONSE",field,"RESPONDS",expected,"",true,true,true,new Evidence("tender","招标",1,"BLOCK",source,"p1",1));}
    private Map<String,Object> input(Document document){return Map.of("tenderDocuments",List.of(document),"requirementProjectId","project");}
    private static final String PROOF="证明材料：需提交有效证明文件，并提供查询网址及网址查询截图。";
    private static final String CERTIFICATES="综合能力 / （商务）\t（1）施工单位具备质量管理体系认证证书，得2分； / （2）施工单位具备职业健康安全管理体系认证，得2分； / （3）施工单位具备环境管理体系认证，得2分。 / "+PROOF+"\t6";

    @Test void legacyCertificateProjectionRestoresOnlyItsBranchAndSharedProof(){
        for(String name:List.of("质量管理体系认证证书","职业健康安全管理体系认证","环境管理体系认证")){
            String expected="施工单位："+name+"。"+PROOF;var cp=checkpoint(expected,"scoring",expected,CERTIFICATES);
            var resolved=RequirementSourceRepair.resolve(cp,CERTIFICATES,input(doc(CERTIFICATES)));
            assertTrue(resolved.scope().resolved());assertEquals("CERTIFICATE_BRANCH_SOURCE_RESTORED",resolved.scope().status());
            assertEquals(resolved.scope().obligation(),CERTIFICATES.substring(resolved.scope().start(),resolved.scope().end()));
            assertTrue(resolved.scope().obligation().contains("得2分"));
            String requirement=RequirementReferences.requirementText(resolved.scope().obligation(),resolved.context());
            assertTrue(requirement.contains(PROOF));assertEquals(1,requirement.split("施工单位",-1).length-1);
            Map<String,Object> proof=resolved.context().sources().stream().filter(s->"SCOPED_PROOF".equals(s.get("kind"))).findFirst().orElseThrow();
            assertEquals(PROOF,CERTIFICATES.substring((int)proof.get("start"),(int)proof.get("end")));
            assertFalse(RequirementReferences.sourceBound(resolved.scope().obligation()+"\n"+PROOF,resolved.scope().obligation(),resolved.context()));
        }
    }
    @Test void certificateMigrationRejectsUnboundEditedRequirementsAndDuplicates(){
        String expected="施工单位：质量管理体系认证证书。"+PROOF;
        assertFalse(RequirementSourceRepair.resolve(checkpoint(expected,"scoring",expected+"证书覆盖全国",CERTIFICATES),CERTIFICATES,input(doc(CERTIFICATES))).scope().resolved());
        String duplicate=CERTIFICATES.replace("职业健康安全管理体系认证","质量管理体系认证证书");
        assertFalse(RequirementSourceRepair.resolve(checkpoint(expected,"scoring",expected,duplicate),duplicate,input(doc(duplicate))).scope().resolved());
        assertFalse(RequirementSourceRepair.resolve(checkpoint(expected,"scoring",expected,CERTIFICATES),CERTIFICATES,input(doc(CERTIFICATES+"\n"+CERTIFICATES))).scope().resolved());
    }
    @Test void certificateSplitNeverDropsAncestorConditionsAlternativesOrScoreCaps(){
        String expected="施工单位：质量管理体系认证证书。"+PROOF;
        for(String prefix:List.of("仅联合体投标时计分：","以下任选其一：","各项合计最多计2分：","如为施工单位：","条件未分类：")){
            String source=prefix+CERTIFICATES.substring(CERTIFICATES.indexOf("（1）"));
            assertFalse(RequirementSourceRepair.resolve(checkpoint(expected,"scoring",expected,source),source,input(doc(source))).scope().resolved(),prefix);
            var generated=new CheckpointExtractor().extract(doc("评分标准",source)).stream().filter(c->"scoring".equals(c.field())).toList();
            assertTrue(generated.stream().allMatch(c->c.expected().contains(prefix)),prefix);
        }
    }
    @Test void bookConditionUsesUniqueNamedOriginalReferenceAndKeepsTheException(){
        String heading="法定代表人（负责人）授权委托书",condition="法定代表人直接签署投标文件时无需提交";
        String expected=heading+"；按招标文件确认适用条件。"+condition;
        String reference="文件签署权\t按规定提交《法定代表人身份证明》及《法定代表人授权委托书》（"+condition+"）。";
        var cp=checkpoint("适用时提供："+heading,"conditionalMaterial",expected,heading);
        var resolved=RequirementSourceRepair.resolve(cp,heading,input(doc(heading,reference)));
        assertTrue(resolved.scope().resolved());assertEquals("BOOK_CONDITION_SOURCE_RESTORED",resolved.scope().status());
        assertEquals("《法定代表人授权委托书》（"+condition+"）",resolved.scope().obligation());
        assertEquals(resolved.scope().obligation(),reference.substring(resolved.scope().start(),resolved.scope().end()));
        assertEquals(2,resolved.scope().sourceLocation().get("location"));
        assertFalse(RequirementSourceRepair.resolve(cp,heading,input(doc(heading))).scope().resolved());
        assertFalse(RequirementSourceRepair.resolve(cp,heading,input(doc(heading,reference,reference))).scope().resolved());
        assertFalse(RequirementSourceRepair.resolve(cp,heading,input(doc(heading,reference.replace(condition,"代理人签署时无需提交")))).scope().resolved());
    }
    @Test void newlyExtractedCertificatesKeepScoreAndValidityWithExplicitSpans(){
        Document document=doc("评分标准",CERTIFICATES);var cp=new CheckpointExtractor().extract(document).stream().filter(c->"scoring".equals(c.field())).findFirst().orElseThrow();
        assertTrue(cp.expected().contains("得2分"));assertTrue(cp.expected().contains(PROOF));
        var resolution=RequirementSourceRepair.resolve(cp,cp.source().quote(),input(document));assertTrue(resolution.scope().resolved());
    }
    @Test void crossBlockPlanKeepsAllTiersAndVerifiesEveryFrozenSource(){
        String content="提供设计方案。方案包括目标分析、总体设计和风险措施。";
        String rubric="根据方案详细内容评分： / A:方案完整、合理得3分； / B:方案基本完整得1分； / C:未提交得0分。\t3";
        Document document=doc("评分标准",content,rubric);var cp=new CheckpointExtractor().extract(document).stream().filter(c->"scoring".equals(c.field())).findFirst().orElseThrow();
        assertEquals(content+"\n"+rubric,cp.expected());assertEquals(content,cp.source().quote());
        var resolution=RequirementSourceRepair.resolve(cp,cp.source().quote(),input(document));
        assertTrue(resolution.scope().resolved());assertEquals("CONTIGUOUS_SCORING_SOURCES",resolution.scope().status());
        String requirement=RequirementReferences.requirementText(resolution.scope().obligation(),resolution.context());assertEquals(cp.expected(),requirement);
        assertFalse(RequirementReferences.sourceBound(content+"\n"+rubric,resolution.scope().obligation(),resolution.context()));
        assertFalse(RequirementSourceRepair.resolve(cp,cp.source().quote(),input(doc("评分标准",content,rubric.replace("3分","2分")))).scope().resolved());
    }
    @Test void discreteTitleAwardsPreserveLowerTierInsteadOfInventingMandatoryTopTier(){
        String source="项目负责人实力\t具有高级职称得3分，中级得1分，未提交不得分。";
        var cp=new CheckpointExtractor().extract(doc("评分标准",source)).stream().filter(c->"scoring".equals(c.field())).findFirst().orElseThrow();
        assertEquals(source,cp.expected());assertTrue(cp.expected().contains("中级得1分"));
    }
    @Test void finalScoringPageAllowsOnlyItsUniqueExactLeadingSpan(){
        String content="提供设计方案。",rubric="A:方案完整得3分；\nB:未提供得0分。";
        var cp=checkpoint("方案评分核查","scoring",content+"\n"+rubric,content);
        String finalPage=rubric+"\n下一评分项：设计业绩。";
        var resolution=RequirementSourceRepair.resolve(cp,content,input(doc(content,finalPage)));
        assertTrue(resolution.scope().resolved());
        Map<String,Object> last=resolution.context().sources().stream().filter(s->"SCOPED_SCORING_CONTINUATION".equals(s.get("kind"))).findFirst().orElseThrow();
        assertEquals(rubric,finalPage.substring((int)last.get("start"),(int)last.get("end")));
        assertFalse(RequirementSourceRepair.resolve(cp,content,input(doc(content,"不得删除的中间条件",finalPage))).scope().resolved());
        assertFalse(RequirementSourceRepair.resolve(cp,content,input(doc(content,rubric+"尾部属于同一条件"))).scope().resolved());
        assertFalse(RequirementSourceRepair.resolve(cp,content,input(doc(content,rubric+"\n"+rubric))).scope().resolved());
    }
    @Test void unmatchedLegacyTierProjectionHasSpecificSourceMappingDiagnostic(){
        String original="项目负责人具有高级职称得3分，中级得1分。证明材料：须提供证书。",expected="具有高级职称。证明材料：须提供证书。";
        var resolved=RequirementSourceRepair.resolve(checkpoint("响应评分","scoring",expected,original),original,input(doc(original)));
        assertFalse(resolved.scope().resolved());assertTrue(resolved.context().identity().toString().contains("SCORING_PROJECTION_OR_CROSS_BLOCK_SOURCE_MISMATCH"));
    }
}
