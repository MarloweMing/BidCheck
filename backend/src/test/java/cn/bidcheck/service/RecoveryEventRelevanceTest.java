package cn.bidcheck.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.service.BidService.fields;
import static org.junit.jupiter.api.Assertions.*;

/** Reduced evidence shapes from full109-03; tests scheduling only and do not issue verdicts. */
class RecoveryEventRelevanceTest {
    @Test void otherManagementCertificateDoesNotWakeAlreadyObservedSystemCertificate(){
        Map<String,Object> binding=fields("target",fields("predicate","CERTIFICATE","subject","施工单位","field","certificate",
            "parameters",fields("category","质量管理体系认证")),"resolvedSubjects",Collections.singletonList("合成施工有限公司"));
        Map<String,Object> fact=fields("kind","CERTIFICATE","subject","合成施工有限公司","field","certificate","value","SYN-S001",
            "attributes",fields("category","职业健康安全管理体系认证","number","SYN-S001"));
        String original="# 职业健康安全管理体系认证证书\n证书编号：SYN-S001\n兹证明：合成施工有限公司\n证书有效日期：2025年09月04日至2028年09月03日";
        assertFalse(matches(binding,fact,453,original));
        mapTarget(binding).put("parameters",fields("category","环境管理体系认证"));
        assertFalse(matches(binding,fact,453,original));
        mapTarget(binding).put("parameters",fields("category","职业健康安全管理体系认证"));
        assertTrue(matches(binding,fact,453,original));
    }

    @Test void categoryLabelWithoutMatchingOriginalHolderNumberAndHeadingCannotSuppressRevalidation(){
        Map<String,Object> binding=fields("target",fields("predicate","CERTIFICATE","subject","施工单位","field","certificate",
            "parameters",fields("category","质量管理体系认证")),"resolvedSubjects",Collections.singletonList("合成施工有限公司"));
        Map<String,Object> fact=fields("kind","CERTIFICATE","subject","合成施工有限公司","field","certificate","value","SYN-S001",
            "attributes",fields("category","职业健康安全管理体系认证","number","SYN-S001"));
        String original="# 职业健康安全管理体系认证证书\n证书编号：SYN-S001\n兹证明：合成施工有限公司";
        assertTrue(matches(binding,fact,453,original.replace("SYN-S001","SYN-S002")));
        assertTrue(matches(binding,fact,453,original.replace("合成施工有限公司","另一家公司")));
        assertTrue(matches(binding,fact,453,original.replace("# 职业健康安全管理体系认证证书","说明：职业健康安全管理体系认证证书")));
        assertTrue(matches(binding,fact,453,original+"\n# 质量管理体系认证证书"));
        assertTrue(matches(binding,fact,453,original+"\n证书编号：SYN-S002"));
        assertTrue(matches(binding,fact,453,original.replace("兹证明：合成施工有限公司","办理机构：合成施工有限公司")));
    }

    private Map<String,Object> mapTarget(Map<String,Object> binding){return (Map<String,Object>)binding.get("target");}

    @Test void missingOrStaleWholePageWitnessKeepsTheCertificateEventEligible(){
        Map<String,Object> binding=fields("target",fields("predicate","CERTIFICATE","subject","施工单位","field","certificate",
            "parameters",fields("category","质量管理体系认证")),"resolvedSubjects",Collections.singletonList("合成施工有限公司"));
        Map<String,Object> fact=fields("kind","CERTIFICATE","subject","合成施工有限公司","field","certificate","value","SYN-S001",
            "attributes",fields("category","职业健康安全管理体系认证","number","SYN-S001"));
        String original="# 职业健康安全管理体系认证证书\n证书编号：SYN-S001\n兹证明：合成施工有限公司";
        assertTrue(matches(binding,fact,453,original,s->s.remove("pageCertificateWitness")));
        for(String field:Arrays.asList("documentHash","documentVersion","page","parserFingerprint","managementCategory","holder")){
            assertTrue(matches(binding,fact,453,original,s->{
                Map<String,Object> witness=(Map<String,Object>)s.get("pageCertificateWitness");
                witness.put(field,Arrays.asList("documentVersion","page").contains(field)?99:"another");
            }),field);
        }
    }

    @Test void screenshotOfOtherPrintedManagementCategoryDoesNotWakeThisCertificate(){
        Map<String,Object> binding=fields("target",fields("predicate","MATERIAL","subject","施工单位","field","material",
            "targetExpected","查询截图","parameters",fields("certificateProofCategory","职业健康安全管理体系认证")));
        Map<String,Object> fact=fields("kind","MATERIAL","subject","施工单位","field","material","value","查询截图");
        assertFalse(matches(binding,fact,456,"| 认证类别 | （下拉选择） |\n- **认证项目**: 环境管理体系认证"));
        assertTrue(matches(binding,fact,456,"- **认证项目**: 职业健康安全管理体系认证"));
        assertTrue(matches(binding,fact,456,"认证项目：环境管理体系认证\n认证项目：职业健康安全管理体系认证"));
        assertTrue(matches(binding,fact,456,"认证信息查询；页面没有标明认证类别"));
    }

