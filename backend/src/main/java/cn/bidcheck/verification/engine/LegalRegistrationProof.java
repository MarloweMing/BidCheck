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
    static Rule compile(String source){return closed(source)?new Rule("LEGAL-"+identity(source).substring(0,16),"登记证照、经营范围及履约能力","CERTIFICATE","投标人","registrationStatus","","","",linked("deterministicFamily","LEGAL_REGISTRATION","registrationProofVersion",VERSION),source,"",cn.bidcheck.compat.Java8Api.listOf()):null;}
    static boolean compilationComplete(Rule rule,String source){return applies(rule)&&VERSION.equals(rule.parameters().get("registrationProofVersion"))&&rule.children().isEmpty()&&rule.subject().equals("投标人")&&rule.sourceQuote().equals(source)&&closed(source);}
    static boolean applies(Rule r){return "LEGAL_REGISTRATION".equals(str(r.parameters(),"deterministicFamily"))&&"CERTIFICATE".equals(r.predicate());}
    static Decision evaluate(Rule r,RuleEvaluator e){
        if(!r.sourceQuote().matches("(?s).*(?:登记注册|依法设立|合法成立).*(?:法人|组织).*"))return e.unknown(r,"REGISTRATION_REQUIREMENT_SOURCE_REQUIRED","登记要求尚未核源",cn.bidcheck.compat.Java8Api.listOf());
        List<String> parties=DomainBindings.subjects(r.subject(),e.facts,e.corpus);
        if(parties.isEmpty())return e.unknown(r,"SUBJECT_RELATION_UNPROVEN","尚未将登记要求对应到本次投标的具体单位",cn.bidcheck.compat.Java8Api.listOf());
        List<Decision> children=new ArrayList<>();
        for(String party:parties){
            List<Registration> records=records(e.corpus,party);
            if(records.isEmpty()){children.add(e.unknown(r,"REGISTRATION_DOCUMENT_REQUIRED","需该投标主体的营业执照、登记证书或其他明确登记证明，资质证书不能替代",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("subject", party)));continue;}
            List<Decision> alternatives=new ArrayList<>();
            for(Registration record:records){
                String number=record.number();
                Decision date=validity(r,e,record);
                List<Fact> status=cn.bidcheck.compat.Java8Api.toList(e.facts.stream().filter(f->party.equals(f.subject())&&"CURRENT_PROJECT".equals(f.scope()))
                    .filter(f->cn.bidcheck.compat.Java8Api.setOf("FIELD", "DECLARATION").contains(f.kind()))
                    .filter(f->cn.bidcheck.compat.Java8Api.setOf("经营范围未受限", "经营范围受限", "吊销", "注销").contains(f.value()))
                    .filter(f->new ProofVerifier().issue(f,e.corpus.byId)==null)
                    .filter(f->f.sourceIds().stream().map(e.corpus.byId::get).filter(Objects::nonNull).anyMatch(s->!s.index()&&!s.historical()&&!s.scopeContext().blocked()&&explicitStatus(s.evidence().quote(),party,f.value()))));
                Decision scope;
                if(status.stream().anyMatch(f->!"经营范围未受限".equals(f.value())))scope=e.fail(r,"登记主体存在明确的经营限制或失效状态，需对照原文处理",status,cn.bidcheck.compat.Java8Api.mapOf("subject", party));
                else if(!r.sourceQuote().contains("经营范围未受限"))scope=new Decision(r.id(),"经营范围独立条件","NOT_APPLICABLE","本原子要求未另指定经营范围未受限证明",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("sourceQuote", r.sourceQuote()));
                else if(status.isEmpty())scope=e.unknown(r,"REGISTRATION_OPERATING_SCOPE_REQUIRED","登记证照已定位，但经营范围未受限这一独立要求尚无对应证明",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.mapOf("registration", record.audit()));
                else scope=e.pass(r,"本次登记主体的经营范围声明已有原文依据",status,cn.bidcheck.compat.Java8Api.mapOf("proofScope", "DOCUMENT_DECLARATION_ONLY", "subject", party));
                List<Decision> obligations=new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(date, scope));
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
        Rule input=new Rule(parent.id()+":capacity-criteria:"+identity(party).substring(0,12),"履约能力核验标准","INPUT",party,"capabilityCriteria","","确认履约能力核验指标及认可的证明方式","",cn.bidcheck.compat.Java8Api.mapOf("registrationProofVersion", VERSION),parent.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
        Decision decision=evaluator.unknown(input,"CAPABILITY_CRITERIA_REQUIRED","原要求未明确履约能力的核验指标或认可的证明方式，需先确认检查标准；不表示投标人缺少材料",cn.bidcheck.compat.Java8Api.listOf(),linked("proofScope","CAPABILITY_STANDARD_NOT_SPECIFIED","obligationKind","INPUT","capabilityVerificationImplemented",false,"bidderDocumentRequired",false,"requiresRequirementConfirmation",true,"subject",party));
        evaluator.decisions.put(input.id(),decision);return decision;
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Registration{
        private final String party;
        private final String category;
        private final String number;
        private final String period;
        private final Evidence evidence;
        private final Map<String,Object> binding;
        @com.fasterxml.jackson.annotation.JsonCreator
        Registration(@com.fasterxml.jackson.annotation.JsonProperty("party") String party, @com.fasterxml.jackson.annotation.JsonProperty("category") String category, @com.fasterxml.jackson.annotation.JsonProperty("number") String number, @com.fasterxml.jackson.annotation.JsonProperty("period") String period, @com.fasterxml.jackson.annotation.JsonProperty("evidence") Evidence evidence, @com.fasterxml.jackson.annotation.JsonProperty("binding") Map<String,Object> binding) { this.party = party; this.category = category; this.number = number; this.period = period; this.evidence = evidence; this.binding = binding; }
        public String party() { return party; }
        public String category() { return category; }
        public String number() { return number; }
        public String period() { return period; }
        public Evidence evidence() { return evidence; }
        public Map<String,Object> binding() { return binding; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Registration that = (Registration) other; return java.util.Objects.equals(this.party, that.party) && java.util.Objects.equals(this.category, that.category) && java.util.Objects.equals(this.number, that.number) && java.util.Objects.equals(this.period, that.period) && java.util.Objects.equals(this.evidence, that.evidence) && java.util.Objects.equals(this.binding, that.binding); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.party); hash = 31 * hash + java.util.Objects.hashCode(this.category); hash = 31 * hash + java.util.Objects.hashCode(this.number); hash = 31 * hash + java.util.Objects.hashCode(this.period); hash = 31 * hash + java.util.Objects.hashCode(this.evidence); hash = 31 * hash + java.util.Objects.hashCode(this.binding); return hash; }
        @Override public String toString() { return "Registration[" + "party=" + party + ", category=" + category + ", number=" + number + ", period=" + period + ", evidence=" + evidence + ", binding=" + binding + "]"; }
Map<String,Object> audit(){return linked("party",party,"category",category,"numberField","统一社会信用代码","number",number,"period",period,"source",evidence,"sourceBinding",binding);}}
    /** The credit code belongs to one labelled registration document, not to a design qualification table. */
    static List<Registration> records(EvidenceCorpus corpus,String party){
        List<Registration> result=new ArrayList<>();Map<String,CertificateEvidenceRecords.ScopeResolver> resolvers=new HashMap<>();
        for(cn.bidcheck.domain.Domain.Document document:corpus.documents)for(cn.bidcheck.domain.Domain.Page page:document.pages()){
            String text=page.text();List<Source> sources=cn.bidcheck.compat.Java8Api.toList(corpus.all.stream().filter(s->s.evidence().documentId().equals(document.id())&&s.evidence().page()==page.number()&&s.evidence().locationType().equals(page.locationType())));
            if(sources.isEmpty()||sources.stream().anyMatch(s->!CertificateEvidenceRecords.usable(s,corpus,resolvers)))continue;
            if(text.matches("(?s).*(?:填写示例|空白模板|尚未取得|拟取得|历史项目营业执照).*"))continue;
            for(DocumentBlock block:blocks(text)){
            List<String> titles=new ArrayList<>();Set<String> owners=new LinkedHashSet<>(),codes=new LinkedHashSet<>(),periods=new LinkedHashSet<>();boolean codeNext=false;
            for(String raw:block.text().split("\\R")){
                String line=cn.bidcheck.compat.Java8Api.strip(raw.replace("*","").replace("`","").replaceFirst("^\\s*#{1,6}\\s*",""));if(cn.bidcheck.compat.Java8Api.isBlank(line))continue;
                if(line.matches("(?:营业执照|事业单位法人证书|社会团体法人登记证书|民办非企业单位登记证书)(?:[（(]副本[）)])?"))titles.add(line.replaceAll("[（(].*$",""));
                String[] cells=line.startsWith("|")?line.split("\\|",-1):new String[0];
                if(cells.length==4){String label=clean(cells[1]),value=cn.bidcheck.compat.Java8Api.strip(cells[2]);if(cn.bidcheck.compat.Java8Api.setOf("名称", "企业名称", "单位名称").contains(label))owners.add(clean(value));if(label.equals("统一社会信用代码")&&value.matches("[0-9A-Z]{18}"))codes.add(value);if(cn.bidcheck.compat.Java8Api.setOf("营业期限", "有效期限", "有效期").contains(label))periods.add(label+"："+value);}
                Matcher owner=Pattern.compile("^(?:名\\s*称|企业名称|单位名称)[：:\\s]+([^|]+)$").matcher(line);if(owner.matches())owners.add(clean(owner.group(1)));
                Matcher code=Pattern.compile("^统一社会信用代码[：:\\s]*([0-9A-Z]{18})$").matcher(line);if(code.matches())codes.add(code.group(1));
                if(codeNext&&line.matches("[0-9A-Z]{18}"))codes.add(line);codeNext=line.matches("统一社会信用代码[：:]?");
                if(line.matches("^(?:营业期限|有效期限|有效期)[：:\\s]+.+"))periods.add(line);
            }
            if(titles.size()!=1||!owners.equals(cn.bidcheck.compat.Java8Api.setOf(clean(party)))||codes.size()!=1||periods.size()>1)continue;
            Evidence evidence=new Evidence(document.id(),document.name(),page.number(),page.locationType(),block.text(),"chars:"+block.start()+"-"+(block.start()+block.text().length()),1);
            result.add(new Registration(party,titles.get(0),codes.iterator().next(),periods.isEmpty()?"":periods.iterator().next(),evidence,linked("documentHash",document.sha256(),"documentVersion",document.version(),"sourceIds",cn.bidcheck.compat.Java8Api.toList(sources.stream().map(Source::id)),"scope","SINGLE_CURRENT_REGISTRATION_BLOCK","start",block.start(),"end",block.start()+block.text().length(),"parserVersion",VERSION)));
            }
        }return cn.bidcheck.compat.Java8Api.listCopyOf(result);
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class DocumentBlock{
        private final String text;
        private final int start;
        @com.fasterxml.jackson.annotation.JsonCreator
        private DocumentBlock(@com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("start") int start) { this.text = text; this.start = start; }
        public String text() { return text; }
        public int start() { return start; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; DocumentBlock that = (DocumentBlock) other; return java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.start, that.start); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.start); return hash; }
        @Override public String toString() { return "DocumentBlock[" + "text=" + text + ", start=" + start + "]"; }
}
    /** Start at a real registration title and stop before another certificate or unscoped record.
     * A leading credit-code field is admitted only if it is the immediately preceding bounded field.
     * Horizontal rules can continue only into an explicit known subsection of this same document. */
    private static List<DocumentBlock> blocks(String original){
        @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    final class Line{
        private final String raw;
        private final String plain;
        private final int start;
        @com.fasterxml.jackson.annotation.JsonCreator
        Line(@com.fasterxml.jackson.annotation.JsonProperty("raw") String raw, @com.fasterxml.jackson.annotation.JsonProperty("plain") String plain, @com.fasterxml.jackson.annotation.JsonProperty("start") int start) { this.raw = raw; this.plain = plain; this.start = start; }
        public String raw() { return raw; }
        public String plain() { return plain; }
        public int start() { return start; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Line that = (Line) other; return java.util.Objects.equals(this.raw, that.raw) && java.util.Objects.equals(this.plain, that.plain) && java.util.Objects.equals(this.start, that.start); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.raw); hash = 31 * hash + java.util.Objects.hashCode(this.plain); hash = 31 * hash + java.util.Objects.hashCode(this.start); return hash; }
        @Override public String toString() { return "Line[" + "raw=" + raw + ", plain=" + plain + ", start=" + start + "]"; }
}List<Line> lines=new ArrayList<>();Matcher line=Pattern.compile("[^\\r\\n]*(?:\\r?\\n|$)").matcher(original);
        while(line.find()){String raw=line.group().replaceFirst("\\r?\\n$","");lines.add(new Line(raw,cn.bidcheck.compat.Java8Api.strip(raw.replace("*","").replace("`","").replaceFirst("^\\s*#{1,6}\\s*","")),line.start()));if(line.end()==original.length())break;}
        List<DocumentBlock> blocks=new ArrayList<>();String registration="(?:营业执照|事业单位法人证书|社会团体法人登记证书|民办非企业单位登记证书)(?:[（(]副本[）)])?";
        for(int i=0;i<lines.size();i++){
            if(!lines.get(i).plain().matches(registration))continue;int start=lines.get(i).start(),end=original.length();
            int before=i-1;while(before>=0&&cn.bidcheck.compat.Java8Api.isBlank(lines.get(before).plain()))before--;if(before>=0&&lines.get(before).plain().matches("[0-9A-Z]{18}")){int label=before-1;while(label>=0&&cn.bidcheck.compat.Java8Api.isBlank(lines.get(label).plain()))label--;if(label>=0&&lines.get(label).plain().matches("统一社会信用代码[：:]?")){boolean precedingCertificate=false;for(int prior=label-1;prior>=0;prior--){String p=lines.get(prior).plain();if(p.matches("[-_—=]{3,}"))break;if(p.matches(registration)||p.matches("[^：:|。；;]{1,50}(?:资质证书|资格证书|注册证书|许可证)")){precedingCertificate=true;break;}}if(!precedingCertificate)start=lines.get(label).start();}}
            for(int j=i+1;j<lines.size();j++){
                String next=lines.get(j).plain();
                if(next.matches("[^：:|。；;]{0,50}(?:证书|许可证|执照)(?:[（(]副本[）)])?")||next.matches("(?:第[一二三四五六七八九十0-9]+份|另[一份张]+|下一份|附件[一二三四五六七八九十0-9]+)[^。；;]{0,30}(?:证照|证件|材料|证明|记录).*")){
                    end=lines.get(j).start();int code=j-1;while(code>i&&cn.bidcheck.compat.Java8Api.isBlank(lines.get(code).plain()))code--;if(code>i&&lines.get(code).plain().matches("[0-9A-Z]{18}")){int label=code-1;while(label>i&&cn.bidcheck.compat.Java8Api.isBlank(lines.get(label).plain()))label--;if(label>i&&lines.get(label).plain().matches("统一社会信用代码[：:]?"))end=lines.get(label).start();}break;
                }
                if(next.matches("[-_—=]{3,}")){
                    int after=j+1;while(after<lines.size()&&cn.bidcheck.compat.Java8Api.isBlank(lines.get(after).plain()))after++;
                    boolean subsection=after<lines.size()&&cn.bidcheck.compat.Java8Api.strip(lines.get(after).raw()).matches("#{2,6}\\s*(?:基本信息|经营范围|登记机关|其他说明|统一社会信用代码)\\s*");
                    if(!subsection){end=lines.get(j).start();break;}
                }
            }
            blocks.add(new DocumentBlock(original.substring(start,end),start));
        }return blocks;
    }
    private static Decision validity(Rule rule,RuleEvaluator e,Registration record){
        Map<String,Object> proof=linked("proofScope","SAME_REGISTRATION_DOCUMENT_VALIDITY","registration",record.audit());
        if(!rule.sourceQuote().contains("有效"))return new Decision(rule.id()+":document",rule.description(),"PASS","已定位同主体原始登记证照，本原子要求未另指定有效期计算",cn.bidcheck.compat.Java8Api.listOf(record.evidence()),cn.bidcheck.compat.Java8Api.listOf(),proof);
        LocalDate reference=e.referenceDate(rule);if(reference==null)return e.unknown(rule,"REFERENCE_TIME_UNCONFIRMED","登记证照有效性需要已确认招标时点",cn.bidcheck.compat.Java8Api.listOf(),proof);
        String period=record.period(),label=period.startsWith("营业期限")?"营业期限":period.startsWith("有效期限")?"有效期限":"有效期";List<LocalDate> dates=DateFieldValues.read(period,label,2);proof.put("referenceDate",reference.toString());
        boolean longTerm=period.matches("^(?:营业期限|有效期限|有效期)[：:\\s]*(?:长期|永久)$");
        if(!longTerm&&(dates.size()!=2||dates.get(1).isBefore(dates.get(0))))return e.unknown(rule,"VALIDITY_RANGE_REQUIRED","同一登记证照未明示完整营业期限或长期有效，不用成立日期、登记日期代替",cn.bidcheck.compat.Java8Api.listOf(),proof);
        boolean valid=longTerm||!reference.isBefore(dates.get(0))&&!reference.isAfter(dates.get(1));proof.put("declaredLongTerm",longTerm);
        return new Decision(rule.id()+":validity",rule.description(),valid?"PASS":"FAIL",valid?"同一登记证照明示期限覆盖招标时点":"同一登记证照明示期限未覆盖招标时点",cn.bidcheck.compat.Java8Api.listOf(record.evidence()),cn.bidcheck.compat.Java8Api.listOf(),proof);
    }
    /** Negated cancellation and another company's status must not be read as this party's state. */
    static boolean explicitStatus(String source,String party,String value){
        String text=clean(source),prefix="(?<![\\p{IsHan}A-Za-z0-9])"+java.util.regex.Pattern.quote(clean(party))+"(?:的)?(?:登记状态|经营状态)?[：:]?";
        String assertion;
switch (value) {case "经营范围未受限":assertion = "经营范围(?:未受限|不受限制)"; break;case "经营范围受限":assertion = "经营范围(?:受限|受到限制)"; break;case "吊销":assertion = "(?:已被|已)?吊销"; break;case "注销":assertion = "(?:已经|已)?注销"; break;default:assertion = "(?!)"; break;}
        return java.util.regex.Pattern.compile(prefix+assertion+"(?=[。；;，,]|$)").matcher(text).find();
    }
}
