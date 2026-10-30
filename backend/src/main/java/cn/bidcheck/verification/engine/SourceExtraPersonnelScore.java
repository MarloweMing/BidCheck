package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.engine.ScoringSourceSegments;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.*;

/** Counts additional, source-identified people only after the full base configuration is proven. */
final class SourceExtraPersonnelScore {
    static final String MODE="EXTRA_QUALIFIED_PERSONNEL";
    private static final Pattern AWARD=Pattern.compile("^拟派项目组人员[（(]商务[）)]在满足项目组人员配置要求的基础上，每增加1人具有建筑工程类中级[（(]及[）)]以上技术职称或高空作业证或电工证[（(]低压[）)]得([0-9.]+)分，最高得([0-9.]+)分。$");
    private SourceExtraPersonnelScore(){}
    static Rule compile(String original){
        String[] parts=ScoringSourceSegments.body(original).replaceAll("\\s+","").split("证明材料[:：]",-1);if(parts.length!=2)return null;
        Matcher award=AWARD.matcher(parts[0]);if(!award.matches())return null;
        Matcher common=Pattern.compile("^填写(附表[0-9]+-[0-9]+)。需提交有效证明文件[（(]并提供查询网址及网址查询截图[）)]及提交(20[0-9]{2})年([0-9]{1,2})月[（(]含[）)]至文件递交截止时间任意连续([一二三四五六七八九十0-9]+)个月在本单位缴纳社保证明材料，特殊情况提交说明文件[（(]格式自拟[）)]及有效的证明文件[（(]如单位设立时间不足、人员新入职、离退休、转业等[）)]。$").matcher(parts[1]);
        if(!common.matches())return null;BigDecimal per=decimal(award.group(1)),max=decimal(award.group(2)),months=RequirementGrounding.number(common.group(4));if(per==null||max==null||months==null||months.signum()<=0||per.signum()<=0||max.signum()<=0||Integer.parseInt(common.group(3))<1||Integer.parseInt(common.group(3))>12)return null;
        Map<String,Object> p=SourcePerformanceScore.params(MODE);p.putAll(linked("pointsPerItem",award.group(1),"maxScore",award.group(2),"formReference",common.group(1),"earliestMonth",common.group(2)+"-"+String.format(Locale.ROOT,"%02d",Integer.parseInt(common.group(3))),"months",months.intValue(),
                "qualificationAlternatives",List.of("建筑工程类中级及以上技术职称","高空作业证","低压电工证"),"requiresBaseConfiguration",true,"specialProofPermitted",true,"commonProofQuote",parts[1],"decisionScope","ADDITIONAL_UNIQUE_QUALIFIED_PEOPLE_WITH_ALL_COMMON_PROOF"));
        return new Rule("SOURCE-EXTRA-PERSON-"+identity(original).substring(0,12),"基础配置之外的合格人员计分","SCORE_COUNT","","scoring","","","",p,original,"",List.of());
    }
    static List<Rule> evidenceRules(Rule parent){
        Map<String,Object> p=SourcePerformanceScore.readParams(parent);List<Rule> reads=new ArrayList<>();
        Map<String,Object> appointment=new LinkedHashMap<>(p);appointment.put("formReference",parent.parameters().get("formReference"));appointment.put("requiredFields",List.of("person","role","employer"));
        reads.add(SourcePerformanceScore.read(parent,"APPOINTMENTS","PERSON_ROLE","","当前项目全部实填岗位、人员身份及成员单位",appointment));
        Map<String,Object> certificate=new LinkedHashMap<>(p);certificate.putAll(linked("qualificationAlternatives",parent.parameters().get("qualificationAlternatives"),"requiredFields",List.of("category","number","numberField","grade","profession","start","end","validityField")));
        reads.add(SourcePerformanceScore.read(parent,"CERTIFICATES","CERTIFICATE","","逐人职称或高空作业或低压电工证字段",certificate));
        reads.add(SourcePerformanceScore.socialRead(parent,"",p));return reads;
    }
    private record Base(Decision decision,List<String> roles,List<Map<String,Object>> sources){}
    static Decision evaluate(Rule rule,RuleEvaluator e){
        BigDecimal per=decimal(rule.parameters().get("pointsPerItem")),max=decimal(rule.parameters().get("maxScore"));
        Base base=base(rule,e);Map<String,Object> proof=linked("executor","source-extra-personnel-score-1","scoreProduced",false,"scoreLower",BigDecimal.ZERO,"scoreUpper",max,"baseConfiguration",base.decision().map(),"baseRequirementSources",base.sources());
        for(String key:List.of("dependencyCheckpointIds","dependencyRevision"))if(base.decision().proof().containsKey(key))proof.put(key,base.decision().proof().get(key));
        // Every dependency remains explicit. A partial base pass or a model flag cannot turn a
        // certificate holder into an additional member of the required current project team.
        if(!base.decision().status().equals("PASS")||!base.decision().gaps().isEmpty())return SourcePerformanceScore.result(rule,"UNDETERMINED","增员计分的基础配置尚未完整核验，已保留实际子条件结果；不会把基础岗位人员重复计为增员",base.decision().evidences(),proof,List.of(gap("SCORING_BASE_CONFIGURATION_REQUIRED","须先闭合本次招标人员配置及其全部共同证明")));
        List<Fact> appointments=e.facts.stream().filter(f->f.kind().equals("PERSON_ROLE")&&f.scope().equals("CURRENT_PROJECT")&&DomainBindings.currentRoleSource(f,e.corpus.byId)&&SourcePerformanceScore.valid(f,e)).toList();
        Set<String> excluded=new HashSet<>();List<String> unboundBase=new ArrayList<>();
        for(String role:base.roles()){
            List<Fact> assigned=appointments.stream().filter(f->DomainBindings.roleMatches(role,f.subject(),f,e.corpus)).toList();
            if(assigned.isEmpty())unboundBase.add(role);
            for(Fact person:assigned){String id=e.identities.countIdentity(person);if(id.isBlank())unboundBase.add(role+":"+person.value());else excluded.add(id);}
        }
        proof.put("excludedBaseIdentityCount",excluded.size());proof.put("unboundBasePeople",unboundBase);
        if(!unboundBase.isEmpty())return e.unknown(rule,"SCORING_BASE_IDENTITY_REQUIRED","基础岗位已核配置，但人员身份尚不能跨来源去重，不能确定哪些人属于额外配置",appointments,proof);
        Map<String,List<Fact>> people=new LinkedHashMap<>();List<Map<String,Object>> checks=new ArrayList<>();
        for(Fact person:appointments){String id=e.identities.countIdentity(person);if(id.isBlank()){checks.add(linked("person",person.value(),"missing",List.of("当前人员强身份，不能按同名计人"),"counted",false));continue;}if(!excluded.contains(id))people.computeIfAbsent(id,x->new ArrayList<>()).add(person);}
        List<Evidence> evidence=new ArrayList<>(base.decision().evidences());int counted=0;
        for(var person:people.entrySet()){
            List<Fact> assignments=person.getValue();Set<String> names=new LinkedHashSet<>();assignments.forEach(f->names.add(f.value()));String name=names.iterator().next();List<String> missing=new ArrayList<>();
            if(names.size()!=1)missing.add("同一身份出现不同姓名");
            List<Source> forms=e.corpus.all.stream().filter(s->!s.index()&&!s.historical()&&!s.scopeContext().blocked()&&s.evidence().quote().contains(str(rule.parameters(),"formReference"))&&s.evidence().quote().contains(name)&&assignments.stream().anyMatch(f->f.sourceIds().contains(s.id()))).toList();
            if(forms.isEmpty())missing.add("附表实填的当前人员记录");
            Decision social=SourcePerformanceScore.social(rule,name,assignments,e);if(!social.status().equals("PASS"))missing.add("同人同单位连续社保或已核特殊情形");
            List<Fact> certificates=e.facts.stream().filter(f->f.kind().equals("CERTIFICATE")&&name.equals(f.subject())&&SourcePerformanceScore.valid(f,e)&&DomainBindings.certificateIdentity(f)&&assignments.stream().anyMatch(a->e.identities.samePerson(a,f))).toList();
            List<Map<String,Object>> alternatives=new ArrayList<>();List<Evidence> qualified=new ArrayList<>();boolean accepted=false;
            for(Fact certificate:certificates){String branch=qualification(certificate,e);if(branch.isBlank())continue;Decision validity=validity(rule,certificate,e);var query=PersonnelQueryScreenshot.find(rule,certificate,e);
                boolean complete=validity.status().equals("PASS")&&!query.sources().isEmpty();alternatives.add(linked("branch",branch,"certificateNumber",str(certificate.attributes(),"number"),"validity",validity.map(),"querySources",query.sources().stream().map(Source::id).toList(),"queryImageProof",query.observations(),"complete",complete));
                if(complete){accepted=true;qualified.addAll(validity.evidences());qualified.addAll(query.evidence());}
            }
            if(!accepted)missing.add("至少一项适用证书的类别/等级/专业、有效期及同证号查询截图");
            boolean count=missing.isEmpty();if(count){counted++;forms.forEach(s->evidence.add(s.evidence()));evidence.addAll(qualified);evidence.addAll(social.evidences());}
            checks.add(linked("person",name,"identityFingerprint",identity(person.getKey()),"appointmentFactIds",assignments.stream().map(Fact::id).toList(),"qualificationAlternatives",alternatives,"socialProof",social.map(),"missing",missing,"counted",count));
        }
        BigDecimal lower=per.multiply(BigDecimal.valueOf(counted)).min(max);proof.putAll(linked("personChecks",checks,"deduplicatedAdditionalPeople",counted,"scoreLower",lower,"basePeopleExcluded",true));
        if(lower.compareTo(max)>=0){proof.putAll(linked("score",max,"scoreProduced",true,"fullScore",true));return SourcePerformanceScore.result(rule,"PASS","完整基础配置之外的去重人员已逐人核验资格、查询和社保，达到原文增员计分上限",evidence,proof,List.of());}
        return SourcePerformanceScore.result(rule,"UNDETERMINED","已计算额外合格人员计分下限，未核全人员名册及共同证明前不宣称确定总分或0分",evidence,proof,List.of(gap("SCORING_EXTRA_PERSONNEL_COVERAGE_REQUIRED","需核清逐人缺口及完整增员名单")));
    }
    private static String qualification(Fact fact,RuleEvaluator e){
        String category=str(fact.attributes(),"category"),profession=str(fact.attributes(),"profession"),grade=str(fact.attributes(),"grade");
        if(category.matches(".*(?:职称|专业技术|工程师).*")&&profession.matches("建筑工程类?|建筑工程")&&grade.matches("中级(?:工程师)?|工程师|高级(?:工程师)?|正高级(?:工程师)?|副高级(?:工程师)?"))return "建筑工程类中级及以上技术职称";
        if(category.equals("高空作业证")||category.matches(".*(?:特种作业|高处作业).*" )&&profession.matches(".*(?:高处|高空).*"))return "高空作业证";
        if(category.matches(".*(?:电工证|特种作业).*" )&&profession.matches(".*低压.*"))return "低压电工证";
        return "";
    }
    private static Decision validity(Rule parent,Fact fact,RuleEvaluator e){
        String category=str(fact.attributes(),"category"),number=str(fact.attributes(),"number");List<Fact> records=e.facts.stream().filter(f->Set.of("CERTIFICATE","DATE_RANGE").contains(f.kind())&&f.subject().equals(fact.subject())&&str(f.attributes(),"category").equals(category)&&str(f.attributes(),"number").equals(number)&&SourcePerformanceScore.valid(f,e)).toList();
        Rule check=new Rule(parent.id()+"-VALIDITY-"+identity(number).substring(0,8),"计分证书有效期","VALIDITY",fact.subject(),"有效期","","","",Map.of("category",category,"number",number,"requiresReferenceDate",true,"referenceEvent","BID_DEADLINE"),parent.sourceQuote(),"",List.of());
        return e.validity(check,records);
    }
    private static Base base(Rule rule,RuleEvaluator e){
        Map<String,Object> scoringIdentity=Map.of();if(rule.parameters().get("requirementSourceContext") instanceof Map<?,?> scope&&scope.get("identity") instanceof Map<?,?> raw){Map<String,Object> copy=new LinkedHashMap<>();raw.forEach((k,v)->copy.put(String.valueOf(k),v));scoringIdentity=copy;}
        List<Document> scoringDocuments=new ArrayList<>();if(e.context.get("tenderDocuments") instanceof List<?> docs)for(Object raw:docs)if(raw instanceof Document d&&"TENDER".equals(d.role())&&d.id().equals(str(scoringIdentity,"documentId"))&&d.sha256().equals(str(scoringIdentity,"documentHash"))&&Objects.equals(d.version(),scoringIdentity.get("documentVersion")))scoringDocuments.add(d);
        if(scoringDocuments.size()!=1)return missingBase(rule,e,"评分要求尚未对应唯一冻结招标文件，不能借用另一文件的基础配置");
        Document scoringDocument=scoringDocuments.get(0);int scoreAt=scoringIdentity.get("location") instanceof Number n?n.intValue():-1;String scoreLocationType=str(scoringIdentity,"locationType");
        List<Page> scorePages=scoringDocument.pages().stream().filter(p->p.number()==scoreAt&&p.locationType().equals(scoreLocationType)&&p.text().contains(rule.sourceQuote())).toList();
        if(scorePages.size()!=1)return missingBase(rule,e,"评分原文位置已变化，基础依赖必须重新绑定");
        String lot=lotAt(scoringDocument,scorePages.get(0),rule.sourceQuote());if(lot.startsWith("UNRESOLVED:"))return missingBase(rule,e,"评分原文所在标段边界不明确，不能沿用上一标段基础配置："+lot);
        List<Map.Entry<Document,Page>> candidates=new ArrayList<>();for(Page page:scoringDocument.pages()){
            String text=page.text();if(text.strip().startsWith("人员要求")&&lot.equals(lotAt(scoringDocument,page,text))&&PersonCountRequirement.matches(text).stream().map(PersonCountRequirement.Match::role).distinct().count()>=2)candidates.add(Map.entry(scoringDocument,page));
        }
        if(candidates.isEmpty())return missingBase(rule,e,"冻结招标文件中尚未唯一定位完整基础人员配置要求");
        Set<String> configurations=new HashSet<>();for(var candidate:candidates)configurations.add(clean(ScoringSourceSegments.body(candidate.getValue().text())));
        if(configurations.size()!=1)return missingBase(rule,e,"招标文件存在不同的基础配置口径，须先确定适用版本");
        var selected=candidates.stream().min(Comparator.comparingInt(v->v.getValue().number())).orElseThrow();Document tender=selected.getKey();Page page=selected.getValue();String quote=page.text();
        List<String> roles=PersonCountRequirement.matches(quote).stream().map(PersonCountRequirement.Match::role).distinct().toList();Map<String,Object> source=linked("documentId",tender.id(),"documentHash",tender.sha256(),"documentVersion",tender.version(),"location",page.number(),"locationType",page.locationType(),"sourceQuote",quote,"sourceHash",identity(quote),"lotScope",lot);
        Checkpoint cp=new Checkpoint("score-base:"+identity(quote).substring(0,16),"增员计分基础人员配置","资格检查","HIGH","SEMANTIC_RESPONSE","compliance","RESPONDS",quote,"",true,true,true,new Evidence(tender.id(),tender.name(),page.number(),page.locationType(),quote,"chars:0-"+quote.length(),1));
        Map<String,Object> context=new LinkedHashMap<>(e.context);context.put("originalRequirement",quote);context.put("focusedCheck",true);context.put("allowModelRuleCompilation",false);context.put("checkpointId",cp.id());
        try{
            Plan plan=new RequirementCompiler().compile(cp,new RunSession(ModelGateway.disabled(),context,"scoring-base"));
            List<Decision> children=evaluateBaseRules(plan.rules(),e,context);
            if(!plan.issues().isEmpty())children.add(new Decision(cp.id()+":source","基础要求覆盖","UNDETERMINED","基础配置原文尚未完整转成执行规则",List.of(),plan.issues().stream().map(issue->gap("SCORING_BASE_RULE_UNRESOLVED",issue)).toList(),Map.of()));
            Decision decision=RuleEvaluator.combine(cp.id(),cp.title(),"ALL",children);Map<String,Object> complete=new LinkedHashMap<>(decision.proof());complete.putAll(linked("baseRuleFingerprint",identity(VERSION+plan.logic()+plan.rules().stream().map(SharedFactReuse::signature).toList()+plan.issues()),"dependencyMethod","LOCAL_RECOMPUTE_FROM_CURRENT_VALIDATED_FACTS","lotScope",lot));
            var snapshot=RequirementDependencyProof.current(e);if(snapshot.verified()){List<String> ids=snapshot.checkpoints().stream().filter(c->c.source()!=null&&c.source().documentId().equals(tender.id())&&c.source().page()==page.number()&&c.source().quote().equals(quote)).map(Checkpoint::id).toList();complete.put("dependencyCheckpointIds",ids);complete.put("dependencyRevision",snapshot.revision());}
            if("PASS".equals(decision.status())&&decision.evidences().isEmpty())decision=new Decision(decision.id(),decision.description(),"UNDETERMINED","基础规则尚无可回溯实际证据，通用完成标记不能满足配置依赖",List.of(),List.of(gap("SCORING_BASE_SOURCE_PROOF_REQUIRED","基础配置须逐项有当前来源证明")),complete);
            else decision=new Decision(decision.id(),decision.description(),decision.status(),decision.reason(),decision.evidences(),decision.gaps(),complete);
            return new Base(decision,roles,List.of(source));
        }catch(Exception failure){return new Base(e.unknown(rule,"SCORING_BASE_CONFIGURATION_REQUIRED","基础配置来源已定位，程序暂未完成依赖求值；不会调用模型判断分值",List.of()),roles,List.of(source));}
    }
    static String lotAt(Document document,Page target,String quote){
        String lot="";Pattern named=Pattern.compile("(?:第[一二三四五六七八九十百零〇0-9A-Za-z]+标段|[一二三四五六七八九十百零〇0-9A-Za-z]+标段|标段(?:编号|名称)[:：]\\s*[^\\s，。；]{1,40}|采购包[一二三四五六七八九十百零〇0-9A-Za-z]+)");
        for(Page page:document.pages()){boolean current=page.number()==target.number()&&page.locationType().equals(target.locationType());int at=current?page.text().indexOf(quote):-1;if(current&&at<0)return "UNRESOLVED:"+page.number()+":原文位置";
            boolean wholePage=current&&page.text().equals(quote);Set<String> within=new LinkedHashSet<>();Matcher lines=Pattern.compile("(?m)^([^\\r\\n]*)").matcher(page.text());
            while(lines.find()){int offset=lines.start();String line=lines.group(1).strip().replaceFirst("^[#|\\s]+","");if(current&&!wholePage&&offset>at)break;
                boolean heading=line.length()<120&&(line.contains("标段")||line.contains("采购包"))&&!line.matches(".*[。；;].*");
                if(heading){Matcher m=named.matcher(line);String found;if(!m.find())found="UNRESOLVED:"+page.number()+":"+offset+":标段标题未识别";else {found=clean(m.group());if(m.find())found="UNRESOLVED:"+page.number()+":"+offset+":多个标段标题";}
                    lot=found;if(current)within.add(found);}
            }
            if(current){if(wholePage&&within.size()>1)return "UNRESOLVED:"+page.number()+":整页原文跨越多个标段";return lot;}
        }return "UNRESOLVED:"+target.number()+":来源位置缺失";
    }
    static List<Decision> evaluateBaseRules(List<Rule> rules,RuleEvaluator parent,Map<String,Object> context){
        RuleEvaluator base=new RuleEvaluator(parent.facts,parent.corpus,context);List<Decision> children=new ArrayList<>();
        try{for(Rule child:rules)children.add(base.evaluate(child));return children;}
        finally{parent.visualRequests.addAll(base.visualRequests);}
    }
    private static Base missingBase(Rule r,RuleEvaluator e,String reason){return new Base(e.unknown(r,"SCORING_BASE_REQUIREMENT_SOURCE_REQUIRED",reason,List.of()),List.of(),List.of());}
}
