package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CredentialGradesTest {
    @Test void higherDesignGradeMeetsSourceBackedMinimum(){
        assertEquals(true,CredentialGrades.meets("工程设计建筑行业","甲级","乙级",">=","建筑行业乙级（含）以上"));
        assertEquals(true,CredentialGrades.meets("工程设计建筑行业","乙级","乙级",">=","建筑行业乙级以上"));
        assertEquals(false,CredentialGrades.meets("工程设计建筑行业","丙级","乙级",">=","建筑行业乙级以上"));
    }
    @Test void unorderedCategoryCrossFamilyAndInventedOperatorAreUnknown(){
        assertNull(CredentialGrades.meets("安全生产许可证","甲级","乙级",">=","乙级以上"));
        assertNull(CredentialGrades.meets("工程设计建筑行业","一级","乙级",">=","乙级以上"));
        assertNull(CredentialGrades.meets("工程设计建筑行业","甲级","乙级",">=","建筑行业乙级资质"));
        assertEquals(false,CredentialGrades.meets("工程设计建筑行业","甲级","乙级","=","乙级资质"));
    }
}
