package cn.bidcheck.document;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PageClassifierPersonTest {
    @Test void drivingLicenseIsDistinctFromVehicleRegistrationAndIdentityCard() {
        var driving=PageClassifier.nativeOnly("中华人民共和国机动车驾驶证 姓名：测试甲 证号：123456",1);
        assertEquals("DRIVING_LICENSE",driving.pageType());assertEquals("DRIVING_LICENSE_V1",driving.schema());
        assertTrue(driving.independent());assertEquals("INDEPENDENT_VISUAL",driving.parserStrategy());
        assertEquals("VEHICLE_LICENSE",PageClassifier.nativeOnly("中华人民共和国机动车行驶证 车辆识别代号",1).pageType());
        assertEquals("ID_CARD",PageClassifier.nativeOnly("居民身份证 公民身份号码",1).pageType());
    }
    @Test void englishCredentialTitlesAndPassportRouteIndependently() {
        for(String title:new String[]{"Driving License","DRIVING LICENCE","Driver's License","Driver License"})
            assertEquals("DRIVING_LICENSE",PageClassifier.nativeOnly(title+" Name: Jane Example",1).pageType());
        var passport=PageClassifier.nativeOnly("Passport Name: Jane Example",1);
        assertEquals("PASSPORT",passport.pageType());assertEquals("PASSPORT_V1",passport.schema());assertTrue(passport.independent());
        assertEquals("ID_CARD",PageClassifier.nativeOnly("National Identity Card",1).pageType());
    }
    @Test void recognizedDriverRefinesUnknownAndNativeTextStillSkipsOcr() {
        var original=PageClassifier.nativeOnly("",1);
        var refined=PageClassifier.afterRecognition(original,"驾驶证 姓名：测试甲 准驾车型：B2",1);
        assertEquals("DRIVING_LICENSE",refined.pageType());assertEquals(original.resolutionBucket(),refined.resolutionBucket());
        assertEquals("NATIVE_PARSE",PageClassifier.nativeOnly("驾驶证 姓名：测试甲 准驾车型：B2",0).parserStrategy());
    }
    @Test void mixedPersonnelTableKeepsTableRouteForExplicitRowBoundaries() {
        var table=PageClassifier.nativeOnly("|Name|Driving License Number|\n|Jane Example|DL001|",1);
        assertEquals("TABLE_PAGE",table.pageType());assertEquals("PERSON_TABLE_V1",table.schema());
    }
    @Test void genericArchiveNumberDoesNotRouteUnrelatedMaterialAsADrivingLicense() {
        assertNotEquals("DRIVING_LICENSE",PageClassifier.nativeOnly("设备登记材料 档案编号：ARCHIVE001",1).pageType());
        assertEquals("DRIVING_LICENSE",PageClassifier.nativeOnly("驾驶证档案编号：ARCHIVE001",1).pageType());
    }
}
