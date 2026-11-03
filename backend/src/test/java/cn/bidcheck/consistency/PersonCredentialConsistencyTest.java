package cn.bidcheck.consistency;

import cn.bidcheck.document.PersonCredentialFacts;
import cn.bidcheck.domain.Domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bidcheck.consistency.ConsistencyRunSupport.*;

class PersonCredentialConsistencyTest {
    private final PersonCredentialConsistency engine=new PersonCredentialConsistency();
    private static final String ID1="110101199001010011",ID2="110101199001010038";
    private Document doc(String...texts){List<Page> pages=new ArrayList<>();for(int i=0;i<texts.length;i++)pages.add(new Page(i==0?1:50+i-1,texts[i],"PAGE","PDFBOX",null));return new Document("bid","project","BID","投标文件.pdf",1,"sha","",pages,cn.bidcheck.compat.Java8Api.listOf());}
    private String driver(String name,String number){return "机动车驾驶证\n姓名："+name+"\n驾驶证号码："+number+"\n";}
    private String card(String name,String number){return "居民身份证\n姓名："+name+"\n公民身份号码："+number+"\n";}
    private List<Map<String,Object>> groups(Map<String,Object> report){return maps(report.get("groups"));}
    private List<Map<String,Object>> information(Map<String,Object> report){return maps(report.get("informationalGroups"));}
    private Map<String,Object> group(Map<String,Object> report,String field){return groups(report).stream().filter(g->field.equals(g.get("field"))).findFirst().orElseThrow(()->new AssertionError("missing group "+field+"; "+report));}

    @Test void sameNamedDriversOnDistantPagesAreNotLostThroughNumberBasedIdentity() {
        Document document=doc(driver("张三",ID1),driver("张三",ID2));java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(document);
        java.util.Map<java.lang.String, java.lang.Object> issue=group(report,"drivingLicenseNumber");
        assertEquals("SUSPECTED",issue.get("status"));assertEquals("NAME_ONLY",issue.get("identityResolution"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 50),issue.get("sourcePages"));
        List<Evidence> evidence=cn.bidcheck.compat.Java8Api.toList(((List<?>)issue.get("evidences")).stream().map(Evidence.class::cast));
        assertTrue(evidence.stream().anyMatch(e->e.page()==1&&e.quote().contains(ID1)));
        assertTrue(evidence.stream().anyMatch(e->e.page()==50&&e.quote().contains(ID2)));
        assertFalse(issue.get("reason").toString().contains(ID1));assertFalse(issue.get("values").toString().contains(ID1));
        Set<Object> ids=new HashSet<>(cn.bidcheck.compat.Java8Api.toList(maps(report.get("occurrences")).stream().map(o->o.get("id"))));
        assertTrue(ids.containsAll((List<?>)issue.get("occurrenceIds")));
    }

    @Test void independentExplicitRoleCanCorroborateSameNamedPersonWithoutUsingDisputedNumber() {
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(doc(driver("张三",ID1)+"岗位：项目经理",driver("张三",ID2)+"岗位：项目经理"));
        java.util.Map<java.lang.String, java.lang.Object> issue=group(report,"drivingLicenseNumber");assertEquals("CORROBORATED",issue.get("identityResolution"));assertEquals("CONFLICT",issue.get("status"));
        assertEquals("UNDETERMINED",issue.get("businessDecision"));
    }

