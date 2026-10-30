package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Source-owned routing: optional documents and external truth are not failed text parsers. */
final class RequirementBusinessRouting {
    static final String VERSION="requirement-business-routing-1";
    private static final String OPTIONAL="OPTIONAL_BIDDER_SUPPLEMENT";
    private static final String EXTERNAL="SOURCE_EXTERNAL_FACT";
    private static final String PENALTY="SOURCE_EXTERNAL_PENALTY";
    private static final String COST="SOURCE_COST_APPLICABILITY";
    private RequirementBusinessRouting() {}

    static List<Rule> compile(Checkpoint cp,String source,RequirementSourceContext.Context sourceContext,Map<String,Object> context){
        if(cp==null||source==null||source.isBlank())return List.of();
        if(!cp.required()&&optional(source))return List.of(rule(source,"INPUT","optionalSupplement","",OPTIONAL,
                linked("required",false,"sourceOptional",true)));
        if(source.contains("成本核算")&&source.contains("前附表")&&source.contains("无需")&&source.contains("或成本核算通过"))return List.of(rule(source,"EXTERNAL","costCalculationPassed","true",COST,
                linked("externalType","BID_COST_CALCULATION","requiredProvider","EVALUATION_RECORDS","evaluationStage","BID_EVALUATION",
                        "requiredScopeFingerprint",identity(source),"requiredConditionFields",List.of("costCalculationPassed"),"bidderDocumentRequired",false,"declarationDoesNotProveExternalFact",true)));
        if(source.contains("限制型供应商名单")&&source.contains("最高扣")&&source.contains("无需提供证明材料")){
            java.util.regex.Matcher group=java.util.regex.Pattern.compile("集团限制型供应商名单[^。；;]{0,20}扣除([0-9]+)分").matcher(source);
            java.util.regex.Matcher province=java.util.regex.Pattern.compile("(?:海南|省|分公司)[^。；;]{0,15}限制型供应商名单[^。；;]{0,20}扣除([0-9]+)分").matcher(source);
            java.util.regex.Matcher cap=java.util.regex.Pattern.compile("最高扣([0-9]+)分").matcher(source);
            if(group.find()&&province.find()&&cap.find())return List.of(rule(source,"EXTERNAL","restrictedSupplierPenalty","",PENALTY,linked(
                    "externalType","SUPPLIER_RESTRICTION_SCORE","requiredProvider","PROCURER_BLACKLIST","evaluationStage","BID_EVALUATION",
                    "requiredScopeFingerprint",identity(source),"requiredConditionFields",List.of("groupRestricted","provinceRestricted"),
                    "groupPenalty",Integer.parseInt(group.group(1)),"provincePenalty",Integer.parseInt(province.group(1)),"penaltyCap",Integer.parseInt(cap.group(1)),
                    "bidderDocumentRequired",false,"declarationDoesNotProveExternalFact",true)));
        }
        List<String> fields=new ArrayList<>();String type="",provider="";String expected="false";
        if(source.contains("经营异常名录")&&source.contains("严重违法失信")){
            type="ENTERPRISE_REPUTATION";provider="www.gsxt.gov.cn";
            fields.addAll(List.of("businessAbnormalProhibition","seriousIllegalProhibition"));
            // These separate obligations remain in the full row; registry status alone cannot prove them.
            if(source.contains("责令停业"))fields.add("businessSuspended");
            if(source.contains("资格被取消"))fields.add("bidQualificationCancelled");
            if(source.contains("严重违约"))fields.add("seriousBreachInRequiredWindow");
            if(source.contains("重大工程质量"))fields.add("majorQualityIssueInRequiredWindow");
            if(source.contains("安全问题"))fields.add("majorSafetyIssueInRequiredWindow");
        }else if(source.contains("信用中国")&&source.contains("失信被执行人")){
            type="CREDIT_CHINA";provider="www.creditchina.gov.cn";fields.add("judgmentDefaulter");
            if(source.contains("重大税收违法"))fields.add("majorTaxViolation");
        }else if(source.contains("不同投标人")&&source.contains("MAC地址")){
            type="BID_UPLOAD_MAC";provider="PROCUREMENT_PLATFORM";fields.add("crossBidderDuplicateMac");
        }else if(source.contains("单位负责人为同一人")&&source.contains("控股")&&source.contains("不同单位")){
            type="BIDDER_RELATIONSHIPS";provider="PROCUREMENT_PARTICIPANT_RECORDS";
            fields.addAll(List.of("sameResponsiblePersonInSameLot","controlOrManagementRelationshipInSameLot"));
        }else if(source.contains("利害关系")&&source.contains("公正")&&source.contains("不得")){
            type="PROCUREMENT_CONFLICT_OF_INTEREST";provider="PROCURER_RECORDS";fields.add("prohibitedConflictOfInterest");
        }else if(source.contains("前期咨询")&&source.contains("监督")&&source.contains("不得参与")){
            type="PRIOR_PROJECT_SERVICE_RELATION";provider="PROCURER_RECORDS";
            fields.addAll(List.of("prohibitedPriorConsultancyAfterExceptions","prohibitedSupervisionRelationship"));
        }else if(source.contains("禁入型供应商黑名单")&&source.contains("禁入期")){
            type="SUPPLIER_PROHIBITION";provider="PROCURER_BLACKLIST";
            fields.addAll(List.of("groupSupplierProhibition","provinceSupplierProhibition"));
            if(source.contains("其他法人或组织"))fields.add("relatedEntityProhibition");
            if(source.contains("个人"))fields.add("requiredPeopleProhibition");
        }else if(closedElectronicSource(source)){
            type="ELECTRONIC_BID_SUBMISSION";provider="PROCUREMENT_PLATFORM";fields.add("electronicSubmissionReceived");expected="true";
        }else if(clean(source).matches("^(?:响应评审)?标的物满足招标文件要求[，,]对招标文件实质性响应[。]?[（(]现场依据投标情况进行判定[）)][。]?$")){
            type="SUBSTANTIVE_RESPONSE_EVALUATION";provider="EVALUATION_RECORDS";
            fields.addAll(List.of("subjectMatterMeetsTender","substantiveResponseAccepted"));expected="true";
        }
        if(fields.isEmpty())return List.of();
        String contextual=source;
        if(sourceContext!=null)contextual+="\n"+sourceContext.sources().stream().filter(s->"OBLIGATION".equals(s.get("kind")))
                .map(s->str(s,"sourceQuote")).reduce("",(a,b)->a+"\n"+b);
        String stage=contextual.matches("(?s).*(?:投标截止时间后评标结束前|现场查询|现场招标人出具|现场依据投标情况进行判定).*" )?"BID_EVALUATION":"";
        List<Rule> rules=new ArrayList<>();
        for(String field:fields)rules.add(rule(source,"EXTERNAL",field,expected,EXTERNAL,linked(
                "externalType",type,"requiredProvider",provider,"evaluationStage",stage,
                "requiredScopeFingerprint",identity(source),"requiredConditionFields",List.copyOf(fields),
                "externalTruthRequired",true,"bidderDocumentRequired",false,"declarationDoesNotProveExternalFact",true)));
        return List.copyOf(rules);
    }

