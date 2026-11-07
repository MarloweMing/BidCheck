package cn.bidcheck.verification.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.ContractRecordSubmissionTest.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkOriginalWorkTest {
    private static final String BASE="甲市营业厅装修改造集中采购框架合同";
    private static final String TITLE=BASE+"（标段1新建厅）（2023年-合成建设）";
    private static final String CLIENT="甲省通信有限公司";
    private static final String NO="CU12-TEST-2023-100144";
    private static final String PARTIES="甲方："+CLIENT+"\n乙方："+COMPANY;
    private static final String BOTH="签署页（此页无正文）\n甲方："+CLIENT+"（盖章）\n授权代表：（签字）\n日期：2023年10月25日\n乙方："+COMPANY+"（盖章）\n授权代表：\n日期：2023年10月25日";
    private static final String PO="订单号：PO-SYN-301\n协议：["+NO+"]"+BASE+"（标段1新建厅）\n本次订单金额100万元";

    private static class Packet {
        EvidenceCorpus c;
        EvidenceInvestigation.Host host;
        Rule parent;
        Map<String,Object> row;
        Packet(String signedPage,String laterOrderText) throws Exception {
            c=corpus("投标函\n本项目施工单位："+COMPANY+"\n投标人："+COMPANY,
                list("1 "+BASE+" 签订时间：2023年10月25日 金额：200万元"),
                "①"+BASE+"\n# "+TITLE+"\n甲方："+CLIENT,PARTIES,signedPage,
                "对应订单\n订单号 金额",PO,laterOrderText);
            parent=withRows(scoringRule(),c);
            row=RuleEvaluator.maps(parent.parameters().get("declaredPerformanceReadRows")).get(0);
            Map<String,Object> ctx=dates();
            host=new EvidenceInvestigation.Host(checkpoint(),new Plan("ALL",Collections.singletonList(parent),"SYNTHETIC",Collections.emptyList()),
                SourcePerformanceScore.evidenceRules(parent),c,new LinkedHashMap<>(),ctx,new SharedFactReuse(c,ctx,"synthetic","synthetic"),
                new RunSession(ModelGateway.disabled(),ctx,"synthetic"),null,new ArrayList<>(),Collections.emptySet());
            host.start();
        }
        List<Fact> fields(String field) {
            return cn.bidcheck.compat.Java8Api.toList(host.facts.values().stream().filter(f->"CONTRACT".equals(f.kind())&&field.equals(f.field())));
        }
        Map<String,List<Fact>> groups(){
            Map<String,List<Fact>> groups=new LinkedHashMap<>();groups.put("parentRef",fields("contractNumber"));
            List<Fact> original=new ArrayList<>(fields("party"));original.addAll(fields("contractName"));original.addAll(fields("signedDate"));
            groups.put("original",original);return groups;
        }
        Map<String,Object> reconcile(Map<String,List<Fact>> groups,Map<String,Object> visual){
            Map<String,Object> context=dates();
            if(visual!=null){context.put("visualObservations",Collections.singletonList(visual));c.addVisualObservations(Collections.singletonList(visual));}
            return FrameworkNumberEvidence.reconcile(parent,row,groups,new RuleEvaluator(new ArrayList<>(host.facts.values()),c,context));
        }
        Map<String,Object> image(String number){
            Source s=source(c,3);
            return linked("type","FIELD","requestedTask","FIELD","requestedFields",Collections.singletonList("合同编号"),
                "obligationId",parent.id(),"requestedSourceIds",Collections.singletonList(s.id()),"documentId",s.evidence().documentId(),
                "location",3,"locationType","PAGE","imageHash","synthetic-image","readability","CLEAR","label","合同编号","value",number,
                "sourceBbox",Arrays.asList(10,10,200,40),"source",linked("documentId",s.evidence().documentId(),"documentHash",s.documentHash(),
                    "documentVersion",s.documentVersion(),"page",3,"renderedSha256","synthetic-image","renderedWidth",800,"renderedHeight",600));
        }
    }

    @Test void sameGridTitleCanRepairTheFrozenSeparatorOnlyInTheSameOriginalRow() {
        EvidenceCorpus c=corpus("合同清单","①甲市项目","合同编号：SYN-001\n甲市项目工程采购合同");
        Map<String,Object> frozen=linked("documentId","synthetic-bid","documentHash","synthetic-hash","documentVersion",1,"row",1,
            "inventoryIdentity","one","inventoryPage",1,"rowQuote","甲市项目","sourceIds",Collections.singletonList(source(c,1).id()),
            "separatorTitle","甲市项目","separatorSourceIds",Collections.singletonList(source(c,2).id()),"proofPageStart",2,"proofPageEnd",3);
        String title="甲市项目工程采购合同";
        Map<String,Object> verified=new LinkedHashMap<>(frozen);verified.put("contractName",title);
        verified.put("sourceBoundTableFields",linked("序号","1","合同名称",title));c.sourceBoundContractRows.put("one",verified);
        Source body=source(c,3);String number="SYN-001";
        Fact id=new Fact("synthetic","CONTRACT","","contractNumber",number,number,"","","HISTORICAL_PROJECT",Collections.singletonList(body.id()),linked("contractNumber",number));
        assertFalse(new ContractRecordBindings(frozen,c.byId,Collections.singleton(body.id())).matches(id,Collections.singletonList(body)));
        assertTrue(new ContractRecordBindings(DeclaredPerformanceRows.rowWithOriginalTitle(frozen,c),c.byId,Collections.singleton(body.id())).matches(id,Collections.singletonList(body)));
        verified.put("documentVersion",2);assertSame(frozen,DeclaredPerformanceRows.rowWithOriginalTitle(frozen,c));
        verified.put("documentVersion",1);verified.put("proofPageEnd",4);assertSame(frozen,DeclaredPerformanceRows.rowWithOriginalTitle(frozen,c));
        verified.put("proofPageEnd",3);frozen.put("contractName","同名但不同标段的合同");
        assertFalse(new ContractRecordBindings(DeclaredPerformanceRows.rowWithOriginalTitle(frozen,c),c.byId,Collections.singleton(body.id())).matches(id,Collections.singletonList(body)));
    }

    @Test void separatedOrdersCannotPoisonOriginalPartiesAndBilateralDateOrProveParentNumber() throws Exception {
        Packet p=new Packet(BOTH,"合同编号：OTHER-ORDER-02\n签约日期：2024年1月1日");
        assertEquals(2,p.fields("party").size());
        for(Fact f:p.fields("party"))assertTrue(f.sourceIds().contains(source(p.c,4).id())&&f.sourceIds().contains(source(p.c,3).id()));
        Fact day=p.fields("signedDate").get(0);assertEquals("2023年10月25日",day.rawValue());
        assertTrue(day.sourceIds().contains(source(p.c,5).id()));
        assertTrue(p.fields("contractNumber").stream().allMatch(f->f.sourceIds().equals(Collections.singletonList(source(p.c,7).id()))));
        Map<String,List<Fact>> groups=p.groups();assertEquals("ORIGINAL_IMAGE_REQUIRED",p.reconcile(groups,null).get("status"));
        assertEquals(2,groups.size());
        assertEquals("ORIGINAL_NUMBER_MATCHED",p.reconcile(groups,p.image(NO)).get("status"));
        assertEquals(1,groups.size());assertTrue(p.fields("amount").isEmpty());
    }

    @Test void divergentNumberWrongCoverOrUnreadableImageNeverJoinTheOrderToTheOriginal() throws Exception {
        for(String fault:Arrays.asList("wrongNumber","wrongSource","wrongHash","unreadable")){
            Packet p=new Packet(BOTH,"订单附件续页");
            Map<String,Object> image=p.image("wrongNumber".equals(fault)?"CU12-OTHER-001":NO);
            if("wrongSource".equals(fault))image.put("requestedSourceIds",Collections.singletonList(source(p.c,7).id()));
            if("wrongHash".equals(fault))image.put("imageHash","another-image");
            if("unreadable".equals(fault))image.put("readability","UNCLEAR");
            Map<String,List<Fact>> groups=p.groups();
            assertNotEquals("ORIGINAL_NUMBER_MATCHED",p.reconcile(groups,image).get("status"),fault);assertEquals(2,groups.size(),fault);
        }
    }

    @Test void filedOrderGetsOnlyNavigationForOneVerifiedInventoryRange() throws Exception {
        Packet p=new Packet(BOTH,"订单附件续页");
        Fact order=p.fields("contractNumber").get(0);
        Map<String,Object> header=p.reconcile(p.groups(),null);
        Map<String,Object> check=linked("factIds",Collections.singletonList(order.id()),"sourceIds",order.sourceIds(),
            "contractNumbers",Collections.singletonList(NO),"counted",false);
        assertTrue(FrameworkNumberEvidence.attachOrderNavigation(check,p.row,Collections.singletonList(p.row),header,p.c));
        Map<?,?> navigation=(Map<?,?>)check.remove("orderNavigationEvidence");
        assertEquals("ORDER_REFERENCE_NAVIGATION_ONLY",navigation.get("method"));
        assertEquals(p.row.get("documentHash"),navigation.get("documentSha256"));
        assertEquals(p.row.get("documentVersion"),navigation.get("documentVersion"));
        assertEquals(p.row.get("documentId"),navigation.get("documentId"));
        assertEquals(check.get("factIds"),navigation.get("factIds"));assertEquals(check.get("sourceIds"),navigation.get("sourceIds"));
        assertEquals(Collections.singletonList(7),navigation.get("sourcePages"));
        assertEquals(p.row,check.remove("declaredNavigationRow"));assertFalse(check.containsKey("declaredRow"));
        assertEquals(false,check.get("counted"));assertEquals("ORIGINAL_IMAGE_REQUIRED",header.get("status"));
        Map<String,Object> overlap=new LinkedHashMap<>(p.row);overlap.put("inventoryIdentity","other-row");
        assertFalse(FrameworkNumberEvidence.attachOrderNavigation(check,p.row,Arrays.asList(p.row,overlap),header,p.c));
        for(String key:Arrays.asList("documentId","documentHash","documentVersion")){
            Map<String,Object> changed=new LinkedHashMap<>(p.row);changed.put(key,key.equals("documentVersion")?2:"other");
            assertFalse(FrameworkNumberEvidence.attachOrderNavigation(check,changed,Collections.singletonList(changed),header,p.c),key);
        }
        check.put("sourceIds",Arrays.asList(source(p.c,7).id(),source(p.c,4).id()));
        assertFalse(FrameworkNumberEvidence.attachOrderNavigation(check,p.row,Collections.singletonList(p.row),header,p.c));
        assertFalse(check.containsKey("declaredNavigationRow"));assertFalse(check.containsKey("declaredRow"));
    }

    @Test void standaloneSignatureFooterDoesNotInventTwoSigningDaysOrBorrowOrderDate()throws Exception {
        Packet p=new Packet("签署页\n甲方："+CLIENT+"（盖章）\n授权代表（签字）：\n乙方："+COMPANY+"（盖章）\n授权代表（签字）：\n2024年2月7日",
            "合同编号：ORDER-001\n日期：2024年3月8日");
        assertTrue(p.fields("signedDate").isEmpty());
        List<Source> originals=FrameworkOriginalFields.originals(p.row,p.c);
        assertFalse(originals.contains(source(p.c,8)));assertTrue(originals.contains(source(p.c,5)));
        assertFalse(FrameworkOriginalFields.samePacketTitles(TITLE,BASE+"（标段2新建厅）"));
        assertTrue(ContractCandidateFields.sameTitle("乙市2023—2024年框架合同","乙市2023-2024年框架合同"));
        assertFalse(ContractCandidateFields.sameTitle("乙市2023—2025年框架合同","乙市2023-2024年框架合同"));
    }

    @Test void personalRowAcquiresOriginalsAfterItsInventoryOwnerIsReadEvenWithPriorPageCoverage()throws Exception {
        for(String rowOwner:Arrays.asList("王立诚","李国兴")){
            String inventory="作为负责人的业绩\n序号 合同名称 签订时间 金额（万元） 项目负责人\n1 "+BASE+" 2023年10月25日 200 "+rowOwner;
            String person="王立诚";
            EvidenceCorpus c=corpus("本次投标拟派人员\n姓名："+person+"\n拟在本合同任职：项目负责人",inventory,
                "①"+BASE+"\n合同编号："+NO+"\n"+BASE,PARTIES,
                "签署页\n甲方："+CLIENT+"（盖章）\n授权代表（签字）：\n乙方："+COMPANY+"（盖章）\n授权代表（签字）：\n2024年2月7日");
            Rule parent=withRows(SourcePerformanceScoreTest.rule(1),c);
            Map<String,Object> row=RuleEvaluator.maps(parent.parameters().get("declaredPerformanceReadRows")).get(0);
            List<Rule> targets=SourcePerformanceScore.evidenceRules(parent);
            Rule contract=targets.stream().filter(rule->"CONTRACT".equals(rule.predicate())).findFirst().get();
            List<Map<String,Object>> cells=new ArrayList<>();
            String[] headers={"序号","合同名称","签订时间","金额（万元）","项目负责人"};
            String[] values={"1",BASE,"2023年10月25日","200",rowOwner};
            for(int n=0;n<headers.length;n++){
                String headerId="h"+n;
                for(int r=1;r<=2;r++){
                    String id=r==1?headerId:"v"+n;
                    cells.add(linked("kind","TABLE_CELL","source","OOXML_NATIVE","cellId",id,"spanId",id,
                        "row",r,"column",n+1,"tableId","inventory","frameId","inventory-frame",
                        "documentId","synthetic-bid","documentHash","synthetic-hash","documentVersion",1,
                        "locationType","PAGE","location",2,"text",r==1?headers[n]:values[n],
                        "header",r==1,"headerCellIds",r==1?Collections.emptyList():Collections.singletonList(headerId)));
                }
            }
            Map<String,Object> ctx=dates();
            ctx.put("layoutLookup",(java.util.function.Function<Map<String,Object>,Object>)q->cells);
            RunSession session=new RunSession(ModelGateway.disabled(),ctx,"synthetic");
            List<String> ids=Arrays.asList(source(c,3).id(),source(c,4).id(),source(c,5).id());
            ContractBodyReadReplay.record(session,contract.id(),row,c,ids,linked("status","COMPLETED"));
            ctx.put("priorSteps",new ArrayList<>(session.steps));
            assertEquals(new HashSet<>(ids),ContractBodyReadReplay.restore(session,contract.id(),row,c));
            Fact role=new Fact("role","PERSON_ROLE","项目负责人","姓名",person,person,"","","CURRENT_PROJECT",
                Collections.singletonList(source(c,1).id()),Collections.emptyMap());
            Map<String,Fact> facts=new LinkedHashMap<>();facts.put(role.id(),role);
            EvidenceInvestigation.Host host=new EvidenceInvestigation.Host(checkpoint(),new Plan("ALL",Collections.singletonList(parent),
                "SYNTHETIC",Collections.emptyList()),targets,c,facts,ctx,new SharedFactReuse(c,ctx,"synthetic","synthetic"),session,
                null,new ArrayList<>(),Collections.emptySet());
            host.start();
            boolean samePerson=rowOwner.equals(person);
            assertFalse(RuleEvaluator.maps(ctx.get("_validatedLayoutCells")).isEmpty());
            assertEquals(samePerson,host.facts.values().stream().anyMatch(f->"party".equals(f.field())&&COMPANY.equals(f.rawValue())
                &&f.sourceIds().contains(source(c,4).id())&&f.sourceIds().contains(source(c,3).id())));
            assertEquals(samePerson,host.visualRequests.stream().anyMatch(q->Integer.valueOf(5).equals(q.get("location"))
                &&"CONTRACT_SIGNING_DATE_ORIGINAL_REQUIRED".equals(q.get("reason"))));
            int before=host.facts.size();host.call("recompute_check",linked("targetObligationId",contract.id()));
            assertEquals(before,host.facts.size());
        }
    }
}
