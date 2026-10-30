package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.engine.ScoringSourceSegments;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.*;

/** Closed scoring formulas from exact tender spans. Finding a document is not a score. */
final class SourceScoringRules {
    static final String VERSION="source-scoring-rules-4-contract-membership-and-lot-boundaries";
    private static final Pattern PLAN=Pattern.compile("^(设计方案|施工组织设计)[^\\t]*\\t\\1完整性[:：](.+?)([0-9]+)项内容[，,]?(?:上述)?内容齐全得([0-9.]+)分[，,]每缺(?:少)?(?:一项|1项)(?:内容)?扣([0-9.]+)分[，,]扣完为止[。]?$",Pattern.DOTALL);
    private static final Pattern TITLE=Pattern.compile("(?:具有|具备)[（(]([^）)]+)[）)]高级工程师职称得([0-9.]+)分[，,]中级得([0-9.]+)分[，,](?:未提交|未提供)不得分");
    private static final Pattern URL=Pattern.compile("(?i)(?:https?://|www\\.)[A-Za-z0-9.-]+(?:/[^\\s<>]*)?");
    private SourceScoringRules(){}

    static List<Rule> compile(Checkpoint cp,String source,RequirementSourceContext.Context context,Map<String,Object> runContext){
        if(!"scoring".equals(cp.field())||context==null||context.identity().isEmpty()||cp.source()==null)return List.of();
        String original=cp.source().quote();
        // A complete selected scoring cell may stop before its separate numeric total
        // column. Keep the selected quote verbatim; only this lossless suffix removal
        // is allowed, never dropping a proof sentence or accepting rewritten prose.
        if(!RequirementReferences.sourceBound(original,source,context)
                && original.startsWith(source.strip())
                && clean(ScoringSourceSegments.body(original)).equals(clean(ScoringSourceSegments.body(source))))original=source.strip();
        if(!RequirementReferences.sourceBound(original,source,context))return List.of();
        StringBuilder closed=new StringBuilder(original);for(Map<String,Object> part:context.sources())if("SCOPED_SCORING_CONTINUATION".equals(part.get("kind")))closed.append('\n').append(str(part,"sourceQuote"));
        if(!source.strip().equals(closed.toString().strip()))return List.of();
        Matcher plan=PLAN.matcher(ScoringSourceSegments.body(original));
        if(plan.find()){
            String material=original.contains("施工组织设计完整性")?"施工组织设计":original.contains("设计方案完整性")?"设计方案":"";
            if(material.isBlank())return List.of();
            String itemText=plan.group(2).replaceFirst("^对本项目的","");
            List<String> items=Arrays.stream(itemText.split("、")).map(String::strip).toList();
            int declared=Integer.parseInt(plan.group(3));if(items.isEmpty()||items.stream().anyMatch(String::isBlank)||items.size()!=declared)return List.of();
            List<String> dimensions=dimensions(items);Map<String,Object> p=params("PLAN_COMPLETENESS");
            p.putAll(linked("materialType",material,"items",items,"dimensions",dimensions,"declaredItems",declared,
                    "maxScore",plan.group(4),"deductionPerMissingItem",plan.group(5),"duplicateItems",ScoringSourceSegments.repeatedPlanItems(original),
                    "decisionScope","OBJECTIVE_COMPLETENESS_SCORE"));
            String id="SOURCE-SCORE-"+identity(original).substring(0,12);List<Rule> result=new ArrayList<>();
            result.add(rule(id,material+"完整性计分","SCORE_COUNT","",p,original));
            for(Map<String,Object> continuation:context.sources())if("SCOPED_SCORING_CONTINUATION".equals(continuation.get("kind"))){
                String quote=str(continuation,"sourceQuote");var rubric=ScoringSourceSegments.rubric(quote);if(rubric.isEmpty())return List.of();
                Map<String,Object> q=params("SUBJECTIVE_RUBRIC");q.putAll(linked("materialType",material,"dimensions",dimensions,"items",items,
                        "objectiveRuleId",id,"completenessSourceQuote",original,"missingItemsExcludeTopTier",quote.contains("缺项")&&quote.contains("非A档"),
                        "rubricIntroduction",rubric.get().introduction().quote(),"rubricTiers",rubric.get().tiers().stream().map(s->linked("sourceQuote",s.quote(),"start",s.start(),"end",s.end())).toList(),
                        "decisionScope","QUALITATIVE_GRADE_REQUIRES_REVIEW"));
                result.add(rule(id+"-RUBRIC",material+"质量评分档次","QUALITY_ADVISORY","",q,quote));
            }
            return List.copyOf(result);
        }
        Matcher title=TITLE.matcher(original);
        if(title.find()&&original.contains("项目负责人")&&original.contains("证明材料：")){
            // The shared proof is not erased. Unsupported proof shapes remain in the
            // general compiler until this executor can verify their complete contract.
            if(!original.contains("填写附表6-1")||!original.contains("查询网址及网址查询截图")||!original.contains("本单位缴纳社保"))return List.of();
            String body=ScoringSourceSegments.body(original),award=body.substring(0,body.indexOf("证明材料：")).strip();
            if(!award.matches("^(?:拟派)?项目负责人实力[（(]商务[）)]\\t"+TITLE.pattern()+"[。]?$"))return List.of();
            String commonProof=body.substring(body.indexOf("证明材料："));
            if(!commonProof.matches("^证明材料：填写附表6-1。需提交有效证明文件[（(]并提供查询网址及网址查询截图[）)]及提交20[0-9]{2}年[0-9]{1,2}月[（(]含[）)]至文件递交截止时间任意连续[一二三四五六七八九十0-9]+个月在本单位缴纳社保证明材料，特殊情况提交说明文件[（(]格式自拟[）)]及有效的证明文件[（(]如单位设立时间不足、人员新入职、离退休、转业等[）)]。$"))return List.of();
            Matcher months=Pattern.compile("(20[0-9]{2})年([0-9]{1,2})月.{0,30}任意连续([一二三四五六七八九十0-9]+)个月").matcher(original);
            if(!months.find())return List.of();BigDecimal count=RequirementGrounding.number(months.group(3));if(count==null)return List.of();
            Map<String,Object> p=params("TITLE_TIER");p.putAll(linked("profession",title.group(1),"highScore",title.group(2),"middleScore",title.group(3),
                    "maxScore",title.group(2),"unsubmittedScore","0","formReference","附表6-1","category","职称","personRole","项目负责人",
                    "months",count.intValue(),"earliestMonth",months.group(1)+"-"+String.format(Locale.ROOT,"%02d",Integer.parseInt(months.group(2))),
                    "requiresReferenceDate",true,"referenceEvent","BID_DEADLINE","specialProofPermitted",original.contains("特殊情况提交说明文件"),
                    "commonProofQuote",original.substring(original.indexOf("证明材料：")).replaceFirst("\\t\\s*[0-9.]+\\s*$",""),
                    "decisionScope","TITLE_TIER_AND_ALL_COMMON_PROOF"));
            return List.of(rule("SOURCE-TITLE-"+identity(original).substring(0,12),"项目负责人职称与共同证明计分","SCORE_COUNT","项目负责人",p,original));
        }
        Rule performance=SourcePerformanceScore.compile(original);if(performance!=null)return List.of(performance);
        Rule extra=SourceExtraPersonnelScore.compile(original);if(extra!=null)return List.of(extra);
        return List.of();
    }
    static boolean applies(Rule rule){return VERSION.equals(rule.parameters().get("sourceScoringVersion"));}
    static boolean compilationComplete(Rule rule,String requirement){
        return applies(rule)&&issue(rule)==null&&clean(rule.sourceQuote()).equals(clean(requirement));
    }
    static boolean programOnly(Rule rule){return applies(rule)&&Set.of("PLAN_COMPLETENESS","SUBJECTIVE_RUBRIC").contains(str(rule.parameters(),"sourceScoringMode"));}
    /** Typed read obligations are not independent business conclusions. They expose
     * the dependencies of a scoring formula to the existing fact lookup/extractor. */
    static List<Rule> evidenceRules(Rule rule){
        if(applies(rule)&&SourcePerformanceScore.MODE.equals(str(rule.parameters(),"sourceScoringMode")))return SourcePerformanceScore.evidenceRules(rule);
        if(applies(rule)&&SourceExtraPersonnelScore.MODE.equals(str(rule.parameters(),"sourceScoringMode")))return SourceExtraPersonnelScore.evidenceRules(rule);
        if(!applies(rule)||!"TITLE_TIER".equals(str(rule.parameters(),"sourceScoringMode")))return List.of(rule);
        String quote=rule.sourceQuote();Map<String,Object> base=linked("scoringParentRuleId",rule.id(),"readPurpose","SCORE_FACT_DEPENDENCY","sourceScoringReadVersion",VERSION);
        if(rule.parameters().containsKey("requirementSourceContext"))base.put("requirementSourceContext",rule.parameters().get("requirementSourceContext"));
        Rule person=new Rule(rule.id()+"-READ-PERSON","当前项目负责人及单位归属","PERSON_ROLE",rule.subject(),"姓名","","","",base,quote,"",List.of());
        Map<String,Object> certificate=new LinkedHashMap<>(base);certificate.put("category","职称");certificate.put("requiredFields",List.of("category","number","numberField","grade","profession"));
        Rule title=new Rule(rule.id()+"-READ-TITLE","同一人员职称证书各字段","CERTIFICATE",rule.subject(),"职称","","","",certificate,quote,"",List.of());
        Map<String,Object> social=new LinkedHashMap<>(base);social.putAll(linked("count",rule.parameters().get("months"),"earliestMonth",rule.parameters().get("earliestMonth"),
                "requiresReferenceDate",true,"referenceEvent","BID_DEADLINE","requiredFields",List.of("person","employer","months")));
        Rule months=new Rule(rule.id()+"-READ-SOCIAL","同一人员及缴费单位逐月社保证明","MONTHS",rule.subject(),"socialInsurance","","","月",social,quote,"",List.of());
        return List.of(person,title,months);
    }
    /** Only server-regenerated read rules acquire a business parent. A caller-supplied
     * parameter naming another obligation cannot broaden recovery or stop its reads. */
    static Map<String,String> readParents(List<Rule> roots,List<Rule> reads){
        Map<String,Rule> supplied=new HashMap<>();reads.forEach(r->supplied.put(r.id(),r));Map<String,String> parents=new LinkedHashMap<>();
        for(Rule root:roots){
            if(applies(root)&&Set.of("TITLE_TIER",SourcePerformanceScore.MODE,SourceExtraPersonnelScore.MODE).contains(str(root.parameters(),"sourceScoringMode"))&&issue(root)==null)
                for(Rule read:evidenceRules(root))if(read.equals(supplied.get(read.id())))parents.put(read.id(),root.id());
            parents.putAll(readParents(root.children(),reads));
        }
        return Map.copyOf(parents);
    }
    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        String invalid=issue(rule);if(invalid!=null)return evaluator.unknown(rule,"SCORING_FORMULA_REQUIRED",invalid,List.of());
        return switch(str(rule.parameters(),"sourceScoringMode")){
            case "PLAN_COMPLETENESS"->completeness(rule,evaluator);
            case "SUBJECTIVE_RUBRIC"->rubric(rule,evaluator);
            case "TITLE_TIER"->title(rule,evaluator);
            case SourcePerformanceScore.MODE->SourcePerformanceScore.evaluate(rule,evaluator);
            case SourceExtraPersonnelScore.MODE->SourceExtraPersonnelScore.evaluate(rule,evaluator);
            default->evaluator.unknown(rule,"SCORING_FORMULA_REQUIRED","来源计分模式尚未支持",List.of());
        };
    }
    /** Rebuild the server-owned parameters from the exact source again at evaluation;
     * replayed/model-supplied metadata cannot invent a score or remove a dimension. */
    static String issue(Rule rule){
        if(!applies(rule))return null;String original="SUBJECTIVE_RUBRIC".equals(str(rule.parameters(),"sourceScoringMode"))?str(rule.parameters(),"completenessSourceQuote"):rule.sourceQuote();
        List<Map<String,Object>> sources=new ArrayList<>();sources.add(linked("kind","OBLIGATION","sourceQuote",original));
        if("SUBJECTIVE_RUBRIC".equals(str(rule.parameters(),"sourceScoringMode")))sources.add(linked("kind","SCOPED_SCORING_CONTINUATION","sourceQuote",rule.sourceQuote()));
        var context=new RequirementSourceContext.Context(original,Map.of("verification","SOURCE_REPARSE"),sources);
        var cp=new Checkpoint("source-reparse","评分要求","响应检查","MEDIUM","SEMANTIC_RESPONSE","scoring","RESPONDS",original,"",true,true,true,new Evidence("source","source",1,"BLOCK",original,"",1));
        String source=original+("SUBJECTIVE_RUBRIC".equals(str(rule.parameters(),"sourceScoringMode"))?"\n"+rule.sourceQuote():"");
        List<Rule> expected=compile(cp,source,context,Map.of());
        Rule canonical=expected.stream().filter(r->str(r.parameters(),"sourceScoringMode").equals(str(rule.parameters(),"sourceScoringMode"))).findFirst().orElse(null);
        if(canonical==null)return "评分规则不能重新对应完整原文公式及证明条件";
        for(var entry:canonical.parameters().entrySet())if(!Objects.equals(entry.getValue(),rule.parameters().get(entry.getKey())))return "评分参数与原文不一致："+entry.getKey();
        if(!canonical.predicate().equals(rule.predicate())||!canonical.subject().equals(rule.subject()))return "评分原语或主体与原文不一致";
        return null;
    }
    private static Decision completeness(Rule rule,RuleEvaluator evaluator){
        EvidenceCorpus.ProgramScan scan=evaluator.corpus.qualityAdvisoryScan(rule);List<String> items=strings(rule.parameters().get("items"));
        List<String> missing=items.stream().filter(item->!itemCovered(item,scan.covered())).toList();
        Map<String,Object> proof=baseProof(rule,scan);proof.putAll(linked("sourceItems",items,"missingItems",missing,
                "declaredItems",rule.parameters().get("declaredItems"),"duplicateItems",rule.parameters().get("duplicateItems"),
                "scoreProduced",false,"qualityGradeProduced",false));
        if(!scan.scopeLocated()||!scan.coverageComplete())return evaluator.unknown(rule,"SCORE_BODY_COVERAGE_REQUIRED",scan.reason(),scan.facts(),proof);
        if(!strings(rule.parameters().get("duplicateItems")).isEmpty())return evaluator.unknown(rule,"SCORE_SOURCE_LIST_AMBIGUOUS",
                "原文清单存在重复项目，已逐项核对正文；不把同一处证据重复计分，也不擅自把声明项目数改小",scan.facts(),proof);
        BigDecimal max=decimal(rule.parameters().get("maxScore")),deduction=decimal(rule.parameters().get("deductionPerMissingItem"));
        if(max==null||deduction==null)return evaluator.unknown(rule,"SCORING_FORMULA_REQUIRED","原文计分公式未完整绑定",scan.facts(),proof);
        BigDecimal score=max.subtract(deduction.multiply(BigDecimal.valueOf(missing.size()))).max(BigDecimal.ZERO);
        proof.putAll(linked("score",score,"scoreProduced",true,"scoreMaximum",max,"fullScore",missing.isEmpty(),"missingItemsExcludeTopTier",!missing.isEmpty()));
        // This is a scoring criterion: a lower exact score is not an eligibility failure.
        return evaluator.pass(rule,missing.isEmpty()?"清单全部项目均有实质正文，已按原文计算完整性分值；不代表质量档次通过":"已按完整正文范围中的缺项计算扣分；该结果是客观评分，不是资格否决",scan.facts(),proof);
    }
    private static Decision rubric(Rule rule,RuleEvaluator evaluator){
        EvidenceCorpus.ProgramScan scan=evaluator.corpus.qualityAdvisoryScan(rule);Map<String,Object> proof=baseProof(rule,scan);
        Decision objective=evaluator.decisions.get(str(rule.parameters(),"objectiveRuleId"));
        boolean missing=!strings(rule.parameters().get("items")).stream().allMatch(item->itemCovered(item,scan.covered()));
        boolean excluded=scan.coverageComplete()&&missing&&Boolean.TRUE.equals(rule.parameters().get("missingItemsExcludeTopTier"));
        proof.putAll(linked("rubricTiers",rule.parameters().get("rubricTiers"),"rubricIntroduction",rule.parameters().get("rubricIntroduction"),
                "topTierEligible",excluded?"NO":"NOT_ESTABLISHED","qualityGradeProduced",false,"scoreProduced",false,
                "objectiveScoreDecision",objective==null?Map.of():objective.map()));
        if(!scan.scopeLocated()||!scan.coverageComplete())return evaluator.unknown(rule,"QUALITY_BODY_COVERAGE_REQUIRED",scan.reason(),scan.facts(),proof);
        return evaluator.unknown(rule,"QUALITY_GRADE_REVIEW_REQUIRED",excluded?
                "正文范围已核全且存在缺项，按招标原文不能进入A档；B/C等质量档次须评价针对性及可行性，不能由材料存在直接判定":
                "已核正文和内容维度；针对性、可行性等主观评分档次尚未评定，材料齐全不等于达到最高档",scan.facts(),proof);
    }
    private static Decision title(Rule rule,RuleEvaluator evaluator){
        List<String> persons=evaluator.resolvedSubjects(rule);Map<String,Object> proof=linked("executor",VERSION,"decisionScope","TITLE_TIER_AND_ALL_COMMON_PROOF",
                "gradeScores",linked("高级",rule.parameters().get("highScore"),"中级",rule.parameters().get("middleScore"),"未提交",0),"scoreProduced",false);
        if(persons.size()!=1)return evaluator.unknown(rule,"CURRENT_APPOINTMENT_REQUIRED","职称评分须先由本次拟派原文唯一关联项目负责人",List.of(),proof);
        String person=persons.get(0);List<Fact> candidates=evaluator.facts.stream().filter(f->"CERTIFICATE".equals(f.kind())&&person.equals(f.subject()))
                .filter(DomainBindings::certificateIdentity).filter(f->str(f.attributes(),"category").matches(".*(?:职称|专业技术|工程师).*"))
                .filter(f->!CertificateEvidenceRecords.certificates(f,evaluator.corpus).isEmpty()).toList();
        List<Fact> accepted=candidates.stream().filter(f->str(f.attributes(),"profession").replaceFirst("类$","").equals(str(rule.parameters(),"profession").replaceFirst("类$","")))
                .filter(f->grade(str(f.attributes(),"grade"))!=null).toList();
        if(candidates.isEmpty()){
            Pattern declaration=Pattern.compile("项目负责人[\\s:：]*"+Pattern.quote(person)+"[\\s，,:：]*(?:未提交|未提供)职称证书");
            Source explicit=evaluator.corpus.all.stream().filter(s->!s.index()&&!s.historical()&&!s.scopeContext().blocked()&&declaration.matcher(s.evidence().quote()).find()).findFirst().orElse(null);
            if(explicit!=null){proof.putAll(linked("person",person,"score",BigDecimal.ZERO,"scoreProduced",true,"fullScore",false,"decisionBasis","EXPLICIT_UNSUBMITTED_DECLARATION"));
                return new Decision(rule.id(),rule.description(),"PASS","本次拟派人员明确声明未提交职称证书，按原文未提交档计0分；不等同于资格否决",List.of(explicit.evidence()),List.of(),proof);}
        }
        Set<String> certificateIdentities=new LinkedHashSet<>();for(Fact fact:accepted)certificateIdentities.add(str(fact.attributes(),"number")+"|"+grade(str(fact.attributes(),"grade"))+"|"+str(fact.attributes(),"profession").replaceFirst("类$",""));
        if(certificateIdentities.size()!=1)return evaluator.unknown(rule,"SCORING_TITLE_IDENTITY_REQUIRED","当前拟派人员的专业、等级与唯一职称证号尚未对应；未召回证书不能直接计为未提交0分",candidates,proof);
        Fact certificate=accepted.get(0);String number=str(certificate.attributes(),"number"),grade=grade(str(certificate.attributes(),"grade"));
        List<Fact> roleLinks=DomainBindings.roleLinks(rule.subject(),evaluator.facts,evaluator.corpus);
        Set<String> employers=new LinkedHashSet<>();for(Fact link:roleLinks)if(person.equals(DomainBindings.roleTarget(link,rule.subject()))&&!str(link.attributes(),"employer").isBlank())employers.add(str(link.attributes(),"employer"));
        List<Evidence> proofEvidence=new ArrayList<>();List<String> missing=new ArrayList<>();
        List<Source> sources=evaluator.corpus.all.stream().filter(s->!s.index()&&!s.historical()&&!s.scopeContext().blocked()).toList();
        Source form=sources.stream().filter(s->s.evidence().quote().contains(str(rule.parameters(),"formReference"))
                &&roleLinks.stream().anyMatch(link->person.equals(DomainBindings.roleTarget(link,rule.subject()))&&link.sourceIds().contains(s.id()))).findFirst().orElse(null);
        if(form==null)missing.add("附表6-1中同一拟派人员的实填记录");else proofEvidence.add(form.evidence());
        Source query=sources.stream().filter(s->sameQuery(s.evidence().quote(),person,number)).findFirst().orElse(null);
        if(query==null)missing.add("同一人员、同一职称证号的查询网址及截图");else proofEvidence.add(query.evidence());
        List<Source> invalidStatus=sources.stream().filter(s->accepted.stream().anyMatch(f->f.sourceIds().contains(s.id()))||sameQuery(s.evidence().quote(),person,number))
                .filter(s->Pattern.compile("(?:证书状态|状态|有效性)[\\s:：|]*(?:已撤销|撤销|已注销|注销|无效|已失效|失效|已过期|过期)(?=[\\s|，,。；;]|$)").matcher(s.evidence().quote()).find()).toList();
        if(!invalidStatus.isEmpty()){
            proof.putAll(linked("certificateStatusConflict",true,"invalidStatusSources",invalidStatus.stream().map(Source::id).toList()));
            return new Decision(rule.id(),rule.description(),"UNDETERMINED","同一职称证书的原文或查询结果明确标记失效、撤销等状态，不能据其等级计分",invalidStatus.stream().map(Source::evidence).toList(),
                    List.of(gap("SCORING_CERTIFICATE_VALIDITY_REQUIRED","需核对同一证书有效状态及适用时点；不得忽略已发现的无效状态")),proof);
        }
        Decision social=null;
        if(employers.size()!=1)missing.add("当前拟派人员与本单位的对应关系");
        else {
            Map<String,Object> params=linked("count",rule.parameters().get("months"),"earliestMonth",rule.parameters().get("earliestMonth"),
                    "employer",employers.iterator().next(),"requiresReferenceDate",true,"referenceEvent","BID_DEADLINE");
            Rule months=new Rule(rule.id()+"-SOCIAL","共同社保证明","MONTHS",person,"socialInsurance","","","月",params,rule.sourceQuote(),"",List.of());
            social=evaluator.months(months,evaluator.match(months));if(!"PASS".equals(social.status()))missing.add("本单位允许时间窗内连续社保，或已核实的特殊情况证明");else proofEvidence.addAll(social.evidences());
        }
        proof.putAll(linked("person",person,"certificateNumber",number,"grade",grade,"commonProofMissing",missing,
                "socialProof",social==null?Map.of():social.map(),"specialProofPermitted",rule.parameters().get("specialProofPermitted"),"commonProofQuote",rule.parameters().get("commonProofQuote")));
        if(!missing.isEmpty()){
            boolean exception=social!=null&&!"PASS".equals(social.status())&&sources.stream().anyMatch(s->s.evidence().quote().contains(person)&&s.evidence().quote().matches("(?s).*(?:离退休|退休证|人员新入职|转业证明|特殊情况说明).*"));
            return evaluator.unknown(rule,exception?"SCORING_EXCEPTION_RULE_REQUIRED":"SCORING_COMMON_PROOF_REQUIRED",exception?
                    "已发现当前人员的特殊社保证明候选，但其适用条件及证明链尚未核实，不能按普通社保缺失否决或直接计分":
                    "职称档位已有证书依据，但共同证明尚未完整，不能仅凭证书存在计分",accepted,proof);
        }
        BigDecimal score=decimal(rule.parameters().get("高级".equals(grade)?"highScore":"middleScore"));
        proof.putAll(linked("score",score,"scoreProduced",true,"fullScore","高级".equals(grade),"decisionBasis","RULE_CALCULATION"));
        Decision out=evaluator.pass(rule,"已按同一拟派人员的职称等级和共同证明计算分值；中级职称按中级档计分",accepted,proof);
        List<Evidence> evidences=new ArrayList<>(out.evidences());evidences.addAll(proofEvidence);
        return new Decision(out.id(),out.description(),out.status(),out.reason(),evidences.stream().distinct().toList(),out.gaps(),out.proof());
    }
    private static String grade(String value){return value.matches("高级(?:工程师)?")?"高级":value.matches("中级(?:工程师)?|工程师")?"中级":null;}
    private static boolean sameQuery(String text,String person,String number){
        if(!text.matches("(?s).*(?:职称|专业技术|工程师).*")||!text.matches("(?s).*(?:查询结果|查询截图|查询详情|证书查询).*"))return false;
        Set<String> names=new LinkedHashSet<>(),numbers=new LinkedHashSet<>();Matcher namesMatcher=Pattern.compile("(?:姓名|持证人)[\\s:：|]*([^\\s|，,。；;:：]{2,12})(?=[\\s|，,。；;]|$)").matcher(text);
        while(namesMatcher.find())names.add(namesMatcher.group(1));Matcher numberMatcher=Pattern.compile("证书(?:编号|号码|号)[\\s:：|]*([A-Za-z0-9][A-Za-z0-9()（）/-]{3,79})").matcher(text);while(numberMatcher.find())numbers.add(numberMatcher.group(1));
        Matcher url=URL.matcher(text);boolean verifiedUrl=false;while(url.find())if(!url.group().matches("(?i).*(?:example\\.(?:com|org)|placeholder|image_url).*"))verifiedUrl=true;
        return names.equals(Set.of(person))&&numbers.equals(Set.of(number))&&verifiedUrl;
    }
    private static Map<String,Object> params(String mode){return linked("sourceScoringVersion",VERSION,"sourceScoringMode",mode,"deterministicFamily","SOURCE_SCORING","serverOwned",true);}
    private static Rule rule(String id,String description,String predicate,String subject,Map<String,Object> params,String quote){return new Rule(id,description,predicate,subject,"scoring","","","",params,quote,"",List.of());}
    private static List<String> dimensions(List<String> items){LinkedHashSet<String> out=new LinkedHashSet<>();for(String item:items){Matcher nested=Pattern.compile("^(.+?)[（(]含(.+?)[）)]$").matcher(item);if(nested.matches()){out.add(nested.group(1));out.add(nested.group(2));}else out.add(item);}return List.copyOf(out);}
    private static boolean itemCovered(String item,List<String> covered){return covered.containsAll(dimensions(List.of(item)));}
    private static Map<String,Object> baseProof(Rule rule,EvidenceCorpus.ProgramScan scan){return linked("executor",VERSION,"decisionBasis","PROGRAM_STRUCTURAL_COVERAGE","decisionScope",rule.parameters().get("decisionScope"),
            "materialType",rule.parameters().get("materialType"),"coveredDimensions",scan.covered(),"missingDimensions",scan.missing(),
            "coverageComplete",scan.coverageComplete(),"scannedSourceIds",scan.sourceIds());}
}
