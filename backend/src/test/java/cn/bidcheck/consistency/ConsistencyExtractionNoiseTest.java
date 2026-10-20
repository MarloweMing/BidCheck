package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsistencyExtractionNoiseTest {
    private List<Map<String,Object>> facts(String text) {
        var doc = new Document("bid", "p", "BID", "投标.pdf", 1, "hash", "",
                List.of(new Page(1, text, "PAGE", "PDFBOX_NATIVE", null)), List.of());
        @SuppressWarnings("unchecked") var facts = (List<Map<String,Object>>) new ConsistencyEngine().scan(doc, List.of()).get("occurrences");
        assertEquals(text, doc.pages().get(0).text());
        return facts;
    }
    @Test void rejectsNeighbouringEmployerColumnsAndCertificateTitlesAsPeople() {
        var result = facts("2022年4月25日 项目负责人 公司山西省分公司\n项目负责人 安全生产考核合格证书\n项目负责人 京建安\n项目经理 指乙方派驻施工现场\n项目经理：张三");
        assertEquals(List.of("张三"), result.stream().filter(f -> "personName".equals(f.get("field"))).map(f -> f.get("rawValue")).toList());
    }
    @Test void rejectsCompanyQueryHeadingsButPreservesActualEmployerAndEthnicNames() {
        var result = facts("| 企业名称 | 人员类型 |\n企业名称：北京某建设有限公司\n项目经理：阿卜杜拉·买买提");
        assertEquals(List.of("北京某建设有限公司"), result.stream().filter(f -> "companyName".equals(f.get("field"))).map(f -> f.get("rawValue")).toList());
        assertTrue(result.stream().anyMatch(f -> "personName".equals(f.get("field")) && "阿卜杜拉·买买提".equals(f.get("rawValue"))));
    }
    @Test void appliesTheSameGuardToExplicitRoleTableCells() {
        var result = facts("| 项目经理 | 投标单位名称 |\n| --- | --- |\n| 公司云南省分公司 | 甲公司 |\n| 李四 | 乙公司 |");
        assertEquals(List.of("李四"), result.stream().filter(f -> "personName".equals(f.get("field"))).map(f -> f.get("rawValue")).toList());
    }
}
