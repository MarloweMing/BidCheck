package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.document.FileParser;
import cn.bidcheck.engine.*;
import cn.bidcheck.persistence.Store;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DemoService {
    private final BidService service;
    public DemoService(BidService service) { this.service=service; }
    public synchronized Map<String,Object> create() throws IOException {
        Map<String,Object> project=service.createProject(BidService.fields("name","智慧园区车辆服务采购 · 示例","code","BC-DEMO-2026"));
        String pid=BidService.text(project,"id");
        String tender="招标文件（演示用虚构资料）\n项目名称：智慧园区车辆服务采购项目\n项目编号：BC-DEMO-2026\n开标时间：2026年11月15日\n最高限价：250万元\n投标有效期不得少于90天。\n服务工期不得超过60天。\n质保期不少于24个月。\n必须提供营业执照。\n必须提供2025年财务审计报告。\n车辆信息表与机动车行驶证中的车牌号码必须一致。\n投标文件须签字并加盖公章。\n技术方案应说明应急保障措施。\n投标文件格式\n一、投标函\n二、车辆信息表\n三、法定代表人授权委托书";
        List<String> pages=new ArrayList<>();
        pages.add("投标文件（演示用虚构资料）\n项目名称：智慧园区车辆服务采购项目\n项目编号：BC-DEMO-2026\n投标人名称：示例园区服务有限公司\n统一社会信用代码：DEMO-NOT-A-REAL-ID\n投标总价：268万元\n投标有效期：60天\n服务工期：55天\n质保期：24个月\n投标函\n我方按所报价格提供车辆服务。\n附件：营业执照\n登记机关：示例市场监督管理局（演示）\n注册资本：500万元\n注册地址：演示地址\n成立日期：2020年01月01日");
        pages.add("车辆信息表\n车牌号码：渝A12345\n车辆识别代号：LSVA12345678901234\n发动机号：DEMOENGINE001\n所有人：示例园区服务有限公司\n车辆类型：小型普通客车");
        for(int i=3;i<=17;i++) pages.add("第"+i+"页 服务方案（演示）\n本页为示例服务安排说明，不包含车辆身份字段。\n技术方案：设置日常服务和值班流程。");
        pages.add("机动车行驶证\n号牌号码：渝A54321\n车辆识别代号：LSVA12345678901234\n发动机号：DEMOENGINE001\n所有人：示例园区服务有限公司\n车辆类型：小型普通客车");
        Document td=add(pid,"TENDER","示例招标文件.txt",tender);
        Document bd=add(pid,"BID","示例投标文件-含跨页冲突.txt",String.join("\f",pages));
        project.put("activeTenderId",td.id()); service.store.save("project",pid,pid,"",project);
        List<Checkpoint> checks=new CheckpointExtractor().extract(td);
        for(Checkpoint c:checks) {
            Checkpoint confirmed=new Checkpoint(c.id(),c.title(),c.category(),c.severity(),c.type(),c.field(),c.operator(),c.expected(),c.unit(),c.required(),true,c.enabled(),c.source());
            service.store.save("checkpoint",c.id(),pid,td.id(),confirmed);
        }
        service.startRun(pid,bd.id()); return project;
    }
    private Document add(String pid,String role,String name,String text) throws IOException {
        String id=Store.id(); Files.writeString(service.stagePath(id),text,StandardCharsets.UTF_8);
        service.commitFile(id);
        ParsedDocument parsed=new FileParser().parse(service.committedPath(id,BidService.hash(text)),name);
        Document d=new Document(id,pid,role,name,1,BidService.hash(text),BidService.now(),parsed.pages(),parsed.warnings());
        service.store.save("document",id,pid,"",d);
        List<Fact> facts=new FactExtractor().extract(d);
        for(int i=0;i<facts.size();i++) service.store.save("fact",BidService.stableId(id+":fact:"+i),pid,id,facts.get(i));
        return d;
    }
}
