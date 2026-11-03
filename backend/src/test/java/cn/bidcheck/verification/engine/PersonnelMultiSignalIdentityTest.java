package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonnelMultiSignalIdentityTest {
    Rule rule(){return new Rule("cert","持证","CERTIFICATE","项目负责人","证书","","","",cn.bidcheck.compat.Java8Api.mapOf("category", "一级注册建造师", "profession", "建筑工程"),"项目负责人须持一级注册建造师建筑工程专业证书","",cn.bidcheck.compat.Java8Api.listOf());}
    EvidenceCorpus fixture(String employer,String owner,boolean historical){
        List<Page> pages=cn.bidcheck.compat.Java8Api.listOf(new Page(1,"投标文件\n项目名称：北方中心办公楼装修工程\n1","PAGE","TEXT",""), new Page(2,"目录\n1.项目负责人 ............ 3\n2","PAGE","TEXT",""), new Page(3,"1.项目负责人\n附表6-1 项目负责人情况表\n姓名 李明 年龄35\n工作单位 北方设计有限公司\n拟在本合同任职 项目负责人\n主要工作经历\n任职经历\n3","PAGE","TEXT",""), new Page(4,"（2）证明文件扫描件\n4","PAGE","TEXT",""), new Page(5,(historical?"历史项目\n":"")+"一级建造师注册证书\n5\n姓名："+owner+"\n注册编号：京1212016201900103\n聘用单位："+employer+"\n注册专业：建筑工程(有效期：2023-08-07至2026-08-06)","PAGE","PDF_OCR",""));
        return new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document("binding-doc","binding-project","BID","合成投标文件.pdf",1,"binding-hash","",pages,cn.bidcheck.compat.Java8Api.listOf())));
    }
    Fact role(EvidenceCorpus c){return new Fact("role","PERSON_ROLE","项目负责人","姓名","李明","李明","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.toList(c.all.stream().filter(s->s.evidence().page()==3).map(Source::id)),cn.bidcheck.compat.Java8Api.mapOf("employer", "北方设计有限公司"));}
    List<Fact> certs(EvidenceCorpus c){return NativeCertificateFacts.extract(rule(),c,cn.bidcheck.compat.Java8Api.toList(c.all.stream().filter(s->s.evidence().page()==5)));}
    @Test void exactNameEmployerAndUniqueExplicitAttachmentCanBindWithoutInventingAnIdentifier(){cn.bidcheck.verification.engine.EvidenceCorpus c=fixture("北方设计有限公司","李明",false);java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Fact> certs=certs(c);assertFalse(certs.isEmpty());Fact certificate=certs.get(0),role=role(c);java.util.Map<java.lang.String, java.lang.Object> proof=PersonnelAttachmentIdentity.multiSignalProof(role,certificate,c);assertFalse(proof.isEmpty(),c.all.toString());assertEquals(100,proof.get("score"));assertEquals(true,proof.get("doesNotEstablishGlobalIdentity"));cn.bidcheck.verification.engine.SubjectIdentity identities=new SubjectIdentity(cn.bidcheck.compat.Java8Api.listOf(role, certificate),c,cn.bidcheck.compat.Java8Api.listOf());assertTrue(identities.samePerson(role,certificate));assertEquals("",identities.countIdentity(role));assertEquals("PASS",new RuleEvaluator(cn.bidcheck.compat.Java8Api.listOf(role, certificate),c,cn.bidcheck.compat.Java8Api.mapOf()).evaluate(rule()).status());}
    @Test void differentEmployerMaskedNameOrHistoricalPacketCannotBind(){for(EvidenceCorpus c:cn.bidcheck.compat.Java8Api.listOf(fixture("南方设计有限公司","李明",false), fixture("北方设计有限公司","李*",false), fixture("北方设计有限公司","李明",true)))for(Fact certificate:certs(c))assertTrue(PersonnelAttachmentIdentity.multiSignalProof(role(c),certificate,c).isEmpty());}
    @Test void anUnreadableCompetingCredentialInTheSamePacketCannotDisappearFromUniqueness(){cn.bidcheck.domain.Domain.Document original=fixture("北方设计有限公司","李明",false).documents.get(0);List<Page> pages=new ArrayList<>(original.pages());pages.add(new Page(6,"一级建造师注册证书\n姓名：李明\n注册编号：识别不清\n6","PAGE","PDF_OCR",""));cn.bidcheck.verification.engine.EvidenceCorpus c=new EvidenceCorpus(cn.bidcheck.compat.Java8Api.listOf(new Document(original.id(),original.projectId(),original.role(),original.name(),original.version(),original.sha256(),original.createdAt(),pages,cn.bidcheck.compat.Java8Api.listOf())));for(Fact cert:certs(c))assertTrue(PersonnelAttachmentIdentity.multiSignalProof(role(c),cert,c).isEmpty());}
}