    @Test void existingRoleNameDoesNotReopenMissingConsortiumAppointmentEdge(){
        Map<String,Object> binding=fields("target",fields("predicate","RELATION","subject","项目负责人","field","委派",
            "targetExpected","牵头单位","parameters",fields("object","牵头单位","compoundReadKind","APPOINT")),
            "resolvedSubjects",Collections.singletonList("甲某"));
        Map<String,Object> role=fields("kind","PERSON_ROLE","subject","项目负责人","field","姓名","value","甲某","scope","CURRENT_PROJECT");
        assertFalse(matches(binding,role,22,"姓名：甲某，拟任项目负责人"));
        role.put("value","乙某");assertTrue(matches(binding,role,22,"姓名：乙某，拟任项目负责人"));
        role.put("value","甲某");binding.remove("resolvedSubjects");assertTrue(matches(binding,role,22,"姓名：甲某"));
        binding.put("resolvedSubjects",Collections.singletonList("甲某"));
        assertTrue(matches(binding,fields("kind","RELATION","subject","甲某","field","委派","value","牵头单位","scope","CURRENT_PROJECT",
            "attributes",fields("object","牵头单位")),457,"牵头单位委派甲某担任项目负责人。"));
    }

    @Test void missingTenderBaseStandardOnlyBlocksDependentBidderCredentials(){
        Map<String,Object> binding=fields("target",fields("predicate","CERTIFICATE","parameters",Collections.emptyMap()),"tenderStandardRequired",true);
        Map<String,Object> fact=fields("kind","CERTIFICATE","subject","甲某","field","职称","value","工程师");
        assertFalse(matches(binding,fact,30,"职称：工程师"));
        binding.remove("tenderStandardRequired");assertTrue(matches(binding,fact,30,"职称：工程师"));
    }

    @Test void numberedBodyOfAnotherNamedProjectDoesNotReopenDesignContractRow(){
        String name="合成老镇更新项目启动区精装及街区设计";
        Map<String,Object> row=fields("documentId","doc","documentHash","hash","documentVersion",1,"proofPageStart",160,
            "proofPageEnd",163,"separatorTitle",name,"inventoryIdentity","doc:152:2");
        Map<String,Object> target=fields("predicate","CONTRACT","parameters",fields("declaredPerformanceRow",row));
        String observation=cn.bidcheck.verification.facts.FieldCoverage.key(target);
        Map<String,Object> receipt=fields("version","server-declared-row-number-1","authority","CURRENT_SERVER_VERIFIED_ROW",
            "complete",true,"businessProof",false,"obligationId","row-two","observationKey",observation,"row",row,"inputs",Collections.emptyList());
        Map<String,Object> binding=fields("target",target,"obligationId","row-two","observationKey",observation,"rowNumberEvidence",receipt);
        String number="SYN-2026-035";
        Map<String,Object> fact=fields("kind","CONTRACT","field","contractNumber","value",number,"rawValue",number,"attributes",fields("contractNumber",number));
        String header="合同编号:"+number+"\n1.1 工程名称：";
        assertFalse(matches(binding,fact,35,header+"合成数字经济演示厅改造项目"));
        assertTrue(matches(binding,fact,160,header+"合成数字经济演示厅改造项目"));
        assertTrue(matches(binding,fact,35,header+name));
        assertTrue(matches(binding,fact,35,"合同编号:"+number+"\n另行核实工程名称"));
        binding.remove("rowNumberEvidence");assertTrue(matches(binding,fact,35,header+"合成数字经济演示厅改造项目"));
    }

    private boolean matches(Map<String,Object> binding,Map<String,Object> fact,int page,String quote){
        return matches(binding,fact,page,quote,s->{});
    }
    private boolean matches(Map<String,Object> binding,Map<String,Object> fact,int page,String quote,
            java.util.function.Consumer<Map<String,Object>> customize){
        Map<String,Object> input=new LinkedHashMap<>(fact);input.put("sourceIds",Collections.singletonList("source"));
        Map<String,Object> source=fields("id","source","documentId","doc","documentHash","hash","documentVersion",1,
            "sourceFingerprint","stable","parserFingerprint","frozen","page",page,"locationType","PAGE",
            "evidence",fields("documentId","doc","locationType","PAGE","page",page,"quote",quote));
        if("CERTIFICATE".equals(fact.get("kind"))){
            Map<String,Object> attrs=(Map<String,Object>)fact.get("attributes");
            if(attrs!=null)source.put("pageCertificateWitness",fields("version","frozen-page-certificate-witness-1",
                "authority","CURRENT_SERVER_PROOF","businessProof",false,"documentId","doc","documentHash","hash","documentVersion",1,
                "page",page,"locationType","PAGE","parserFingerprint","frozen",
                "pageTextHash","0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "managementCategory",attrs.get("category"),"number",attrs.get("number"),"holder",fact.get("subject")));
        }
        customize.accept(source);
        return OrdinaryRecoveryDependencies.matches(binding,fields("recordType","FACT","itemId","event","fact",input,"sources",Collections.singletonList(source)));
    }
}
