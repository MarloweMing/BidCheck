package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.engine.ScoringSourceSegments;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.*;

/** Source-closed performance awards. Contracts are counted only after all identity, amount,
 * time and common-document conditions have been proven. Unread contracts never mean zero. */
final class SourcePerformanceScore {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SourcePerformanceScore.class);
    static final String MODE="PERFORMANCE_CONTRACTS";
    private static final Pattern AWARD=Pattern.compile("^(施工业绩情况|项目负责人业绩|设计负责人业绩)[（(]商务[）)]自(20[0-9]{2})年([0-9]{1,2})月([0-9]{1,2})日至今(?:作为(项目负责人|设计负责人)的)?(建安费)?金额在([0-9.]+)(万元|元)含以上装饰装修工程(施工|设计)项目合同或EPC合同(?:中)?担任(施工|设计)部分[。，,](?:每有|具有)1个得([0-9.]+)分[，,]最高得([0-9.]+)分[。]?$");
    static final String SOCIAL="需提交(20[0-9]{2})年([0-9]{1,2})月[（(]含[）)]至文件递交截止时间任意连续([一二三四五六七八九十0-9]+)个月在本单位缴纳社保证明材料，特殊情况提交说明文件[（(]格式自拟[）)]及有效的证明文件[（(]如单位设立时间不足、人员新入职、离退休、转业等[）)]";
    private static final String FRAMEWORK="如提交框架合同，按框架合同金额。如框架合同无签约金额，需提交汇总金额不低于上述限额的项下订单，并列表汇总（格式自拟）";
    private SourcePerformanceScore(){}
    static Rule compile(String original){
        String body=ScoringSourceSegments.body(original).replaceAll("\\s+","");String[] parts=body.split("证明材料[:：]",-1);if(parts.length!=2)return null;
        Matcher award=AWARD.matcher(parts[0]);if(!award.matches()||!award.group(9).equals(award.group(10)))return null;
        String role=Objects.toString(award.group(5),""),basis=Objects.toString(award.group(6),"");boolean person=!role.isBlank();
        if(person!=!award.group(1).equals("施工业绩情况")||person&&!award.group(1).equals(role+"业绩"))return null;
        String proof=parts[1];Map<String,Object> parameters=params(MODE);
        if(person){
            Matcher social=Pattern.compile("^"+SOCIAL+"，并").matcher(proof);if(!social.find())return null;putSocial(parameters,social);
            proof=proof.substring(social.end());
            String tail="需提交合同关键页扫描件，关键页包括合同名称、合同金额、签字盖章页和项目负责人证明材料（如合同项目负责人姓名页、中标通知书、具有"+role+"签字及甲方盖章的竣工验收单等）；"+FRAMEWORK+"。时间以合同签订日期为准。";
            if(!proof.equals(tail))return null;
        }else{
            Matcher form=Pattern.compile("^填写(附表[0-9]+-[0-9]+)。").matcher(proof);if(!form.find())return null;parameters.put("formReference",form.group(1));proof=proof.substring(form.end());
            String tail="需提交合同关键页扫描件，关键页包括合同名称、合同金额及签字盖章页等；"+FRAMEWORK+"，如提供EPC项目合同，则以施工部分金额为准。时间以合同签订日期为准。（如为联合体投标，则须为施工单位业绩）。";
            if(!proof.equals(tail))return null;
        }
        LocalDate since;try{since=LocalDate.of(Integer.parseInt(award.group(2)),Integer.parseInt(award.group(3)),Integer.parseInt(award.group(4)));}catch(Exception invalid){return null;}
        BigDecimal per=decimal(award.group(11)),max=decimal(award.group(12)),minimum=decimal(award.group(7));if(per==null||max==null||minimum==null||minimum.signum()<0||per.signum()<=0||max.signum()<=0)return null;
        parameters.putAll(linked("sinceDate",since.toString(),"amountMinimum",award.group(7),"amountUnit",award.group(8),"performanceAmountBasis",basis.isBlank()?"CONTRACT_OR_EPC_CONSTRUCTION":"CONSTRUCTION_INSTALLATION",
                "performanceType",award.group(9),"personRole",role,"pointsPerItem",award.group(11),"maxScore",award.group(12),"commonProofQuote",parts[1],
                "frameworkOrderAggregation",true,"decisionScope","PERFORMANCE_SCORE_WITH_COMMON_PROOF"));
        if(person)parameters.putAll(linked("requiresReferenceDate",true,"referenceEvent","BID_DEADLINE"));
        else parameters.put("performanceCutoffPolicy","VERIFIED_SUBMISSION_BOUNDARY");
        return new Rule("SOURCE-PERFORMANCE-"+identity(original).substring(0,12),award.group(1)+"逐合同核验计分","SCORE_COUNT",person?role:"施工单位","scoring","","","",parameters,original,"",List.of());
    }
    static Map<String,Object> params(String mode){return linked("sourceScoringVersion",SourceScoringRules.VERSION,"sourceScoringMode",mode,"deterministicFamily","SOURCE_SCORING","serverOwned",true);}
    static void putSocial(Map<String,Object> params,Matcher m){params.putAll(linked("earliestMonth",m.group(1)+"-"+String.format(Locale.ROOT,"%02d",Integer.parseInt(m.group(2))),"months",RequirementGrounding.number(m.group(3)).intValue(),"specialProofPermitted",true));}
    static List<Rule> evidenceRules(Rule parent){
        Map<String,Object> p=readParams(parent);List<Rule> rules=new ArrayList<>();
        boolean personal=!str(parent.parameters(),"personRole").isBlank();
        rules.add(read(parent,"SUBJECT",personal?"PERSON_ROLE":"RELATION",parent.subject(),personal?"当前任职人员及单位":"当前施工单位、投标人与联合体成员的明确角色关系",p));
        Map<String,Object> contracts=new LinkedHashMap<>(p);contracts.putAll(linked("requiredFields",List.of("contractName","contractNumber","party","person","personRole","amount","unit","amountBasis","signedDate"),"requiredAmountBasis",personal?"建安费、建筑安装工程费":"合同金额、签约金额；EPC仅施工部分金额", "amountBasisMustQuoteOriginalLabel",true,"performanceType",parent.parameters().get("performanceType")));
        rules.add(read(parent,"CONTRACTS","CONTRACT","","逐合同名称、合同号、签约主体、签订日期、同合同施工或建安费金额及人员任职",contracts));
        if(!str(parent.parameters(),"personRole").isBlank())rules.add(socialRead(parent,parent.subject(),p));
        return rules;
    }
    static Map<String,Object> readParams(Rule parent){Map<String,Object> p=linked("scoringParentRuleId",parent.id(),"readPurpose","SCORE_FACT_DEPENDENCY","sourceScoringReadVersion",SourceScoringRules.VERSION);if(parent.parameters().containsKey("requirementSourceContext"))p.put("requirementSourceContext",parent.parameters().get("requirementSourceContext"));return p;}
    static Rule read(Rule parent,String suffix,String predicate,String subject,String description,Map<String,Object> p){return new Rule(parent.id()+"-READ-"+suffix,description,predicate,subject,"","","","",p,parent.sourceQuote(),"",List.of());}
    static Rule socialRead(Rule parent,String subject,Map<String,Object> base){Map<String,Object> p=new LinkedHashMap<>(base);p.putAll(linked("count",parent.parameters().get("months"),"earliestMonth",parent.parameters().get("earliestMonth"),"requiresReferenceDate",true,"referenceEvent","BID_DEADLINE","requiredFields",List.of("person","employer","months")));return read(parent,"SOCIAL","MONTHS",subject,"当前人员及本单位连续社保",p);}
    static Decision evaluate(Rule rule,RuleEvaluator e){
        BigDecimal per=decimal(rule.parameters().get("pointsPerItem")),max=decimal(rule.parameters().get("maxScore")),minimum=RuleEvaluator.normalize(decimal(rule.parameters().get("amountMinimum")),str(rule.parameters(),"amountUnit"));
        Map<String,Object> proof=linked("executor","source-performance-score-1","scoreProduced",false,"pointsPerItem",per,"scoreMaximum",max,"contractChecks",new ArrayList<>());
        boolean personal=!str(rule.parameters(),"personRole").isBlank();
        if(!personal&&!"BID_SUBMISSION".equals(str(e.context,"evaluationStage")))return e.unknown(rule,"PERFORMANCE_CUTOFF_STAGE_REQUIRED","原文“至今”未明确日期事件；当前并非已确认投标阶段，不能默认使用系统今天或其他阶段日期",List.of(),proof);
        Rule cutoff=new Rule(rule.id()+"-STATISTICS-CUTOFF","投标阶段业绩统计截止依据","REFERENCE_TIME","","","","","",Map.of("referenceEvent","BID_DEADLINE"),rule.sourceQuote(),"",List.of());
        LocalDate deadline=e.referenceDate(cutoff);Decision cutoffProof=e.reference(cutoff);
        if(deadline!=null&&!uniqueCutoff(cutoffProof,deadline)){deadline=null;cutoffProof=e.unknown(cutoff,"REFERENCE_TIME_UNCONFIRMED","截止日期的原文标签存在冲突或未唯一绑定，不能以已确认标记代替日期核对",List.of(),cutoffProof.proof());}
        proof.put("performanceCutoff",linked("policy",personal?"EXPLICIT_SOURCE_SUBMISSION_DEADLINE":"VERIFIED_SUBMISSION_BOUNDARY","sourceEventExplicit",personal,"evaluationStage",str(e.context,"evaluationStage"),"referenceProof",cutoffProof.map(),"interpretation",personal?"原文共同证明明确文件递交截止时点":"仅在投标阶段以已核招标截止日作为业绩统计边界；此为统计策略，不声称至今原文明确了日期事件"));
        if(deadline==null)return e.unknown(rule,"REFERENCE_TIME_UNCONFIRMED","业绩统计所需投标截止日期或唯一原文出处尚未核实，不使用系统今天补齐",List.of(),proof);
        List<String> targets=e.resolvedSubjects(rule);
        if(!personal&&targets.isEmpty()&&!EnterpriseSubjectBindings.consortiumDeclared(e.corpus.all))targets=DomainBindings.subjects("投标人",e.facts,e.corpus);
        if(targets.size()!=1)return e.unknown(rule,"PERFORMANCE_SUBJECT_REQUIRED","须先从本次投标材料确定唯一拟派人员或施工单位，不能混算其他人员、其他成员业绩",List.of(),proof);
        String target=targets.get(0);proof.put("target",target);List<Fact> roles=DomainBindings.roleLinks(rule.subject(),e.facts,e.corpus);
        List<Fact> validated=e.facts.stream().filter(f->f.kind().equals("CONTRACT")&&valid(f,e)).toList();Map<String,List<Fact>> groups=contractGroups(validated);Set<String> ambiguousNumbers=ambiguousNumberCandidates(groups);
        Inventory inventory=inventory(rule,target,e);proof.put("submittedInventory",inventory.proof());
        List<Fact> accepted=new ArrayList<>();List<Evidence> evidence=new ArrayList<>();List<Map<String,Object>> checks=new ArrayList<>();Set<String> counted=new LinkedHashSet<>();
        Decision social=null;if(personal){social=social(rule,target,roles,e);proof.put("socialProof",social.map());if(social.status().equals("PASS"))evidence.addAll(social.evidences());}
        String form=str(rule.parameters(),"formReference");PerformanceInventoryProof submittedForm=form.isBlank()?null:new PerformanceInventoryProof(form,e);
        Map<String,Long> contractNameCounts=new HashMap<>();for(List<Fact> group:groups.values())group.stream().map(f->clean(str(f.attributes(),"contractName"))).filter(n->!n.isBlank()).distinct().forEach(n->contractNameCounts.merge(n,1L,Long::sum));
        for(var group:groups.entrySet()){
            List<Fact> facts=group.getValue();List<String> missing=new ArrayList<>(),excluded=new ArrayList<>();Set<String> parties=new LinkedHashSet<>(),dates=new LinkedHashSet<>(),names=new LinkedHashSet<>();
            if(facts.stream().map(f->numberCandidate(str(f.attributes(),"contractNumber"))).anyMatch(ambiguousNumbers::contains))missing.add("合同编号去除分隔符后存在同号候选，名称或日期未能证明同一合同，暂不重复计分");
            Set<String> numbers=new LinkedHashSet<>();facts.forEach(f->{if(!str(f.attributes(),"contractNumber").isBlank())numbers.add(str(f.attributes(),"contractNumber"));});
            if(inventory.complete()&&numbers.stream().noneMatch(inventory.numbers()::contains))continue;
            facts.forEach(f->{if(!str(f.attributes(),"party").isBlank())parties.add(str(f.attributes(),"party"));if(!str(f.attributes(),"signedDate").isBlank())dates.add(str(f.attributes(),"signedDate"));if(!str(f.attributes(),"contractName").isBlank())names.add(str(f.attributes(),"contractName"));});
            List<Source> sources=contractSources(facts,e);String text=sources.stream().map(s->s.evidence().quote()).reduce((a,b)->a+'\n'+b).orElse("");
            if(names.size()!=1)missing.add("同一合同名称");if(parties.isEmpty())missing.add("签约单位");else if(!personal&&!parties.contains(target))excluded.add("非本次施工单位业绩");
            if(parties.stream().anyMatch(party->!Pattern.compile("(?:承包人|承包方|乙方|施工单位|设计单位)[\\s:：|（(盖章）)]*"+Pattern.quote(party)+"(?=[\\s|，,。；;（）()]|$)").matcher(text).find()))missing.add("各签约单位与同一合同承包方的原文关系");
            if(dates.size()!=1)missing.add("同一合同签订日期");else {LocalDate date=date(dates.iterator().next());if(date==null)missing.add("签订日期格式");else if(date.isBefore(LocalDate.parse(str(rule.parameters(),"sinceDate")))||date.isAfter(deadline))excluded.add("签订日期不在招标窗口");}
            if(dates.size()==1&&!boundSigningDate(text,dates.iterator().next()))missing.add("签订日期标签与日期的对应关系");
            if(text.matches("(?s).*(?:示范文本|填写示例|空白合同|拟签订).*"))missing.add("非模板的已签合同原文");
            if(!text.contains("装饰装修")&&!text.contains("装修工程"))missing.add("装饰装修项目性质");
            boolean epc=Pattern.compile("(?i)(?:EPC|工程总承包)").matcher(text).find();String type=str(rule.parameters(),"performanceType");
            List<Map<String,Object>> workRoles=new ArrayList<>();
            if(!personal)workRoles.addAll(workRole(sources,target,type,epc,names,parties.size()));
            else {List<Map<String,Object>> membership=personalMembership(sources,target,str(rule.parameters(),"personRole"),parties);Set<String> members=new LinkedHashSet<>();membership.forEach(m->members.add(str(m,"party")));
                if(membership.isEmpty()||members.size()!=1||members.contains("")||!parties.contains(members.iterator().next()))missing.add("历史任职人员与同一合同成员单位的局部归属尚未唯一证明");
                else {String member=members.iterator().next();workRoles.addAll(workRole(sources,member,type,epc,names,parties.size()));workRoles.forEach(m->m.put("personMembershipProof",membership));}}
            if(workRoles.isEmpty())missing.add("同一合同中签约主体明确承担的"+type+"工作；其他成员分工不能借用");
            if(sources.stream().noneMatch(s->s.evidence().quote().matches("(?s).*(?:甲方|发包人|建设单位).*(?:签字|签章|盖章).*" )&&s.evidence().quote().matches("(?s).*(?:乙方|承包人|施工单位|设计单位).*(?:签字|签章|盖章).*")))missing.add("同一合同签字盖章页");
            if(personal){
                List<Fact> individual=facts.stream().filter(f->target.equals(str(f.attributes(),"person"))&&PersonnelRoles.canonical(str(f.attributes(),"personRole")).equals(PersonnelRoles.canonical(str(rule.parameters(),"personRole")))).toList();
                if(individual.isEmpty())missing.add("该合同中的同一负责人任职证明");
                else if(individual.stream().noneMatch(f->roles.stream().anyMatch(r->e.identities.samePerson(r,f))))missing.add("当前任职与历史合同人员身份关联");
                if(individual.stream().noneMatch(f->Pattern.compile(Pattern.quote(str(f.attributes(),"personRole"))+"[\\s:：|]*"+Pattern.quote(target)+"(?=[\\s|，,。；;（）()]|$)").matcher(text).find()))missing.add("合同任职岗位与该姓名的直接对应");
                if(social==null||!social.status().equals("PASS"))missing.add("同一人员在本单位连续社保或已核特殊证明");
            }
            List<Map<String,Object>> formRecords=List.of();if(submittedForm!=null){
                String contractName=names.size()==1?names.iterator().next():"";PerformanceInventoryProof.Match rows=submittedForm.match(contractName,numbers,contractNameCounts.getOrDefault(clean(contractName),0L)==1L);
                if(!rows.present())missing.add(form+"中对应本合同的唯一实填企业业绩记录");else {evidence.addAll(rows.evidence());formRecords=rows.records();}
            }
            Amount amount=amount(rule,facts,sources,epc,minimum);missing.addAll(amount.missing());if(amount.value()!=null&&amount.value().compareTo(minimum)<0)excluded.add("正确金额口径低于限额");
            for(Map<String,Object> row:formRecords){List<String> rowDates=strings(row.get("signedDates"));if(rowDates.size()!=1||dates.size()!=1)missing.add("业绩清单签订日期尚未与合同逐行核对");else if(!dates.contains(rowDates.get(0)))missing.add("业绩清单签订日期与合同签订日期矛盾");
                Set<BigDecimal> listed=new TreeSet<>();for(String value:strings(row.get("listedAmounts"))){BigDecimal n=decimal(value);if(n!=null)listed.add(n);}Set<BigDecimal> totals=new TreeSet<>();for(Fact f:facts)if(str(f.attributes(),"amountBasis").matches(".*(?:合同金额|签约金额|合同总价|合同价款).*")){BigDecimal total=RuleEvaluator.normalize(decimal(f.attributes().get("amount")),str(f.attributes(),"unit"));if(total!=null)totals.add(total);}
                if(totals.isEmpty()&&"DEDUPLICATED_ORDER_LOWER_BOUND".equals(amount.proof().get("method"))&&amount.value()!=null)totals.add(amount.value());
                if(listed.size()!=1||totals.size()!=1)missing.add("业绩清单金额与合同同口径金额尚未逐行核对");else if(listed.iterator().next().compareTo(totals.iterator().next())!=0)missing.add("业绩清单金额与合同同口径金额矛盾");}
            if(missing.isEmpty()&&excluded.isEmpty()){counted.add(group.getKey());accepted.addAll(facts);sources.forEach(s->evidence.add(s.evidence()));}
            checks.add(linked("contractIdentity",group.getKey(),"contractNumbers",List.copyOf(numbers),"factIds",facts.stream().map(Fact::id).toList(),"sourceIds",sources.stream().map(Source::id).toList(),"submittedFormRecords",formRecords,"partyWorkRoleProof",workRoles,"amountBasis",amount.basis(),"amount",amount.value(),"amountEvidence",amount.proof(),"missing",missing,"excluded",excluded,"counted",missing.isEmpty()&&excluded.isEmpty()));
        }
        BigDecimal lower=per.multiply(BigDecimal.valueOf(counted.size())).min(max);proof.putAll(linked("contractChecks",checks,"scoreLower",lower,"scoreUpper",max,"deduplicatedContracts",counted.size(),"countedContractIdentities",counted,"signaturePageCheck","KEY_PAGE_SUBMISSION_ONLY","authenticityAssessed",false));
        if(lower.compareTo(max)>=0){proof.putAll(linked("score",max,"scoreProduced",true,"fullScore",true));return result(rule,"PASS","已逐合同核清主体、时间、金额口径及共同证明，按去重业绩达到原文评分上限",evidence,proof,List.of());}
        Set<String> checked=new HashSet<>();checks.forEach(c->checked.addAll(strings(c.get("contractNumbers"))));
        if(inventory.complete()&&checked.equals(inventory.numbers())&&checks.stream().allMatch(c->strings(c.get("missing")).isEmpty())){
            proof.putAll(linked("score",lower,"scoreUpper",lower,"scoreProduced",true,"fullScore",false,"exactSubmittedInventoryScore",true));inventory.sources().forEach(s->evidence.add(s.evidence()));
            return result(rule,"PASS","原文明确列示的完整申报清单已逐合同核完，已按实际合格业绩计算确定分值；未达到满分不作为资格否决",evidence,proof,List.of());
        }
        // A cached/model boolean contractSetComplete is not proof of a complete submitted list.
        return result(rule,"UNDETERMINED","已计算有完整证明的业绩分值下限；尚未证明全部申报合同及共同材料覆盖，不把未召回合同计为0分",evidence,proof,List.of(gap("PERFORMANCE_PROOF_COVERAGE_REQUIRED","逐合同缺口和已排除原因见 contractChecks；需闭合申报清单或继续核证至评分上限")));
    }
    private record Amount(BigDecimal value,String basis,List<String> missing,Map<String,Object> proof){}
    private static boolean uniqueCutoff(Decision proof,LocalDate expected){
        if(!(proof.proof().get("referenceDateEvidence") instanceof Map<?,?> source))return false;String quote=Objects.toString(source.get("quote"),"");
        Matcher labels=Pattern.compile("(?:投标截止|文件递交截止|递交截止|投标文件递交的截止|提交截止)(?:时间|日期)?").matcher(quote);Set<String> seen=new HashSet<>();Set<LocalDate> values=new HashSet<>();
        while(labels.find())if(seen.add(labels.group())){List<LocalDate> dates=cn.bidcheck.verification.source.DateFieldValues.read(quote,labels.group(),1);if(dates.size()!=1)return false;values.addAll(dates);}
        return values.equals(Set.of(expected));
    }
    private static Amount amount(Rule rule,List<Fact> facts,List<Source> sources,boolean epc,BigDecimal minimum){
        String requested=str(rule.parameters(),"performanceAmountBasis");List<Fact> suitable=facts.stream().filter(f->{String basis=str(f.attributes(),"amountBasis");return requested.equals("CONSTRUCTION_INSTALLATION")?basis.matches(".*(?:建安费|建筑安装工程费).*" ):epc?basis.matches(".*(?:施工部分|施工费用|建安费).*" ):basis.matches(".*(?:合同金额|签约金额|合同价款|合同总价).*" );}).filter(f->decimal(f.attributes().get("amount"))!=null).toList();
        Set<BigDecimal> values=new TreeSet<>();for(Fact f:suitable){BigDecimal value=RuleEvaluator.normalize(decimal(f.attributes().get("amount")),str(f.attributes(),"unit"));if(value!=null)values.add(value);}
        if(values.size()==1)return new Amount(values.iterator().next(),requested,List.of(),linked("method","SOURCE_BOUND_CONTRACT_AMOUNT","factIds",suitable.stream().map(Fact::id).toList()));
        if(values.size()>1)return new Amount(null,requested,List.of("同一合同金额存在不同取值，需核清版本"),Map.of());
        String all=sources.stream().map(s->s.evidence().quote()).reduce((a,b)->a+'\n'+b).orElse("");
        if(!all.contains("框架合同")||!all.matches("(?s).*(?:无签约金额|无固定金额|不约定合同金额|框架合同金额[:：]无).*"))return new Amount(null,requested,List.of("可核的"+(requested.equals("CONSTRUCTION_INSTALLATION")?"建安费":epc?"施工部分金额":"合同金额")),Map.of());
        String number=facts.stream().map(f->str(f.attributes(),"contractNumber")).filter(s->!s.isBlank()).findFirst().orElse("");if(number.isBlank())return new Amount(null,requested,List.of("无金额框架合同的唯一合同编号"),Map.of());
        Map<String,BigDecimal> orders=new LinkedHashMap<>();List<Map<String,Object>> rows=new ArrayList<>();boolean conflict=false,summary=false;
        for(Source source:sources){String text=source.evidence().quote();if(!text.contains(number)||text.matches("(?s).*(?:订单作废|订单取消|已取消订单|负数冲销).*"))continue;if(text.contains("订单汇总")||text.contains("订单列表"))summary=true;
            Matcher order=Pattern.compile("订单(?:编号|号)[:：\\s]*([A-Za-z0-9][A-Za-z0-9/-]{2,60})").matcher(text);Set<String> identifiers=new LinkedHashSet<>();while(order.find())identifiers.add(order.group(1));if(identifiers.size()!=1)continue;
            String label=requested.equals("CONSTRUCTION_INSTALLATION")?"(?:建安费|建筑安装工程费)":epc?"(?:施工部分金额|施工费用|建安费)":"(?:订单金额|订单价款|含税金额)";
            Matcher amount=Pattern.compile(label+"[:：\\s]*([0-9]+(?:[,.][0-9]+)*)(万元|元)").matcher(text);Set<BigDecimal> amounts=new TreeSet<>();while(amount.find()){BigDecimal value=RuleEvaluator.normalize(decimal(amount.group(1)),amount.group(2));if(value!=null)amounts.add(value);}
            if(amounts.size()!=1)continue;String id=identifiers.iterator().next();BigDecimal value=amounts.iterator().next(),old=orders.putIfAbsent(id,value);if(old!=null&&old.compareTo(value)!=0)conflict=true;
            rows.add(linked("orderId",id,"amount",value,"sourceId",source.id(),"parentContractNumber",number));
        }
        if(conflict||!summary||orders.isEmpty())return new Amount(null,requested,List.of(conflict?"同一订单金额冲突":!summary?"同一框架合同的订单汇总清单":"框架合同项下逐订单金额与归属"),linked("orders",rows));
        BigDecimal sum=orders.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        // A proven lower bound suffices for a minimum threshold; missing other nonnegative orders
        // must not be represented as an exact final contract amount.
        return sum.compareTo(minimum)>=0?new Amount(sum,requested,List.of(),linked("method","DEDUPLICATED_ORDER_LOWER_BOUND","exactContractTotal",false,"orders",rows))
                :new Amount(null,requested,List.of("已核订单合计尚未达到限额且订单全集未证明"),linked("orders",rows,"amountLower",sum));
    }
    static boolean valid(Fact f,RuleEvaluator e){return new ProofVerifier().issue(f,e.corpus.byId)==null&&DomainBindings.issue(f,e.corpus.byId,RuleEvaluator.maps(e.context.get("_validatedLayoutCells")))==null;}
    private static boolean boundSigningDate(String text,String date){Matcher m=Pattern.compile("(?:合同签订日期|签订日期|合同签订时间|签订时间|签署日期)[\\s:：|]*([^\\n;；|]{1,70})").matcher(text);while(m.find())if(datePresent(m.group(1),date))return true;return false;}
    private static String numberExact(String value){return java.text.Normalizer.normalize(clean(value),java.text.Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).replaceAll("[‐‑‒–—―−]","-");}
    private static String numberCandidate(String value){return numberExact(value).replaceAll("[^\\p{L}\\p{N}]","");}
    static String contractIdentity(Fact f){String number=numberExact(str(f.attributes(),"contractNumber")),name=clean(str(f.attributes(),"contractName"));return number.isBlank()&&name.isBlank()?"":identity(number.isBlank()?"name:"+name:"number:"+number);}
    private static Map<String,List<Fact>> contractGroups(List<Fact> facts){
        Map<String,List<Fact>> exact=new LinkedHashMap<>();for(Fact f:facts)if(!str(f.attributes(),"contractNumber").isBlank())exact.computeIfAbsent(contractIdentity(f),k->new ArrayList<>()).add(f);
        Map<String,List<String>> candidates=new LinkedHashMap<>();for(var entry:exact.entrySet()){String candidate=numberCandidate(str(entry.getValue().get(0).attributes(),"contractNumber"));if(!candidate.isBlank())candidates.computeIfAbsent(candidate,k->new ArrayList<>()).add(entry.getKey());}
        Map<String,List<Fact>> result=new LinkedHashMap<>(exact);
        for(var candidate:candidates.entrySet())if(candidate.getValue().size()>1){List<Fact> all=new ArrayList<>();candidate.getValue().forEach(key->all.addAll(exact.get(key)));Set<String> names=new HashSet<>(),dates=new HashSet<>();all.forEach(f->{names.add(clean(str(f.attributes(),"contractName")));dates.add(str(f.attributes(),"signedDate"));});
            // Punctuation removal identifies a duplicate candidate, not a proven contract.
            // A matching nonempty name AND signing date merge variants. Other collisions
            // stay separate and cannot score until their original identities are resolved.
            if(names.size()==1&&!names.contains("")&&dates.size()==1&&!dates.contains("")){candidate.getValue().forEach(result::remove);result.put(identity("number-variant:"+candidate.getKey()),all);}}
        Map<String,Set<String>> groupsByName=new HashMap<>();for(var entry:result.entrySet())entry.getValue().forEach(f->groupsByName.computeIfAbsent(clean(str(f.attributes(),"contractName")),k->new HashSet<>()).add(entry.getKey()));
        for(Fact f:facts)if(str(f.attributes(),"contractNumber").isBlank()){String name=clean(str(f.attributes(),"contractName"));Set<String> keys=groupsByName.getOrDefault(name,Set.of());if(keys.size()>1)continue;String key=keys.size()==1?keys.iterator().next():contractIdentity(f);if(!key.isBlank())result.computeIfAbsent(key,k->new ArrayList<>()).add(f);}return result;
    }
    private static Set<String> ambiguousNumberCandidates(Map<String,List<Fact>> groups){Map<String,Set<String>> keys=new HashMap<>();for(var entry:groups.entrySet())for(Fact f:entry.getValue()){String number=numberCandidate(str(f.attributes(),"contractNumber"));if(!number.isBlank())keys.computeIfAbsent(number,k->new HashSet<>()).add(entry.getKey());}Set<String> ambiguous=new HashSet<>();keys.forEach((number,identities)->{if(identities.size()>1)ambiguous.add(number);});return ambiguous;}
    private static final Pattern PARTY_LABEL=Pattern.compile("(?:乙方|承包人|承包方|施工单位|设计单位|成员单位|工作单位|任职单位)[（(]?(?:盖章)?[）)]?[:：\\s|]+([^\\s|，,。；;:：（）()]{2,100})");
    private static Set<String> labelledParties(String text){Set<String> result=new LinkedHashSet<>();Matcher matcher=PARTY_LABEL.matcher(text);while(matcher.find())result.add(matcher.group(1));return result;}
    private static List<Map<String,Object>> personalMembership(List<Source> sources,String person,String role,Set<String> contractParties){
        Pattern appointment=Pattern.compile(Pattern.quote(role)+"[:：\\s|]*"+Pattern.quote(person)+"(?=[\\s|，,。；;（）()]|$)");List<Map<String,Object>> proof=new ArrayList<>();
        for(Source source:sources){String text=source.evidence().quote();if(!appointment.matcher(text).find())continue;Set<String> local=labelledParties(text);List<String> lines=Arrays.asList(text.split("\\R",-1));
            for(int i=0;i<lines.size();i++){String line=lines.get(i);if(!appointment.matcher(line).find())continue;Set<String> bound=labelledParties(line);String quote=line;
                if(bound.isEmpty()&&i>0&&!lines.get(i-1).isBlank()){bound=labelledParties(lines.get(i-1));if(!bound.isEmpty())quote=lines.get(i-1)+"\n"+line;}
                if(bound.isEmpty()&&contractParties.size()==1&&local.equals(contractParties)){bound=local;quote=text;}
                String party=bound.size()==1?bound.iterator().next():"";proof.add(linked("sourceId",source.id(),"person",person,"personRole",role,"party",party,"quote",quote,"binding",party.isBlank()?"UNRESOLVED_PERSON_PARTY":"LOCAL_PERSON_PARTY"));}}
        return proof;
    }
    private static List<Map<String,Object>> workRole(List<Source> sources,String party,String type,boolean epc,Set<String> names,int partyCount){
        List<Map<String,Object>> proof=new ArrayList<>();String p=Pattern.quote(party),role=Pattern.quote(type);
        Pattern explicit=Pattern.compile("(?:^|[\\s|，,])(?:"+role+"(?:单位|方|承包人|部分)[:：\\s]*"+p+"(?=[\\s|，,。；;（）()]|$)|"+p+"\\s*(?:承担|负责)\\s*(?:本合同|本项目|本工程)?\\s*"+role+"(?:部分|工作|任务|工程)?(?:[\\s|，,。；;]|$)|(?:承包人|乙方)[:：\\s]*"+p+"[（(]"+role+"(?:单位|方|部分)[）)])");
        for(Source source:sources)for(String line:source.evidence().quote().split("[\\r\\n；;。]")){if(line.matches(".*(?:不负责|不承担|未承担|不得承担|不再承担|不是|并非)[^。；]{0,15}"+role+".*"))continue;Matcher found=explicit.matcher(line);if(found.find())proof.add(linked("sourceId",source.id(),"party",party,"workRole",type,"quote",line,"binding","EXPLICIT_LOCAL_PARTY_WORK_ROLE"));}
        if(!proof.isEmpty())return proof;
        // A single-party, non-EPC contract may bind its sole contractor to its explicit contract
        // type. EPC/multi-party contracts always need a local division-of-work statement.
        boolean opposite=sources.stream().anyMatch(s->Pattern.compile((type.equals("施工")?"设计":"施工")+"(?:单位|方|部分)[:：\\s]*"+p).matcher(s.evidence().quote()).find());
        if(!epc&&partyCount==1&&!opposite&&names.size()==1&&names.iterator().next().matches(".*"+role+"(?:项目|工程)?合同$"))for(Source source:sources){String text=source.evidence().quote();if(text.contains(names.iterator().next())&&Pattern.compile("(?:乙方|承包人)[:：\\s]*"+p+"(?=[\\s|，,。；;（）()]|$)").matcher(text).find())proof.add(linked("sourceId",source.id(),"party",party,"workRole",type,"quote",text,"binding","SOLE_CONTRACTOR_OF_EXPLICIT_SINGLE_WORK_CONTRACT"));}
        return proof;
    }
    private record Inventory(boolean complete,Set<String> numbers,List<Source> sources,Map<String,Object> proof){}
    private static Inventory inventory(Rule rule,String target,RuleEvaluator e){
        List<Source> sources=new ArrayList<>();Set<Set<String>> variants=new HashSet<>();
        for(Source source:e.corpus.all){String text=source.evidence().quote();if(source.index()||source.historical()||source.scopeContext().blocked()||!text.contains(target)||!text.contains("以上为本次申报全部业绩"))continue;
            String role=str(rule.parameters(),"personRole");if(!role.isBlank()&&!text.contains(role))continue;String form=str(rule.parameters(),"formReference");if(!form.isBlank()&&!text.contains(form))continue;
            Matcher total=Pattern.compile("本次申报(?:施工|项目负责人|设计负责人)?业绩共([0-9]{1,3})项").matcher(text);if(!total.find())continue;int declared=Integer.parseInt(total.group(1));if(total.find())continue;
            Matcher contract=Pattern.compile("合同(?:编号|号)[:：\\s]*([A-Za-z0-9][A-Za-z0-9/-]{2,60})").matcher(text);Set<String> numbers=new LinkedHashSet<>();while(contract.find())numbers.add(contract.group(1));if(numbers.size()!=declared)continue;
            variants.add(Set.copyOf(numbers));sources.add(source);
        }
        boolean complete=variants.size()==1;Set<String> numbers=complete?variants.iterator().next():Set.of();return new Inventory(complete,numbers,List.copyOf(sources),linked("complete",complete,"method","EXPLICIT_SOURCE_COMPLETE_DECLARATION","contractNumbers",numbers,"sourceIds",sources.stream().map(Source::id).toList()));
    }
    private static List<Source> contractSources(List<Fact> facts,RuleEvaluator e){Set<String> ids=new LinkedHashSet<>();facts.forEach(f->ids.addAll(f.sourceIds()));String number=facts.stream().map(f->str(f.attributes(),"contractNumber")).filter(v->!v.isBlank()).findFirst().orElse("");Set<String> names=new HashSet<>(),parties=new HashSet<>();facts.forEach(f->{names.add(str(f.attributes(),"contractName"));parties.add(str(f.attributes(),"party"));});
        // Additional key/ordering pages must explicitly name the same contract and party. Page
        // adjacency, project similarity and a common person are not contract linkage evidence.
        for(Source source:e.corpus.all)if(!source.index()&&!number.isBlank()&&source.evidence().quote().contains(number)&&parties.stream().filter(v->!v.isBlank()).anyMatch(source.evidence().quote()::contains))ids.add(source.id());
        return ids.stream().map(e.corpus.byId::get).filter(Objects::nonNull).filter(s->!s.index()).toList();}
    static Decision social(Rule rule,String person,List<Fact> appointments,RuleEvaluator e){
        Set<String> employers=new LinkedHashSet<>();for(Fact link:appointments)if(person.equals(SubjectIdentity.person(link))&&!str(link.attributes(),"employer").isBlank())employers.add(str(link.attributes(),"employer"));
        if(employers.size()!=1)return e.unknown(rule,"SOCIAL_EMPLOYER_REQUIRED","人员评分须确定本次任职单位，不借另一成员的社保",List.of());
        String employer=employers.iterator().next();List<Fact> social=e.facts.stream().filter(f->f.kind().equals("MONTHS")&&person.equals(str(f.attributes(),"person"))&&employer.equals(str(f.attributes(),"employer"))&&valid(f,e)&&appointments.stream().anyMatch(a->e.identities.samePerson(a,f))).toList();
        Map<String,Object> params=linked("count",rule.parameters().get("months"),"earliestMonth",rule.parameters().get("earliestMonth"),"employer",employer,"requiresReferenceDate",true,"referenceEvent","BID_DEADLINE");
        Rule requirement=new Rule(rule.id()+"-SOCIAL-"+identity(person).substring(0,8),"同人同单位连续社保","MONTHS",person,"socialInsurance","","","月",params,rule.sourceQuote(),"",List.of());
        return e.months(requirement,social);
    }
    static Decision result(Rule r,String status,String reason,List<Evidence> evidence,Map<String,Object> proof,List<Gap> gaps){LOG.info("bidcheck.source_score rule={} mode={} status={} scoreProduced={} sourceCount={} gapCodes={}",r.id(),str(r.parameters(),"sourceScoringMode"),status,proof.getOrDefault("scoreProduced",false),evidence.size(),gaps.stream().map(Gap::code).toList());return new Decision(r.id(),r.description(),status,reason,evidence.stream().distinct().toList(),gaps,proof);}
}
