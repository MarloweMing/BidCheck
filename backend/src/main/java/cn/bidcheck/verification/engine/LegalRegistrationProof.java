package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.verification.source.DateFieldValues;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Registration is proved by a registration document, never by an unrelated design certificate. */
final class LegalRegistrationProof {
    static final String VERSION="legal-registration-proof-3-bounded-document-and-criteria-input";
    private LegalRegistrationProof(){}
    private static boolean closed(String source){return clean(source).matches("(?:资格评审)?注册要求[:：]?具备履约能力的在中华人民共和国境内行政主管部门登记注册、根据中华人民共和国有关法律合法成立的独立法人或其他组织，经营范围未受限。须提交有效的营业执照或登记证书或其他有效证照。");}
    static Rule compile(String source){return closed(source)?new Rule("LEGAL-"+identity(source).substring(0,16),"登记证照、经营范围及履约能力","CERTIFICATE","投标人","registrationStatus","","","",linked("deterministicFamily","LEGAL_REGISTRATION","registrationProofVersion",VERSION),source,"",List.of()):null;}
    static boolean compilationComplete(Rule rule,String source){return applies(rule)&&VERSION.equals(rule.parameters().get("registrationProofVersion"))&&rule.children().isEmpty()&&rule.subject().equals("投标人")&&rule.sourceQuote().equals(source)&&closed(source);}
    static boolean applies(Rule r){return "LEGAL_REGISTRATION".equals(str(r.parameters(),"deterministicFamily"))&&"CERTIFICATE".equals(r.predicate());}
    static Decision evaluate(Rule r,RuleEvaluator e){
        if(!r.sourceQuote().matches("(?s).*(?:登记注册|依法设立|合法成立).*(?:法人|组织).*"))return e.unknown(r,"REGISTRATION_REQUIREMENT_SOURCE_REQUIRED","登记要求尚未核源",List.of());
        List<String> parties=DomainBindings.subjects(r.subject(),e.facts,e.corpus);
        if(parties.isEmpty())return e.unknown(r,"SUBJECT_RELATION_UNPROVEN","尚未将登记要求对应到本次投标的具体单位",List.of());
        List<Decision> children=new ArrayList<>();
        for(String party:parties){
            List<Registration> records=records(e.corpus,party);
            if(records.isEmpty()){children.add(e.unknown(r,"REGISTRATION_DOCUMENT_REQUIRED","需该投标主体的营业执照、登记证书或其他明确登记证明，资质证书不能替代",List.of(),Map.of("subject",party)));continue;}
            List<Decision> alternatives=new ArrayList<>();
            for(Registration record:records){
                String number=record.number();
                Decision date=validity(r,e,record);
                List<Fact> status=e.facts.stream().filter(f->party.equals(f.subject())&&"CURRENT_PROJECT".equals(f.scope()))
                    .filter(f->Set.of("FIELD","DECLARATION").contains(f.kind()))
                    .filter(f->Set.of("经营范围未受限","经营范围受限","吊销","注销").contains(f.value()))
                    .filter(f->new ProofVerifier().issue(f,e.corpus.byId)==null)
                    .filter(f->f.sourceIds().stream().map(e.corpus.byId::get).filter(Objects::nonNull).anyMatch(s->!s.index()&&!s.historical()&&!s.scopeContext().blocked()&&explicitStatus(s.evidence().quote(),party,f.value()))).toList();
                Decision scope;
                if(status.stream().anyMatch(f->!"经营范围未受限".equals(f.value())))scope=e.fail(r,"登记主体存在明确的经营限制或失效状态，需对照原文处理",status,Map.of("subject",party));
                else if(!r.sourceQuote().contains("经营范围未受限"))scope=new Decision(r.id(),"经营范围独立条件","NOT_APPLICABLE","本原子要求未另指定经营范围未受限证明",List.of(),List.of(),Map.of("sourceQuote",r.sourceQuote()));
                else if(status.isEmpty())scope=e.unknown(r,"REGISTRATION_OPERATING_SCOPE_REQUIRED","登记证照已定位，但经营范围未受限这一独立要求尚无对应证明",List.of(),Map.of("registration",record.audit()));
                else scope=e.pass(r,"本次登记主体的经营范围声明已有原文依据",status,Map.of("proofScope","DOCUMENT_DECLARATION_ONLY","subject",party));
                List<Decision> obligations=new ArrayList<>(List.of(date,scope));
                if(r.sourceQuote().contains("具备履约能力"))obligations.add(capabilityCriteria(r,e,party));
                alternatives.add(RuleEvaluator.combine(r.id()+":registration:"+identity(party+number).substring(0,12),"登记证照及经营范围","ALL",obligations));
            }
            children.add(RuleEvaluator.combine(r.id()+":party:"+identity(party).substring(0,12),"该主体的登记证照","ANY",alternatives));
        }
        Decision result=RuleEvaluator.combine(r.id(),r.description(),"ALL",children);Map<String,Object> proof=new LinkedHashMap<>(result.proof());proof.put("executor",VERSION);proof.put("doesNotProveLiveRegistryStatus",true);
        return new Decision(result.id(),result.description(),result.status(),result.reason(),result.evidences(),result.gaps(),proof);
    }
    /** This closed source has no measurable capacity criterion or authorized substitute declaration.
     * Keep an explicit input obligation, rather than claim an implemented check is missing bid material.
     * Any future declaration/resource route must first be bound to its actual acceptance requirement. */
    private static Decision capabilityCriteria(Rule parent,RuleEvaluator evaluator,String party){
        Rule input=new Rule(parent.id()+":capacity-criteria:"+identity(party).substring(0,12),"履约能力核验标准","INPUT",party,"capabilityCriteria","","确认履约能力核验指标及认可的证明方式","",Map.of("registrationProofVersion",VERSION),parent.sourceQuote(),"",List.of());
        Decision decision=evaluator.unknown(input,"CAPABILITY_CRITERIA_REQUIRED","原要求未明确履约能力的核验指标或认可的证明方式，需先确认检查标准；不表示投标人缺少材料",List.of(),linked("proofScope","CAPABILITY_STANDARD_NOT_SPECIFIED","obligationKind","INPUT","capabilityVerificationImplemented",false,"bidderDocumentRequired",false,"requiresRequirementConfirmation",true,"subject",party));
        evaluator.decisions.put(input.id(),decision);return decision;
    }
    record Registration(String party,String category,String number,String period,Evidence evidence,Map<String,Object> binding){Map<String,Object> audit(){return linked("party",party,"category",category,"numberField","统一社会信用代码","number",number,"period",period,"source",evidence,"sourceBinding",binding);}}
    /** The credit code belongs to one labelled registration document, not to a design qualification table. */
    static List<Registration> records(EvidenceCorpus corpus,String party){
        List<Registration> result=new ArrayList<>();Map<String,CertificateEvidenceRecords.ScopeResolver> resolvers=new HashMap<>();
        for(var document:corpus.documents)for(var page:document.pages()){
            String text=page.text();List<Source> sources=corpus.all.stream().filter(s->s.evidence().documentId().equals(document.id())&&s.evidence().page()==page.number()&&s.evidence().locationType().equals(page.locationType())).toList();
            if(sources.isEmpty()||sources.stream().anyMatch(s->!CertificateEvidenceRecords.usable(s,corpus,resolvers)))continue;
            if(text.matches("(?s).*(?:填写示例|空白模板|尚未取得|拟取得|历史项目营业执照).*"))continue;
            for(DocumentBlock block:blocks(text)){
            List<String> titles=new ArrayList<>();Set<String> owners=new LinkedHashSet<>(),codes=new LinkedHashSet<>(),periods=new LinkedHashSet<>();boolean codeNext=false;
            for(String raw:block.text().split("\\R")){
                String line=raw.replace("*","").replace("`","").replaceFirst("^\\s*#{1,6}\\s*","").strip();if(line.isBlank())continue;
                if(line.matches("(?:营业执照|事业单位法人证书|社会团体法人登记证书|民办非企业单位登记证书)(?:[（(]副本[）)])?"))titles.add(line.replaceAll("[（(].*$",""));
                String[] cells=line.startsWith("|")?line.split("\\|",-1):new String[0];
                if(cells.length==4){String label=clean(cells[1]),value=cells[2].strip();if(Set.of("名称","企业名称","单位名称").contains(label))owners.add(clean(value));if(label.equals("统一社会信用代码")&&value.matches("[0-9A-Z]{18}"))codes.add(value);if(Set.of("营业期限","有效期限","有效期").contains(label))periods.add(label+"："+value);}
                Matcher owner=Pattern.compile("^(?:名\\s*称|企业名称|单位名称)[：:\\s]+([^|]+)$").matcher(line);if(owner.matches())owners.add(clean(owner.group(1)));
                Matcher code=Pattern.compile("^统一社会信用代码[：:\\s]*([0-9A-Z]{18})$").matcher(line);if(code.matches())codes.add(code.group(1));
                if(codeNext&&line.matches("[0-9A-Z]{18}"))codes.add(line);codeNext=line.matches("统一社会信用代码[：:]?");
                if(line.matches("^(?:营业期限|有效期限|有效期)[：:\\s]+.+"))periods.add(line);
            }
            if(titles.size()!=1||!owners.equals(Set.of(clean(party)))||codes.size()!=1||periods.size()>1)continue;
            Evidence evidence=new Evidence(document.id(),document.name(),page.number(),page.locationType(),block.text(),"chars:"+block.start()+"-"+(block.start()+block.text().length()),1);
            result.add(new Registration(party,titles.get(0),codes.iterator().next(),periods.isEmpty()?"":periods.iterator().next(),evidence,linked("documentHash",document.sha256(),"documentVersion",document.version(),"sourceIds",sources.stream().map(Source::id).toList(),"scope","SINGLE_CURRENT_REGISTRATION_BLOCK","start",block.start(),"end",block.start()+block.text().length(),"parserVersion",VERSION)));
            }
        }return List.copyOf(result);
    }
    private record DocumentBlock(String text,int start){}
    /** Start at a real registration title and stop before another certificate or unscoped record.
     * A leading credit-code field is admitted only if it is the immediately preceding bounded field.
     * Horizontal rules can continue only into an explicit known subsection of this same document. */
    private static List<DocumentBlock> blocks(String original){
        record Line(String raw,String plain,int start){}List<Line> lines=new ArrayList<>();Matcher line=Pattern.compile("[^\\r\\n]*(?:\\r?\\n|$)").matcher(original);
        while(line.find()){String raw=line.group().replaceFirst("\\r?\\n$","");lines.add(new Line(raw,raw.replace("*","").replace("`","").replaceFirst("^\\s*#{1,6}\\s*","").strip(),line.start()));if(line.end()==original.length())break;}
        List<DocumentBlock> blocks=new ArrayList<>();String registration="(?:营业执照|事业单位法人证书|社会团体法人登记证书|民办非企业单位登记证书)(?:[（(]副本[）)])?";
        for(int i=0;i<lines.size();i++){
            if(!lines.get(i).plain().matches(registration))continue;int start=lines.get(i).start(),end=original.length();
            int before=i-1;while(before>=0&&lines.get(before).plain().isBlank())before--;if(before>=0&&lines.get(before).plain().matches("[0-9A-Z]{18}")){int label=before-1;while(label>=0&&lines.get(label).plain().isBlank())label--;if(label>=0&&lines.get(label).plain().matches("统一社会信用代码[：:]?")){boolean precedingCertificate=false;for(int prior=label-1;prior>=0;prior--){String p=lines.get(prior).plain();if(p.matches("[-_—=]{3,}"))break;if(p.matches(registration)||p.matches("[^：:|。；;]{1,50}(?:资质证书|资格证书|注册证书|许可证)")){precedingCertificate=true;break;}}if(!precedingCertificate)start=lines.get(label).start();}}
            for(int j=i+1;j<lines.size();j++){
                String next=lines.get(j).plain();
                if(next.matches("[^：:|。；;]{0,50}(?:证书|许可证|执照)(?:[（(]副本[）)])?")||next.matches("(?:第[一二三四五六七八九十0-9]+份|另[一份张]+|下一份|附件[一二三四五六七八九十0-9]+)[^。；;]{0,30}(?:证照|证件|材料|证明|记录).*")){
                    end=lines.get(j).start();int code=j-1;while(code>i&&lines.get(code).plain().isBlank())code--;if(code>i&&lines.get(code).plain().matches("[0-9A-Z]{18}")){int label=code-1;while(label>i&&lines.get(label).plain().isBlank())label--;if(label>i&&lines.get(label).plain().matches("统一社会信用代码[：:]?"))end=lines.get(label).start();}break;
                }
                if(next.matches("[-_—=]{3,}")){
                    int after=j+1;while(after<lines.size()&&lines.get(after).plain().isBlank())after++;
                    boolean subsection=after<lines.size()&&lines.get(after).raw().strip().matches("#{2,6}\\s*(?:基本信息|经营范围|登记机关|其他说明|统一社会信用代码)\\s*");
                    if(!subsection){end=lines.get(j).start();break;}
                }
            }
            blocks.add(new DocumentBlock(original.substring(start,end),start));
        }return blocks;
    }
    private static Decision validity(Rule rule,RuleEvaluator e,Registration record){
        Map<String,Object> proof=linked("proofScope","SAME_REGISTRATION_DOCUMENT_VALIDITY","registration",record.audit());
        if(!rule.sourceQuote().contains("有效"))return new Decision(rule.id()+":document",rule.description(),"PASS","已定位同主体原始登记证照，本原子要求未另指定有效期计算",List.of(record.evidence()),List.of(),proof);
        LocalDate reference=e.referenceDate(rule);if(reference==null)return e.unknown(rule,"REFERENCE_TIME_UNCONFIRMED","登记证照有效性需要已确认招标时点",List.of(),proof);
        String period=record.period(),label=period.startsWith("营业期限")?"营业期限":period.startsWith("有效期限")?"有效期限":"有效期";List<LocalDate> dates=DateFieldValues.read(period,label,2);proof.put("referenceDate",reference.toString());
        boolean longTerm=period.matches("^(?:营业期限|有效期限|有效期)[：:\\s]*(?:长期|永久)$");
        if(!longTerm&&(dates.size()!=2||dates.get(1).isBefore(dates.get(0))))return e.unknown(rule,"VALIDITY_RANGE_REQUIRED","同一登记证照未明示完整营业期限或长期有效，不用成立日期、登记日期代替",List.of(),proof);
        boolean valid=longTerm||!reference.isBefore(dates.get(0))&&!reference.isAfter(dates.get(1));proof.put("declaredLongTerm",longTerm);
        return new Decision(rule.id()+":validity",rule.description(),valid?"PASS":"FAIL",valid?"同一登记证照明示期限覆盖招标时点":"同一登记证照明示期限未覆盖招标时点",List.of(record.evidence()),List.of(),proof);
    }
    /** Negated cancellation and another company's status must not be read as this party's state. */
    static boolean explicitStatus(String source,String party,String value){
        String text=clean(source),prefix="(?<![\\p{IsHan}A-Za-z0-9])"+java.util.regex.Pattern.quote(clean(party))+"(?:的)?(?:登记状态|经营状态)?[：:]?";
        String assertion=switch(value){case "经营范围未受限"->"经营范围(?:未受限|不受限制)";case "经营范围受限"->"经营范围(?:受限|受到限制)";case "吊销"->"(?:已被|已)?吊销";case "注销"->"(?:已经|已)?注销";default->"(?!)";};
        return java.util.regex.Pattern.compile(prefix+assertion+"(?=[。；;，,]|$)").matcher(text).find();
    }
}