    static boolean applies(Rule rule){return VERSION.equals(rule.parameters().get("routingVersion"));}
    /** Only the closed definition-plus-submission grammar has a single business leaf. */
    static boolean compilationComplete(Rule rule,String source){
        return applies(rule)&&"EXTERNAL".equals(rule.predicate())&&EXTERNAL.equals(str(rule.parameters(),"routingFamily"))
                &&"ELECTRONIC_BID_SUBMISSION".equals(str(rule.parameters(),"externalType"))
                &&"electronicSubmissionReceived".equals(rule.field())&&"true".equals(rule.expected())
                &&List.of("electronicSubmissionReceived").equals(ProofVerifier.strings(rule.parameters().get("requiredConditionFields")))
                &&identity(source).equals(str(rule.parameters(),"requiredScopeFingerprint"))
                &&clean(rule.sourceQuote()).equals(clean(source))&&closedElectronicSource(source);
    }
    private static boolean closedElectronicSource(String source){
        String text=source.replaceAll("[\\s/]+","").replace('（','(').replace('）',')');
        java.util.regex.Matcher closed=java.util.regex.Pattern.compile(
                "^(?:[0-9]+(?:[.．][0-9]+)*投标文件应答和编写本条款增加以下规定[：:])?"
                +"1[.．]文件定义[：:]\\(1\\)证明文件[：:]指投标人按招标(?:招标)?文件要求递交的第三方出具的文件扫描件或影印件[，,]如([^；;]+)[；;]"
                +"\\(2\\)其他文件[：:]指([^。]+)。2[.．]本项目采用电子投标[，,]不接收纸质投标文件。?$").matcher(text);
        if(!closed.matches())return false;
        return !(closed.group(1)+closed.group(2)).matches(".*(?:必须|应当|不得|须|应提供|应提交|另外|本项目).*");
    }
    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        String family=str(rule.parameters(),"routingFamily");
        if(OPTIONAL.equals(family)){
            if(!optional(rule.sourceQuote())||!Boolean.FALSE.equals(rule.parameters().get("required")))
                return evaluator.unknown(rule,"REQUIREMENT_OPTIONAL_SOURCE_INVALID","自选资料未取得原文豁免依据",List.of());
            return new Decision(rule.id(),rule.description(),"NOT_APPLICABLE","原文由投标人自行决定是否补充，本项没有必交材料义务",List.of(),List.of(),
                    linked("executor",VERSION,"proofScope","SOURCE_EXPLICIT_OPTIONAL","sourceQuote",rule.sourceQuote(),"absenceIsNotDefect",true));
        }
        if(!Set.of(EXTERNAL,PENALTY,COST).contains(family))return evaluator.unknown(rule,"REQUIREMENT_ROUTING_INVALID","未知要求路由",List.of());
        List<Map<String,Object>> costConditions=COST.equals(family)?costConditions(evaluator.context):List.of();
        if(COST.equals(family)&&costConditions.size()==1&&Boolean.TRUE.equals(costConditions.get(0).get("exempt")))
            return new Decision(rule.id(),rule.description(),"NOT_APPLICABLE","同一招标文件前附表明确无需成本核算",List.of(),List.of(),linked("executor",VERSION,"proofScope","SOURCE_EXPLICIT_COST_EXEMPTION","source",costConditions.get(0)));
        String requiredStage=str(rule.parameters(),"evaluationStage");
        if(!requiredStage.isBlank()&&!requiredStage.equals(str(evaluator.context,"evaluationStage")))
            return evaluator.unknown(rule,"WAIT_STAGE","原文要求在投标截止后评标阶段取得外部结果，当前文件预检不代替该结果",List.of(),
                    linked("executor",VERSION,"requiredStage",requiredStage,"declarationDoesNotProveExternalFact",true));
        // A connector's VERIFIED marker is not enough: require the same full business scope,
        // provider and frozen requirement. An uploaded promise or screenshot never enters here.
        List<Map<String,Object>> records=RuleEvaluator.maps(evaluator.context.get("externalRecords"));
        List<Map<String,Object>> accepted=records.stream().filter(row->str(row,"type").equals(str(rule.parameters(),"externalType")))
                .filter(row->!failedRecord(row))
                .filter(row->str(row,"requirementScopeFingerprint").equals(str(rule.parameters(),"requiredScopeFingerprint")))
                .filter(row->providerMatches(str(rule.parameters(),"requiredProvider"),row))
                .filter(row->new HashSet<>(ProofVerifier.strings(row.get("coveredConditionFields"))).containsAll(ProofVerifier.strings(rule.parameters().get("requiredConditionFields"))))
                .toList();
        if(accepted.isEmpty()){
            boolean failed=records.stream().anyMatch(row->str(row,"type").equals(str(rule.parameters(),"externalType"))
                    &&failedRecord(row));
            return evaluator.unknown(rule,failed?"EXTERNAL_CONNECTOR_FAILED":"EXTERNAL_RECORD_REQUIRED",
                    failed?"指定外部查询失败，需修复或重试查询；不判定投标材料不合格":"需要指定来源、同主体与同范围的外部记录；承诺文件和历史截图只能证明书面声明，不能证明实时状态",List.of(),
                    linked("executor",VERSION,"externalType",rule.parameters().get("externalType"),"requiredProvider",rule.parameters().get("requiredProvider"),
                            "requirementScopeFingerprint",rule.parameters().get("requiredScopeFingerprint"),"requiredConditionFields",rule.parameters().get("requiredConditionFields"),
                            "bidderDocumentRequired",false,"processingFailed",failed,"declarationDoesNotProveExternalFact",true));
        }
        Map<String,Object> context=new LinkedHashMap<>(evaluator.context);context.put("externalRecords",accepted);
        if(PENALTY.equals(family))return penalty(rule,evaluator,context);
        Rule concrete=bidderRule(rule);
        RuleEvaluator externalReader=bidderReader(evaluator,context,rule);
        Decision decision=externalReader.external(concrete);
        if(COST.equals(family)&&"FAIL".equals(decision.status())&&(costConditions.size()!=1||!Boolean.FALSE.equals(costConditions.get(0).get("exempt"))))
            return evaluator.unknown(rule,"COST_APPLICABILITY_REQUIRED","成本核算结果不通过，但前附表免核条件尚未明确，不能跳过原文允许的免核分支判不符合",List.of(),linked("externalResult",decision.map(),"costConditions",costConditions));
        return decision;
    }

    static List<Map<String,Object>> requests(Rule rule,RuleEvaluator evaluator){
        if(!applies(rule)||!"EXTERNAL".equals(rule.predicate()))return List.of();
        Rule concrete=bidderRule(rule);RuleEvaluator reader=bidderReader(evaluator,evaluator.context,rule);
        return reader.resolvedSubjects(concrete).stream().map(subject->linked("obligationId",rule.id(),"type",str(rule.parameters(),"externalType"),"subject",subject,
                "evaluationStage",str(rule.parameters(),"evaluationStage").isBlank()?evaluator.context.getOrDefault("evaluationStage",""):rule.parameters().get("evaluationStage"),
                "referenceDate",evaluator.context.getOrDefault("referenceDate",""),"field",rule.field(),"operator",rule.operator(),"expected",rule.expected(),
                "requiredProvider",rule.parameters().get("requiredProvider"),"requirementScopeFingerprint",rule.parameters().get("requiredScopeFingerprint"),
                "requiredConditionFields",rule.parameters().get("requiredConditionFields"))).toList();
    }
    private static Rule bidderRule(Rule rule){return new Rule(rule.id(),rule.description(),rule.predicate(),rule.subject().isBlank()?"投标人":rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),rule.parameters(),rule.sourceQuote(),rule.logic(),rule.children());}
    private static RuleEvaluator bidderReader(RuleEvaluator evaluator,Map<String,Object> context,Rule rule){
        List<Fact> facts=new ArrayList<>(evaluator.facts);facts.addAll(EnterpriseSubjectBindings.extract(List.of(bidderRule(rule)),evaluator.corpus.all));
        return new RuleEvaluator(facts,evaluator.corpus,context);
    }
    private static List<Map<String,Object>> costConditions(Map<String,Object> context){
        List<Map<String,Object>> found=new ArrayList<>();
        if(context.get("tenderDocuments") instanceof Collection<?> documents)for(Object raw:documents)if(raw instanceof Document d&&"TENDER".equals(d.role())){
            boolean front=false;for(Page page:d.pages()){
                String text=page.text().strip();if(clean(text).equals("投标人须知前附表")){front=true;continue;}
                if(front&&text.matches("^(?:第[一二三四五六七八九十0-9]+章|[0-9]+[.][0-9]+\\s+总则).*$"))front=false;
                if(!front)continue;
                java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("^(?:[0-9]+(?:[.][0-9]+)*[\\t ]+)?成本核算[\\t ：:]+(无需进行|不需要进行|不进行|不适用|需要进行|进行)[成本核算。\\s]*$").matcher(text);
                if(matcher.matches())found.add(linked("documentId",d.id(),"documentHash",d.sha256(),"documentVersion",d.version(),"locationType",page.locationType(),"location",page.number(),"sourceQuote",text,"exempt",!Set.of("需要进行","进行").contains(matcher.group(1))));
            }
        }return found;
    }

    private static Decision penalty(Rule rule,RuleEvaluator evaluator,Map<String,Object> context){
        RuleEvaluator reader=bidderReader(evaluator,context,rule);Map<String,Boolean> memberships=new LinkedHashMap<>();List<Map<String,Object>> receipts=new ArrayList<>();
        if(reader.resolvedSubjects(bidderRule(rule)).size()!=1)return evaluator.unknown(rule,"EXTERNAL_SCORE_SUBJECT_SCOPE_REQUIRED","该扣分要求尚未明确多个联合体成员的计分归并口径",List.of());
        for(String field:List.of("groupRestricted","provinceRestricted")){
            Rule value=new Rule(rule.id()+":"+field,rule.description(),"EXTERNAL","投标人",field,"=","true","",rule.parameters(),rule.sourceQuote(),"",List.of());
            Decision read=reader.external(value);if(!Set.of("PASS","FAIL").contains(read.status()))return read;
            memberships.put(field,"PASS".equals(read.status()));receipts.add(read.map());
        }
        int group=((Number)rule.parameters().get("groupPenalty")).intValue(),province=((Number)rule.parameters().get("provincePenalty")).intValue(),cap=((Number)rule.parameters().get("penaltyCap")).intValue();
        int penalty=Math.min(cap,(memberships.get("groupRestricted")?group:0)+(memberships.get("provinceRestricted")?province:0));
        return new Decision(rule.id(),rule.description(),"PASS","已按指定名单的真实记录计算扣分并应用上限；原文无需投标人提供证明材料",List.of(),List.of(),
                linked("executor",VERSION,"proofScope","EXTERNAL_OBJECTIVE_SCORE_CALCULATED","scoreDeduction",penalty,"penaltyCap",cap,"memberships",memberships,"externalReceipts",receipts,"bidderDocumentRequired",false));
    }

    private static boolean providerMatches(String provider,Map<String,Object> record){
        if(provider.contains(".")){
            try{String value=str(record,"source");String host=java.net.URI.create(value.contains("://")?value:"https://"+value).getHost();String expected=provider.replaceFirst("^www[.]","");
                return host!=null&&(host.equals(expected)||host.endsWith("."+expected));}catch(RuntimeException ignored){return false;}
        }
        return provider.equals(str(record,"providerKind"));
    }
    private static boolean failedRecord(Map<String,Object> record){
        Set<String> failed=Set.of("FAILED","ERROR","TIMEOUT");if(failed.contains(str(record,"status")))return true;
        return record.get("result") instanceof Map<?,?> result&&failed.contains(Objects.toString(result.get("status"),""));
    }
    private static boolean optional(String source){return clean(source).matches("^(?:其他)?投标人(?:认为)?有必要(?:提供|提交)?的(?:其他)?(?:资料|材料)[。；;]?$|^其他投标人认为有必要的资料[。；;]?$");}
    private static Rule rule(String source,String predicate,String field,String expected,String family,Map<String,Object> extra){
        Map<String,Object> parameters=new LinkedHashMap<>(extra);parameters.put("routingVersion",VERSION);parameters.put("routingFamily",family);
        return new Rule("ROUTE-"+identity(family+"|"+field+"|"+source).substring(0,18),source,predicate,"",field,"=",expected,"",parameters,source,"",List.of());
    }
}
