package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsistencyExtractionNoiseTest {
    @Test void historyAndCertificateTitlesDoNotSupplyCurrentProjectPosts(){
        List<Map<String,Object>> occurrences=facts("本项目人员配置\n项目经理：张三\n主要工作经历\n项目经理：李四\n本项目人员配置\n项目经理：王五\n# 建筑施工企业项目负责人 安全生产考核合格证书\n项目经理：赵六");
        List<String> people=new ArrayList<>();
        for(Map<String,Object> fact:occurrences)if("personName".equals(fact.get("field")))people.add(String.valueOf(fact.get("rawValue")));
        assertTrue(people.containsAll(Arrays.asList("张三","王五")),people.toString());assertFalse(people.contains("李四"));assertFalse(people.contains("赵六"));
    }
    private List<Map<String,Object>> facts(String text) {
        cn.bidcheck.domain.Domain.Document doc = new Document("bid", "p", "BID", "投标.pdf", 1, "hash", "",
                cn.bidcheck.compat.Java8Api.listOf(new Page(1, text, "PAGE", "PDFBOX_NATIVE", null)), cn.bidcheck.compat.Java8Api.listOf());
        @SuppressWarnings("unchecked") java.util.List<java.util.Map<java.lang.String, java.lang.Object>> facts = (List<Map<String,Object>>) new ConsistencyEngine().scan(doc, cn.bidcheck.compat.Java8Api.listOf()).get("occurrences");
        assertEquals(text, doc.pages().get(0).text());
        return facts;
    }
    @Test void rejectsNeighbouringEmployerColumnsAndCertificateTitlesAsPeople() {
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> result = facts("2022年4月25日 项目负责人 公司山西省分公司\n项目负责人 安全生产考核合格证书\n项目负责人 京建安\n项目经理 指乙方派驻施工现场\n项目经理：张三");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("张三"), cn.bidcheck.compat.Java8Api.toList(result.stream().filter(f -> "personName".equals(f.get("field"))).map(f -> f.get("rawValue"))));
    }
    @Test void rejectsCompanyQueryHeadingsButPreservesActualEmployerAndEthnicNames() {
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> result = facts("| 企业名称 | 人员类型 |\n企业名称：北京某建设有限公司\n项目经理：阿卜杜拉·买买提");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("北京某建设有限公司"), cn.bidcheck.compat.Java8Api.toList(result.stream().filter(f -> "companyName".equals(f.get("field"))).map(f -> f.get("rawValue"))));
        assertTrue(result.stream().anyMatch(f -> "personName".equals(f.get("field")) && "阿卜杜拉·买买提".equals(f.get("rawValue"))));
    }
    @Test void appliesTheSameGuardToExplicitRoleTableCells() {
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> result = facts("| 项目经理 | 投标单位名称 |\n| --- | --- |\n| 公司云南省分公司 | 甲公司 |\n| 李四 | 乙公司 |");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("李四"), cn.bidcheck.compat.Java8Api.toList(result.stream().filter(f -> "personName".equals(f.get("field"))).map(f -> f.get("rawValue"))));
    }
}
