package cn.bidcheck.checktemplate;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TemplateCheckExecutorTest {
    private final CheckTemplateRegistry registry=CheckTemplateRegistry.defaultRegistry();
    private final TemplateCheckExecutor executor=new TemplateCheckExecutor(registry);
    private final SourceRef source=new SourceRef("bid",1,"sha","parser","source","PAGE",8,"正文",null,null);

    @Test void registryLoadsOnlyCodeOwnedTemplatesAndRejectsExecutableParameters(){
        assertEquals(cn.bidcheck.compat.Java8Api.setOf("MATERIAL_PRESENT", "COMMITMENT_WITH_SEAL", "MATERIAL_ALTERNATIVE", "CERTIFICATE_VALIDITY", "PRICE_COMPARE", "PERSONNEL_ROSTER", "SECTION_CHECKLIST", "ENTITY_FIELD_MATCH", "PERSONNEL_CREDENTIAL", "CONSORTIUM_RESPONSIBILITY", "PRICE_RECONCILE", "DURATION_COMPARE", "CROSS_DOCUMENT_CONSISTENCY", "FORM_FIELD_COMPLETENESS", "DATE_RELATION", "TEXT_ANOMALY", "OBJECTIVE_SCORING", "QUALITY_ADVISORY", "EXTERNAL_CHECK"),
                registry.metadata().stream().map(TemplateMetadata::id).collect(java.util.stream.Collectors.toSet()));
        CheckSpec injected=spec("PRICE_COMPARE",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("field", "totalPrice", "operator", "<=", "expected", "100", "currency", "CNY", "unit", "元", "expression", "Runtime.exec()"));
        ValidationResult result=registry.validate(injected);
        assertFalse(result.valid());assertTrue(result.errors().stream().anyMatch(error->"UNKNOWN_PARAMETER".equals(error.code())));
        CheckSpec externalDisguisedAsDocument=spec("EXTERNAL_CHECK",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("source", "信用中国", "queryObject", "投标人", "requiredFields", cn.bidcheck.compat.Java8Api.listOf("失信状态")));
        assertTrue(registry.validate(externalDisguisedAsDocument).errors().stream().anyMatch(error->"VERIFICATION_MODE_UNSUPPORTED".equals(error.code())));
        CheckSpec qualityDisguisedAsDocument=spec("QUALITY_ADVISORY",TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                cn.bidcheck.compat.Java8Api.mapOf("materialType", "施工组织设计", "dimensions", cn.bidcheck.compat.Java8Api.listOf("进度计划")));
        assertTrue(registry.validate(qualityDisguisedAsDocument).errors().stream().anyMatch(error->"VERIFICATION_MODE_UNSUPPORTED".equals(error.code())));
        TemplateMetadata metadata=new TemplateMetadata("BAD","1","bad","java.lang.Runtime",TemplateTypes.ProofStandard.FACTUAL_CONDITION,cn.bidcheck.compat.Java8Api.mapOf(),cn.bidcheck.compat.Java8Api.listOf());
        assertThrows(IllegalStateException.class,()->new CheckTemplateRegistry(cn.bidcheck.compat.Java8Api.listOf(metadata)));
    }

    @Test void titleOnlyNeverProvesMaterialPresenceButBodyDoes(){
        CheckSpec spec=spec("MATERIAL_PRESENT",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,cn.bidcheck.compat.Java8Api.mapOf("materialType", "营业执照", "requireBody", true));
        Observation title=new Observation.MaterialObservation("m1","营业执照",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,false,true,cn.bidcheck.compat.Java8Api.setOf(),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),.9);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(title),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        Observation body=new Observation.MaterialObservation("m2","营业执照",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf(),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),.9);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(body),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
    }

    @Test void commitmentRequiresBodyEveryPointAndObservedSeal(){
        CheckSpec spec=spec("COMMITMENT_WITH_SEAL",TemplateTypes.ProofStandard.WRITTEN_COMMITMENT,
                cn.bidcheck.compat.Java8Api.mapOf("materialType", "企业经营状况承诺书", "requiredCommitmentPoints", cn.bidcheck.compat.Java8Api.listOf("经营状况承诺", "承担相应责任"), "issuerRole", "BIDDER", "sealLocation", "落款"));
        Observation.MaterialObservation body=new Observation.MaterialObservation("m1","企业经营状况承诺书",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,
                cn.bidcheck.compat.Java8Api.setOf("经营状况承诺", "承担相应责任"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        Observation.SealObservation seal=new Observation.SealObservation("s1","m1","BIDDER","落款",true,true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(body, seal),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        TemplateExecution pending=executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(body),cn.bidcheck.compat.Java8Api.setOf()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,pending.projection().conclusion().state());
        String sealNeed=pending.evidenceNeeds().stream().filter(need->need.kind()==TemplateTypes.NeedKind.SEAL).findFirst().orElseThrow(java.util.NoSuchElementException::new).key();
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(body),cn.bidcheck.compat.Java8Api.setOf(sealNeed))).projection().conclusion().state());
    }

    @Test void allowedAlternativeUsesAtLeastNWithoutTurningOrIntoAnd(){
        CheckSpec spec=spec("MATERIAL_ALTERNATIVE",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                cn.bidcheck.compat.Java8Api.mapOf("alternatives", cn.bidcheck.compat.Java8Api.listOf("税务证明", "开票承诺书"), "atLeast", 1, "requireBody", true));
        Observation.MaterialObservation commitment=new Observation.MaterialObservation("m","开票承诺书",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf(),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(commitment),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
    }

    @Test void certificateNeedsItsOwnExpiryAndComparesTheBoundary(){
        CheckSpec spec=spec("CERTIFICATE_VALIDITY",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("certificateType", "建造师注册证", "comparisonDate", "2026-10-20", "inclusive", true),
                new SubjectSelector("PERSON","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.mapOf()));
        Observation.CertificateObservation expired=new Observation.CertificateObservation("c","建造师注册证","person-1","张三","A1",LocalDate.of(2024,1,1),LocalDate.of(2026,10,19),false,true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(expired),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        Observation.CertificateObservation noExpiry=new Observation.CertificateObservation("c2","建造师注册证","person-1","张三","A1",LocalDate.of(2024,1,1),null,false,true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(noExpiry),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        Observation.CertificateObservation boundary=new Observation.CertificateObservation("c3","建造师注册证","person-1","张三","A1",LocalDate.of(2024,1,1),LocalDate.of(2026,10,20),false,true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(boundary),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
    }

    @Test void priceComparisonRequiresOneExactBusinessScope(){
        CheckSpec spec=spec("PRICE_COMPARE",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("field", "totalPrice", "operator", "<=", "expected", "1000000", "currency", "CNY", "unit", "元", "taxBasis", "INCLUSIVE", "lot", "A"));
        Observation.PriceObservation actual=price("p1",new BigDecimal("999999"),"A","INCLUSIVE",true);
        Observation.PriceObservation historical=price("p2",new BigDecimal("2000000"),"A","INCLUSIVE",false);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(actual, historical),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        Observation.PriceObservation conflict=price("p3",new BigDecimal("900000"),"A","INCLUSIVE",true);
        assertEquals(TemplateTypes.EvidenceState.CONFLICTING,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(actual, conflict),cn.bidcheck.compat.Java8Api.setOf())).projection().evidence().state());
        Observation.PriceObservation wrongTax=price("p4",new BigDecimal("800000"),"A","EXCLUSIVE",true);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(wrongTax),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
    }

    @Test void personnelLowerBoundCanPassEarlyButFailureAndMaximumNeedClosedRoster(){
        CheckSpec spec=spec("PERSONNEL_ROSTER",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("role", "项目经理", "minCount", 2, "maxCount", 2, "allowConcurrent", true),SubjectSelector.personnel("项目经理"));
        Observation.PersonnelObservation one=person("p1","张三",true);
        TemplateExecution open=executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(one),cn.bidcheck.compat.Java8Api.setOf()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,open.projection().conclusion().state());
        String roster=open.evidenceNeeds().stream().filter(need->need.kind()==TemplateTypes.NeedKind.ROSTER_SCOPE).findFirst().orElseThrow(java.util.NoSuchElementException::new).key();
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(one),cn.bidcheck.compat.Java8Api.setOf(roster))).projection().conclusion().state());
        Observation.PersonnelObservation two=person("p2","李四",true);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(one, two),cn.bidcheck.compat.Java8Api.setOf(roster))).projection().conclusion().state());
        Observation.PersonnelObservation duplicate=new Observation.PersonnelObservation("duplicate","p1","张三",cn.bidcheck.compat.Java8Api.setOf("项目经理"),"bidder","",true,true,cn.bidcheck.compat.Java8Api.mapOf(),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(one, duplicate),cn.bidcheck.compat.Java8Api.setOf(roster))).projection().conclusion().state());
    }

    @Test void personnelRejectsNonPositiveMinimumAndMaximumBelowMinimum(){
        CheckSpec zero=spec("PERSONNEL_ROSTER",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("role", "项目经理", "minCount", 0, "allowConcurrent", true),SubjectSelector.personnel("项目经理"));
        ValidationResult zeroValidation=registry.validate(zero);
        assertFalse(zeroValidation.valid());
        assertTrue(zeroValidation.errors().stream().anyMatch(error->error.field().equals("minCount")&&error.code().equals("OUT_OF_RANGE")));
        assertEquals("INVALID_CHECK_SPEC",executor.execute(zero,EvidenceView.empty()).projection().conclusion().reasonCode());

        CheckSpec negative=spec("PERSONNEL_ROSTER",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("role", "项目经理", "minCount", -1, "allowConcurrent", true),SubjectSelector.personnel("项目经理"));
        assertTrue(registry.validate(negative).errors().stream().anyMatch(error->error.field().equals("minCount")&&error.code().equals("OUT_OF_RANGE")));
        CheckSpec blank=spec("PERSONNEL_ROSTER",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("role", "项目经理", "minCount", "", "allowConcurrent", true),SubjectSelector.personnel("项目经理"));
        assertTrue(registry.validate(blank).errors().stream().anyMatch(error->error.field().equals("minCount")&&error.code().equals("TYPE_MISMATCH")));

        CheckSpec inverted=spec("PERSONNEL_ROSTER",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("role", "项目经理", "minCount", 2, "maxCount", 1, "allowConcurrent", true),SubjectSelector.personnel("项目经理"));
        ValidationResult invertedValidation=registry.validate(inverted);
        assertFalse(invertedValidation.valid());
        assertTrue(invertedValidation.errors().stream().anyMatch(error->error.field().equals("maxCount")&&error.code().equals("INVALID_RANGE")));
    }

    @Test void sealMustMatchIssuerLocationAndMaterialInsteadOfBorrowingAnySeal(){
        CheckSpec spec=spec("COMMITMENT_WITH_SEAL",TemplateTypes.ProofStandard.WRITTEN_COMMITMENT,
                cn.bidcheck.compat.Java8Api.mapOf("materialType", "诚信承诺书", "requiredCommitmentPoints", cn.bidcheck.compat.Java8Api.listOf("依法经营"), "issuerRole", "BIDDER", "sealLocation", "落款"));
        SourceRef bodySource=new SourceRef("bid",1,"sha","parser","body","PAGE",8,"承诺书正文",null,null);
        SourceRef otherPage=new SourceRef("bid",1,"sha","parser","seal","PAGE",20,"其他文件印章",null,null);
        Observation.MaterialObservation body=new Observation.MaterialObservation("m1","诚信承诺书",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,
                cn.bidcheck.compat.Java8Api.setOf("依法经营"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(bodySource),1);
        Observation.SealObservation borrowed=new Observation.SealObservation("s1","","BIDDER","落款",true,true,
                TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(otherPage),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(body, borrowed),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());

        Observation.SealObservation colocated=new Observation.SealObservation("s2","","BIDDER","落款",true,true,
                TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(bodySource),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(body, colocated),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());

        Observation.SealObservation wrongLocation=new Observation.SealObservation("s3","m1","BIDDER","骑缝处",true,true,
                TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(bodySource),1);
        TemplateExecution pending=executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(body, wrongLocation),cn.bidcheck.compat.Java8Api.setOf()));
        String sealNeed=pending.evidenceNeeds().stream().filter(need->need.kind()==TemplateTypes.NeedKind.SEAL).findFirst().orElseThrow(java.util.NoSuchElementException::new).key();
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(body, wrongLocation),cn.bidcheck.compat.Java8Api.setOf(sealNeed))).projection().conclusion().state());

        SubjectSelector companyWithoutRole=new SubjectSelector("ORGANIZATION","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.mapOf("entityKey", "ACME"));
        CheckSpec issuerUnproven=spec("COMMITMENT_WITH_SEAL",TemplateTypes.ProofStandard.WRITTEN_COMMITMENT,
                cn.bidcheck.compat.Java8Api.mapOf("materialType", "诚信承诺书", "requiredCommitmentPoints", cn.bidcheck.compat.Java8Api.listOf("依法经营"), "issuerRole", "BIDDER", "sealLocation", "落款"),companyWithoutRole);
        Observation.MaterialObservation acmeBody=new Observation.MaterialObservation("m-acme","诚信承诺书",cn.bidcheck.compat.Java8Api.setOf(),"ACME",true,true,false,
                cn.bidcheck.compat.Java8Api.setOf("依法经营"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(bodySource),1);
        Observation.SealObservation acmeSeal=new Observation.SealObservation("s-acme","m-acme","ACME","落款",true,true,
                TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(bodySource),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(issuerUnproven,view(cn.bidcheck.compat.Java8Api.listOf(acmeBody, acmeSeal),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
    }

    @Test void supportedObservationNeedsPositiveConfidenceAndValidSource(){
        CheckSpec spec=spec("MATERIAL_PRESENT",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                cn.bidcheck.compat.Java8Api.mapOf("materialType", "营业执照", "requireBody", true));
        Observation.MaterialObservation noSource=new Observation.MaterialObservation("m1","营业执照",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,
                cn.bidcheck.compat.Java8Api.setOf(),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(),1);
        String need=executor.execute(spec,EvidenceView.empty()).evidenceNeeds().get(0).key();
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(noSource),cn.bidcheck.compat.Java8Api.setOf(need))).projection().conclusion().state());
        Observation.MaterialObservation zeroConfidence=new Observation.MaterialObservation("m2","营业执照",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,
                cn.bidcheck.compat.Java8Api.setOf(),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),0);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec,view(cn.bidcheck.compat.Java8Api.listOf(zeroConfidence),cn.bidcheck.compat.Java8Api.setOf(need))).projection().conclusion().state());
    }

    @Test void selectorRoleEntityTypeAndScopeArePartOfTheProof(){
        Observation.MaterialObservation body=new Observation.MaterialObservation("m1","营业执照",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,
                cn.bidcheck.compat.Java8Api.setOf(),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        CheckSpec unresolvedRole=spec("MATERIAL_PRESENT",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                cn.bidcheck.compat.Java8Api.mapOf("materialType", "营业执照", "requireBody", true),new SubjectSelector("ORGANIZATION","LEGAL_REPRESENTATIVE","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.mapOf("entityKey", "BIDDER")));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(unresolvedRole,view(cn.bidcheck.compat.Java8Api.listOf(body),cn.bidcheck.compat.Java8Api.setOf(unresolvedRole.id()+":material"))).projection().conclusion().state());

        CheckSpec unresolvedScope=spec("MATERIAL_PRESENT",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                cn.bidcheck.compat.Java8Api.mapOf("materialType", "营业执照", "requireBody", true),new SubjectSelector("ORGANIZATION","BIDDER","CURRENT_LOT",cn.bidcheck.compat.Java8Api.mapOf()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(unresolvedScope,view(cn.bidcheck.compat.Java8Api.listOf(body),cn.bidcheck.compat.Java8Api.setOf(unresolvedScope.id()+":material"))).projection().conclusion().state());

        CheckSpec wrongEntity=spec("MATERIAL_PRESENT",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                cn.bidcheck.compat.Java8Api.mapOf("materialType", "营业执照", "requireBody", true),new SubjectSelector("PERSON","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.mapOf()));
        assertNotEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(wrongEntity,view(cn.bidcheck.compat.Java8Api.listOf(body),cn.bidcheck.compat.Java8Api.setOf(wrongEntity.id()+":material"))).projection().conclusion().state());
    }

    @Test void sectionChecklistRequiresEveryNamedBodyAndNeverPassesAnEmptyList(){
        CheckSpec invalid=spec("SECTION_CHECKLIST",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                cn.bidcheck.compat.Java8Api.mapOf("requiredItems", cn.bidcheck.compat.Java8Api.listOf(), "requireBody", true));
        assertFalse(registry.validate(invalid).valid());

        CheckSpec checklist=spec("SECTION_CHECKLIST",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                cn.bidcheck.compat.Java8Api.mapOf("requiredItems", cn.bidcheck.compat.Java8Api.listOf("施工方案", "进度计划"), "requireBody", true));
        Observation.MaterialObservation plan=new Observation.MaterialObservation("m-plan","施工方案",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf(),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        TemplateExecution open=executor.execute(checklist,view(cn.bidcheck.compat.Java8Api.listOf(plan),cn.bidcheck.compat.Java8Api.setOf()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,open.projection().conclusion().state());
        String missingNeed=open.evidenceNeeds().stream().filter(need->"进度计划".equals(need.constraints().get("materialType"))).findFirst().orElseThrow(java.util.NoSuchElementException::new).key();
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(checklist,view(cn.bidcheck.compat.Java8Api.listOf(plan),cn.bidcheck.compat.Java8Api.setOf(missingNeed))).projection().conclusion().state());
        Observation.MaterialObservation scheduleTitle=new Observation.MaterialObservation("m-title","进度计划",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,false,true,cn.bidcheck.compat.Java8Api.setOf(),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertNotEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(checklist,view(cn.bidcheck.compat.Java8Api.listOf(plan, scheduleTitle),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        Observation.MaterialObservation scheduleBody=new Observation.MaterialObservation("m-body","进度计划",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf(),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(checklist,view(cn.bidcheck.compat.Java8Api.listOf(plan, scheduleBody),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
    }

    @Test void entityFieldMatchJoinsTheSamePersonAndNeedsAClosedSubjectSet(){
        CheckSpec match=spec("ENTITY_FIELD_MATCH",TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                cn.bidcheck.compat.Java8Api.mapOf("role", "项目经理", "leftField", "personName", "rightField", "certificate.holderName", "certificateType", "建造师注册证", "caseSensitive", false),
                SubjectSelector.personnel("项目经理"));
        Observation.PersonnelObservation person=new Observation.PersonnelObservation("person","p1","张三",cn.bidcheck.compat.Java8Api.setOf("项目经理"),"bidder","",true,true,cn.bidcheck.compat.Java8Api.mapOf(),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        Observation.CertificateObservation certificate=new Observation.CertificateObservation("cert","建造师注册证","p1","张三","A1",null,null,true,true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        TemplateExecution open=executor.execute(match,view(cn.bidcheck.compat.Java8Api.listOf(person, certificate),cn.bidcheck.compat.Java8Api.setOf()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,open.projection().conclusion().state());
        String roster=open.evidenceNeeds().stream().filter(need->need.kind()==TemplateTypes.NeedKind.ROSTER_SCOPE).findFirst().orElseThrow(java.util.NoSuchElementException::new).key();
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(match,view(cn.bidcheck.compat.Java8Api.listOf(person, certificate),cn.bidcheck.compat.Java8Api.setOf(roster))).projection().conclusion().state());
        Observation.CertificateObservation wrongName=new Observation.CertificateObservation("cert2","建造师注册证","p1","李四","A1",null,null,true,true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(match,view(cn.bidcheck.compat.Java8Api.listOf(person, wrongName),cn.bidcheck.compat.Java8Api.setOf(roster))).projection().conclusion().state());
        Observation.CertificateObservation wrongPerson=new Observation.CertificateObservation("cert3","建造师注册证","p2","张三","A1",null,null,true,true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertNotEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(match,view(cn.bidcheck.compat.Java8Api.listOf(person, wrongPerson),cn.bidcheck.compat.Java8Api.setOf(roster))).projection().conclusion().state());
    }

    @Test void personnelCredentialRequiresIdentityLevelEmployerValidityAndClosedRoster(){
        CheckSpec credential=spec("PERSONNEL_CREDENTIAL",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("role", "项目经理", "certificateType", "建造师注册证", "requiredLevel", "一级", "employerKey", "bidder", "comparisonDate", "2026-10-20"),
                SubjectSelector.personnel("项目经理"));
        Observation.PersonnelObservation person=new Observation.PersonnelObservation("person","p1","张三",cn.bidcheck.compat.Java8Api.setOf("项目经理"),"bidder","",true,true,
                cn.bidcheck.compat.Java8Api.mapOf("certificateLevel", "一级", "registeredEmployer", "bidder"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        Observation.CertificateObservation certificate=new Observation.CertificateObservation("cert","建造师注册证","p1","张三","A1",LocalDate.of(2025,1,1),LocalDate.of(2027,1,1),false,true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        TemplateExecution open=executor.execute(credential,view(cn.bidcheck.compat.Java8Api.listOf(person, certificate),cn.bidcheck.compat.Java8Api.setOf()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,open.projection().conclusion().state());
        String roster=open.evidenceNeeds().stream().filter(need->need.kind()==TemplateTypes.NeedKind.ROSTER_SCOPE).findFirst().orElseThrow(java.util.NoSuchElementException::new).key();
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(credential,view(cn.bidcheck.compat.Java8Api.listOf(person, certificate),cn.bidcheck.compat.Java8Api.setOf(roster))).projection().conclusion().state());
        Observation.PersonnelObservation wrongEmployer=new Observation.PersonnelObservation("person2","p1","张三",cn.bidcheck.compat.Java8Api.setOf("项目经理"),"bidder","",true,true,
                cn.bidcheck.compat.Java8Api.mapOf("certificateLevel", "一级", "registeredEmployer", "other"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(credential,view(cn.bidcheck.compat.Java8Api.listOf(wrongEmployer, certificate),cn.bidcheck.compat.Java8Api.setOf(roster))).projection().conclusion().state());
        Observation.CertificateObservation historical=new Observation.CertificateObservation("old","建造师注册证","p1","张三","A1",null,null,true,false,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertNotEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(credential,view(cn.bidcheck.compat.Java8Api.listOf(person, historical),cn.bidcheck.compat.Java8Api.setOf(roster))).projection().conclusion().state());
    }

    @Test void consortiumResponsibilityNeedsExplicitMembersRolesAndDuties(){
        CheckSpec consortium=spec("CONSORTIUM_RESPONSIBILITY",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("requiredMemberRoles", cn.bidcheck.compat.Java8Api.listOf("牵头方", "成员方"), "requiredResponsibilities", cn.bidcheck.compat.Java8Api.listOf("施工负责"), "leadRole", "牵头方", "minMembers", 2));
        Set<String> allPoints=cn.bidcheck.compat.Java8Api.setOf("牵头方", "成员方", "施工负责", "成员:A公司", "成员:B公司");
        Observation.MaterialObservation agreement=new Observation.MaterialObservation("agreement","联合体协议书",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,allPoints,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(consortium,view(cn.bidcheck.compat.Java8Api.listOf(agreement),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        Observation.MaterialObservation incomplete=new Observation.MaterialObservation("agreement2","联合体协议书",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,
                cn.bidcheck.compat.Java8Api.setOf("牵头方", "成员方", "成员:A公司", "成员:B公司"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        TemplateExecution pending=executor.execute(consortium,view(cn.bidcheck.compat.Java8Api.listOf(incomplete),cn.bidcheck.compat.Java8Api.setOf()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,pending.projection().conclusion().state());
        String need=pending.evidenceNeeds().get(0).key();
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(consortium,view(cn.bidcheck.compat.Java8Api.listOf(incomplete),cn.bidcheck.compat.Java8Api.setOf(need))).projection().conclusion().state());
    }

    @Test void priceReconcileUsesExactDecimalsAndDoesNotPassMissingOrConflictingFields(){
        CheckSpec reconcile=spec("PRICE_RECONCILE",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("totalField", "totalPrice", "componentFields", cn.bidcheck.compat.Java8Api.listOf("labor", "material"), "currency", "CNY", "unit", "元", "taxBasis", "INCLUSIVE", "lot", "A"));
        Observation.PriceObservation labor=priceField("labor",new BigDecimal("10.10"));
        Observation.PriceObservation material=priceField("material",new BigDecimal("20.20"));
        Observation.PriceObservation total=priceField("totalPrice",new BigDecimal("30.30"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(reconcile,view(cn.bidcheck.compat.Java8Api.listOf(labor, material, total),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        Observation.PriceObservation wrongTotal=priceField("totalPrice",new BigDecimal("31"));
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(reconcile,view(cn.bidcheck.compat.Java8Api.listOf(labor, material, wrongTotal),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        TemplateExecution missing=executor.execute(reconcile,view(cn.bidcheck.compat.Java8Api.listOf(labor, total),cn.bidcheck.compat.Java8Api.setOf()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,missing.projection().conclusion().state());
        String materialNeed=missing.evidenceNeeds().stream().filter(need->"material".equals(need.constraints().get("field"))).findFirst().orElseThrow(java.util.NoSuchElementException::new).key();
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(reconcile,view(cn.bidcheck.compat.Java8Api.listOf(labor, total),cn.bidcheck.compat.Java8Api.setOf(materialNeed))).projection().conclusion().state());
        Observation.PriceObservation conflictingMaterial=priceField("material",new BigDecimal("21"));
        assertEquals(TemplateTypes.EvidenceState.CONFLICTING,
                executor.execute(reconcile,view(cn.bidcheck.compat.Java8Api.listOf(labor, material, conflictingMaterial, total),cn.bidcheck.compat.Java8Api.setOf())).projection().evidence().state());
    }

    @Test void durationComparisonRequiresOneAlignedBasisAndDoesNotFailMissingEvidence(){
        CheckSpec duration=spec("DURATION_COMPARE",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("field", "constructionPeriod", "operator", "<=", "expected", "120", "unit", "日", "startEvent", "合同签订", "calendarBasis", "CALENDAR"));
        Observation.PriceObservation actual=new Observation.PriceObservation("duration","constructionPeriod","BIDDER","","合同签订","CALENDAR","DURATION","日",new BigDecimal("118"),true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(duration,view(cn.bidcheck.compat.Java8Api.listOf(actual),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(duration,view(cn.bidcheck.compat.Java8Api.listOf(actual),cn.bidcheck.compat.Java8Api.setOf(duration.id()+":duration"))).projection().conclusion().state());
        Observation.PriceObservation wrongBasis=new Observation.PriceObservation("duration-workday","constructionPeriod","BIDDER","","合同签订","WORKDAY","DURATION","日",new BigDecimal("100"),true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        TemplateExecution pending=executor.execute(duration,view(cn.bidcheck.compat.Java8Api.listOf(wrongBasis),cn.bidcheck.compat.Java8Api.setOf(duration.id()+":duration")));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,pending.projection().conclusion().state());
        assertEquals("DURATION_PENDING",pending.result().reasonCode());
    }

    @Test void crossDocumentConsistencyNeedsTwoDocumentsAndReportsDifferenceAsSuspectedRisk(){
        CheckSpec consistency=spec("CROSS_DOCUMENT_CONSISTENCY",TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                cn.bidcheck.compat.Java8Api.mapOf("field", "companyName", "minimumOccurrences", 2, "caseSensitive", false));
        SourceRef second=new SourceRef("bid-attachment",1,"sha2","parser","source-2","PAGE",2,"正文",null,null);
        Observation.MaterialObservation first=new Observation.MaterialObservation("field-1","FIELD:companyName",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf("VALUE:甲公司"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        Observation.MaterialObservation other=new Observation.MaterialObservation("field-2","FIELD:companyName",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf("VALUE:乙公司"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(second),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(consistency,view(cn.bidcheck.compat.Java8Api.listOf(first),cn.bidcheck.compat.Java8Api.setOf(consistency.id()+":field-values"))).projection().conclusion().state());
        assertEquals(TemplateTypes.ConclusionState.SUSPECTED_RISK,executor.execute(consistency,view(cn.bidcheck.compat.Java8Api.listOf(first, other),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        Observation.MaterialObservation same=new Observation.MaterialObservation("field-3","FIELD:companyName",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf("VALUE:甲公司"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(second),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(consistency,view(cn.bidcheck.compat.Java8Api.listOf(first, same),cn.bidcheck.compat.Java8Api.setOf(consistency.id()+":field-values"))).projection().conclusion().state());
    }

    @Test void crossDocumentConsistencyCanonicalizesMoneyAndRejectsUnknownFields(){
        CheckSpec money=spec("CROSS_DOCUMENT_CONSISTENCY",TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                cn.bidcheck.compat.Java8Api.mapOf("field", "totalPrice", "minimumOccurrences", 2, "caseSensitive", false));
        SourceRef otherSource=new SourceRef("tender",1,"sha2","parser","source-2","PAGE",1,"正文",null,null);
        Observation.MaterialObservation bidValue=new Observation.MaterialObservation("money-1","FIELD:totalPrice",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf("VALUE:人民币100万元"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        Observation.MaterialObservation tenderValue=new Observation.MaterialObservation("money-2","FIELD:totalPrice",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf("VALUE:CNY:1000000"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(otherSource),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(money,view(cn.bidcheck.compat.Java8Api.listOf(bidValue, tenderValue),cn.bidcheck.compat.Java8Api.setOf(money.id()+":field-values"))).projection().conclusion().state());
        CheckSpec unsupported=spec("CROSS_DOCUMENT_CONSISTENCY",TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                cn.bidcheck.compat.Java8Api.mapOf("field", "freeFormUnknown", "minimumOccurrences", 2, "caseSensitive", false));
        assertFalse(registry.validate(unsupported).valid());
    }

    @Test void formCompletenessFailsOnlyAnExplicitBlankOrPlaceholder(){
        CheckSpec form=spec("FORM_FIELD_COMPLETENESS",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("formType", "投标函", "requiredFields", cn.bidcheck.compat.Java8Api.listOf("投标人名称", "报价")));
        Observation.MaterialObservation partial=new Observation.MaterialObservation("form-1","投标函",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf("FIELD_FILLED:投标人名称"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(form,view(cn.bidcheck.compat.Java8Api.listOf(partial),cn.bidcheck.compat.Java8Api.setOf(form.id()+":form"))).projection().conclusion().state());
        Observation.MaterialObservation blank=new Observation.MaterialObservation("form-2","投标函",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf("FIELD_FILLED:投标人名称", "FIELD_PLACEHOLDER:报价"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,executor.execute(form,view(cn.bidcheck.compat.Java8Api.listOf(blank),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        Observation.MaterialObservation complete=new Observation.MaterialObservation("form-3","投标函",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf("FIELD_FILLED:投标人名称", "FIELD_FILLED:报价"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(form,view(cn.bidcheck.compat.Java8Api.listOf(complete),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        SourceRef secondPage=new SourceRef("bid",1,"sha","parser","source-2","PAGE",2,"正文",null,null);
        Observation.MaterialObservation otherPage=new Observation.MaterialObservation("form-4","投标函",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf("FIELD_FILLED:报价"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(secondPage),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(form,view(cn.bidcheck.compat.Java8Api.listOf(partial, otherPage),cn.bidcheck.compat.Java8Api.setOf(form.id()+":form"))).projection().conclusion().state());
    }

    @Test void dateRelationComparesNamedRolesAndNeverBorrowsUnrelatedDates(){
        CheckSpec relation=spec("DATE_RELATION",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("leftRole", "签署日期", "rightRole", "开标日期", "operator", "<=", "relationKey", "project"));
        Observation.CertificateObservation signed=new Observation.CertificateObservation("signed","DATE:签署日期","project","", "",LocalDate.of(2026,10,18),null,false,true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        Observation.CertificateObservation opened=new Observation.CertificateObservation("opened","DATE:开标日期","project","", "",LocalDate.of(2026,10,19),null,false,true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(relation,view(cn.bidcheck.compat.Java8Api.listOf(signed, opened),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        Set<String> closed=cn.bidcheck.compat.Java8Api.setOf(relation.id()+":left-date", relation.id()+":right-date");
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(relation,view(cn.bidcheck.compat.Java8Api.listOf(signed, opened),closed)).projection().conclusion().state());
        Observation.CertificateObservation late=new Observation.CertificateObservation("late","DATE:签署日期","project","", "",LocalDate.of(2026,10,20),null,false,true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,executor.execute(relation,view(cn.bidcheck.compat.Java8Api.listOf(late, opened),closed)).projection().conclusion().state());
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(relation,view(cn.bidcheck.compat.Java8Api.listOf(signed),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
    }

    @Test void textAnomalyNeedsAClosedScanBeforeItCanReportClear(){
        CheckSpec text=spec("TEXT_ANOMALY",TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                cn.bidcheck.compat.Java8Api.mapOf("textScope", "投标正文", "anomalyTypes", cn.bidcheck.compat.Java8Api.listOf("TYPO", "TRUNCATED")));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(text,EvidenceView.empty()).projection().conclusion().state());
        String need=executor.execute(text,EvidenceView.empty()).evidenceNeeds().get(0).key();
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(text,view(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.setOf(need))).projection().conclusion().state());
        Observation.MaterialObservation anomaly=new Observation.MaterialObservation("anomaly","TEXT:投标正文",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf("ANOMALY:TYPO:项日经理"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.SUSPECTED_RISK,executor.execute(text,view(cn.bidcheck.compat.Java8Api.listOf(anomaly),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
    }

    @Test void objectiveScoreWaitsForClosureAndCalculatesWithoutTurningZeroIntoFailure(){
        CheckSpec scoring=spec("OBJECTIVE_SCORING",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                cn.bidcheck.compat.Java8Api.mapOf("criterion", "一级建造师", "aggregation", "SUM", "maxScore", "6", "scoreUnit", "分", "deduplicateBySubject", true),SubjectSelector.personnel("项目经理"));
        Observation.PriceObservation scoreA=new Observation.PriceObservation("score-a","一级建造师","p1","","","","SCORE","分",new BigDecimal("3"),true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(scoring,view(cn.bidcheck.compat.Java8Api.listOf(scoreA),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        String need=executor.execute(scoring,EvidenceView.empty()).evidenceNeeds().get(0).key();
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(scoring,view(cn.bidcheck.compat.Java8Api.listOf(scoreA),cn.bidcheck.compat.Java8Api.setOf(need))).projection().conclusion().state());
        TemplateExecution zero=executor.execute(scoring,view(cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.setOf(need)));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,zero.projection().conclusion().state());
        assertTrue(zero.result().summary().contains("0分"));
    }

    @Test void qualityAdviceSeparatesEvidenceGapFromImprovementSuggestion(){
        CheckSpec quality=specMode("QUALITY_ADVISORY",TemplateTypes.ProofStandard.QUALITY_ADVISORY,TemplateTypes.VerificationMode.HUMAN_JUDGMENT,
                cn.bidcheck.compat.Java8Api.mapOf("materialType", "施工组织设计", "dimensions", cn.bidcheck.compat.Java8Api.listOf("进度计划", "质量保障")),SubjectSelector.bidder());
        Observation.MaterialObservation incomplete=new Observation.MaterialObservation("quality-1","施工组织设计",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf("DIMENSION:进度计划:SUPPORTED"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(quality,view(cn.bidcheck.compat.Java8Api.listOf(incomplete),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
        Observation.MaterialObservation gap=new Observation.MaterialObservation("quality-2","施工组织设计",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf("DIMENSION:进度计划:SUPPORTED", "GAP:质量保障:缺少检查频次"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        TemplateExecution suggested=executor.execute(quality,view(cn.bidcheck.compat.Java8Api.listOf(gap),cn.bidcheck.compat.Java8Api.setOf()));
        assertEquals(TemplateTypes.ConclusionState.SUSPECTED_RISK,suggested.projection().conclusion().state());
        assertEquals(TemplateTypes.ActionOwner.USER,suggested.result().actionOwner());
    }

    @Test void highestTierDescriptionCannotSilentlyIntroduceUngroundedQualityDimensions(){
        CheckSpec quality=specMode("QUALITY_ADVISORY",TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                TemplateTypes.VerificationMode.HUMAN_JUDGMENT,
                cn.bidcheck.compat.Java8Api.mapOf("materialType", "施工组织设计", "dimensions", cn.bidcheck.compat.Java8Api.listOf("完整性", "可行性"), "highestTierDescription", "最高档要求方案完整"),SubjectSelector.bidder());
        ValidationResult validation=registry.validate(quality);
        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error->"DIMENSION_NOT_GROUNDED".equals(error.code())));
    }

    @Test void externalCheckStaysExternalUntilARealRecordIsPresent(){
        CheckSpec external=specMode("EXTERNAL_CHECK",TemplateTypes.ProofStandard.FACTUAL_CONDITION,TemplateTypes.VerificationMode.EXTERNAL,
                cn.bidcheck.compat.Java8Api.mapOf("source", "信用中国", "queryObject", "投标人统一社会信用代码", "requiredFields", cn.bidcheck.compat.Java8Api.listOf("失信状态"), "asOfDate", "2026-10-19", "adapterId", "CREDIT_CHINA", "evaluationStage", "BID_OPENING"),SubjectSelector.bidder());
        TemplateExecution waiting=executor.execute(external,EvidenceView.empty());
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,waiting.projection().conclusion().state());
        assertEquals(TemplateTypes.ActionOwner.EXTERNAL,waiting.result().actionOwner());
        assertEquals("REQUEST_EXTERNAL_RECORD",waiting.result().actionType());
        Observation.MaterialObservation record=new Observation.MaterialObservation("external-record","EXTERNAL:信用中国",cn.bidcheck.compat.Java8Api.setOf(),"BIDDER",true,true,false,cn.bidcheck.compat.Java8Api.setOf("FIELD:失信状态=无失信记录"),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(external,view(cn.bidcheck.compat.Java8Api.listOf(record),cn.bidcheck.compat.Java8Api.setOf())).projection().conclusion().state());
    }

    @Test void restrictedLogicNeverPassesAnEmptyOrOpenUniversalSet(){
        assertEquals(TemplateTypes.ConditionState.UNKNOWN,RestrictedLogic.allOf(cn.bidcheck.compat.Java8Api.listOf()));
        assertEquals(TemplateTypes.ConditionState.UNKNOWN,RestrictedLogic.forEachSubject(cn.bidcheck.compat.Java8Api.listOf(TemplateTypes.ConditionState.SATISFIED),false));
        assertEquals(TemplateTypes.ConditionState.SATISFIED,RestrictedLogic.anyOf(cn.bidcheck.compat.Java8Api.listOf(TemplateTypes.ConditionState.UNKNOWN, TemplateTypes.ConditionState.SATISFIED)));
        assertEquals(TemplateTypes.ConditionState.UNKNOWN,RestrictedLogic.atLeastN(cn.bidcheck.compat.Java8Api.listOf(TemplateTypes.ConditionState.SATISFIED, TemplateTypes.ConditionState.UNKNOWN),2));
    }

    private CheckSpec spec(String id,TemplateTypes.ProofStandard standard,Map<String,Object> parameters){return spec(id,standard,parameters,SubjectSelector.bidder());}
    private CheckSpec spec(String id,TemplateTypes.ProofStandard standard,Map<String,Object> parameters,SubjectSelector selector){
        return specMode(id,standard,TemplateTypes.VerificationMode.DOCUMENT,parameters,selector);
    }
    private CheckSpec specMode(String id,TemplateTypes.ProofStandard standard,TemplateTypes.VerificationMode mode,Map<String,Object> parameters,SubjectSelector selector){
        List<ParameterBinding> bindings=cn.bidcheck.compat.Java8Api.toList(parameters.entrySet().stream().map(entry->new ParameterBinding(entry.getKey(),entry.getValue(),"TENDER_SOURCE","测试规格参数",cn.bidcheck.compat.Java8Api.listOf(source))));
        return new CheckSpec("spec-"+id,1,"item-"+id,"checkpoint-"+id,TemplateTypes.Category.QUALIFICATION,TemplateTypes.Purpose.MANDATORY,mode,id,"1.0",selector,parameters,Applicability.applies(),standard,cn.bidcheck.compat.Java8Api.listOf(source),bindings,cn.bidcheck.compat.Java8Api.mapOf(),cn.bidcheck.compat.Java8Api.mapOf(),cn.bidcheck.compat.Java8Api.mapOf("state", "FULL", "complete", true),true,Confirmation.legacyConfirmed(),"semantic-"+id);
    }
    private EvidenceView view(List<? extends Observation> observations,Set<String> closed){return new EvidenceView("snapshot",1,cn.bidcheck.compat.Java8Api.listCopyOf(observations),closed,cn.bidcheck.compat.Java8Api.setOf(),cn.bidcheck.compat.Java8Api.mapOf());}
    private Observation.PriceObservation price(String id,BigDecimal amount,String lot,String tax,boolean current){return new Observation.PriceObservation(id,"totalPrice","BIDDER",lot,"TOTAL",tax,"CNY","元",amount,current,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);}
    private Observation.PriceObservation priceField(String field,BigDecimal amount){return new Observation.PriceObservation("price-"+field+"-"+amount,field,"BIDDER","A","TOTAL","INCLUSIVE","CNY","元",amount,true,TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);}
    private Observation.PersonnelObservation person(String key,String name,boolean current){return new Observation.PersonnelObservation("o-"+key,key,name,cn.bidcheck.compat.Java8Api.setOf("项目经理"),"bidder","",true,current,cn.bidcheck.compat.Java8Api.mapOf(),TemplateTypes.ObservationState.SUPPORTED,cn.bidcheck.compat.Java8Api.listOf(source),1);}
}
