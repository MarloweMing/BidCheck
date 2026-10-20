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
        assertEquals(Set.of("MATERIAL_PRESENT","COMMITMENT_WITH_SEAL","MATERIAL_ALTERNATIVE","CERTIFICATE_VALIDITY","PRICE_COMPARE","PERSONNEL_ROSTER",
                        "SECTION_CHECKLIST","ENTITY_FIELD_MATCH","PERSONNEL_CREDENTIAL","CONSORTIUM_RESPONSIBILITY","PRICE_RECONCILE",
                        "DURATION_COMPARE","CROSS_DOCUMENT_CONSISTENCY","FORM_FIELD_COMPLETENESS","DATE_RELATION","TEXT_ANOMALY",
                        "OBJECTIVE_SCORING","QUALITY_ADVISORY","EXTERNAL_CHECK"),
                registry.metadata().stream().map(TemplateMetadata::id).collect(java.util.stream.Collectors.toSet()));
        CheckSpec injected=spec("PRICE_COMPARE",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("field","totalPrice","operator","<=","expected","100","currency","CNY","unit","元","expression","Runtime.exec()"));
        ValidationResult result=registry.validate(injected);
        assertFalse(result.valid());assertTrue(result.errors().stream().anyMatch(error->"UNKNOWN_PARAMETER".equals(error.code())));
        CheckSpec externalDisguisedAsDocument=spec("EXTERNAL_CHECK",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("source","信用中国","queryObject","投标人","requiredFields",List.of("失信状态")));
        assertTrue(registry.validate(externalDisguisedAsDocument).errors().stream().anyMatch(error->"VERIFICATION_MODE_UNSUPPORTED".equals(error.code())));
        CheckSpec qualityDisguisedAsDocument=spec("QUALITY_ADVISORY",TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("materialType","施工组织设计","dimensions",List.of("进度计划")));
        assertTrue(registry.validate(qualityDisguisedAsDocument).errors().stream().anyMatch(error->"VERIFICATION_MODE_UNSUPPORTED".equals(error.code())));
        TemplateMetadata metadata=new TemplateMetadata("BAD","1","bad","java.lang.Runtime",TemplateTypes.ProofStandard.FACTUAL_CONDITION,Map.of(),List.of());
        assertThrows(IllegalStateException.class,()->new CheckTemplateRegistry(List.of(metadata)));
    }

    @Test void titleOnlyNeverProvesMaterialPresenceButBodyDoes(){
        CheckSpec spec=spec("MATERIAL_PRESENT",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,Map.of("materialType","营业执照","requireBody",true));
        Observation title=new Observation.MaterialObservation("m1","营业执照",Set.of(),"BIDDER",true,false,true,Set.of(),TemplateTypes.ObservationState.SUPPORTED,List.of(source),.9);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(spec,view(List.of(title),Set.of())).projection().conclusion().state());
        Observation body=new Observation.MaterialObservation("m2","营业执照",Set.of(),"BIDDER",true,true,false,Set.of(),TemplateTypes.ObservationState.SUPPORTED,List.of(source),.9);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(spec,view(List.of(body),Set.of())).projection().conclusion().state());
    }

    @Test void commitmentRequiresBodyEveryPointAndObservedSeal(){
        CheckSpec spec=spec("COMMITMENT_WITH_SEAL",TemplateTypes.ProofStandard.WRITTEN_COMMITMENT,
                Map.of("materialType","企业经营状况承诺书","requiredCommitmentPoints",List.of("经营状况承诺","承担相应责任"),
                        "issuerRole","BIDDER","sealLocation","落款"));
        Observation.MaterialObservation body=new Observation.MaterialObservation("m1","企业经营状况承诺书",Set.of(),"BIDDER",true,true,false,
                Set.of("经营状况承诺","承担相应责任"),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        Observation.SealObservation seal=new Observation.SealObservation("s1","m1","BIDDER","落款",true,true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(spec,view(List.of(body,seal),Set.of())).projection().conclusion().state());
        TemplateExecution pending=executor.execute(spec,view(List.of(body),Set.of()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,pending.projection().conclusion().state());
        String sealNeed=pending.evidenceNeeds().stream().filter(need->need.kind()==TemplateTypes.NeedKind.SEAL).findFirst().orElseThrow().key();
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,executor.execute(spec,view(List.of(body),Set.of(sealNeed))).projection().conclusion().state());
    }

    @Test void allowedAlternativeUsesAtLeastNWithoutTurningOrIntoAnd(){
        CheckSpec spec=spec("MATERIAL_ALTERNATIVE",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                Map.of("alternatives",List.of("税务证明","开票承诺书"),"atLeast",1,"requireBody",true));
        Observation.MaterialObservation commitment=new Observation.MaterialObservation("m","开票承诺书",Set.of(),"BIDDER",true,true,false,Set.of(),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(spec,view(List.of(commitment),Set.of())).projection().conclusion().state());
    }

    @Test void certificateNeedsItsOwnExpiryAndComparesTheBoundary(){
        CheckSpec spec=spec("CERTIFICATE_VALIDITY",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("certificateType","建造师注册证","comparisonDate","2026-10-20","inclusive",true),
                new SubjectSelector("PERSON","","CURRENT_PROJECT",Map.of()));
        Observation.CertificateObservation expired=new Observation.CertificateObservation("c","建造师注册证","person-1","张三","A1",LocalDate.of(2024,1,1),LocalDate.of(2026,10,19),false,true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,executor.execute(spec,view(List.of(expired),Set.of())).projection().conclusion().state());
        Observation.CertificateObservation noExpiry=new Observation.CertificateObservation("c2","建造师注册证","person-1","张三","A1",LocalDate.of(2024,1,1),null,false,true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(spec,view(List.of(noExpiry),Set.of())).projection().conclusion().state());
        Observation.CertificateObservation boundary=new Observation.CertificateObservation("c3","建造师注册证","person-1","张三","A1",LocalDate.of(2024,1,1),LocalDate.of(2026,10,20),false,true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(spec,view(List.of(boundary),Set.of())).projection().conclusion().state());
    }

    @Test void priceComparisonRequiresOneExactBusinessScope(){
        CheckSpec spec=spec("PRICE_COMPARE",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("field","totalPrice","operator","<=","expected","1000000","currency","CNY","unit","元","taxBasis","INCLUSIVE","lot","A"));
        Observation.PriceObservation actual=price("p1",new BigDecimal("999999"),"A","INCLUSIVE",true);
        Observation.PriceObservation historical=price("p2",new BigDecimal("2000000"),"A","INCLUSIVE",false);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(spec,view(List.of(actual,historical),Set.of())).projection().conclusion().state());
        Observation.PriceObservation conflict=price("p3",new BigDecimal("900000"),"A","INCLUSIVE",true);
        assertEquals(TemplateTypes.EvidenceState.CONFLICTING,executor.execute(spec,view(List.of(actual,conflict),Set.of())).projection().evidence().state());
        Observation.PriceObservation wrongTax=price("p4",new BigDecimal("800000"),"A","EXCLUSIVE",true);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(spec,view(List.of(wrongTax),Set.of())).projection().conclusion().state());
    }

    @Test void personnelLowerBoundCanPassEarlyButFailureAndMaximumNeedClosedRoster(){
        CheckSpec spec=spec("PERSONNEL_ROSTER",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("role","项目经理","minCount",2,"maxCount",2,"allowConcurrent",true),SubjectSelector.personnel("项目经理"));
        Observation.PersonnelObservation one=person("p1","张三",true);
        TemplateExecution open=executor.execute(spec,view(List.of(one),Set.of()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,open.projection().conclusion().state());
        String roster=open.evidenceNeeds().stream().filter(need->need.kind()==TemplateTypes.NeedKind.ROSTER_SCOPE).findFirst().orElseThrow().key();
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,executor.execute(spec,view(List.of(one),Set.of(roster))).projection().conclusion().state());
        Observation.PersonnelObservation two=person("p2","李四",true);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(spec,view(List.of(one,two),Set.of(roster))).projection().conclusion().state());
        Observation.PersonnelObservation duplicate=new Observation.PersonnelObservation("duplicate","p1","张三",Set.of("项目经理"),"bidder","",true,true,Map.of(),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,executor.execute(spec,view(List.of(one,duplicate),Set.of(roster))).projection().conclusion().state());
    }

    @Test void personnelRejectsNonPositiveMinimumAndMaximumBelowMinimum(){
        CheckSpec zero=spec("PERSONNEL_ROSTER",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("role","项目经理","minCount",0,"allowConcurrent",true),SubjectSelector.personnel("项目经理"));
        ValidationResult zeroValidation=registry.validate(zero);
        assertFalse(zeroValidation.valid());
        assertTrue(zeroValidation.errors().stream().anyMatch(error->error.field().equals("minCount")&&error.code().equals("OUT_OF_RANGE")));
        assertEquals("INVALID_CHECK_SPEC",executor.execute(zero,EvidenceView.empty()).projection().conclusion().reasonCode());

        CheckSpec negative=spec("PERSONNEL_ROSTER",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("role","项目经理","minCount",-1,"allowConcurrent",true),SubjectSelector.personnel("项目经理"));
        assertTrue(registry.validate(negative).errors().stream().anyMatch(error->error.field().equals("minCount")&&error.code().equals("OUT_OF_RANGE")));
        CheckSpec blank=spec("PERSONNEL_ROSTER",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("role","项目经理","minCount","","allowConcurrent",true),SubjectSelector.personnel("项目经理"));
        assertTrue(registry.validate(blank).errors().stream().anyMatch(error->error.field().equals("minCount")&&error.code().equals("TYPE_MISMATCH")));

        CheckSpec inverted=spec("PERSONNEL_ROSTER",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("role","项目经理","minCount",2,"maxCount",1,"allowConcurrent",true),SubjectSelector.personnel("项目经理"));
        ValidationResult invertedValidation=registry.validate(inverted);
        assertFalse(invertedValidation.valid());
        assertTrue(invertedValidation.errors().stream().anyMatch(error->error.field().equals("maxCount")&&error.code().equals("INVALID_RANGE")));
    }

    @Test void sealMustMatchIssuerLocationAndMaterialInsteadOfBorrowingAnySeal(){
        CheckSpec spec=spec("COMMITMENT_WITH_SEAL",TemplateTypes.ProofStandard.WRITTEN_COMMITMENT,
                Map.of("materialType","诚信承诺书","requiredCommitmentPoints",List.of("依法经营"),
                        "issuerRole","BIDDER","sealLocation","落款"));
        SourceRef bodySource=new SourceRef("bid",1,"sha","parser","body","PAGE",8,"承诺书正文",null,null);
        SourceRef otherPage=new SourceRef("bid",1,"sha","parser","seal","PAGE",20,"其他文件印章",null,null);
        Observation.MaterialObservation body=new Observation.MaterialObservation("m1","诚信承诺书",Set.of(),"BIDDER",true,true,false,
                Set.of("依法经营"),TemplateTypes.ObservationState.SUPPORTED,List.of(bodySource),1);
        Observation.SealObservation borrowed=new Observation.SealObservation("s1","","BIDDER","落款",true,true,
                TemplateTypes.ObservationState.SUPPORTED,List.of(otherPage),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec,view(List.of(body,borrowed),Set.of())).projection().conclusion().state());

        Observation.SealObservation colocated=new Observation.SealObservation("s2","","BIDDER","落款",true,true,
                TemplateTypes.ObservationState.SUPPORTED,List.of(bodySource),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(spec,view(List.of(body,colocated),Set.of())).projection().conclusion().state());

        Observation.SealObservation wrongLocation=new Observation.SealObservation("s3","m1","BIDDER","骑缝处",true,true,
                TemplateTypes.ObservationState.SUPPORTED,List.of(bodySource),1);
        TemplateExecution pending=executor.execute(spec,view(List.of(body,wrongLocation),Set.of()));
        String sealNeed=pending.evidenceNeeds().stream().filter(need->need.kind()==TemplateTypes.NeedKind.SEAL).findFirst().orElseThrow().key();
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(spec,view(List.of(body,wrongLocation),Set.of(sealNeed))).projection().conclusion().state());

        SubjectSelector companyWithoutRole=new SubjectSelector("ORGANIZATION","","CURRENT_PROJECT",Map.of("entityKey","ACME"));
        CheckSpec issuerUnproven=spec("COMMITMENT_WITH_SEAL",TemplateTypes.ProofStandard.WRITTEN_COMMITMENT,
                Map.of("materialType","诚信承诺书","requiredCommitmentPoints",List.of("依法经营"),
                        "issuerRole","BIDDER","sealLocation","落款"),companyWithoutRole);
        Observation.MaterialObservation acmeBody=new Observation.MaterialObservation("m-acme","诚信承诺书",Set.of(),"ACME",true,true,false,
                Set.of("依法经营"),TemplateTypes.ObservationState.SUPPORTED,List.of(bodySource),1);
        Observation.SealObservation acmeSeal=new Observation.SealObservation("s-acme","m-acme","ACME","落款",true,true,
                TemplateTypes.ObservationState.SUPPORTED,List.of(bodySource),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(issuerUnproven,view(List.of(acmeBody,acmeSeal),Set.of())).projection().conclusion().state());
    }

    @Test void supportedObservationNeedsPositiveConfidenceAndValidSource(){
        CheckSpec spec=spec("MATERIAL_PRESENT",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                Map.of("materialType","营业执照","requireBody",true));
        Observation.MaterialObservation noSource=new Observation.MaterialObservation("m1","营业执照",Set.of(),"BIDDER",true,true,false,
                Set.of(),TemplateTypes.ObservationState.SUPPORTED,List.of(),1);
        String need=executor.execute(spec,EvidenceView.empty()).evidenceNeeds().get(0).key();
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec,view(List.of(noSource),Set.of(need))).projection().conclusion().state());
        Observation.MaterialObservation zeroConfidence=new Observation.MaterialObservation("m2","营业执照",Set.of(),"BIDDER",true,true,false,
                Set.of(),TemplateTypes.ObservationState.SUPPORTED,List.of(source),0);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(spec,view(List.of(zeroConfidence),Set.of(need))).projection().conclusion().state());
    }

    @Test void selectorRoleEntityTypeAndScopeArePartOfTheProof(){
        Observation.MaterialObservation body=new Observation.MaterialObservation("m1","营业执照",Set.of(),"BIDDER",true,true,false,
                Set.of(),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        CheckSpec unresolvedRole=spec("MATERIAL_PRESENT",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                Map.of("materialType","营业执照","requireBody",true),new SubjectSelector("ORGANIZATION","LEGAL_REPRESENTATIVE","CURRENT_PROJECT",Map.of("entityKey","BIDDER")));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(unresolvedRole,view(List.of(body),Set.of(unresolvedRole.id()+":material"))).projection().conclusion().state());

        CheckSpec unresolvedScope=spec("MATERIAL_PRESENT",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                Map.of("materialType","营业执照","requireBody",true),new SubjectSelector("ORGANIZATION","BIDDER","CURRENT_LOT",Map.of()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(unresolvedScope,view(List.of(body),Set.of(unresolvedScope.id()+":material"))).projection().conclusion().state());

        CheckSpec wrongEntity=spec("MATERIAL_PRESENT",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                Map.of("materialType","营业执照","requireBody",true),new SubjectSelector("PERSON","","CURRENT_PROJECT",Map.of()));
        assertNotEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(wrongEntity,view(List.of(body),Set.of(wrongEntity.id()+":material"))).projection().conclusion().state());
    }

    @Test void sectionChecklistRequiresEveryNamedBodyAndNeverPassesAnEmptyList(){
        CheckSpec invalid=spec("SECTION_CHECKLIST",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                Map.of("requiredItems",List.of(),"requireBody",true));
        assertFalse(registry.validate(invalid).valid());

        CheckSpec checklist=spec("SECTION_CHECKLIST",TemplateTypes.ProofStandard.MATERIAL_SUBMISSION,
                Map.of("requiredItems",List.of("施工方案","进度计划"),"requireBody",true));
        Observation.MaterialObservation plan=new Observation.MaterialObservation("m-plan","施工方案",Set.of(),"BIDDER",true,true,false,Set.of(),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        TemplateExecution open=executor.execute(checklist,view(List.of(plan),Set.of()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,open.projection().conclusion().state());
        String missingNeed=open.evidenceNeeds().stream().filter(need->"进度计划".equals(need.constraints().get("materialType"))).findFirst().orElseThrow().key();
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(checklist,view(List.of(plan),Set.of(missingNeed))).projection().conclusion().state());
        Observation.MaterialObservation scheduleTitle=new Observation.MaterialObservation("m-title","进度计划",Set.of(),"BIDDER",true,false,true,Set.of(),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertNotEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(checklist,view(List.of(plan,scheduleTitle),Set.of())).projection().conclusion().state());
        Observation.MaterialObservation scheduleBody=new Observation.MaterialObservation("m-body","进度计划",Set.of(),"BIDDER",true,true,false,Set.of(),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(checklist,view(List.of(plan,scheduleBody),Set.of())).projection().conclusion().state());
    }

    @Test void entityFieldMatchJoinsTheSamePersonAndNeedsAClosedSubjectSet(){
        CheckSpec match=spec("ENTITY_FIELD_MATCH",TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                Map.of("role","项目经理","leftField","personName","rightField","certificate.holderName","certificateType","建造师注册证","caseSensitive",false),
                SubjectSelector.personnel("项目经理"));
        Observation.PersonnelObservation person=new Observation.PersonnelObservation("person","p1","张三",Set.of("项目经理"),"bidder","",true,true,Map.of(),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        Observation.CertificateObservation certificate=new Observation.CertificateObservation("cert","建造师注册证","p1","张三","A1",null,null,true,true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        TemplateExecution open=executor.execute(match,view(List.of(person,certificate),Set.of()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,open.projection().conclusion().state());
        String roster=open.evidenceNeeds().stream().filter(need->need.kind()==TemplateTypes.NeedKind.ROSTER_SCOPE).findFirst().orElseThrow().key();
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(match,view(List.of(person,certificate),Set.of(roster))).projection().conclusion().state());
        Observation.CertificateObservation wrongName=new Observation.CertificateObservation("cert2","建造师注册证","p1","李四","A1",null,null,true,true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(match,view(List.of(person,wrongName),Set.of(roster))).projection().conclusion().state());
        Observation.CertificateObservation wrongPerson=new Observation.CertificateObservation("cert3","建造师注册证","p2","张三","A1",null,null,true,true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertNotEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(match,view(List.of(person,wrongPerson),Set.of(roster))).projection().conclusion().state());
    }

    @Test void personnelCredentialRequiresIdentityLevelEmployerValidityAndClosedRoster(){
        CheckSpec credential=spec("PERSONNEL_CREDENTIAL",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("role","项目经理","certificateType","建造师注册证","requiredLevel","一级","employerKey","bidder","comparisonDate","2026-10-20"),
                SubjectSelector.personnel("项目经理"));
        Observation.PersonnelObservation person=new Observation.PersonnelObservation("person","p1","张三",Set.of("项目经理"),"bidder","",true,true,
                Map.of("certificateLevel","一级","registeredEmployer","bidder"),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        Observation.CertificateObservation certificate=new Observation.CertificateObservation("cert","建造师注册证","p1","张三","A1",LocalDate.of(2025,1,1),LocalDate.of(2027,1,1),false,true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        TemplateExecution open=executor.execute(credential,view(List.of(person,certificate),Set.of()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,open.projection().conclusion().state());
        String roster=open.evidenceNeeds().stream().filter(need->need.kind()==TemplateTypes.NeedKind.ROSTER_SCOPE).findFirst().orElseThrow().key();
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(credential,view(List.of(person,certificate),Set.of(roster))).projection().conclusion().state());
        Observation.PersonnelObservation wrongEmployer=new Observation.PersonnelObservation("person2","p1","张三",Set.of("项目经理"),"bidder","",true,true,
                Map.of("certificateLevel","一级","registeredEmployer","other"),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(credential,view(List.of(wrongEmployer,certificate),Set.of(roster))).projection().conclusion().state());
        Observation.CertificateObservation historical=new Observation.CertificateObservation("old","建造师注册证","p1","张三","A1",null,null,true,false,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertNotEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(credential,view(List.of(person,historical),Set.of(roster))).projection().conclusion().state());
    }

    @Test void consortiumResponsibilityNeedsExplicitMembersRolesAndDuties(){
        CheckSpec consortium=spec("CONSORTIUM_RESPONSIBILITY",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("requiredMemberRoles",List.of("牵头方","成员方"),"requiredResponsibilities",List.of("施工负责"),"leadRole","牵头方","minMembers",2));
        Set<String> allPoints=Set.of("牵头方","成员方","施工负责","成员:A公司","成员:B公司");
        Observation.MaterialObservation agreement=new Observation.MaterialObservation("agreement","联合体协议书",Set.of(),"BIDDER",true,true,false,allPoints,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(consortium,view(List.of(agreement),Set.of())).projection().conclusion().state());
        Observation.MaterialObservation incomplete=new Observation.MaterialObservation("agreement2","联合体协议书",Set.of(),"BIDDER",true,true,false,
                Set.of("牵头方","成员方","成员:A公司","成员:B公司"),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        TemplateExecution pending=executor.execute(consortium,view(List.of(incomplete),Set.of()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,pending.projection().conclusion().state());
        String need=pending.evidenceNeeds().get(0).key();
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(consortium,view(List.of(incomplete),Set.of(need))).projection().conclusion().state());
    }

    @Test void priceReconcileUsesExactDecimalsAndDoesNotPassMissingOrConflictingFields(){
        CheckSpec reconcile=spec("PRICE_RECONCILE",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("totalField","totalPrice","componentFields",List.of("labor","material"),"currency","CNY","unit","元","taxBasis","INCLUSIVE","lot","A"));
        Observation.PriceObservation labor=priceField("labor",new BigDecimal("10.10"));
        Observation.PriceObservation material=priceField("material",new BigDecimal("20.20"));
        Observation.PriceObservation total=priceField("totalPrice",new BigDecimal("30.30"));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(reconcile,view(List.of(labor,material,total),Set.of())).projection().conclusion().state());
        Observation.PriceObservation wrongTotal=priceField("totalPrice",new BigDecimal("31"));
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(reconcile,view(List.of(labor,material,wrongTotal),Set.of())).projection().conclusion().state());
        TemplateExecution missing=executor.execute(reconcile,view(List.of(labor,total),Set.of()));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,missing.projection().conclusion().state());
        String materialNeed=missing.evidenceNeeds().stream().filter(need->"material".equals(need.constraints().get("field"))).findFirst().orElseThrow().key();
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,
                executor.execute(reconcile,view(List.of(labor,total),Set.of(materialNeed))).projection().conclusion().state());
        Observation.PriceObservation conflictingMaterial=priceField("material",new BigDecimal("21"));
        assertEquals(TemplateTypes.EvidenceState.CONFLICTING,
                executor.execute(reconcile,view(List.of(labor,material,conflictingMaterial,total),Set.of())).projection().evidence().state());
    }

    @Test void durationComparisonRequiresOneAlignedBasisAndDoesNotFailMissingEvidence(){
        CheckSpec duration=spec("DURATION_COMPARE",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("field","constructionPeriod","operator","<=","expected","120","unit","日","startEvent","合同签订","calendarBasis","CALENDAR"));
        Observation.PriceObservation actual=new Observation.PriceObservation("duration","constructionPeriod","BIDDER","","合同签订","CALENDAR","DURATION","日",new BigDecimal("118"),true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(duration,view(List.of(actual),Set.of())).projection().conclusion().state());
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(duration,view(List.of(actual),Set.of(duration.id()+":duration"))).projection().conclusion().state());
        Observation.PriceObservation wrongBasis=new Observation.PriceObservation("duration-workday","constructionPeriod","BIDDER","","合同签订","WORKDAY","DURATION","日",new BigDecimal("100"),true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        TemplateExecution pending=executor.execute(duration,view(List.of(wrongBasis),Set.of(duration.id()+":duration")));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,pending.projection().conclusion().state());
        assertEquals("DURATION_PENDING",pending.result().reasonCode());
    }

    @Test void crossDocumentConsistencyNeedsTwoDocumentsAndReportsDifferenceAsSuspectedRisk(){
        CheckSpec consistency=spec("CROSS_DOCUMENT_CONSISTENCY",TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                Map.of("field","companyName","minimumOccurrences",2,"caseSensitive",false));
        SourceRef second=new SourceRef("bid-attachment",1,"sha2","parser","source-2","PAGE",2,"正文",null,null);
        Observation.MaterialObservation first=new Observation.MaterialObservation("field-1","FIELD:companyName",Set.of(),"BIDDER",true,true,false,Set.of("VALUE:甲公司"),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        Observation.MaterialObservation other=new Observation.MaterialObservation("field-2","FIELD:companyName",Set.of(),"BIDDER",true,true,false,Set.of("VALUE:乙公司"),TemplateTypes.ObservationState.SUPPORTED,List.of(second),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(consistency,view(List.of(first),Set.of(consistency.id()+":field-values"))).projection().conclusion().state());
        assertEquals(TemplateTypes.ConclusionState.SUSPECTED_RISK,executor.execute(consistency,view(List.of(first,other),Set.of())).projection().conclusion().state());
        Observation.MaterialObservation same=new Observation.MaterialObservation("field-3","FIELD:companyName",Set.of(),"BIDDER",true,true,false,Set.of("VALUE:甲公司"),TemplateTypes.ObservationState.SUPPORTED,List.of(second),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(consistency,view(List.of(first,same),Set.of(consistency.id()+":field-values"))).projection().conclusion().state());
    }

    @Test void crossDocumentConsistencyCanonicalizesMoneyAndRejectsUnknownFields(){
        CheckSpec money=spec("CROSS_DOCUMENT_CONSISTENCY",TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                Map.of("field","totalPrice","minimumOccurrences",2,"caseSensitive",false));
        SourceRef otherSource=new SourceRef("tender",1,"sha2","parser","source-2","PAGE",1,"正文",null,null);
        Observation.MaterialObservation bidValue=new Observation.MaterialObservation("money-1","FIELD:totalPrice",Set.of(),"BIDDER",true,true,false,Set.of("VALUE:人民币100万元"),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        Observation.MaterialObservation tenderValue=new Observation.MaterialObservation("money-2","FIELD:totalPrice",Set.of(),"BIDDER",true,true,false,Set.of("VALUE:CNY:1000000"),TemplateTypes.ObservationState.SUPPORTED,List.of(otherSource),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,
                executor.execute(money,view(List.of(bidValue,tenderValue),Set.of(money.id()+":field-values"))).projection().conclusion().state());
        CheckSpec unsupported=spec("CROSS_DOCUMENT_CONSISTENCY",TemplateTypes.ProofStandard.OBSERVED_CONSISTENCY,
                Map.of("field","freeFormUnknown","minimumOccurrences",2,"caseSensitive",false));
        assertFalse(registry.validate(unsupported).valid());
    }

    @Test void formCompletenessFailsOnlyAnExplicitBlankOrPlaceholder(){
        CheckSpec form=spec("FORM_FIELD_COMPLETENESS",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("formType","投标函","requiredFields",List.of("投标人名称","报价")));
        Observation.MaterialObservation partial=new Observation.MaterialObservation("form-1","投标函",Set.of(),"BIDDER",true,true,false,Set.of("FIELD_FILLED:投标人名称"),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(form,view(List.of(partial),Set.of(form.id()+":form"))).projection().conclusion().state());
        Observation.MaterialObservation blank=new Observation.MaterialObservation("form-2","投标函",Set.of(),"BIDDER",true,true,false,Set.of("FIELD_FILLED:投标人名称","FIELD_PLACEHOLDER:报价"),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,executor.execute(form,view(List.of(blank),Set.of())).projection().conclusion().state());
        Observation.MaterialObservation complete=new Observation.MaterialObservation("form-3","投标函",Set.of(),"BIDDER",true,true,false,Set.of("FIELD_FILLED:投标人名称","FIELD_FILLED:报价"),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(form,view(List.of(complete),Set.of())).projection().conclusion().state());
        SourceRef secondPage=new SourceRef("bid",1,"sha","parser","source-2","PAGE",2,"正文",null,null);
        Observation.MaterialObservation otherPage=new Observation.MaterialObservation("form-4","投标函",Set.of(),"BIDDER",true,true,false,Set.of("FIELD_FILLED:报价"),TemplateTypes.ObservationState.SUPPORTED,List.of(secondPage),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,
                executor.execute(form,view(List.of(partial,otherPage),Set.of(form.id()+":form"))).projection().conclusion().state());
    }

    @Test void dateRelationComparesNamedRolesAndNeverBorrowsUnrelatedDates(){
        CheckSpec relation=spec("DATE_RELATION",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("leftRole","签署日期","rightRole","开标日期","operator","<=","relationKey","project"));
        Observation.CertificateObservation signed=new Observation.CertificateObservation("signed","DATE:签署日期","project","", "",LocalDate.of(2026,10,18),null,false,true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        Observation.CertificateObservation opened=new Observation.CertificateObservation("opened","DATE:开标日期","project","", "",LocalDate.of(2026,10,19),null,false,true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(relation,view(List.of(signed,opened),Set.of())).projection().conclusion().state());
        Set<String> closed=Set.of(relation.id()+":left-date",relation.id()+":right-date");
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(relation,view(List.of(signed,opened),closed)).projection().conclusion().state());
        Observation.CertificateObservation late=new Observation.CertificateObservation("late","DATE:签署日期","project","", "",LocalDate.of(2026,10,20),null,false,true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.NOT_SATISFIED,executor.execute(relation,view(List.of(late,opened),closed)).projection().conclusion().state());
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(relation,view(List.of(signed),Set.of())).projection().conclusion().state());
    }

    @Test void textAnomalyNeedsAClosedScanBeforeItCanReportClear(){
        CheckSpec text=spec("TEXT_ANOMALY",TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                Map.of("textScope","投标正文","anomalyTypes",List.of("TYPO","TRUNCATED")));
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(text,EvidenceView.empty()).projection().conclusion().state());
        String need=executor.execute(text,EvidenceView.empty()).evidenceNeeds().get(0).key();
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(text,view(List.of(),Set.of(need))).projection().conclusion().state());
        Observation.MaterialObservation anomaly=new Observation.MaterialObservation("anomaly","TEXT:投标正文",Set.of(),"BIDDER",true,true,false,Set.of("ANOMALY:TYPO:项日经理"),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.SUSPECTED_RISK,executor.execute(text,view(List.of(anomaly),Set.of())).projection().conclusion().state());
    }

    @Test void objectiveScoreWaitsForClosureAndCalculatesWithoutTurningZeroIntoFailure(){
        CheckSpec scoring=spec("OBJECTIVE_SCORING",TemplateTypes.ProofStandard.FACTUAL_CONDITION,
                Map.of("criterion","一级建造师","aggregation","SUM","maxScore","6","scoreUnit","分","deduplicateBySubject",true),SubjectSelector.personnel("项目经理"));
        Observation.PriceObservation scoreA=new Observation.PriceObservation("score-a","一级建造师","p1","","","","SCORE","分",new BigDecimal("3"),true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(scoring,view(List.of(scoreA),Set.of())).projection().conclusion().state());
        String need=executor.execute(scoring,EvidenceView.empty()).evidenceNeeds().get(0).key();
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(scoring,view(List.of(scoreA),Set.of(need))).projection().conclusion().state());
        TemplateExecution zero=executor.execute(scoring,view(List.of(),Set.of(need)));
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,zero.projection().conclusion().state());
        assertTrue(zero.result().summary().contains("0分"));
    }

    @Test void qualityAdviceSeparatesEvidenceGapFromImprovementSuggestion(){
        CheckSpec quality=specMode("QUALITY_ADVISORY",TemplateTypes.ProofStandard.QUALITY_ADVISORY,TemplateTypes.VerificationMode.HUMAN_JUDGMENT,
                Map.of("materialType","施工组织设计","dimensions",List.of("进度计划","质量保障")),SubjectSelector.bidder());
        Observation.MaterialObservation incomplete=new Observation.MaterialObservation("quality-1","施工组织设计",Set.of(),"BIDDER",true,true,false,Set.of("DIMENSION:进度计划:SUPPORTED"),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,executor.execute(quality,view(List.of(incomplete),Set.of())).projection().conclusion().state());
        Observation.MaterialObservation gap=new Observation.MaterialObservation("quality-2","施工组织设计",Set.of(),"BIDDER",true,true,false,Set.of("DIMENSION:进度计划:SUPPORTED","GAP:质量保障:缺少检查频次"),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        TemplateExecution suggested=executor.execute(quality,view(List.of(gap),Set.of()));
        assertEquals(TemplateTypes.ConclusionState.SUSPECTED_RISK,suggested.projection().conclusion().state());
        assertEquals(TemplateTypes.ActionOwner.USER,suggested.result().actionOwner());
    }

    @Test void highestTierDescriptionCannotSilentlyIntroduceUngroundedQualityDimensions(){
        CheckSpec quality=specMode("QUALITY_ADVISORY",TemplateTypes.ProofStandard.QUALITY_ADVISORY,
                TemplateTypes.VerificationMode.HUMAN_JUDGMENT,
                Map.of("materialType","施工组织设计","dimensions",List.of("完整性","可行性"),
                        "highestTierDescription","最高档要求方案完整"),SubjectSelector.bidder());
        ValidationResult validation=registry.validate(quality);
        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error->"DIMENSION_NOT_GROUNDED".equals(error.code())));
    }

    @Test void externalCheckStaysExternalUntilARealRecordIsPresent(){
        CheckSpec external=specMode("EXTERNAL_CHECK",TemplateTypes.ProofStandard.FACTUAL_CONDITION,TemplateTypes.VerificationMode.EXTERNAL,
                Map.of("source","信用中国","queryObject","投标人统一社会信用代码","requiredFields",List.of("失信状态"),
                        "asOfDate","2026-10-19","adapterId","CREDIT_CHINA","evaluationStage","BID_OPENING"),SubjectSelector.bidder());
        TemplateExecution waiting=executor.execute(external,EvidenceView.empty());
        assertEquals(TemplateTypes.ConclusionState.UNDETERMINED,waiting.projection().conclusion().state());
        assertEquals(TemplateTypes.ActionOwner.EXTERNAL,waiting.result().actionOwner());
        assertEquals("REQUEST_EXTERNAL_RECORD",waiting.result().actionType());
        Observation.MaterialObservation record=new Observation.MaterialObservation("external-record","EXTERNAL:信用中国",Set.of(),"BIDDER",true,true,false,Set.of("FIELD:失信状态=无失信记录"),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);
        assertEquals(TemplateTypes.ConclusionState.SATISFIED,executor.execute(external,view(List.of(record),Set.of())).projection().conclusion().state());
    }

    @Test void restrictedLogicNeverPassesAnEmptyOrOpenUniversalSet(){
        assertEquals(TemplateTypes.ConditionState.UNKNOWN,RestrictedLogic.allOf(List.of()));
        assertEquals(TemplateTypes.ConditionState.UNKNOWN,RestrictedLogic.forEachSubject(List.of(TemplateTypes.ConditionState.SATISFIED),false));
        assertEquals(TemplateTypes.ConditionState.SATISFIED,RestrictedLogic.anyOf(List.of(TemplateTypes.ConditionState.UNKNOWN,TemplateTypes.ConditionState.SATISFIED)));
        assertEquals(TemplateTypes.ConditionState.UNKNOWN,RestrictedLogic.atLeastN(List.of(TemplateTypes.ConditionState.SATISFIED,TemplateTypes.ConditionState.UNKNOWN),2));
    }

    private CheckSpec spec(String id,TemplateTypes.ProofStandard standard,Map<String,Object> parameters){return spec(id,standard,parameters,SubjectSelector.bidder());}
    private CheckSpec spec(String id,TemplateTypes.ProofStandard standard,Map<String,Object> parameters,SubjectSelector selector){
        return specMode(id,standard,TemplateTypes.VerificationMode.DOCUMENT,parameters,selector);
    }
    private CheckSpec specMode(String id,TemplateTypes.ProofStandard standard,TemplateTypes.VerificationMode mode,Map<String,Object> parameters,SubjectSelector selector){
        List<ParameterBinding> bindings=parameters.entrySet().stream().map(entry->new ParameterBinding(entry.getKey(),entry.getValue(),"TENDER_SOURCE","测试规格参数",List.of(source))).toList();
        return new CheckSpec("spec-"+id,1,"item-"+id,"checkpoint-"+id,TemplateTypes.Category.QUALIFICATION,TemplateTypes.Purpose.MANDATORY,mode,id,"1.0",selector,parameters,Applicability.applies(),standard,List.of(source),bindings,Map.of(),Map.of(),Map.of("state","FULL","complete",true),true,Confirmation.legacyConfirmed(),"semantic-"+id);
    }
    private EvidenceView view(List<? extends Observation> observations,Set<String> closed){return new EvidenceView("snapshot",1,List.copyOf(observations),closed,Set.of(),Map.of());}
    private Observation.PriceObservation price(String id,BigDecimal amount,String lot,String tax,boolean current){return new Observation.PriceObservation(id,"totalPrice","BIDDER",lot,"TOTAL",tax,"CNY","元",amount,current,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);}
    private Observation.PriceObservation priceField(String field,BigDecimal amount){return new Observation.PriceObservation("price-"+field+"-"+amount,field,"BIDDER","A","TOTAL","INCLUSIVE","CNY","元",amount,true,TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);}
    private Observation.PersonnelObservation person(String key,String name,boolean current){return new Observation.PersonnelObservation("o-"+key,key,name,Set.of("项目经理"),"bidder","",true,current,Map.of(),TemplateTypes.ObservationState.SUPPORTED,List.of(source),1);}
}
