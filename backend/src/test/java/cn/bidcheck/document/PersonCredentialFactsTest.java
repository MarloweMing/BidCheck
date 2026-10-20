package cn.bidcheck.document;

import cn.bidcheck.domain.Domain.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersonCredentialFactsTest {
    private static final String ID_A = "110101199001011234", ID_B = "110101199001011235";
    private static Page page(int n, String text) { return new Page(n,text,"PAGE","QWEN_PDF_OCR",null); }
    private static Document doc(Page... pages) { return new Document("bid","project","BID","证件材料.pdf",2,"source-hash","2026-10-14",List.of(pages),List.of()); }
    private static PersonCredentialFacts.Result parse(String text) { Page page = page(1,text); return PersonCredentialFacts.analyze(doc(page),page); }
    private static List<PersonCredentialFacts.Occurrence> field(PersonCredentialFacts.Result r,String f) { return r.occurrences().stream().filter(o -> o.field().equals(f)).toList(); }
    private static Set<String> issues(PersonCredentialFacts.Result r) { return new HashSet<>(r.issues().stream().map(i -> i.get("code").toString()).toList()); }
    private static String driver(String name,String number) { return "中华人民共和国机动车驾驶证\n姓名："+name+"\n证号："+number+"\n档案编号：123456789012\n准驾车型：B2\n有效期限：2024-01-01 至 2030-01-01"; }

    @Test void extractsAllExplicitDriverFieldsWithoutConflatingFileNumber() {
        var result = parse(driver("测试甲",ID_A)+"\n性别：男\n出生日期：1990-01-01\n初次领证日期：2010-01-01\n住址：北京市测试区\n签发机关：测试市公安局");
        assertTrue(result.candidate()); assertTrue(result.complete(),result.issues().toString());
        assertEquals(ID_A,field(result,"drivingLicenseNumber").get(0).value());
        assertEquals("123456789012",field(result,"drivingFileNumber").get(0).value());
        assertEquals(Set.of("personName","drivingLicenseNumber","drivingFileNumber","vehicleClass","validPeriod","gender","birthDate","firstIssueDate","address","issuingAuthority"),
                new HashSet<>(result.occurrences().stream().map(PersonCredentialFacts.Occurrence::field).toList()));
        assertTrue(result.occurrences().stream().allMatch(o -> o.credentialType().equals("DRIVING_LICENSE")));
        assertTrue(field(result,"idNumber").isEmpty(),"A driver number is not silently copied into the identity-card field");
    }

    @Test void eachEvidenceAndAnchorReferToOriginalPageAndValue() {
        String source=driver("测试甲",ID_A); Page p=page(50,source); var result=PersonCredentialFacts.analyze(doc(p),p);
        for(var o:result.occurrences()) {
            assertEquals(50,o.evidence().page()); assertEquals("bid",o.evidence().documentId()); assertEquals("PAGE",o.evidence().locationType());
            assertTrue(source.contains(o.evidence().quote())); assertTrue(o.evidence().quote().contains(o.rawValue()));
            Matcher m=Pattern.compile("person-record:[0-9]+:field:[A-Za-z]+:chars:([0-9]+)-([0-9]+)").matcher(o.evidence().anchor());
            assertTrue(m.matches()); assertEquals(o.rawValue(),source.substring(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2))));
            assertEquals(0d,o.evidence().confidence());
        }
        assertEquals(result.occurrences().size(),result.facts().size());
        assertTrue(result.facts().stream().allMatch(f->f.entityType().equals("PERSON_CREDENTIAL")));
    }

    @Test void pageOneAndFiftyRemainIndependentSourceRecordsEvenWhenValuesMatch() {
        Page first=page(1,driver("测试甲",ID_A)),last=page(50,driver("测试甲",ID_A)); Document d=doc(first,last);
        var a=PersonCredentialFacts.analyze(d,first);var b=PersonCredentialFacts.analyze(d,last);
        assertNotEquals(a.occurrences().get(0).recordKey(),b.occurrences().get(0).recordKey());
        assertNotEquals(a.occurrences().get(0).id(),b.occurrences().get(0).id());
        assertEquals(a,PersonCredentialFacts.analyze(d,first));
        Document version=new Document(d.id(),d.projectId(),d.role(),d.name(),3,"other-hash",d.createdAt(),d.pages(),d.warnings());
        assertNotEquals(a.occurrences().get(0).recordKey(),PersonCredentialFacts.analyze(version,first).occurrences().get(0).recordKey());
    }

    @Test void separatelyTitledPeopleOnOnePageDoNotExchangeFields() {
        var r=parse(driver("测试甲",ID_A)+"\n\n"+driver("测试乙",ID_B));
        assertTrue(r.complete(),r.issues().toString()); assertEquals(2,field(r,"personName").size());
        assertEquals(2,r.occurrences().stream().map(PersonCredentialFacts.Occurrence::recordKey).distinct().count());
        assertEquals(List.of("测试甲","测试乙"),field(r,"drivingLicenseNumber").stream().map(PersonCredentialFacts.Occurrence::personName).toList());
        assertEquals(List.of(ID_A,ID_B),field(r,"drivingLicenseNumber").stream().map(PersonCredentialFacts.Occurrence::value).toList());
    }

    @Test void explicitRepeatedNameLabelsBoundSeparateRecords() {
        var r=parse("姓名：测试甲 身份证号："+ID_A+"\n姓名：测试乙 身份证号："+ID_B);
        assertTrue(r.complete(),r.issues().toString());
        assertEquals(List.of("测试甲","测试乙"),field(r,"idNumber").stream().map(PersonCredentialFacts.Occurrence::personName).toList());
        assertEquals(2,r.occurrences().stream().map(PersonCredentialFacts.Occurrence::recordKey).distinct().count());
    }

    @Test void conflictingUnseparatedIdentifiersAreKeptUnassigned() {
        var r=parse("姓名：测试甲\n身份证号："+ID_A+"\n身份证号："+ID_B+"\n姓名：测试乙\n性别：女");
        assertFalse(r.complete());assertTrue(issues(r).contains("PERSON_RECORD_AMBIGUOUS"));
        assertEquals(2,field(r,"idNumber").size());assertTrue(field(r,"idNumber").stream().allMatch(o->o.personName().isEmpty()));
        assertTrue(field(r,"idNumber").stream().allMatch(o->o.context().get("personNameStatus").equals("AMBIGUOUS")));
    }

    @Test void labelsMayHaveValuesOnImmediatelyFollowingLines() {
        var r=parse("居民身份证\n姓名\n测试甲\n公民身份号码\n"+ID_A+"\n性别\n男\n出生日期\n1990-01-01");
        assertTrue(r.complete(),r.issues().toString());assertEquals("测试甲",field(r,"personName").get(0).value());
        assertEquals(ID_A,field(r,"idNumber").get(0).value());
    }

    @Test void englishDriverAndPassportLabelsKeepTheirTypes() {
        var driver=parse("Driving License\nFull Name: Jane Example\nLicense No.: DL-00123\nFile Number: ARCHIVE-22\nSex: Female\nDate of Birth: 1990-01-01\nClass: B\nValid Until: 2030-01-01");
        assertTrue(driver.complete(),driver.issues().toString());
        assertEquals("DRIVING_LICENSE",field(driver,"drivingLicenseNumber").get(0).credentialType());assertEquals("女",field(driver,"gender").get(0).value());
        var passport=parse("Passport\nName: Jane Example\nPassport Number: e00123456\nNationality: China");
        assertTrue(passport.complete(),passport.issues().toString());assertEquals("E00123456",field(passport,"passportNumber").get(0).value());
        assertEquals("e00123456",field(passport,"passportNumber").get(0).rawValue());
    }
    @Test void specificExpiryLabelsDoNotBecomeGenericValidityPeriods() {
        for(String label:List.of("有效期至","有效期截止日期","有效期截止")) {
            var r=parse(driver("测试甲",ID_A)+"\n"+label+"：2030-01-01");
            assertEquals("2030-01-01",field(r,"validUntil").get(0).value());
        }
    }
    @Test void identityCardCandidatesIncludeEnglishAndShortChineseTitles() {
        var english=parse("Identity Card\nName: Jane Example\nIdentity Card Number: "+ID_A);
        assertTrue(english.candidate());assertTrue(english.complete(),english.issues().toString());assertEquals(ID_A,field(english,"idNumber").get(0).value());
        var chinese=parse("身份证\n姓名测试甲\n证号"+ID_A);
        assertTrue(chinese.candidate());assertTrue(chinese.complete(),chinese.issues().toString());assertEquals(ID_A,field(chinese,"idNumber").get(0).value());
    }
    @Test void professionalTitleIsKeptSeparatelyAndNeverSwallowedByNameOrRole() {
        var r=parse("姓名：张三；职称：高级工程师");
        assertTrue(r.complete(),r.issues().toString());assertEquals("张三",field(r,"personName").get(0).value());
        assertEquals("高级工程师",field(r,"professionalTitle").get(0).value());assertTrue(field(r,"role").isEmpty());
    }
    @Test void unknownSemicolonFieldCannotBeSwallowedIntoPreviousName() {
        var r=parse("姓名：张三；未知字段：测试值\n身份证号："+ID_A);
        assertFalse(r.complete());assertEquals("张三",field(r,"personName").get(0).value());assertEquals("张三",field(r,"idNumber").get(0).personName());
        assertTrue(issues(r).contains("PERSON_FIELD_LABEL_UNSUPPORTED"));
    }
    @Test void markdownBoldLabelsAndCodeValuesKeepExactSourceOffsets() {
        String text="## 居民身份证\n- **姓名**：**测 试 甲**\n- **性别**：男\n- **公民身份号码**：`"+ID_A+"`";
        var r=parse(text);assertTrue(r.complete(),r.issues().toString());assertEquals("测试甲",field(r,"personName").get(0).value());
        assertEquals("测 试 甲",field(r,"personName").get(0).rawValue());assertEquals(ID_A,field(r,"idNumber").get(0).rawValue());
        assertEquals("VALID",field(r,"idNumber").get(0).context().get("numberStatus"));
        for(var o:r.occurrences()){assertTrue(o.evidence().quote().contains(o.rawValue()));Matcher m=Pattern.compile(".*:chars:([0-9]+)-([0-9]+)").matcher(o.evidence().anchor());assertTrue(m.matches());assertEquals(o.rawValue(),text.substring(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2))));}
    }
    @Test void markdownCredentialTitlesGiveGenericNumbersTheirExplicitType() {
        for(String title:List.of("# 驾驶证","**驾驶证**","## **驾驶证**")) {
            var r=parse(title+"\n**姓名**：测试甲\n**证号**：`"+ID_A+"`");
            assertTrue(r.complete(),r.issues().toString());assertEquals(ID_A,field(r,"drivingLicenseNumber").get(0).value());
            assertTrue(field(r,"idNumber").isEmpty());assertTrue(field(r,"drivingLicenseNumber").get(0).evidence().quote().contains(title));
        }
    }
    @Test void nativeCharacterSpacedLabelsAndNamesAreParsedWithoutChangingOriginalText() {
        var r=parse("身份证\n姓 名：测 试 甲；性 别：男\n公 民 身 份 号 码："+ID_A);
        assertTrue(r.complete(),r.issues().toString());assertEquals("测试甲",field(r,"idNumber").get(0).personName());
        assertEquals("测 试 甲",field(r,"personName").get(0).rawValue());assertEquals("男",field(r,"gender").get(0).value());
    }
    @Test void spacedMultipleNamesAreNeverCollapsedIntoASinglePerson() {
        var r=parse("身份证\n姓名：张三 李四\n身份证号："+ID_A);
        assertFalse(r.complete());assertTrue(issues(r).contains("PERSON_NAME_AMBIGUOUS"));assertEquals("",field(r,"idNumber").get(0).personName());
        assertEquals("张三 李四",field(r,"personName").get(0).value());
    }
    @Test void twoColumnFieldValueTableHeaderAndMarkdownValuesAreRecognized() {
        var r=parse("身份证\n| 字段 | 内容 |\n|---|---|\n| 姓名 | **测试甲** |\n| 公民身份号码 | `"+ID_A+"` |");
        assertTrue(r.complete(),r.issues().toString());assertEquals("测试甲",field(r,"idNumber").get(0).personName());assertEquals(ID_A,field(r,"idNumber").get(0).rawValue());
    }
    @Test void genericArchiveNumbersDoNotBecomeDrivingLicenseIdentifiers() {
        var r=parse("居民身份证\n姓名：测试甲\n身份证号："+ID_A+"\n档案编号：AR0001");
        assertTrue(r.complete(),r.issues().toString());assertEquals("AR0001",field(r,"archiveNumber").get(0).value());assertTrue(field(r,"drivingFileNumber").isEmpty());
        var explicit=parse("姓名：测试甲\n驾驶证号码："+ID_A+"\n档案编号：AR0001");
        assertEquals("AR0001",field(explicit,"drivingFileNumber").get(0).value());assertTrue(field(explicit,"archiveNumber").isEmpty());
    }
    @Test void genericEnglishClassAndIssueDateDoNotTurnQualificationsIntoDrivingLicenses() {
        var r=parse("注册资格证书\n姓名：测试甲\n证书编号：CERT001\nClass: A\n初次领证日期：2020-01-01");
        assertTrue(r.complete(),r.issues().toString());assertTrue(field(r,"vehicleClass").isEmpty());
        assertEquals("A",field(r,"classification").get(0).value());assertEquals("PERSON_CERTIFICATE",field(r,"firstIssueDate").get(0).credentialType());
    }
    @Test void pureDottedContentsAreReferencesButStandaloneCredentialTitleRemainsCandidate() {
        var toc=parse("目录\n身份证复印件 ........ 21\n人员资格证书 ........ 22\n授权委托书 ........ 23\n4");
        assertFalse(toc.candidate());assertTrue(toc.issues().isEmpty());
        var card=parse("身份证\n[图片暂未识别]");assertTrue(card.candidate());assertFalse(card.complete());assertTrue(issues(card).contains("PERSON_FIELDS_UNRESOLVED"));
    }
    @Test void bilingualRepeatedLabelDoesNotCreateAnEmptyPersonRecord() {
        var r=parse("Identity Card\n姓名 Name: Jane Example\nIdentity Card Number: "+ID_A);
        assertTrue(r.complete(),r.issues().toString());assertEquals(1,field(r,"personName").size());assertEquals("Jane Example",field(r,"idNumber").get(0).personName());
    }

    @Test void certificateTitlesAndExplicitTypesAreRetainedForTypeScopedComparison() {
        var a=parse("建筑施工特种作业资格证书\n姓名：测试甲\n证书编号：CERT-001\n证书等级：一级");
        assertTrue(a.complete(),a.issues().toString());assertEquals("建筑施工特种作业资格证书",field(a,"certificateName").get(0).value());
        assertEquals("建筑施工特种作业资格证书",field(a,"certificateNumber").get(0).context().get("credentialName"));
        var b=parse("姓名：测试甲\n证件类型：护照\n证件号码：E00123456");
        assertTrue(b.complete(),b.issues().toString());assertEquals("E00123456",field(b,"passportNumber").get(0).value());
        assertTrue(field(b,"certificateNumber").isEmpty());
    }

    @Test void ambiguousGenericNumberDoesNotBecomeAnIdentityNumber() {
        var r=parse("姓名：测试甲\n编号：ABC123");
        assertFalse(r.complete());assertTrue(issues(r).contains("CREDENTIAL_TYPE_UNKNOWN"));assertTrue(field(r,"idNumber").isEmpty());
        assertTrue(field(r,"drivingLicenseNumber").isEmpty());assertTrue(field(r,"certificateNumber").isEmpty());
    }

    @Test void nameOnlyAndNumberOnlyRecordsStayIncomplete() {
        var a=parse("居民身份证\n姓名：测试甲");var b=parse("居民身份证\n公民身份号码："+ID_A);
        assertFalse(a.complete());assertTrue(issues(a).contains("CREDENTIAL_NUMBER_MISSING"));
        assertFalse(b.complete());assertTrue(issues(b).contains("PERSON_NAME_MISSING"));
        assertEquals("",field(b,"idNumber").get(0).personName());assertEquals("false",field(b,"idNumber").get(0).context().get("recordComplete"));
    }

    @Test void maskedInvalidAndTruncatedNumbersAreRetainedWithCoverageIssues() {
        for(String number:List.of("110101********1234","11010119900101O234","110101199013011234","1234")) {
            var r=parse("居民身份证\n姓名：测试甲\n公民身份号码："+number);
            assertFalse(r.complete());assertEquals(number,field(r,"idNumber").get(0).rawValue());
            assertEquals(number,field(r,"idNumber").get(0).value());
            assertNotEquals("VALID",field(r,"idNumber").get(0).context().get("numberStatus"));
        }
        var masked=parse(driver("测试甲","110101XXXXXXXX1234"));assertEquals("MASKED",field(masked,"drivingLicenseNumber").get(0).context().get("numberStatus"));
    }

    @Test void multipleNamesInOneValueAreNotAssignedToAnIndividual() {
        var r=parse("居民身份证\n姓名：测试甲、测试乙\n公民身份号码："+ID_A);
        assertFalse(r.complete());assertTrue(issues(r).contains("PERSON_NAME_AMBIGUOUS"));assertEquals("",field(r,"idNumber").get(0).personName());
    }

    @Test void markdownPersonnelTableExtractsEachRowWithRoleAndBothIdentifierTypes() {
        String source="人员一览表\n| 姓名 | 岗位 | 身份证号 | 驾驶证号 | 档案编号 |\n|---|---|---|---|---|\n"
                +"|测试甲|驾驶员|"+ID_A+"|DL-001|AR-001|\n|测试乙|安全员|"+ID_B+"|DL-002|AR-002|";
        var r=parse(source);assertTrue(r.complete(),r.issues().toString());assertEquals(10,r.occurrences().size());
        assertEquals(List.of("驾驶员","安全员"),field(r,"idNumber").stream().map(o->o.context().get("role")).toList());
        assertEquals(List.of("测试甲","测试乙"),field(r,"drivingFileNumber").stream().map(PersonCredentialFacts.Occurrence::personName).toList());
        for(var o:r.occurrences()) assertTrue(source.contains(o.evidence().quote()));
    }

    @Test void htmlPersonnelRowsHaveSourceBoundCellsAndDoNotFillMissingNames() {
        String source="<table><tr><th>姓名</th><th>身份证号</th></tr><tr><td>测试甲</td><td>"+ID_A+"</td></tr>"
                +"<tr><td></td><td>"+ID_B+"</td></tr></table>";
        var r=parse(source);assertFalse(r.complete());assertEquals(2,field(r,"idNumber").size());
        assertEquals("测试甲",field(r,"idNumber").get(0).personName());assertEquals("",field(r,"idNumber").get(1).personName());
        assertTrue(issues(r).contains("PERSON_NAME_MISSING"));assertEquals(1,field(r,"personName").size());
        assertTrue(r.occurrences().stream().allMatch(o->source.contains(o.evidence().quote())&&o.evidence().quote().contains(o.rawValue())));
    }

    @Test void twoColumnCredentialKeyValueTablesAreNotMistakenForPersonnelHeaderRows() {
        var html=parse("<table><tr><td>姓名</td><td>测试甲</td></tr><tr><td>身份证号</td><td>"+ID_A+"</td></tr><tr><td>性别</td><td>男</td></tr></table>");
        assertTrue(html.complete(),html.issues().toString());assertEquals("测试甲",field(html,"idNumber").get(0).personName());assertEquals(1,field(html,"personName").size());
        var md=parse("驾驶证\n|姓名|测试甲|\n|驾驶证号|DL001|\n|档案编号|AR001|\n|姓名|测试乙|\n|驾驶证号|DL002|");
        assertTrue(md.complete(),md.issues().toString());assertEquals(List.of("测试甲","测试乙"),field(md,"drivingLicenseNumber").stream().map(PersonCredentialFacts.Occurrence::personName).toList());
    }

    @Test void titleEvidenceIsNotCopiedIntoLaterIndependentRecordsOutsideItsSourceRange() {
        var r=parse("建筑施工特种作业资格证书\n姓名：测试甲\n证书编号：CERT001\n姓名：测试乙\n证书编号：CERT002");
        assertEquals(2,field(r,"certificateNumber").size());assertFalse(r.complete());
        assertTrue(issues(r).contains("CERTIFICATE_NAME_MISSING"));
        assertTrue(r.occurrences().stream().allMatch(o->o.evidence().quote().contains(o.rawValue())));
    }

    @Test void htmlMergedMalformedAndTruncatedTablesNeverGuessARecord() {
        String good="<table><tr><th>姓名</th><th>身份证号</th></tr><tr><td>测试甲</td><td>"+ID_A+"</td></tr></table>";
        for(String bad:List.of(good.replace("<td>测试甲","<td rowspan='2'>测试甲"),good.replace("</td>","</th>"),good.substring(0,good.length()-8))) {
            var r=parse(bad);assertTrue(r.candidate());assertFalse(r.complete());assertTrue(r.occurrences().isEmpty(),r.occurrences().toString());assertFalse(r.issues().isEmpty());
        }
    }

    @Test void mixedGoodAndBadTableRowsExposePartialCoverage() {
        var r=parse("|姓名|身份证号|\n|---|---|\n|测试甲|"+ID_A+"|\n|测试乙|"+ID_B+"|extra|");
        assertFalse(r.complete());assertEquals(1,field(r,"idNumber").size());assertTrue(issues(r).contains("PERSON_TABLE_COLUMNS_MISMATCH"));
    }

    @Test void unrecognizedTableColumnsAreReportedWithoutDroppingKnownFields() {
        var r=parse("|姓名|身份证号|特殊岗位编号|\n|---|---|---|\n|测试甲|"+ID_A+"|TEST-1|");
        assertFalse(r.complete());assertEquals(ID_A,field(r,"idNumber").get(0).value());assertTrue(issues(r).contains("PERSON_TABLE_FIELDS_UNSUPPORTED"));
    }

    @Test void tableCertificateTypeControlsGenericNumberWithoutMixingDifferentCertificates() {
        var r=parse("|姓名|证件类型|证件号码|\n|---|---|---|\n|测试甲|护照|E00123456|\n|测试甲|驾驶证|DL-001|");
        assertTrue(r.complete(),r.issues().toString());assertEquals(1,field(r,"passportNumber").size());assertEquals(1,field(r,"drivingLicenseNumber").size());
        assertNotEquals(field(r,"passportNumber").get(0).recordKey(),field(r,"drivingLicenseNumber").get(0).recordKey());
    }

    @Test void explicitRosterWithoutCredentialNumbersIsACompleteRosterNotACompletedCertificate() {
        var r=parse("人员名单\n|姓名|岗位|\n|---|---|\n|测试甲|驾驶员|\n|测试乙|安全员|");
        assertTrue(r.complete(),r.issues().toString());assertTrue(r.occurrences().stream().allMatch(o->o.credentialType().equals("PERSON_ROSTER")));
        assertEquals(2,field(r,"role").size());
    }

    @Test void nonPersonnelPageDoesNotClaimAnyCoverageProblem() {
        var r=parse("施工组织方案。工程质量应符合招标文件要求。");
        assertFalse(r.candidate());assertTrue(r.complete());assertTrue(r.issues().isEmpty());assertTrue(r.facts().isEmpty());
    }

    @Test void sourceMismatchAndDeferredRecognitionNeverClaimCompleteness() {
        Page p=page(50,driver("测试甲",ID_A));var mismatch=PersonCredentialFacts.analyze(doc(page(1,p.text())),p);
        assertFalse(mismatch.complete());assertTrue(mismatch.facts().isEmpty());assertTrue(issues(mismatch).contains("SOURCE_NOT_BOUND"));
        Page deferred=new Page(50,p.text(),"PAGE",p.parser(),"[OCR_DEFERRED]");var pending=PersonCredentialFacts.analyze(doc(deferred),deferred);
        assertFalse(pending.complete());assertTrue(issues(pending).contains("SOURCE_INCOMPLETE"));
    }

    @Test void issueMessagesContainNoNamesOrCredentialValues() {
        var r=parse("居民身份证\n姓名：测试甲\n公民身份号码：ABCD1234");
        for(var issue:r.issues()) {assertEquals(1,issue.get("page"));assertFalse(issue.toString().contains("测试甲"));assertFalse(issue.toString().contains("ABCD1234"));}
    }

    @Test void oversizedCandidatePageIsExplicitlyIncomplete() {
        var r=parse("居民身份证\n姓名：测试甲\n公民身份号码："+ID_A+"\n"+"原文".repeat(50_001));
        assertFalse(r.complete());assertTrue(r.occurrences().isEmpty());assertTrue(issues(r).contains("PERSON_PAGE_LIMIT"));
    }
}
