package cn.bidcheck.verification.engine;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class Ticket231MaterialTest {
    private final PersonnelCountRoleTest fixture = new PersonnelCountRoleTest();

    private Rule rule(String title) {
        return new Rule("inventory", title, "MATERIAL", "", "material", "", title, "",
            Collections.<String,Object>singletonMap("inventoryEntry", true), title, "", Collections.emptyList());
    }

    @Test void substantiveIndexRowsProveTheIndexItself() {
        EvidenceCorpus corpus = fixture.corpus("2. 商务评审索引表（适用综合评估法）\n条款号 评审因素 对应页码\n1 投标函 P7 满足");
        assertFalse(corpus.materialBodyAnchors("商务技术评审索引表").isEmpty());
        assertEquals("PASS", new RuleEvaluator(Collections.emptyList(), corpus, Collections.emptyMap()).evaluate(rule("商务技术评审索引表")).status());
    }

    @Test void subsectionBodyIsProofButDirectoryOrHeadingAloneIsNot() {
        EvidenceCorpus corpus = fixture.corpus("4. 技术建议书\n4.1 设计方案\n采用分级控制和双机备份保证现场服务连续运行。");
        assertEquals("PASS", new RuleEvaluator(Collections.emptyList(), corpus, Collections.emptyMap()).evaluate(rule("技术建议书")).status());
        for (String bad : new String[]{"目录\n技术建议书 ........ 5", "4. 技术建议书\n5. 其他材料\n无关正文", "4. 技术建议书"}) {
            assertTrue(fixture.corpus(bad).materialBodyAnchors("技术建议书").isEmpty());
        }
    }

    @Test void missingSeparateVolumeAsksForThatUpload() {
        EvidenceCorpus corpus=fixture.corpus("投标文件（商务部分）\n投标人名称：甲有限公司", "目录\n投标函 ........ 7");
        Decision d=new RuleEvaluator(Collections.emptyList(),corpus,Collections.emptyMap()).evaluate(rule("第三分册 报价文件"));
        assertEquals("UNDETERMINED",d.status());
        assertTrue(d.gaps().stream().anyMatch(g -> "BID_VOLUME_NOT_UPLOADED".equals(g.code())));
    }
}
