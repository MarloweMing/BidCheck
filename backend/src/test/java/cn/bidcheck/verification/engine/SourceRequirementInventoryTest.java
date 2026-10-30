package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceRequirementInventoryTest {
    private Document tender(String... blocks){List<Page> pages=new ArrayList<>();for(int i=0;i<blocks.length;i++)pages.add(new Page(i+1,blocks[i],"BLOCK","TEXT",null));return new Document("t","p","TENDER","招标",1,"th","",pages,List.of());}
    private Checkpoint cp(String source){return new Checkpoint("cp",source,"资格","HIGH","SEMANTIC_RESPONSE","qualification","RESPONDS",source,"",true,true,true,new Evidence("t","招标",1,"BLOCK",source,"",1));}
    private String body(String name){return "投标人承诺按照本项目招标文件要求完成全部工作，项目名称及本次投标人名称应当如实填写，不得修改承诺约定的具体内容，发生违约时自愿承担相应责任。投标人名称：____，日期：____。";}
    @Test void namedCommitmentsRequireAClosedBodyInventoryNotTheContentsPage(){String source="须提交《供应商合规承诺》等必须的承诺文件（清单详见第六章中《承诺书》）。";
        Document doc=tender(source,"第六章 投标文件格式","★承诺书","其他承诺书","★承诺书","供应商合规承诺","供应商合规承诺",body("供应商合规承诺"),"安全承诺书","安全承诺书",body("安全承诺书"),"其他承诺");
        var inventory=SourceRequirementInventory.resolve(cp(source),source,Map.of("tenderDocuments",List.of(doc)));
        assertTrue(inventory.issues().isEmpty(),inventory.toString());assertEquals(List.of("供应商合规承诺","安全承诺书"),inventory.members().stream().map(SourceRequirementInventory.Member::name).toList());
        Rule rule=SourceRequirementInventory.compile(cp(source),source,null,Map.of("tenderDocuments",List.of(doc))).get(0);
        var result=SourceRequirementInventory.evaluate(rule,new RuleEvaluator(List.of(),new EvidenceCorpus(List.of()),Map.of("tenderDocuments",List.of(doc))));
        assertEquals("UNDETERMINED",result.status());assertEquals(2,result.proof().get("expectedMemberCount"));
    }
    @Test void anUnclosedInventoryCannotPass(){String source="清单详见《承诺书》";Document doc=tender(source,"承诺书","安全承诺书",body("安全承诺书"));
        var inventory=SourceRequirementInventory.resolve(cp(source),source,Map.of("tenderDocuments",List.of(doc)));
        assertFalse(inventory.issues().isEmpty());
    }
    @Test void referencedTechnicalAttachmentIsNotAssumedToBeBidderMaterialMissing(){String source="技术规范书中明确要求投标人提供的其他相关文件";Document doc=tender(source,"相关内容详见《招标文件附件：技术规范书》。");Map<String,Object> context=Map.of("tenderDocuments",List.of(doc));
        Rule rule=SourceRequirementInventory.compile(cp(source),source,null,context).get(0);Decision result=SourceRequirementInventory.evaluate(rule,new RuleEvaluator(List.of(),new EvidenceCorpus(List.of()),context));
        assertEquals("MISSING_TENDER_ATTACHMENT",result.gaps().get(0).code());assertNotEquals("FAIL",result.status());
    }
    @Test void summaryDoesNotCopyPriceRulesOrDropTheUniquePriceObligation(){String source="只能有一个有效报价，并符合第三章3.1.2初步评审要求。";
        assertTrue(SourceRequirementInventory.compile(cp(source),source,null,Map.of()).isEmpty());
    }
    @Test void sourceChangedOrAbsentCannotBorrowAnInventory(){String source="清单详见《承诺书》";Document doc=tender("其他要求","承诺书","安全承诺书",body("安全承诺书"),"其他承诺");
        assertFalse(SourceRequirementInventory.resolve(cp(source),source,Map.of("tenderDocuments",List.of(doc))).issues().isEmpty());
    }
    @Test void unnamedBodyCannotMakeANamedMemberDisappear(){String source="清单详见《承诺书》";Document doc=tender(source,"承诺书","安全承诺书",body("安全承诺书"),"保密承诺书","空白","其他承诺");
        var inventory=SourceRequirementInventory.resolve(cp(source),source,Map.of("tenderDocuments",List.of(doc)));
        assertEquals(2,inventory.members().size());assertTrue(inventory.issues().stream().anyMatch(s->s.contains("保密承诺书")));
    }
    @Test void recursiveReferenceCycleIsNotTreatedAsFullCoverage(){String source="按照3.1.2条款";Document doc=tender(source,"3.1.2 按照3.1.3条款","3.1.3 按照3.1.2条款","3.1.4 其他要求");
        var context=Map.<String,Object>of("tenderDocuments",List.of(doc));var resolved=RequirementSourceContext.resolve(cp(source),source,context);
        var expansion=RequirementReferences.expand(cp(source),source,resolved,context);
        assertTrue(expansion.issues().stream().anyMatch(s->s.contains("循环")),expansion.toString());
    }
    @Test void splitChildrenMustCoverEveryObligationRegardlessOfCompletionOrder(){
        String source="须提交营业执照；须提交安全许可证";var member=new SourceRequirementInventory.Member("完整原文",List.of(Map.of("documentId","t","locationType","BLOCK","location",1,"sourceQuote",source)));
        Finding first=child("a",source,"须提交营业执照"),second=child("b",source,"须提交安全许可证");
        assertFalse(SourceRequirementInventory.sourceCovered(member,List.of(first)));
        assertTrue(SourceRequirementInventory.sourceCovered(member,List.of(first,second)));
        assertTrue(SourceRequirementInventory.sourceCovered(member,List.of(second,first)));
    }
    @Test void quoteSharingWithoutCoveredBusinessTextCannotCloseAggregate(){
        String source="须提交营业执照；证书须在投标截止日有效";var member=new SourceRequirementInventory.Member("完整原文",List.of(Map.of("documentId","t","locationType","BLOCK","location",1,"sourceQuote",source)));
        assertFalse(SourceRequirementInventory.sourceCovered(member,List.of(child("a",source,"须提交营业执照"))));
    }
    @Test void chapterInGeneralContentsCannotReplaceTheActualFormatCatalogue(){
        Document doc=tender("第七章 技术标准和要求","第八章 投标文件格式","招标人另有要求的，见投标人须知前附表。",
                "第六章 投标文件格式","第一分册 商务投标文件","投标函","安全承诺书","第一分册 商务投标文件","投标函","致：（招标人）：");
        var inventory=SourceRequirementInventory.format(doc);
        assertTrue(inventory.issues().isEmpty(),inventory.toString());
        assertEquals(List.of("投标函","安全承诺书"),inventory.members().stream().map(SourceRequirementInventory.Member::name).toList());
        assertTrue(inventory.sources().stream().allMatch(s->((Number)s.get("location")).intValue()>=4));
    }
    @Test void repeatedVolumeHeadingWithoutAnActualFormBodyDoesNotCloseTheCatalogue(){
        Document doc=tender("第六章 投标文件格式","第一分册 商务投标文件","投标函","第一分册 商务投标文件","投标函","另一个目录条目");
        assertFalse(SourceRequirementInventory.format(doc).issues().isEmpty());
    }
    @Test void crossPageSynonymsRequireTheWholeSameObligationIncludingItsConditions(){
        String quote="须提供施工单位近三个月的社保证明";
        Document doc=tender("本项目不划分标段。","投标人资格要求","施工单位资格要求","须提交施工单位近三个月的社保证明",quote);
        var member=new SourceRequirementInventory.Member(quote,List.of(Map.of("documentId","t","locationType","BLOCK","location",5,"sourceQuote",quote)));
        Finding same=child("same","须提交施工单位近三个月的社保证明","须提交施工单位近三个月的社保证明",4);
        assertTrue(SourceRequirementInventory.sourceCovered(member,List.of(same),List.of(doc)));
        assertFalse(SourceRequirementInventory.sourceCovered(member,List.of(same)),"without frozen scope the identical phrase is not enough");
        assertFalse(SourceRequirementInventory.sourceCovered(member,List.of(child("wrong-window","须提交施工单位近六个月的社保证明","须提交施工单位近六个月的社保证明"))));
        assertFalse(SourceRequirementInventory.sourceCovered(member,List.of(child("wrong-owner","须提交设计单位近三个月的社保证明","须提交设计单位近三个月的社保证明"))));
        assertFalse(SourceRequirementInventory.sourceCovered(member,List.of(child("missing-window","须提交施工单位的社保证明","须提交施工单位的社保证明"))));
    }
    @Test void identicalPhrasesInOtherLotsOrInheritedSubjectsCannotSupplyParentProof(){
        String quote="须提交有效营业执照";
        Document lots=tender("投标人资格要求","第一标段设计单位资格要求",quote,"第二标段施工单位资格要求",quote);
        var member=new SourceRequirementInventory.Member(quote,List.of(SourceObligationDirectory.source(lots,lots.pages().get(4))));
        assertFalse(SourceRequirementInventory.sourceCovered(member,List.of(child("other-lot",quote,quote,3)),List.of(lots)));
        Document actors=tender("本项目不划分标段。","投标人资格要求","设计单位资格要求",quote,"施工单位资格要求",quote);
        var actorMember=new SourceRequirementInventory.Member(quote,List.of(SourceObligationDirectory.source(actors,actors.pages().get(5))));
        assertFalse(SourceRequirementInventory.sourceCovered(actorMember,List.of(child("other-subject",quote,quote,4)),List.of(actors)));
    }
    private Finding child(String id,String source,String selected){
        return child(id,source,selected,1);
    }
    private Finding child(String id,String source,String selected,int page){
        var cp=new Checkpoint(id,selected,"资格","HIGH","SEMANTIC_RESPONSE","qualification","RESPONDS",selected,"",true,true,true,new Evidence("t","招标",page,"BLOCK",source,"",1));
        return new Finding("f-"+id,"run",cp,new Evaluation("PASS","完整子项证明","",List.of(),1,"RULE"),Map.of());
    }
}
