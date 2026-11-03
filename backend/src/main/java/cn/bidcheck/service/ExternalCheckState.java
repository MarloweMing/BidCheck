package cn.bidcheck.service;

import java.util.*;
import static cn.bidcheck.service.CheckResolution.*;
import static cn.bidcheck.service.BidService.text;

/** Execution responsibility is separate from the truth returned by an external source. */
public final class ExternalCheckState {
    private ExternalCheckState() {}
    public static boolean systemFailure(String code) {
        return cn.bidcheck.compat.Java8Api.setOf("EXTERNAL_CONNECTOR_NOT_CONFIGURED", "EXTERNAL_CONFIGURATION_INVALID", "EXTERNAL_REQUEST_INVALID", "EXTERNAL_QUERY_SCOPE_INCOMPLETE", "EXTERNAL_ENGINE_FAILURE", "EXTERNAL_CONNECTOR_FAILED", "EXTERNAL_QUERY_NOT_EXECUTED", "EXTERNAL_BUDGET_EXHAUSTED", "EXTERNAL_SINGLE_FLIGHT_INTERRUPTED").contains(code);
    }
    public static Map<String,Object> describe(Map<String,Object> assessment,Map<String,Object> job) {
        Map<String,Object> a=assessment==null?cn.bidcheck.compat.Java8Api.mapOf():assessment,atomic=map(a.get("atomicCheck"));
        List<Map<String,Object>> events=new ArrayList<>(maps(a.get("externalQueryDiagnostics")));
        if(events.isEmpty())events.addAll(maps(job==null?null:job.get("diagnostics")));
        List<Map<String,Object>> requests=maps(atomic.get("externalRequests"));
        Set<String> codes=new HashSet<>();events.forEach(e->codes.add(text(e,"code")));
        maps(atomic.get("gaps")).forEach(e->codes.add(text(e,"code")));
        String stage=requests.stream().map(r->text(r,"evaluationStage")).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).distinct().reduce((x,y)->x.equals(y)?x:"MULTIPLE").orElse("");
        if(cn.bidcheck.compat.Java8Api.isBlank(stage))stage=maps(atomic.get("conditions")).stream().map(c->text(map(c.get("proof")),"requiredStage")).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).findFirst().orElse("");
        String state="",label="",next="",owner="SYSTEM";
        if(codes.contains("EXTERNAL_CONFIGURATION_INVALID")){state="CONFIGURATION_INVALID";label="外部连接配置有误";next="系统需修复指定来源的连接配置后重试；不要求补交投标材料。";}
        else if(codes.contains("EXTERNAL_CONNECTOR_NOT_CONFIGURED")){state="NOT_CONFIGURED";label="外部来源未接入";next="系统需接入条款指定的数据来源，再按原主体、阶段和范围查询；不要求补交投标材料。";}
        else if(codes.contains("EXTERNAL_QUERY_SCOPE_INCOMPLETE")||codes.contains("EXTERNAL_REQUEST_INVALID")){state="SCOPE_INCOMPLETE";label="外部查询范围未解析完整";next="系统需补齐查询主体、来源、阶段或日期，再执行查询；当前不能据此认定缺材料。";}
        else if(codes.stream().anyMatch(ExternalCheckState::systemFailure)){state="FAILED";label="外部查询未完成";next="系统需恢复指定外部查询；服务失败不表示来源无记录，也不表示投标不符合。";}
        else if(codes.contains("EXTERNAL_WAIT_STAGE")||codes.contains("WAIT_STAGE")||"WAIT_STAGE".equals(text(atomic,"workflowState"))){state="WAIT_STAGE";label="待"+stageLabel(stage)+"核验";owner="EXTERNAL";next="到"+stageLabel(stage)+"后，由招标人或评审方取得条款指定记录，再重新计算本项；当前不要求投标人补交材料。";}
        else if(codes.contains("EXTERNAL_OWNER_DATA_REQUIRED")){state="WAIT_OWNER_DATA";label="待业主或平台数据";owner="EXTERNAL";next="需由招标人或平台提供指定范围的可信记录，取得后重算；投标人承诺不能替代该记录。";}
        else if(codes.contains("EXTERNAL_RECORD_NOT_FOUND")){state="NOT_FOUND";label="指定来源未查到记录";owner="EXTERNAL";next="已完成指定来源查询，覆盖范围内未命中；需按本项规则处理该结果，不能直接判通过或材料缺失。";}
        else if(codes.contains("EXTERNAL_RECORD_INCONCLUSIVE")){state="INCONCLUSIVE";label="外部记录不足以判断";owner="EXTERNAL";next="来源已响应，但记录覆盖或内容不足；需补齐指定来源的数据后重算。";}
        else if(codes.contains("EXTERNAL_RECORD_RECEIVED")){state="RECEIVED_UNRESOLVED";label="外部记录已取得，条件待核全";next="系统需关联已取得的来源记录并核全剩余条件；取得记录本身不代表符合要求。";}
        else if(!requests.isEmpty()){state="NOT_EXECUTED";label="外部查询尚未完成";next="系统需执行指定来源查询并保存结果；已生成请求不代表查询已经完成。";}
        if(cn.bidcheck.compat.Java8Api.isBlank(state))return cn.bidcheck.compat.Java8Api.mapOf();
        Map<String,Object> result=new LinkedHashMap<>();result.put("state",state);result.put("label",label);result.put("owner",owner);result.put("nextStep",next);
        result.put("requiredStage",stage);result.put("stageLabel",stageLabel(stage));result.put("systemOwned","SYSTEM".equals(owner));result.put("userActionRequired",false);
        result.put("requiredProviders",cn.bidcheck.compat.Java8Api.toList(requests.stream().map(r->text(r,"requiredProvider")).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).distinct()));
        result.put("dataOwners",cn.bidcheck.compat.Java8Api.toList(requests.stream().map(r->providerOwner(text(r,"requiredProvider"))).distinct()));
        result.put("queryExecuted",events.stream().anyMatch(e->Boolean.TRUE.equals(e.get("queryExecuted"))));result.put("businessComplete",false);
        result.put("diagnostics",events);return result;
    }
    public static String stageLabel(String value){switch (value) {case "BID_EVALUATION":case "EVALUATION":return "评标阶段";case "BID_OPENING":case "OPENING":return "开标阶段";case "POST_SUBMISSION":return "投标提交后阶段";case "BID_SUBMISSION":case "PRE_SUBMISSION":return "投标提交阶段";case "CONTRACT_PERFORMANCE":return "履约阶段";default:return "条款指定阶段";}}
    private static String providerOwner(String provider){switch (provider) {case "PROCURER_RECORDS":case "PROCURER_BLACKLIST":case "PROCUREMENT_PARTICIPANT_RECORDS":return "招标人";case "PROCUREMENT_PLATFORM":return "采购平台";case "EVALUATION_RECORDS":return "评审方";default:return "指定外部来源";}}
}
