package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ProofVerifierCertificateBindingTest {
    private static final String CERT="# 安全生产许可证\n\n编号：A123\n\n单位名称：甲公司\n主要负责人：张甲\n有效期：2022年11月01日至2025年10月31日\n发证机关：主管部门";
    private final ObjectMapper json=new ObjectMapper();
    private EvidenceCorpus corpus(String text){return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("bid","p","BID","certificate.pdf",1,"hash","",cn.bidcheck.compat.Java8Api.listOf(new Page(1,text,"PAGE","PDF","")),cn.bidcheck.compat.Java8Api.listOf())));}
    private ProofVerifier.Verified verify(String text,String kind,String subject,Map<String,Object> attrs){
        EvidenceCorpus corpus=corpus(text);String raw=kind.equals("CERTIFICATE")?"编号：A123":"有效期：2022年11月01日至2025年10月31日";
        Fact fact=new Fact("candidate",kind,subject,kind.equals("CERTIFICATE")?"安全生产许可证":"有效期",raw,raw,"","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(corpus.all.get(0).id()),attrs);
        return new ProofVerifier().verify(json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(fact))),corpus.all);
    }
    private Map<String,Object> identity(){return linked("category","安全生产许可证","number","A123","numberField","编号","employer","甲公司");}
    private Map<String,Object> dates(){Map<String,Object> attrs=identity();attrs.putAll(cn.bidcheck.compat.Java8Api.mapOf("field", "有效期", "start", "2022年11月01日", "end", "2025年10月31日"));return attrs;}
    @Test void headingAndNumberImmediatelyAboveLabelledHolderRemainInCertificate(){cn.bidcheck.verification.engine.ProofVerifier.Verified result=verify(CERT,"CERTIFICATE","甲公司",identity());assertEquals(1,result.facts().size(),result.rejected().toString());}
    @Test void chineseOriginalDatesBindToSameCertificateWithoutChangingRawValue(){cn.bidcheck.verification.engine.ProofVerifier.Verified result=verify(CERT,"DATE_RANGE","甲公司",dates());assertEquals(1,result.facts().size(),result.rejected().toString());assertEquals("2025年10月31日",result.facts().get(0).attributes().get("end"));}
    @Test void completeDateRepresentationsAreParsedWithoutGuessing(){for(String value:cn.bidcheck.compat.Java8Api.listOf("2025-10-31", "2025年10月31日", "2025/10/31", "2025.10.31"))assertEquals(LocalDate.of(2025,10,31),ProofVerifier.date(value));for(String value:cn.bidcheck.compat.Java8Api.listOf("2025年10月", "2025年2月29日", "2025年10月31日至2026年10月31日", "截至2025年10月31日", "2025年13月31日"))assertNull(ProofVerifier.date(value));}
    @Test void anotherHolderBlocksPrefixInheritance(){String text=CERT.replace("单位名称：甲公司","单位名称：乙公司\n单位名称：甲公司");assertTrue(verify(text,"CERTIFICATE","甲公司",identity()).facts().isEmpty());}
    @Test void unrelatedProseBlocksPrefixInheritance(){String text=CERT.replace("单位名称：甲公司","以上为乙公司证书，仅供示例。\n单位名称：甲公司");assertTrue(verify(text,"CERTIFICATE","甲公司",identity()).facts().isEmpty());}
    @Test void anUnknownHolderCannotBorrowTheOnlyCertificate(){assertTrue(verify(CERT,"CERTIFICATE","乙公司",identity()).facts().isEmpty());}
    @Test void anotherCertificateTitleBlocksBorrowedValidity(){String text=CERT.replace("有效期：","注册证书\n证书编号：B456\n有效期：");assertTrue(verify(text,"DATE_RANGE","甲公司",dates()).facts().isEmpty());}
    @Test void twoNumbersInOneCertificateStayAmbiguous(){String text=CERT.replace("编号：A123","编号：A123\n编号：B456");assertTrue(verify(text,"CERTIFICATE","甲公司",identity()).facts().isEmpty());}
    @Test void issueDateCannotSupplyValidity(){String text=CERT.replace("有效期：2022年11月01日至2025年10月31日","有效期：2022年11月01日至2024年10月31日\n签发日期：2025年10月31日");Map<String,Object> attrs=dates();Fact f;EvidenceCorpus corpus=corpus(text);f=new Fact("candidate","DATE_RANGE","甲公司","有效期","有效期：2022年11月01日至2024年10月31日","2022年11月01日至2024年10月31日","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(corpus.all.get(0).id()),attrs);assertTrue(new ProofVerifier().verify(json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(f))),corpus.all).facts().isEmpty());}
    @Test void certificateOfficerStillIsNotAProjectAppointment(){EvidenceCorpus corpus=corpus(CERT);Fact fact=new Fact("candidate","PERSON_ROLE","主要负责人","主要负责人","主要负责人：张甲","张甲","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.listOf(corpus.all.get(0).id()),cn.bidcheck.compat.Java8Api.mapOf("employer", "甲公司"));cn.bidcheck.verification.engine.ProofVerifier.Verified result=new ProofVerifier().verify(json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(fact))),corpus.all);assertTrue(result.facts().isEmpty());assertEquals("CURRENT_ROLE_SOURCE_UNPROVEN",result.rejected().get(0).get("code"));}
    private ProofVerifier.Verified registration(String text){EvidenceCorpus c=corpus(text);Fact f=new Fact("registration","CERTIFICATE","李明","证书","AR1001","AR1001","","","UNRESOLVED",cn.bidcheck.compat.Java8Api.listOf(c.all.get(0).id()),cn.bidcheck.compat.Java8Api.mapOf("category", "一级注册建筑师", "number", "AR1001", "numberField", "注册编号"));return new ProofVerifier().verify(json.valueToTree(cn.bidcheck.compat.Java8Api.mapOf("facts", cn.bidcheck.compat.Java8Api.listOf(f))),c.all);}
    private String registrationText(){return "一级注册建筑师注册证书\n姓名：李明\n注册编号：AR1001\n聘用单位：甲公司";}
    @Test void registeredProfessionAndLiteralDocumentTitleRemainOneCertificate(){assertEquals(1,registration(registrationText()).facts().size());}
    @Test void holdersIdentityNumberDoesNotBecomeASecondCertificateNumber(){for(String label:cn.bidcheck.compat.Java8Api.listOf("身份证号", "身份证号码", "证件号码")){String text=registrationText().replace("姓名：李明","姓名：李明\n"+label+"：110101198001010010");assertEquals(1,registration(text).facts().size(),registration(text).rejected().toString());}}
    @Test void priorNarrativeNameDoesNotDisplaceUniqueLabelledCertificateHolder(){assertEquals(1,registration("本项目拟派李明担任设计负责人。\n"+registrationText()).facts().size());}
    @Test void twoSameNameHolderRecordsCannotBeCombined(){assertTrue(registration(registrationText()+"\n一级注册建筑师注册证书\n姓名：李明\n注册编号：AR2002").facts().isEmpty());}
    @Test void examinationQualificationCannotBorrowRegistrationTitle(){assertTrue(registration(registrationText().replace("一级注册建筑师注册证书","一级注册建筑师资格证书")).facts().isEmpty());}
}
