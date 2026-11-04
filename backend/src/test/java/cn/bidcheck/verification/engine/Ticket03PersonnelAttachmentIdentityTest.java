package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.bidcheck.compat.Java8Api.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Entirely synthetic; no model, filesystem fixture, or real bidding document. */
class Ticket03PersonnelAttachmentIdentityTest {
    static String id(){String prefix="11010119900101001";int[] w={7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};int sum=0;for(int i=0;i<17;i++)sum+=(prefix.charAt(i)-'0')*w[i];return prefix+"10X98765432".charAt(sum%11);}
    static Rule rule(){return new Rule("design","设计负责人证书","CERTIFICATE","设计部分负责人","证书","","","",mapOf("category","一级注册建筑师"),"须提供一级注册建筑师证书","",listOf());}
    static String certificate(){return "一级注册建筑师注册证书\n姓名：李明\n出生日期：1990年01月01日\n注册编号：20991101234\n聘用单位：北方设计有限公司\n注册有效期：2024年01月01日-2028年01月01日";}
    static EvidenceCorpus fixture(String socialId,String name,String extra,boolean card,boolean fragments){
        String number=id();String row="北京市社会保险个人权益记录\n单位名称：北方设计有限公司\n<table><tr><td>姓名</td><td>社会保障号码</td><td>险种</td><td>起始年月</td><td>截止年月</td><td>本单位实际缴费月数</td></tr><tr><td>"+name+"</td><td>"+socialId+"</td><td>养老保险</td><td>2025年01月</td><td>2025年08月</td><td>8</td></tr></table>";
        List<Page> pages=new ArrayList<>();pages.add(new Page(1,"投标文件\n项目名称：合成办公楼工程\n1","PAGE","TEXT",""));pages.add(new Page(2,"目录\n1.设计部分负责人 ............ 3\n2","PAGE","TEXT",""));
        pages.add(new Page(3,"1.设计部分负责人\n附表6-4 设计部分负责人情况表\n姓 名 李明 年 龄 35岁 学历 大专\n"+(fragments?number.substring(0,13):"未提供")+"\n职 称 建筑设计 身份证号 拟在本合同任职 设计部分负责人\n"+(fragments?number.substring(13):"未提供")+"\n工程师\n主要工作经历\n任职经历\n3","PAGE","TEXT",""));
        pages.add(new Page(4,"（2）证明文件扫描件\n4","PAGE","TEXT",""));pages.add(new Page(5,row+"\n5","PAGE","PDF_OCR",""));
        if(card)pages.add(new Page(6,"身份证\n姓名 李明\n出生 1990 年 1 月 1 日\n公民身份号码 "+number+"\n6","PAGE","PDF_OCR",""));
        pages.add(new Page(7,certificate()+"\n7","PAGE","PDF_OCR",""));if(!extra.isEmpty())pages.add(new Page(8,extra+"\n8","PAGE","PDF_OCR",""));
        return new EvidenceCorpus(listOf(new Document("synthetic-design","synthetic-project","BID","合成资料.pdf",1,"synthetic-hash","",pages,listOf())));
    }
    static Fact role(EvidenceCorpus c){for(Source s:c.all)if(s.evidence().page()==3){List<Fact> f=DocumentSubjectScope.nativeAppointments(s,"设计部分负责人");if(!f.isEmpty())return f.get(0);}throw new AssertionError("synthetic appointment missing");}
    static Fact certificate(EvidenceCorpus c){return NativeCertificateFacts.extract(rule(),c,toList(c.all.stream().filter(s->s.evidence().page()==7))).get(0);}
    @Test void splitAppointmentUsesOnlyItsSameNameChecksumValidatedSocialRow(){
        EvidenceCorpus c=fixture(id(),"李明","",false,true);Fact r=role(c);assertFalse(PersonnelAttachmentIdentity.appointmentIdentity(r,c).isEmpty());assertFalse(new SubjectIdentity(listOf(r),c,listOf()).countIdentity(r).isEmpty());
    }
    @Test void wrongDigitMissingFragmentsAndOtherPersonNeverBecomeStrong(){
        for(EvidenceCorpus c:listOf(fixture(id().substring(0,17)+(id().endsWith("0")?"1":"0"),"李明","",false,true),fixture(id(),"李明","",false,false),fixture(id(),"王强","",false,true))){Fact r=role(c);assertTrue(PersonnelAttachmentIdentity.appointmentIdentity(r,c).isEmpty());assertEquals("",new SubjectIdentity(listOf(r),c,listOf()).countIdentity(r));assertFalse(c.identityPacketRejections.isEmpty());}
    }
    @Test void queryEmployerColumnIsNotAnUnreadableCompetingCertificate(){
        EvidenceCorpus c=fixture(id(),"李明","证书查询网址及网址查询截图\nhttps://example.invalid/query\n姓名：李明\n注册证书所在单位名称 北方设计有限公司\n一级注册建筑师\n电子证书编号：20991101234",true,true);
        assertFalse(PersonnelAttachmentIdentity.proof(role(c),certificate(c),c).isEmpty(),"The query's employer column is not a second certificate occurrence");
    }
    @Test void actualUnreadableOrDifferentCompetingCertificateStillVetoes(){
        for(String extra:listOf("一级注册建筑师注册证书\n姓名：李明\n注册编号：识别不清",certificate().replace("20991101234","20991109999"))){EvidenceCorpus c=fixture(id(),"李明",extra,true,true);assertTrue(PersonnelAttachmentIdentity.proof(role(c),certificate(c),c).isEmpty());}
    }
}
