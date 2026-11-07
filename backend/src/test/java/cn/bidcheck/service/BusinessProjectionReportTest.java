package cn.bidcheck.service;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.persistence.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Export only, in-memory collaborators; no database, application server or provider calls. */
class BusinessProjectionReportTest {
    @Test void originalAdviceColumnUsesRawModelRecordAcrossStorageFormsAndKeepsPublishedNextStep() throws Exception {
        Evaluation model=new Evaluation("UNDETERMINED","模型原始原因","模型原始建议",Collections.emptyList(),0.5,"MODEL");
        Evaluation beforePublication=new Evaluation("UNDETERMINED","原始判断","来源保护后建议",Collections.emptyList(),0.5,"RULE");
        Map<String,Object> persisted=new ObjectMapper().convertValue(model,Map.class);
        Map<String,Object> explicitBlank=Collections.singletonMap("suggestion","");
        for(Object stored:Arrays.asList(model,persisted,explicitBlank,null)){
            Map<String,Object> trace=new LinkedHashMap<>();
            trace.put("coverage",Collections.singletonMap("semanticGap",BidService.fields("validated",true,"kind","EXTERNAL_INPUT")));
            trace.put("prePresentationEvaluation",beforePublication);
            if(stored!=null)trace.put("rawEvaluation",stored);
            // Older records may lack rawEvaluation. Absence of original advice is different
            // from an original record whose suggestion was explicitly empty.
            Finding input=finding("UNDETERMINED",trace);
            List<List<String>> table=rows(new String(reports(input).generate("r","csv").bytes(),StandardCharsets.UTF_8));
            List<String> columns=table.get(0),row=table.get(1);
            String expected=stored==null?"来源保护后建议":stored==explicitBlank?"":"模型原始建议";
            assertEquals(expected,row.get(columns.indexOf("原始模型建议（未作为用户待办）")));
            assertEquals(row.get(columns.indexOf("下一步")),row.get(columns.indexOf("修改建议")));
            assertTrue(row.get(columns.indexOf("修改建议")).contains("外部记录"));
        }
    }
    @Test void csvAndHtmlUseSharedResponsibilityWhilePreservingOriginalAdvice() throws Exception {
        for(java.util.Map<java.lang.String, java.lang.Object> item:cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.<String, Object>mapOf("resolutionJob", cn.bidcheck.compat.Java8Api.mapOf("id", "j", "status", "BLOCKED_NO_PROGRESS")), cn.bidcheck.compat.Java8Api.<String, Object>mapOf("guards", cn.bidcheck.compat.Java8Api.listOf(cn.bidcheck.compat.Java8Api.mapOf("code", "MODEL_TIMEOUT"))), cn.bidcheck.compat.Java8Api.<String, Object>mapOf("coverage", cn.bidcheck.compat.Java8Api.mapOf("semanticGap", cn.bidcheck.compat.Java8Api.mapOf("validated", true, "kind", "EXTERNAL_INPUT"))))) {
            cn.bidcheck.domain.Domain.Finding finding=finding("UNDETERMINED",item);String before=new ObjectMapper().writeValueAsString(finding);cn.bidcheck.service.ReportService reports=reports(finding);
            java.util.List<java.util.List<java.lang.String>> rows=rows(new String(reports.generate("r","csv").bytes(),StandardCharsets.UTF_8));java.util.List<java.lang.String> header=rows.get(0);java.util.List<java.lang.String> row=rows.get(1);
            assertEquals(header.size(),row.size());assertEquals(row.get(header.indexOf("下一步")),row.get(header.indexOf("修改建议")));
            assertEquals("请补交现有文件中的材料。",row.get(header.indexOf("原始模型建议（未作为用户待办）")));
            if(item.containsKey("resolutionJob")){assertTrue(row.get(header.indexOf("修改建议")).contains("已停止"));assertEquals("否",row.get(header.indexOf("是否需要用户动作")));}
            java.lang.String html=new String(reports.generate("r","html").bytes(),StandardCharsets.UTF_8);assertTrue(html.contains(row.get(header.indexOf("下一步"))));
            assertEquals(before,new ObjectMapper().writeValueAsString(finding));
        }
    }
    @Test void declarationPassExportsTheNarrowWrittenScope() throws Exception {
        cn.bidcheck.domain.Domain.Finding finding=finding("PASS",cn.bidcheck.compat.Java8Api.mapOf("coverage", cn.bidcheck.compat.Java8Api.mapOf("semanticProof", cn.bidcheck.compat.Java8Api.mapOf("validated", true, "scope", "WRITTEN_RESPONSE", "evidenceIds", cn.bidcheck.compat.Java8Api.listOf("C1")))));
        cn.bidcheck.service.ReportService reports=reports(finding);java.util.List<java.util.List<java.lang.String>> rows=rows(new String(reports.generate("r","csv").bytes(),StandardCharsets.UTF_8));
        assertEquals("书面要求已满足",rows.get(1).get(rows.get(0).indexOf("确定业务结论")));
        assertTrue(new String(reports.generate("r","html").bytes(),StandardCharsets.UTF_8).contains("业务结论（自动）：书面要求已满足"));
    }
    private Finding finding(String status,Map<String,Object> assessment){
        return new Finding("f","r",new Checkpoint("c","书面材料要求","资格检查","HIGH","SEMANTIC_RESPONSE","material","","须提供承诺书","",true,true,true,null),
            new Evaluation(status,"正文尚待关联。","请补交现有文件中的材料。",cn.bidcheck.compat.Java8Api.listOf(new Evidence("bid","投标文件",1,"PAGE","我方承诺遵守规定。","p1",1)),0,"TEST"),assessment);
    }
    private ReportService reports(Finding finding) throws Exception {
        cn.bidcheck.service.BidService service=mock(BidService.class);cn.bidcheck.persistence.Store store=mock(Store.class);java.lang.reflect.Field field=BidService.class.getField("store");field.setAccessible(true);field.set(service,store);
        com.fasterxml.jackson.databind.ObjectMapper json=new ObjectMapper();when(store.encode(any())).thenAnswer(call->json.writeValueAsString(call.getArgument(0)));
        when(store.getMap(eq("project"),anyString())).thenAnswer(call->new LinkedHashMap<>(cn.bidcheck.compat.Java8Api.mapOf("id", "p", "name", "合成项目")));
        cn.bidcheck.domain.Domain.Document doc=new Document("bid","p","BID","投标文件",1,"hash","now",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf());when(service.document("bid")).thenReturn(doc);
        when(service.run("r",false)).thenReturn(cn.bidcheck.compat.Java8Api.mapOf("run", cn.bidcheck.compat.Java8Api.mapOf("id", "r", "projectId", "p", "documentId", "bid", "status", "COMPLETED"), "results", cn.bidcheck.compat.Java8Api.listOf(finding), "reviews", cn.bidcheck.compat.Java8Api.listOf(), "documents", cn.bidcheck.compat.Java8Api.listOf()));
        return new ReportService(service);
    }
    private List<List<String>> rows(String csv){
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;
        for(int i=csv.charAt(0)=='\uFEFF'?1:0;i<csv.length();i++){char c=csv.charAt(i);
            if(c=='"'){if(quoted&&i+1<csv.length()&&csv.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}
            else if(c==','&&!quoted){row.add(cell.toString());cell.setLength(0);}
            else if(c=='\n'&&!quoted){row.add(cell.toString());cell.setLength(0);rows.add(row);row=new ArrayList<>();}
            else if(c!='\r'||quoted)cell.append(c);
        }return rows;
    }
}
