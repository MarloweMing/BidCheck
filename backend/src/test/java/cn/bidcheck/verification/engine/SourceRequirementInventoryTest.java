package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceRequirementInventoryTest {
    private Document tender(String... blocks){List<Page> pages=new ArrayList<>();for(int i=0;i<blocks.length;i++)pages.add(new Page(i+1,blocks[i],"BLOCK","TEXT",null));return new Document("t","p","TENDER","招标",1,"th","",pages,cn.bidcheck.compat.Java8Api.listOf());}
    private Checkpoint cp(String source){return new Checkpoint("cp",source,"资格","HIGH","SEMANTIC_RESPONSE","qualification","RESPONDS",source,"",true,true,true,new Evidence("t","招标",1,"BLOCK",source,"",1));}
    private String body(String name){return "投标人承诺按照本项目招标文件要求完成全部工作，项目名称及本次投标人名称应当如实填写，不得修改承诺约定的具体内容，发生违约时自愿承担相应责任。投标人名称：____，日期：____。";}
    @Test void namedCommitmentsRequireAClosedBodyInventoryNotTheContentsPage(){String source="须提交《供应商合规承诺》等必须的承诺文件（清单详见第六章中《承诺书》）。";
        Document doc=tender(source,"第六章 投标文件格式","★承诺书","其他承诺书","★承诺书","供应商合规承诺","供应商合规承诺",body("供应商合规承诺"),"安全承诺书","安全承诺书",body("安全承诺书"),"其他承诺");
        cn.bidcheck.verification.engine.SourceRequirementInventory.Inventory inventory=SourceRequirementInventory.resolve(cp(source),source,cn.bidcheck.compat.Java8Api.mapOf("tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(doc)));
        assertTrue(inventory.issues().isEmpty(),inventory.toString());assertEquals(cn.bidcheck.compat.Java8Api.listOf("供应商合规承诺", "安全承诺书"),cn.bidcheck.compat.Java8Api.toList(inventory.members().stream().map(SourceRequirementInventory.Member::name)));
        Rule rule=SourceRequirementInventory.compile(cp(source),source,null,cn.bidcheck.compat.Java8Api.mapOf("tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(doc))).get(0);
        cn.bidcheck.verification.engine.VerificationTypes.Decision result=SourceRequirementInventory.evaluate(rule,new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.mapOf("tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(doc))));
        assertEquals("UNDETERMINED",result.status());assertEquals(2,result.proof().get("expectedMemberCount"));
    }
    @Test void anUnclosedInventoryCannotPass(){String source="清单详见《承诺书》";Document doc=tender(source,"承诺书","安全承诺书",body("安全承诺书"));
        cn.bidcheck.verification.engine.SourceRequirementInventory.Inventory inventory=SourceRequirementInventory.resolve(cp(source),source,cn.bidcheck.compat.Java8Api.mapOf("tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(doc)));
        assertFalse(inventory.issues().isEmpty());
    }
    @Test void referencedTechnicalAttachmentIsNotAssumedToBeBidderMaterialMissing(){String source="技术规范书中明确要求投标人提供的其他相关文件";Document doc=tender(source,"相关内容详见《招标文件附件：技术规范书》。");Map<String,Object> context=cn.bidcheck.compat.Java8Api.mapOf("tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(doc));
        Rule rule=SourceRequirementInventory.compile(cp(source),source,null,context).get(0);Decision result=SourceRequirementInventory.evaluate(rule,new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(),new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf()),context));
        assertEquals("MISSING_TENDER_ATTACHMENT",result.gaps().get(0).code());assertNotEquals("FAIL",result.status());
    }
    @Test void summaryDoesNotCopyPriceRulesOrDropTheUniquePriceObligation(){String source="只能有一个有效报价，并符合第三章3.1.2初步评审要求。";
        assertTrue(SourceRequirementInventory.compile(cp(source),source,null,cn.bidcheck.compat.Java8Api.mapOf()).isEmpty());
    }
    @Test void sourceChangedOrAbsentCannotBorrowAnInventory(){String source="清单详见《承诺书》";Document doc=tender("其他要求","承诺书","安全承诺书",body("安全承诺书"),"其他承诺");
        assertFalse(SourceRequirementInventory.resolve(cp(source),source,cn.bidcheck.compat.Java8Api.mapOf("tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(doc))).issues().isEmpty());
    }
    @Test void unnamedBodyCannotMakeANamedMemberDisappear(){String source="清单详见《承诺书》";Document doc=tender(source,"承诺书","安全承诺书",body("安全承诺书"),"保密承诺书","空白","其他承诺");
        cn.bidcheck.verification.engine.SourceRequirementInventory.Inventory inventory=SourceRequirementInventory.resolve(cp(source),source,cn.bidcheck.compat.Java8Api.mapOf("tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(doc)));
        assertEquals(2,inventory.members().size());assertTrue(inventory.issues().stream().anyMatch(s->s.contains("保密承诺书")));
    }
    @Test void recursiveReferenceCycleIsNotTreatedAsFullCoverage(){String source="按照3.1.2条款";Document doc=tender(source,"3.1.2 按照3.1.3条款","3.1.3 按照3.1.2条款","3.1.4 其他要求");
        java.util.Map<java.lang.String, java.lang.Object> context=cn.bidcheck.compat.Java8Api.<String, Object>mapOf("tenderDocuments", cn.bidcheck.compat.Java8Api.listOf(doc));cn.bidcheck.verification.engine.RequirementSourceContext.Context resolved=RequirementSourceContext.resolve(cp(source),source,context);
        cn.bidcheck.verification.engine.RequirementReferences.Expansion expansion=RequirementReferences.expand(cp(source),source,resolved,context);
        assertTrue(expansion.issues().stream().anyMatch(s->s.contains("循环")),expansion.toString());
    }
    @Test void splitChildrenMustCoverEveryObligationRegardlessOfCompletionOrder(){
        String source="须提交营业执照；须提交安全许可证";cn.bidcheck.verification.engine.SourceRequirementInventory.Member member=new SourceRequirementInventory.Member("完整原文",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", "t", "locationType", "BLOCK", "location", 1, "sourceQuote", source)));
        Finding first=child("a",source,"须提交营业执照"),second=child("b",source,"须提交安全许可证");
        assertFalse(SourceRequirementInventory.sourceCovered(member,cn.bidcheck.compat.Java8Api.listOf(first)));
        assertTrue(SourceRequirementInventory.sourceCovered(member,cn.bidcheck.compat.Java8Api.listOf(first, second)));
        assertTrue(SourceRequirementInventory.sourceCovered(member,cn.bidcheck.compat.Java8Api.listOf(second, first)));
    }
    @Test void quoteSharingWithoutCoveredBusinessTextCannotCloseAggregate(){
        String source="须提交营业执照；证书须在投标截止日有效";cn.bidcheck.verification.engine.SourceRequirementInventory.Member member=new SourceRequirementInventory.Member("完整原文",cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", "t", "locationType", "BLOCK", "location", 1, "sourceQuote", source)));
        assertFalse(SourceRequirementInventory.sourceCovered(member,cn.bidcheck.compat.Java8Api.listOf(child("a",source,"须提交营业执照"))));
    }
    @Test void chapterInGeneralContentsCannotReplaceTheActualFormatCatalogue(){
        Document doc=tender("第七章 技术标准和要求","第八章 投标文件格式","招标人另有要求的，见投标人须知前附表。",
                "第六章 投标文件格式","第一分册 商务投标文件","投标函","安全承诺书","第一分册 商务投标文件","投标函","致：（招标人）：");
        cn.bidcheck.verification.engine.SourceRequirementInventory.Inventory inventory=SourceRequirementInventory.format(doc);
        assertTrue(inventory.issues().isEmpty(),inventory.toString());
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("投标函", "安全承诺书"),cn.bidcheck.compat.Java8Api.toList(inventory.members().stream().map(SourceRequirementInventory.Member::name)));
        assertTrue(inventory.sources().stream().allMatch(s->((Number)s.get("location")).intValue()>=4));
    }
    @Test void repeatedVolumeHeadingWithoutAnActualFormBodyDoesNotCloseTheCatalogue(){
        Document doc=tender("第六章 投标文件格式","第一分册 商务投标文件","投标函","第一分册 商务投标文件","投标函","另一个目录条目");
        assertFalse(SourceRequirementInventory.format(doc).issues().isEmpty());
    }
    @Test void crossPageSynonymsRequireTheWholeSameObligationIncludingItsConditions(){
        String quote="须提供施工单位近三个月的社保证明";
        Document doc=tender("本项目不划分标段。","投标人资格要求","施工单位资格要求","须提交施工单位近三个月的社保证明",quote);
        cn.bidcheck.verification.engine.SourceRequirementInventory.Member member=new SourceRequirementInventory.Member(quote,cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("documentId", "t", "locationType", "BLOCK", "location", 5, "sourceQuote", quote)));
        Finding same=child("same","须提交施工单位近三个月的社保证明","须提交施工单位近三个月的社保证明",4);
        assertTrue(SourceRequirementInventory.sourceCovered(member,cn.bidcheck.compat.Java8Api.listOf(same),cn.bidcheck.compat.Java8Api.listOf(doc)));
        assertFalse(SourceRequirementInventory.sourceCovered(member,cn.bidcheck.compat.Java8Api.listOf(same)),"without frozen scope the identical phrase is not enough");
        assertFalse(SourceRequirementInventory.sourceCovered(member,cn.bidcheck.compat.Java8Api.listOf(child("wrong-window","须提交施工单位近六个月的社保证明","须提交施工单位近六个月的社保证明"))));
        assertFalse(SourceRequirementInventory.sourceCovered(member,cn.bidcheck.compat.Java8Api.listOf(child("wrong-owner","须提交设计单位近三个月的社保证明","须提交设计单位近三个月的社保证明"))));
        assertFalse(SourceRequirementInventory.sourceCovered(member,cn.bidcheck.compat.Java8Api.listOf(child("missing-window","须提交施工单位的社保证明","须提交施工单位的社保证明"))));
    }
    @Test void identicalPhrasesInOtherLotsOrInheritedSubjectsCannotSupplyParentProof(){
        String quote="须提交有效营业执照";
        Document lots=tender("投标人资格要求","第一标段设计单位资格要求",quote,"第二标段施工单位资格要求",quote);
        cn.bidcheck.verification.engine.SourceRequirementInventory.Member member=new SourceRequirementInventory.Member(quote,cn.bidcheck.compat.Java8Api.listOf(SourceObligationDirectory.source(lots,lots.pages().get(4))));
        assertFalse(SourceRequirementInventory.sourceCovered(member,cn.bidcheck.compat.Java8Api.listOf(child("other-lot",quote,quote,3)),cn.bidcheck.compat.Java8Api.listOf(lots)));
        Document actors=tender("本项目不划分标段。","投标人资格要求","设计单位资格要求",quote,"施工单位资格要求",quote);
        cn.bidcheck.verification.engine.SourceRequirementInventory.Member actorMember=new SourceRequirementInventory.Member(quote,cn.bidcheck.compat.Java8Api.listOf(SourceObligationDirectory.source(actors,actors.pages().get(5))));
        assertFalse(SourceRequirementInventory.sourceCovered(actorMember,cn.bidcheck.compat.Java8Api.listOf(child("other-subject",quote,quote,4)),cn.bidcheck.compat.Java8Api.listOf(actors)));
    }
    private Finding child(String id,String source,String selected){
        return child(id,source,selected,1);
    }
    private Finding child(String id,String source,String selected,int page){
        cn.bidcheck.domain.Domain.Checkpoint cp=new Checkpoint(id,selected,"资格","HIGH","SEMANTIC_RESPONSE","qualification","RESPONDS",selected,"",true,true,true,new Evidence("t","招标",page,"BLOCK",source,"",1));
        return new Finding("f-"+id,"run",cp,new Evaluation("PASS","完整子项证明","",cn.bidcheck.compat.Java8Api.listOf(),1,"RULE"),cn.bidcheck.compat.Java8Api.mapOf());
    }
}