    @Test void oneStrongNumberWithDifferentNamesOrGenderProducesEvidenceBackedIssues() {
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(doc(card("张三",ID1)+"性别：男\n出生日期：1990-01-01",card("李四",ID1)+"性别：女\n出生日期：1991-02-02"));
        for(String field:cn.bidcheck.compat.Java8Api.listOf("credentialPersonName", "personGender", "personBirthDate")){
            java.util.Map<java.lang.String, java.lang.Object> issue=group(report,field);assertEquals("CONFLICT",issue.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 50),issue.get("sourcePages"));
        }
    }

    @Test void differentCredentialNumberFamiliesAreNotComparedToEachOther() {
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(doc(card("张三",ID1),"护照\n姓名：张三\n护照号码：E12345678",driver("张三",ID2)));
        assertTrue(groups(report).isEmpty(),()->report.toString());
        assertEquals(6,maps(report.get("occurrences")).size());
    }

    @Test void explicitIdentityNumbersAcrossDifferentMaterialsAreCompared() {
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(doc(card("张三",ID1),"人员名单\n姓名：张三\n身份证号码："+ID2+"\n岗位：驾驶员"));
        assertEquals("SUSPECTED",group(report,"idNumber").get("status"));
    }

    @Test void licenseNumberAndFileNumberStaySeparate() {
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(doc(driver("张三",ID1)+"档案编号：123456789012",driver("张三",ID1)+"档案编号：223456789012"));
        assertNotNull(group(report,"drivingFileNumber"));assertTrue(groups(report).stream().noneMatch(g->"drivingLicenseNumber".equals(g.get("field"))));
        assertEquals("CONFLICT",group(report,"drivingFileNumber").get("status"));
    }

    @Test void renewalAndVehicleClassDifferencesRemainReviewable() {
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(doc(driver("张三",ID1)+"准驾车型：C1\n有效期至：2026-01-01",driver("张三",ID1)+"准驾车型：B2\n有效期至：2036-01-01"));
        assertEquals("SUSPECTED",group(report,"vehicleClass").get("status"));
        assertEquals("SUSPECTED",group(report,"credentialValidUntil").get("status"));
        assertTrue(groups(report).stream().noneMatch(g->"FAIL".equals(g.get("status"))));
    }

    @Test void differentCertificateKindsDoNotProduceNumberConflicts() {
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(doc("资格证书\n姓名：张三\n证书名称：安全资格证书\n证书编号：SAFE12345", "资格证书\n姓名：张三\n证书名称：技术资格证书\n证书编号：TECH98765"));
        assertTrue(groups(report).stream().noneMatch(g->"personCertificateNumber".equals(g.get("field"))),()->report.toString());
    }

    @Test void repeatedIdenticalRecordsAreRetainedWithoutDeclaringWholeDocumentPass() {
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(doc(driver("张三",ID1),driver("张三",ID1),driver("张三",ID1)));
        assertEquals(6,maps(report.get("occurrences")).size());assertTrue(groups(report).isEmpty());assertEquals("NO_ACTIONABLE_FINDINGS",report.get("overall"));
        assertEquals(3,maps(report.get("occurrences")).stream().map(o->((Evidence)o.get("evidence")).page()).distinct().count());
    }

    @Test void maskedNumbersKeepBothSourcesAndCannotPass() {
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(doc(driver("张三",ID1),driver("张三","110101********0011")));
        assertEquals("PARTIAL",map(report.get("coverage")).get("status"));
        assertTrue(information(report).stream().anyMatch(g->"EVIDENCE_INSUFFICIENT".equals(g.get("status"))&&cn.bidcheck.compat.Java8Api.listOf(1, 50).equals(g.get("sourcePages"))));
        assertTrue(groups(report).stream().noneMatch(g->"CONFLICT".equals(g.get("status"))));
        assertFalse(PersonCredentialConsistency.requiresReview(map(report.get("coverage"))));
        assertEquals("NO_ACTIONABLE_FINDINGS",report.get("overall"));
    }

    @Test void deferredPagesAndUnextractedCandidatesAreVisibleEvenWhenNoDifferenceExists() {
        Document input=doc(driver("张三",ID1),"居民身份证");List<Page> pages=new ArrayList<>(input.pages());pages.add(new Page(581,"","PAGE","OCR_DEFERRED","OCR未完成"));
        Document document=new Document(input.id(),input.projectId(),input.role(),input.name(),input.version(),input.sha256(),"",pages,cn.bidcheck.compat.Java8Api.listOf("OCR未完成"));
        java.util.Map<java.lang.String, java.lang.Object> coverage=map(engine.scan(document).get("coverage"));assertEquals("PARTIAL",coverage.get("status"));assertEquals(cn.bidcheck.compat.Java8Api.listOf(50),coverage.get("missingCandidatePages"));
        assertTrue(((Number)coverage.get("pagesUnparsed")).intValue()>=2);
        assertTrue(PersonCredentialConsistency.requiresReview(coverage));assertEquals(1L,coverage.get("actionablePages"));
        assertTrue(maps(coverage.get("actionableIssues")).stream().anyMatch(i->Integer.valueOf(581).equals(i.get("page"))));
        assertTrue(maps(coverage.get("informationalIssues")).stream().anyMatch(i->Integer.valueOf(50).equals(i.get("page"))));
    }

    @Test void cachedFactsFromOtherVersionOrChangedPageAreRejected() {
        Document first=doc(driver("张三",ID1));cn.bidcheck.document.PersonCredentialFacts.Result result=PersonCredentialFacts.analyze(first,first.pages().get(0));
        Document changedVersion=new Document(first.id(),first.projectId(),first.role(),first.name(),2,"other-sha","",first.pages(),cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(changedVersion,cn.bidcheck.compat.Java8Api.listOf(result));assertTrue(maps(report.get("occurrences")).isEmpty());assertTrue(((Number)map(report.get("coverage")).get("sourceRejected")).intValue()>0);
        assertTrue(PersonCredentialConsistency.requiresReview(map(report.get("coverage"))));
        java.util.Map<java.lang.String, java.lang.Object> changedText=engine.scan(doc(driver("李四",ID2)),cn.bidcheck.compat.Java8Api.listOf(result));assertTrue(maps(changedText.get("occurrences")).isEmpty());
    }

    @Test void equivalentDateFormatsDoNotBecomeConflicts() {
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(doc(card("张三",ID1)+"出生日期：1990年1月1日",card("张三",ID1)+"出生日期：1990-01-01"));
        assertTrue(groups(report).stream().noneMatch(g->"personBirthDate".equals(g.get("field"))));
    }

    @Test void invalidOrPartialBirthDateIsEvidenceGapInsteadOfCertainConflict() {
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(doc(card("张三",ID1)+"出生日期：1990年1月",card("张三",ID1)+"出生日期：1990-01-01"));
        assertTrue(groups(report).stream().noneMatch(g->"personBirthDate".equals(g.get("field"))));
        assertTrue(information(report).stream().anyMatch(g->"personBirthDate".equals(g.get("field"))));
        assertEquals("PARTIAL",map(report.get("coverage")).get("status"));
        assertFalse(PersonCredentialConsistency.requiresReview(map(report.get("coverage"))));
    }

    @Test void multipleVolumesKeepOriginalDocumentIdsEvenWithSamePageNumbers() {
        Document first=doc(driver("张三",ID1));Document second=new Document("bid2",first.projectId(),first.role(),"技术分册.pdf",3,"sha2","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,driver("张三",ID2),"PAGE","PDFBOX",null)),cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(cn.bidcheck.compat.Java8Api.listOf(first, second));java.util.Map<java.lang.String, java.lang.Object> issue=group(report,"drivingLicenseNumber");
        assertEquals(cn.bidcheck.compat.Java8Api.listOf("bid", "bid2"),report.get("documentIds"));
        Set<String> ids=new HashSet<>(cn.bidcheck.compat.Java8Api.toList(((List<?>)issue.get("evidences")).stream().map(Evidence.class::cast).map(Evidence::documentId)));assertEquals(cn.bidcheck.compat.Java8Api.setOf("bid", "bid2"),ids);
        assertEquals(2,maps(issue.get("sourceLocations")).size());assertFalse(report.containsKey("documentId"));
    }

    @Test void coverageKeepsEachUnreadVolumeAndSourceLocationDistinct() {
        Document a=doc("居民身份证"),b=new Document("other",a.projectId(),a.role(),"技术分册.pdf",1,"sha2","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,"居民身份证","PAGE","PDFBOX",null), new Page(51,"","PAGE","OCR_DEFERRED","OCR未完成")),cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> coverage=map(engine.scan(cn.bidcheck.compat.Java8Api.listOf(a, b)).get("coverage"));
        assertEquals(3,coverage.get("pagesTotal"));assertEquals(2,maps(coverage.get("missingCandidateLocations")).size());
        assertEquals(cn.bidcheck.compat.Java8Api.setOf(a.id(), b.id()),new HashSet<>(cn.bidcheck.compat.Java8Api.toList(maps(coverage.get("missingCandidateLocations")).stream().map(x->string(x,"documentId")))));
        assertTrue(maps(coverage.get("issues")).stream().anyMatch(x->b.id().equals(x.get("documentId"))&&Integer.valueOf(51).equals(x.get("page"))));
        assertTrue(maps(coverage.get("issues")).stream().anyMatch(x->string(x,"message").contains("未提取到")));
    }

    @Test void formalEngineUsesDedicatedPersonnelComparisonAndKeepsOtherRules() {
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc("本次投标函\n"+driver("张三",ID1)+"\n投标人名称：甲公司","本次投标函\n"+driver("张三",ID2)+"\n投标人名称：乙公司"),cn.bidcheck.compat.Java8Api.listOf());
        assertNotNull(group(report,"drivingLicenseNumber"));assertNotNull(group(report,"companyName"));assertTrue(map(report.get("coverage")).containsKey("personCredentials"));
    }

    @Test void genericModelSupplementCannotBypassHolderAndCredentialBinding() {
        Document document=doc("本项目无人员证件字段");java.util.Map<java.lang.String, java.lang.Object> report=new ExpandedConsistencyEngine().scan(document,cn.bidcheck.compat.Java8Api.listOf(values("field","idNumber","rawValue",ID1,"subject","张三")));
        assertTrue(maps(report.get("occurrences")).isEmpty());
    }

    @Test void additionalExtractedFieldsArePreservedEvenWithoutDedicatedComparisonRules() {
        Document input=doc(card("张三",ID1)+"性别：男\n民族：汉\n国籍：中国\n联系电话：13912345678\n工作单位：合成公司\n社会保障号码：SOC12345\n住址：合成路1号\n签发机关：合成机关\n出生日期：1990-01-01");
        cn.bidcheck.document.PersonCredentialFacts.Result extraction=PersonCredentialFacts.analyze(input,input.pages().get(0));java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(input);
        assertEquals(extraction.occurrences().size(),maps(report.get("occurrences")).size());assertEquals(0,map(report.get("coverage")).get("sourceRejected"));
        assertTrue(cn.bidcheck.compat.Java8Api.toList(maps(report.get("occurrences")).stream().map(o->o.get("field"))).containsAll(cn.bidcheck.compat.Java8Api.listOf("credentialPhone", "credentialEmployer", "socialSecurityNumber", "ethnicGroup", "nationality")));
    }

    @Test void explicitDriverAndPassportNumbersOnRosterAreComparedToCredentialPages() {
        java.util.Map<java.lang.String, java.lang.Object> drivers=engine.scan(doc("人员名单\n姓名：张三\n岗位：驾驶员\n驾驶证号码："+ID1,driver("张三",ID2)));
        assertEquals("SUSPECTED",group(drivers,"drivingLicenseNumber").get("status"));
        java.util.Map<java.lang.String, java.lang.Object> passports=engine.scan(doc("人员名单\n姓名：张三\n岗位：商务代表\n护照号码：E12345678","护照\n姓名：张三\n护照号码：E87654321"));
        assertEquals("SUSPECTED",group(passports,"passportNumber").get("status"));
    }

    @Test void ambiguousMultiPersonNameCannotBecomeAConfirmedConflictThroughSharedIdentifier() {
        Document input=doc(card("张三、李四",ID1),card("张三",ID1));java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(input);
        java.util.Map<java.lang.String, java.lang.Object> issue=information(report).stream().filter(g->"credentialPersonName".equals(g.get("field"))).findFirst().orElseThrow(java.util.NoSuchElementException::new);assertEquals("EVIDENCE_INSUFFICIENT",issue.get("status"));assertEquals("INCOMPLETE_IDENTITY",issue.get("identityResolution"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 50),issue.get("sourcePages"));assertTrue(maps(report.get("occurrences")).stream().anyMatch(o->"张三、李四".equals(o.get("rawValue"))));
        assertTrue(groups(report).stream().noneMatch(g->"CONFLICT".equals(g.get("status"))));
    }

    @Test void extractionAmbiguitiesAreInformationalWithoutDiscardingOriginalFields() {
        Document document=doc(card("张三",ID1)+"住址：\n",
                "人员名单\n姓名：张三、李四\n身份证号码："+ID1,
                driver("王五","110101********0011"),
                "资格证书\n姓名：赵六\n证书编号：CERT12345");
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(document);java.util.Map<java.lang.String, java.lang.Object> coverage=map(report.get("coverage"));
        assertEquals("PARTIAL",coverage.get("status"));assertEquals("INFORMATION_ONLY",coverage.get("attentionStatus"));
        assertFalse(PersonCredentialConsistency.requiresReview(coverage));assertTrue(maps(coverage.get("actionableIssues")).isEmpty());
        assertTrue(maps(coverage.get("informationalIssues")).stream().allMatch(i->"INFO".equals(i.get("severity"))&&Boolean.FALSE.equals(i.get("requiresReview"))));
        assertTrue(groups(report).isEmpty());assertFalse(information(report).isEmpty());
        assertTrue(maps(report.get("occurrences")).stream().anyMatch(o->"110101********0011".equals(o.get("rawValue"))));
        assertTrue(maps(report.get("occurrences")).stream().anyMatch(o->"CERT12345".equals(o.get("rawValue"))));
        assertEquals("NO_ACTIONABLE_FINDINGS",report.get("overall"));
    }

    @Test void maskedLicenseDoesNotSuppressConcreteDifferenceInAnotherField() {
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(doc(driver("张三","110101********0011")+"性别：男\n身份证号码："+ID1,
                driver("张三","110101********0011")+"性别：女\n身份证号码："+ID1));
        assertEquals("CONFLICT",group(report,"personGender").get("status"));
        assertEquals(cn.bidcheck.compat.Java8Api.listOf(1, 50),group(report,"personGender").get("sourcePages"));
        assertTrue(groups(report).stream().noneMatch(g->"drivingLicenseNumber".equals(g.get("field"))));
        assertFalse(information(report).isEmpty());assertEquals("REVIEW_REQUIRED",report.get("overall"));
    }

    @Test void oldPartialCoverageDoesNotTurnExtractionInformationIntoARequiredReview() {
        java.util.Map<java.lang.String, java.lang.Object> oldInfo=values("status","PARTIAL","pagesUnparsed",70,"issues",cn.bidcheck.compat.Java8Api.listOf(values("code","CREDENTIAL_TYPE_UNKNOWN"), values("code","PERSON_TABLE_SPAN_UNSUPPORTED")));
        assertFalse(PersonCredentialConsistency.requiresReview(oldInfo));
        java.util.Map<java.lang.String, java.lang.Object> oldTechnical=values("status","PARTIAL","pagesUnparsed",1,"issues",cn.bidcheck.compat.Java8Api.listOf(values("code","PAGE_SOURCE_INCOMPLETE")));
        assertTrue(PersonCredentialConsistency.requiresReview(oldTechnical));
        assertTrue(PersonCredentialConsistency.requiresReview(values("comparisonsTruncated",true)));
        assertTrue(PersonCredentialConsistency.requiresReview(values("sourceRejected",1)));
    }

    @Test void processingLimitRemainsActionableAlongsideInformationalIncompleteFields() {
        Document document=doc(card("张三",ID1));cn.bidcheck.document.PersonCredentialFacts.Result parsed=PersonCredentialFacts.analyze(document,document.pages().get(0));
        cn.bidcheck.document.PersonCredentialFacts.Result result=new PersonCredentialFacts.Result(parsed.page(),parsed.locationType(),true,false,parsed.occurrences(),cn.bidcheck.compat.Java8Api.listOf(values("code","PERSON_RECORD_LIMIT"), values("code","PERSON_FIELD_EMPTY")),parsed.facts());
        java.util.Map<java.lang.String, java.lang.Object> report=engine.scan(document,cn.bidcheck.compat.Java8Api.listOf(result));java.util.Map<java.lang.String, java.lang.Object> coverage=map(report.get("coverage"));
        assertTrue(PersonCredentialConsistency.requiresReview(coverage));assertEquals("ACTION_REQUIRED",coverage.get("attentionStatus"));
        assertEquals(1,maps(coverage.get("actionableIssues")).size());assertEquals(1,maps(coverage.get("informationalIssues")).size());
        assertEquals(1L,coverage.get("actionablePages"));assertEquals(1L,coverage.get("informationalPages"));
        assertEquals("REVIEW_REQUIRED",report.get("overall"));
    }

    @Test void fullTextMergeKeepsExtractionPartialDetailWithoutAddingUnreadPagesOrDifferenceGroups() {
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc(driver("张三","110101********0011")),cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> coverage=map(report.get("coverage"));java.util.Map<java.lang.String, java.lang.Object> personnel=map(coverage.get("personCredentials"));
        assertEquals("PARTIAL",personnel.get("status"));assertFalse(PersonCredentialConsistency.requiresReview(personnel));
        assertEquals(0,coverage.get("pagesUnparsed"));
        assertTrue(groups(report).stream().noneMatch(g->"personnel".equals(g.get("categoryId"))&&"EVIDENCE_INSUFFICIENT".equals(g.get("status"))));
        assertFalse(maps(report.get("personCredentialInformationalGroups")).isEmpty());
    }

    @Test void fullTextGenericCertificateNoticeUsesPersonnelInformationPolicyOnlyForProvenSource() {
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc("资格证书\n姓名：赵六\n证书编号：CERT12345"),cn.bidcheck.compat.Java8Api.listOf());
        assertTrue(groups(report).isEmpty(),()->report.toString());
        assertTrue(maps(report.get("personCredentialInformationalGroups")).stream().anyMatch(g->"certificateNumber".equals(g.get("field"))&&Boolean.FALSE.equals(g.get("requiresReview"))));
        assertTrue(maps(report.get("occurrences")).stream().anyMatch(o->"CERT12345".equals(o.get("rawValue"))));
    }

    @Test void mixedGenericCertificateNoticeRetainsCompanyEvidenceAndMovesOnlyPersonnelSource() {
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc("资格证书\n姓名：赵六\n证书编号：CERT12345", "企业资格证书\n企业名称：示例公司\n证书编号：COMPANY67890"),cn.bidcheck.compat.Java8Api.listOf());
        java.util.Map<java.lang.String, java.lang.Object> company=group(report,"certificateNumber");assertEquals("EVIDENCE_INSUFFICIENT",company.get("status"));
        assertTrue(((List<?>)company.get("evidences")).stream().map(Evidence.class::cast).allMatch(e->e.page()==50));
        assertTrue(((List<?>)company.get("evidences")).stream().map(Evidence.class::cast).anyMatch(e->e.quote().contains("COMPANY67890")));
        assertTrue(maps(report.get("personCredentialInformationalGroups")).stream().filter(g->"certificateNumber".equals(g.get("field"))).flatMap(g->((List<?>)g.get("evidences")).stream()).map(Evidence.class::cast).allMatch(e->e.page()==1));
    }

    @Test void repeatedSameValueAndQuoteOnSamePageMustMatchThePersonnelRecordOffsets() {
        String source="资格证书\n姓名：赵六\n证书编号：CERT12345\n\n企业资格证书\n企业名称：示例公司\n证书编号：CERT12345";
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc(source),cn.bidcheck.compat.Java8Api.listOf());java.util.Map<java.lang.String, java.lang.Object> company=group(report,"certificateNumber");
        Set<Object> companyIds=new HashSet<>((List<?>)company.get("occurrenceIds"));
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> originals=cn.bidcheck.compat.Java8Api.toList(maps(report.get("occurrences")).stream().filter(o->companyIds.contains(o.get("id"))));
        assertEquals(1,originals.size());assertTrue(((Number)originals.get(0).get("sourceOffset")).intValue()>source.indexOf("企业资格证书"));
        assertTrue(maps(report.get("personCredentialInformationalGroups")).stream().anyMatch(g->"certificateNumber".equals(g.get("field"))));
    }

    @Test void concreteGenericAndPersonnelCertificateDifferencesAreNeverMovedToInformation() {
        java.util.Map<java.lang.String, java.lang.Object> report=new ConsistencyEngine().scan(doc("本次投标资格材料\n资格证书\n持证人：张三\n证书名称：安全资格证书\n证书编号：CERT12345",
                "本次投标资格材料\n资格证书\n持证人：张三\n证书名称：安全资格证书\n证书编号：CERT67890"),cn.bidcheck.compat.Java8Api.listOf());
        assertEquals("SUSPECTED",group(report,"certificateNumber").get("status"));
        assertEquals("SUSPECTED",group(report,"personCertificateNumber").get("status"));
        assertTrue(cn.bidcheck.compat.Java8Api.toList(groups(report).stream().filter(g->"certificateNumber".equals(g.get("field"))).flatMap(g->((List<?>)g.get("evidences")).stream()).map(Evidence.class::cast).map(Evidence::page).distinct()).containsAll(cn.bidcheck.compat.Java8Api.listOf(1, 50)));
    }
}
