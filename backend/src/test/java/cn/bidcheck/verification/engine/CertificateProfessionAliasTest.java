package cn.bidcheck.verification.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CertificateProfessionAliasTest {
    @Test void buildingAbbreviationMatchesOnlyRegisteredBuilderProfession(){
        for(String category:new String[]{"一级注册建造师","二级注册建造师"}){
            assertTrue(CertificateSourceComparison.equivalentFieldValue(category,"PROFESSION","建筑","建筑工程"));
            assertTrue(CertificateSourceComparison.equivalentFieldValue(category,"PROFESSION","建筑工程","建筑"));
        }
    }
    @Test void architecturalDesignAndProfessionalTitleDoNotBorrowBuilderAlias(){
        for(String category:new String[]{"一级注册建筑师","工程设计资质","工程师职称"})
            assertFalse(CertificateSourceComparison.equivalentFieldValue(category,"PROFESSION","建筑","建筑工程"));
    }
    @Test void differentProfessionsListsAndOtherFieldsRemainDifferent(){
        assertFalse(CertificateSourceComparison.equivalentFieldValue("二级注册建造师","PROFESSION","机电","建筑工程"));
        assertFalse(CertificateSourceComparison.equivalentFieldValue("二级注册建造师","PROFESSION","建筑,机电","建筑工程"));
        assertFalse(CertificateSourceComparison.equivalentFieldValue("二级注册建造师","NAME","建筑","建筑工程"));
    }
}
