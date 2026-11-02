package cn.bidcheck.verification.engine;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.*;

/** Counts source-identified appointments, never certificate pages or model-declared list completeness. */
final class PersonnelCount {
    private PersonnelCount() {}
    static Decision evaluate(Rule r,RuleEvaluator e){
        if(!PersonnelRoles.personRole(r.subject()))return e.unknown(r,"PERSON_COUNT_ROLE_REQUIRED","人数条件须对应明确人员岗位，不能以公司、证件或模糊主体计人",List.of());
        Integer expected=integer(r.expected());boolean configuration=PersonCountRequirement.roleConfiguration(r);String operator=configuration?"ROLE_CONFIGURATION":operator(r.operator());
        if(expected==null||expected<0||configuration&&expected==0||!Set.of("人","名").contains(r.unit())||!configuration&&(operator.isBlank()||!operator.equals(PersonCountRequirement.sourceComparison(r))))
            return e.unknown(r,"PERSON_COUNT_COMPARISON_REQUIRED","人数要求尚未对应明确岗位配置或原文上下限；不把模型补出的比较词作为招标约束",List.of());
        if(Boolean.TRUE.equals(r.parameters().get("qualificationRequired"))||"QUALIFIED".equals(str(r.parameters(),"countBasis"))
            ||List.of("category","grade","profession").stream().anyMatch(k->known(str(r.parameters(),k)))
            ||r.field().matches("(?i).*(?:qualified|合格|持证).*")
            ||r.sourceQuote().matches("(?s).*(?:持证|合格)"+Pattern.quote(PersonnelRoles.canonical(r.subject()))+".*")
            ||r.sourceQuote().matches("(?s).*(?:具有|具备|持有|取得)[^，。；;\\n]{0,30}(?:证书|资格)[^，。；;\\n]{0,12}"+Pattern.quote(PersonnelRoles.canonical(r.subject()))+".*"))
            return e.unknown(r,"PERSON_COUNT_QUALIFICATION_UNPROVEN","任职人数不能代替合格或持证人数；须另行逐人证明该资格并建立身份关联",List.of());
        String employer=str(r.parameters(),"employer");List<String> employers=employer.isBlank()?List.of():DomainBindings.subjects(employer,e.facts,e.corpus);
        if(!employer.isBlank()&&employers.isEmpty())return e.unknown(r,"PERSON_COUNT_EMPLOYER_REQUIRED","人数要求的成员单位尚未由本项目原文对应到具体公司",List.of());
        List<Map<String,Object>> memberProof=List.of();
        if(DomainBindings.memberSelector(employer)){
            memberProof=DomainBindings.completeMemberSet(employer,DomainBindings.roleLinks(employer,e.facts,e.corpus),employers,e.corpus);
            if(memberProof.isEmpty())return e.unknown(r,"SUBJECT_SET_INCOMPLETE","逐成员人数要求尚未证明成员全集，不能仅按已找到的公司宣布全部满足",List.of());
        }
        if(employers.size()>1||DomainBindings.role(employer)){List<Decision> children=new ArrayList<>();for(String company:employers){
                Rule child=new Rule(r.id()+":"+identity(company).substring(0,12),r.description(),r.predicate(),r.subject(),r.field(),r.operator(),r.expected(),r.unit(),r.parameters(),r.sourceQuote(),r.logic(),List.of());children.add(count(child,e,company,expected,operator));}
            Decision combined=RuleEvaluator.combine(r.id(),r.description(),"ALL",children);Map<String,Object> proof=new LinkedHashMap<>(combined.proof());proof.put("memberSubjects",employers);if(!memberProof.isEmpty())proof.put("completeMemberSet",memberProof);
            List<cn.bidcheck.domain.Domain.Evidence> evidence=new ArrayList<>(combined.evidences());
            DomainBindings.roleLinks(employer,e.facts,e.corpus).stream().flatMap(f->f.sourceIds().stream()).map(e.corpus.byId::get).filter(Objects::nonNull).map(Source::evidence).forEach(evidence::add);
            return new Decision(combined.id(),combined.description(),combined.status(),combined.reason(),evidence.stream().distinct().toList(),combined.gaps(),proof);}
        return count(r,e,employers.isEmpty()?"":employers.get(0),expected,operator);
    }
    private static Decision count(Rule r,RuleEvaluator e,String employer,int expected,String operator){
        List<Fact> all=e.facts.stream().filter(f->f.kind().equals("PERSON_ROLE")&&f.scope().equals("CURRENT_PROJECT"))
            .filter(f->DomainBindings.currentRoleSource(f,e.corpus.byId)&&DomainBindings.roleMatches(r.subject(),f.subject(),f,e.corpus))
            .filter(f->!SubjectIdentity.person(f).isBlank()).toList();
        String lot=str(r.parameters(),"lot");
        Map<String,String> memberByFact=new HashMap<>();for(Fact f:all){String member=str(f.attributes(),"employer");if(!known(member)){Set<String> candidates=new LinkedHashSet<>();for(Fact sibling:all)if(known(str(sibling.attributes(),"employer"))&&e.identities.samePerson(f,sibling)&&new ProofVerifier().issue(sibling,e.corpus.byId)==null&&DomainBindings.issue(sibling,e.corpus.byId,List.of())==null)candidates.add(str(sibling.attributes(),"employer"));if(candidates.size()==1)member=candidates.iterator().next();}memberByFact.put(f.id(),member);}
        Set<String> members=new HashSet<>(),lots=new HashSet<>();all.forEach(f->{if(known(memberByFact.get(f.id())))members.add(memberByFact.get(f.id()));if(known(str(f.attributes(),"lot")))lots.add(DomainBindings.lot(str(f.attributes(),"lot")));});
        // A source-local single-slot appointment is an existential witness. Unrelated
        // candidates with unknown employer must not invalidate it. This path cannot add
        // members, establish a per-member quota, exact count, or cross-lot coverage.
        if(operator.equals("ROLE_CONFIGURATION")&&expected==1&&employer.isBlank()&&lot.isBlank()&&lots.isEmpty()
                &&!DomainBindings.employment(r.description()+r.sourceQuote())
                &&!r.sourceQuote().matches("(?s).*(?:各成员|每个成员|每一成员|各标段|每个标段).*")){
            List<Map<String,Object>> witness=existenceWitness(r,e,all);
            if(!witness.isEmpty()){
                // Existence avoids a cross-source identity join, but it does not authorize
                // ignoring contradictory strong identities already present for this role.
                IdentityCount candidates=identities(e,all);
                if(candidates.ambiguous())return e.unknown(r,"PERSON_COUNT_IDENTITY_AMBIGUOUS","同名任职材料出现不同身份，当前岗位对应人员未消歧，不能据此判人数足够或不足",all,
                        countProof(r,e,all,employer,lot,expected,operator,candidates,List.of()));
                String witnessId=str(witness.get(0),"factId");List<Fact> used=all.stream().filter(f->f.id().equals(witnessId)).toList();
                Map<String,Object> proof=countProof(r,e,used,employer,lot,expected,operator,identities(e,used),List.of());
                proof.putAll(linked("proofScope","ROLE_CONFIGURATION_ONLY","countBasis","SOURCE_LOCAL_CURRENT_APPOINTMENT_EXISTENCE","sourceLocalExistenceWitness",witness,
                        "provenExistenceLowerBound",1,"provenDistinctPeople",0,"populationComplete",false,"globalIdentityProven",false,"upperBoundProven",false,"exactCountProven",false,
                        "qualificationCountProven",false,"concurrentRolesProven",false,"ignoredUnrelatedCandidateCount",Math.max(0,all.size()-used.size())));
                return e.pass(r,"已由当前项目明确任职原文证明该岗位有1名人员配置；无关候选不改变此存在性证明，单位归属、资格及人数上限另核",used,
                        proof);
            }
        }
        if(employer.isBlank()&&(members.size()>1||!members.isEmpty()&&all.stream().anyMatch(f->!known(memberByFact.get(f.id())))))return e.unknown(r,"PERSON_COUNT_MEMBER_REQUIRED","同岗位涉及不同或未明确的成员单位，须明确本条人数按哪一成员计算，不能把成员人数相加",all);
        if(lot.isBlank()&&(lots.size()>1||!lots.isEmpty()&&all.stream().anyMatch(f->!known(str(f.attributes(),"lot")))))return e.unknown(r,"PERSON_COUNT_LOT_REQUIRED","同岗位涉及不同或未明确的标段，须明确本条人数适用标段，不能跨标段合计",all);
        List<Fact> selected=all.stream().filter(f->employer.isBlank()||employer.equals(memberByFact.get(f.id())))
            .filter(f->lot.isBlank()||DomainBindings.lot(lot).equals(DomainBindings.lot(str(f.attributes(),"lot")))).toList();
        // A declared employer on an appointment is a dimension, not evidence of an employment relationship.
        if(!employer.isBlank()&&DomainBindings.employment(r.description()+r.sourceQuote())){
            for(Fact f:selected)if(e.facts.stream().noneMatch(link->link.kind().equals("RELATION")&&link.scope().equals("CURRENT_PROJECT")
                &&DomainBindings.employment(link.field())&&employer.equals(str(link.attributes(),"object"))&&e.identities.samePerson(f,link)))
                return e.unknown(r,"EMPLOYMENT_RELATION_REQUIRED","已找到岗位人员，但人数要求涉及聘用单位，须独立证明聘用关系；联合体职责不替代劳动关系",selected);
        }
        IdentityCount identities=identities(e,selected);int unresolved=identities.unresolved();boolean sameNameAmbiguous=identities.ambiguous();
        List<Map<String,Object>> complete=completeLists(r,e,selected,employer,lot);
        boolean populationComplete=!complete.isEmpty()&&unresolved==0&&!sameNameAmbiguous;
        int observed=identities.distinct();
        Map<String,Object> proof=countProof(r,e,selected,employer,lot,expected,operator,identities,complete);proof.put("memberByFact",memberByFact);proof.put("memberReusePolicy","SOURCE_VALIDATED_SAME_PERSON_ONLY_NO_NEW_EMPLOYMENT_FACT");
        if(sameNameAmbiguous)return e.unknown(r,"PERSON_COUNT_IDENTITY_AMBIGUOUS","同名任职材料出现不同身份，当前岗位对应人员未消歧，不能据此判人数足够或不足",selected,proof);
        if(operator.equals("ROLE_CONFIGURATION")){
            proof.put("proofScope","ROLE_CONFIGURATION_ONLY");proof.put("upperBoundProven",false);proof.put("exactCountProven",false);proof.put("concurrentRolesProven",false);
            if(observed>=expected)return e.pass(r,"已由本项目任职材料和去重身份填满原文要求的岗位配置；未据此认定人数上限、人员资格或兼任限制满足",selected,proof);
            // Existence of one explicitly appointed person does not require joining identities
            // across pages. This witness never increases the distinct-person count and cannot
            // establish two people, exact counts, caps, qualifications or concurrent roles.
            List<Map<String,Object>> witness=expected==1?existenceWitness(r,e,selected):List.of();
            if(!witness.isEmpty()){
                proof.put("sourceLocalExistenceWitness",witness);proof.put("provenExistenceLowerBound",1);
                proof.put("countBasis","SOURCE_LOCAL_CURRENT_APPOINTMENT_EXISTENCE");proof.put("globalIdentityProven",false);
                return e.pass(r,"已由当前项目原文姓名与明确任职关系确认该岗位有1名人员配置；仅证明配置存在，不证明跨来源为同一人、总人数、人员资格或兼任限制",selected,proof);
            }
            if(populationComplete)return e.fail(r,"同范围完整名单与身份已核定，现有任职人员未填满原文要求的岗位配置",selected,proof);
            return e.unknown(r,unresolved>0?"PERSON_COUNT_IDENTITY_REQUIRED":"PERSON_SET_INCOMPLETE","该岗位尚未找到足够的、身份可去重的当前项目任职证明；未证明名单完整前不判定缺员",selected,proof);
        }
        Boolean satisfied=RuleEvaluator.compare(BigDecimal.valueOf(observed),BigDecimal.valueOf(expected),operator);
        boolean lower=Set.of(">=",">").contains(operator),upper=Set.of("<=","<").contains(operator);
        if(lower&&Boolean.TRUE.equals(satisfied))return e.pass(r,"已由本项目岗位原文和去重身份证明达到明确人数下限；不代表人员资格已全部合格",selected,proof);
        if((upper&&!Boolean.TRUE.equals(satisfied))||operator.equals("=")&&observed>expected)return e.fail(r,"已证明的不同人员数量超过原文明确人数上限",selected,proof);
        if(!populationComplete)return e.unknown(r,unresolved>0?"PERSON_COUNT_IDENTITY_REQUIRED":"PERSON_SET_INCOMPLETE",
            "目前仅有已核到人员，尚未证明同岗位、成员和标段的完整名单及全部身份；不能由部分材料判不足或恰好满足",selected,proof);
        return Boolean.TRUE.equals(satisfied)?e.pass(r,"同范围完整名单与人员身份已核定，去重人数符合原文约束；人员资格另行核验",selected,proof)
            :e.fail(r,"已证明同范围名单完整且身份可去重，人数不符合原文明示约束",selected,proof);
    }
    private record IdentityCount(int distinct,int unresolved,boolean ambiguous){}
    private static IdentityCount identities(RuleEvaluator evaluator,List<Fact> facts){
        Map<String,Set<String>> namesByIdentity=new LinkedHashMap<>(),identitiesByName=new LinkedHashMap<>();int unresolved=0;
        for(Fact fact:facts){String id=evaluator.identities.countIdentity(fact);if(id.isBlank()){unresolved++;continue;}
            namesByIdentity.computeIfAbsent(id,key->new LinkedHashSet<>()).add(fact.value());
            identitiesByName.computeIfAbsent(fact.value(),key->new LinkedHashSet<>()).add(id);
        }
        boolean ambiguous=identitiesByName.values().stream().anyMatch(ids->ids.size()>1)||namesByIdentity.values().stream().anyMatch(names->names.size()>1)
                ||evaluator.identities.bindings(facts).stream().anyMatch(binding->RuleEvaluator.maps(binding.get("identityClaims")).stream()
                    .filter(claim->"CN_ID".equals(str(claim,"field"))&&!str(claim,"valueFingerprint").isBlank())
                    .map(claim->str(claim,"valueFingerprint")).distinct().count()>1);
        return new IdentityCount(namesByIdentity.size(),unresolved,ambiguous);
    }
    private static Map<String,Object> countProof(Rule rule,RuleEvaluator evaluator,List<Fact> facts,String employer,String lot,int expected,String operator,IdentityCount identities,List<Map<String,Object>> complete){
        return linked("requestedRole",rule.subject(),"normalizedRole",PersonnelRoles.canonical(rule.subject()),"employer",employer,"lot",lot,
                "comparison",operator,"requiredCount",expected,"provenDistinctPeople",identities.distinct(),"appointmentOccurrences",facts.size(),"unresolvedIdentityOccurrences",identities.unresolved(),
                "populationComplete",!complete.isEmpty()&&identities.unresolved()==0&&!identities.ambiguous(),"countBasis","SOURCE_IDENTIFIED_CURRENT_APPOINTMENTS","identityBindings",evaluator.identities.bindings(facts),
                "roleBindings",facts.stream().map(f->linked("factId",f.id(),"originalRole",f.subject(),"normalizedRole",PersonnelRoles.canonical(f.subject()),"sourceIds",f.sourceIds())).toList(),
                "completeListEvidence",complete,"qualificationCountProven",false);
    }
    private static List<Map<String,Object>> existenceWitness(Rule rule,RuleEvaluator evaluator,List<Fact> facts){
        if(rule.sourceQuote().matches("(?s).*(?:不得兼任|不能兼任|禁止兼任|不得兼岗|必须不同人员|人员不得重复).*"))return List.of();
        Pattern names=Pattern.compile("姓\\s*名\\s*[:：]?\\s*([\\p{IsHan}·]{2,12})(?=\\s|[|<]|$)");
        for(Fact fact:facts){
            String person=SubjectIdentity.person(fact);
            if(!person.matches("[\\p{IsHan}·]{2,12}")||person.matches(".*(?:某|待定|待补|未知|未定|姓名|人员|填写).*"))continue;
            for(String id:fact.sourceIds()){
                Source source=evaluator.corpus.byId.get(id);
                if(source==null||!DocumentSubjectScope.appointment(source,fact))continue;
                for(String block:DocumentSubjectScope.localBlocks(source)){
                    String plain=block.replaceAll("(?i)<[^>]+>"," ");
                    if(plain.matches("(?s).*(?:示例|示范|参考|范本|模板|例如|候选|待确认|待定|暂定|未确定|未指定|拟更换|拟变更|不担任|未担任|不再担任|不得担任|不配备|未配备|没有配备|不设置|未设置|不是|并非|非本项目|非本次).*"))continue;
                    boolean confirmed=false;Matcher name=names.matcher(plain);
                    while(name.find())if(name.group(1).equals(person)){
                        int end=plain.length();Matcher next=names.matcher(plain);if(next.find(name.end()))end=next.start();
                        String record=plain.substring(name.start(),end);
                        if(record.matches("(?s).*拟在本合同任职\\s*[:：]?\\s*"+Pattern.quote(fact.subject())+"(?:\\s|[|<。；;]|$).*")){confirmed=true;break;}
                    }
                    if(!confirmed){
                        Pattern direct=Pattern.compile(Pattern.quote(fact.subject())+"\\s*[:：]\\s*"+Pattern.quote(person)+"(?=\\s|[，,。；;（）()|]|$)");
                        confirmed=Arrays.stream(plain.split("[。；;\\n]")).anyMatch(line->line.matches(".*(?:本项目|本工程|本次投标).*" )&&!line.matches(".*(?:或|/|二选一|择一).*" )&&direct.matcher(line).find());
                    }
                    if(confirmed)return List.of(linked("factId",fact.id(),"sourceId",source.id(),"role",fact.subject(),"personName",person,"sourceQuote",block,"confirmation","EXPLICIT_CURRENT_SINGLE_APPOINTMENT","identityScope","SOURCE_LOCAL","proves","EXISTENCE_ONLY"));
                }
            }
        }
        return List.of();
    }
    private static List<Map<String,Object>> completeLists(Rule r,RuleEvaluator e,List<Fact> facts,String employer,String lot){
        List<Map<String,Object>> proofs=new ArrayList<>();Set<String> selectedNames=new LinkedHashSet<>();facts.forEach(f->selectedNames.add(f.value()));
        String roles=PersonnelRoles.labels(r.subject()).stream().map(Pattern::quote).reduce((a,b)->a+"|"+b).orElse("");
        Pattern roster=Pattern.compile("^(?:本项目|本工程|本次投标)"+Pattern.quote(clean(employer))+Pattern.quote(clean(lot))+"(?:"+roles+")(?:完整名单|全部人员名单|全部名单)[:：]([^。；;\\n]{1,300})$");
        for(Source source:e.corpus.all){if(source.index()||source.historical())continue;
            for(String sentence:source.evidence().quote().split("[。；;\\n]")){
                String text=clean(sentence);if(!text.matches(".*(?:本项目|本工程|本次投标).*"))continue;
                if(text.matches(".*(?:示例|范本|参考|部分|暂定|拟变更|不完整|非本项目).*"))continue;
                if(!employer.isBlank()&&!text.contains(clean(employer))||!lot.isBlank()&&!text.contains(clean(lot)))continue;
                Matcher matcher=roster.matcher(text);while(matcher.find()){
                    List<String> named=Arrays.stream(matcher.group(1).split("[、,，]|以及|及|和|与")).map(String::strip).toList();
                    // Another full list or an unextracted listed person disproves completeness of the observed set.
                    if(named.isEmpty()||new HashSet<>(named).size()!=named.size()||!new HashSet<>(named).equals(selectedNames))return List.of();
                    // Each listed person must have a source-bound appointment in this same original block.
                    if(named.stream().anyMatch(name->facts.stream().noneMatch(f->f.value().equals(name)&&f.sourceIds().contains(source.id()))))continue;
                    proofs.add(linked("sourceId",source.id(),"evidences",List.of(source.evidence()),"confirmation","EXPLICIT_COMPLETE_ROLE_LIST","members",named));
                }
            }
        }
        return proofs;
    }
    private static String operator(String value){return switch(value){case ">=","GE","不少于","不低于","至少","≥"->">=";case "<=","LE","不超过","不得超过","至多","最多","不高于","≤"->"<=";case "=","==","EQ","恰好","正好","等于","仅","只","为"->"=";case ">","GT","超过","大于"->">";case "<","LT","少于","小于"->"<";default->"";};}
    private static Integer integer(String value){try{if(value.matches("[0-9]+"))return Integer.parseInt(value);if(value.matches("[零一二三四五六七八九十百两]+")){int total=0,current=0;for(char c:value.toCharArray()){if(c=='十'||c=='百'){total+=(current==0?1:current)*(c=='十'?10:100);current=0;}else current=c=='两'?2:"零一二三四五六七八九".indexOf(c);}return total+current;}}catch(NumberFormatException ignored){}return null;}
    private static boolean known(String value){return DomainBindings.known(value);}
}
