package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Local, typed two-document comparison. Every field is read again from immutable original text. */
final class CertificateSourceComparison {
    static final String VERSION="certificate-source-comparison-3-profession-date-scope";
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(CertificateSourceComparison.class);
    private static final String DATE="(?:19|20)[0-9]{2}[年./-][0-9]{1,2}[月./-][0-9]{1,2}日?";
    private static final Pattern CATEGORY=Pattern.compile("([一二壹贰]级注册建筑师|[一二壹贰]级注册建造师|注册监理工程师|注册造价工程师|安全生产考核合格证书)");
    private static final Pattern OWNER=Pattern.compile("(?:姓名[:：]?|持证人[:：])([\\p{IsHan}·*＊×●]{2,12}?)(?=性别|年龄|身份证|证件|出生|注册|证书|聘用|执业|[|│]|$)");
    private static final Pattern PROFILE_OWNER=Pattern.compile("(?:手机查看器?|人员详情|个人信息)[:：]?([\\p{IsHan}·]{2,12}?)(?=证件类型|证件号码|性别|注册证书)");
    private static final Pattern NUMBER=Pattern.compile("(电子证书编号|注册编号/执业印章号|执业印章号|证书编号|注册证号|注册编号|资格证号|证件号码|身份证号)[:：]?([A-Za-z0-9()（）-]{4,80})(?![A-Za-z0-9])");
    private static final Pattern EMPLOYER=Pattern.compile("(注册证书所在单位名称|注册单位|聘用单位)[:：]?([^:：|│]{2,100}?)(?=电子证书编号|注册编号|执业印章号|注册专业|注册有效期|有效期|执业注册信息|证书编号|签名|发证日期|$)");
    private static final Pattern PERIOD=Pattern.compile("(注册有效期|证书有效期|有效期限|有效期至|有效期)[:：]?("+DATE+")(?:[-至—~～]("+DATE+"))?");
    private static final Pattern QUERY_URL=Pattern.compile("(?i)https?://[^\\s<>]+|www\\.[^\\s<>]+");
    private CertificateSourceComparison() {}

    /** Proves a personnel query URL/screenshot only from the same source-bound certificate
     * and query record that passes the existing common-field comparison. */
    static Decision personnelQueryMaterial(Rule rule,RuleEvaluator evaluator){
        if(!"MATERIAL".equals(rule.predicate())||!cn.bidcheck.compat.Java8Api.setOf("查询网址","查询截图").contains(rule.expected())
            ||!"CERTIFICATE_PROOF_DOCUMENTS".equals(str(rule.parameters(),"deterministicFamily"))
            ||!(PersonnelRoles.personRole(rule.subject())||!cn.bidcheck.compat.Java8Api.isBlank(str(rule.parameters(),"personnelRoleSelector"))))return null;
        String requirement=str(rule.parameters(),"certificateRequirementQuote"),original=str(evaluator.context,"originalRequirement");
        if(cn.bidcheck.compat.Java8Api.isBlank(requirement)||!original.contains(requirement)||!requirement.contains(rule.sourceQuote()))
            return evaluator.unknown(rule,"REQUIREMENT_CREDENTIAL_SCHEMA_UNPROVEN","查询材料未绑定原条款中的具体人员证照要求",evaluator.match(rule));
        List<String> people=evaluator.resolvedSubjects(rule);
        if(people.size()!=1||DomainBindings.role(people.get(0)))return evaluator.unknown(rule,"SUBJECT_RELATION_UNPROVEN","查询材料尚未绑定唯一的本次拟派人员",evaluator.match(rule));
        List<Record> all=evaluator.personnelCredentialRecords(),certificates=cn.bidcheck.compat.Java8Api.toList(all.stream().filter(r->"CERTIFICATE".equals(r.side())&&ConfirmedSubjectBindings.recordMatches(people.get(0),r,evaluator)));
        Set<String> categories=requiredCategories(rule,certificates,evaluator.context);
        boolean submissionOnlyComparison=false;
        if(categories.isEmpty()&&requirement.contains("安全生产考核合格证")){
            categories=submissionComparisonCategories(people.get(0),certificates,all);
            submissionOnlyComparison=!categories.isEmpty();
        }
        if(categories.isEmpty())return evaluator.unknown(rule,"CERTIFICATE_QUERY_IDENTITY_REQUIRED","未从本人员原条款和原件记录解析唯一证照类别",evaluator.match(rule));
        Map<String,Object> comparisonParameters=new LinkedHashMap<>(rule.parameters());
        if(submissionOnlyComparison)comparisonParameters.put("submissionOnlyExactCredentialPair",Boolean.TRUE);
        Rule comparison=new Rule(rule.id()+"-same-certificate-comparison",rule.description(),"SAME_FIELD",rule.subject(),"证书信息","","","",comparisonParameters,rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
        Decision matched=evaluate(comparison,evaluator);
        if(!"PASS".equals(matched.status()))return evaluator.unknown(rule,"CERTIFICATE_QUERY_IDENTITY_REQUIRED","同一人员、同类证照及证号的原件与查询记录尚未通过现有一致性核验",evaluator.match(rule));
        List<Map<String,Object>> links=new ArrayList<>();List<Evidence> evidence=new ArrayList<>();
        for(String category:categories){
            List<Record> certs=cn.bidcheck.compat.Java8Api.toList(certificates.stream().filter(r->r.category().equals(category)));
            List<Record> queries=cn.bidcheck.compat.Java8Api.toList(all.stream().filter(r->"QUERY".equals(r.side())&&people.get(0).equals(r.owner())&&category.equals(r.category())));
            if(certs.size()!=1||queries.size()!=1)return evaluator.unknown(rule,"CERTIFICATE_QUERY_IDENTITY_REQUIRED","存在多份或缺少同一人员、同一类别的证书/查询记录，不能选取其中一项",evaluator.match(rule));
            Record certificate=certs.get(0),query=queries.get(0);String certNumber=number(certificate),queryNumber=number(query);
            if(cn.bidcheck.compat.Java8Api.isBlank(certNumber)||!certNumber.equals(queryNumber))return evaluator.unknown(rule,"CERTIFICATE_QUERY_IDENTITY_REQUIRED","证书原件与查询记录没有同一证书编号，不能仅凭同名关联",evaluator.match(rule));
            Set<String> pageKeys=new LinkedHashSet<>();for(Source source:query.sources())pageKeys.add(EvidenceCorpus.pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType()));
            List<String> matchingPages=new ArrayList<>();for(String key:pageKeys){cn.bidcheck.domain.Domain.Page page=evaluator.corpus.pages.get(key);if(page==null)continue;String text=Objects.toString(page.text(),"");boolean incompleteOtherScreenshot=false,pageHasTarget=false;
                List<QueryFragment> blocks=queryMaterialFragments(text);for(QueryFragment fragment:blocks){String block=fragment.text;
                    // URL, name and certificate number must share this screenshot fragment.
                    boolean hasTarget=targetQueryFragment(block,query.owner(),certNumber)
                        &&fieldInFragment(query,"NAME",key,fragment.start,fragment.end,evaluator.corpus)
                        &&numberFieldInFragment(query,key,fragment.start,fragment.end,evaluator.corpus);
                    if(hasScreenshotUrl(block)&&!hasCompleteIdentityRow(block)&&!hasTarget)incompleteOtherScreenshot=true;
                    if(hasTarget)pageHasTarget=true;
                }
                if(pageHasTarget&&!incompleteOtherScreenshot)matchingPages.add(key);
            }
            if(matchingPages.size()!=1)return evaluator.unknown(rule,"CERTIFICATE_QUERY_SOURCE_REQUIRED","同一证号的查询原文尚未明确包含查询网址及截图来源",evaluator.match(rule));
            links.add(linked("subject",people.get(0),"category",category,"certificateNumber",certNumber,"certificateRecordId",certificate.id(),"queryRecordId",query.id(),"querySourceIds",cn.bidcheck.compat.Java8Api.toList(query.sources().stream().map(Source::id)),"queryPageKey",matchingPages.get(0)));
            for(Source source:query.sources())evidence.add(source.evidence());
        }
        return new Decision(rule.id(),rule.description(),"PASS","已按本次人员、证照类别及同一证书编号核到查询网址/截图原文；仅证明材料提交",cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()),cn.bidcheck.compat.Java8Api.listOf(),
            linked("executor",VERSION,"proofScope","PERSON_SAME_CERTIFICATE_QUERY_SUBMISSION_ONLY","certificateBindings",links,"commonFieldComparison",matched.proof(),"qualificationVerified",false,"validityVerified",false,"registrationVerified",false));
    }

    private static Set<String> submissionComparisonCategories(
            String person, List<Record> certificates, List<Record> all) {
        Set<String> categories = new LinkedHashSet<>();
        certificates.stream()
            .filter(record -> record.category().startsWith("安全生产考核合格证书"))
            .forEach(record -> categories.add(record.category()));
        if (categories.size() != 1) {
            return Collections.emptySet();
        }

        String category = categories.iterator().next();
        List<Record> matchingCertificates = cn.bidcheck.compat.Java8Api.toList(
            certificates.stream().filter(record -> category.equals(record.category())));
        List<Record> matchingQueries = cn.bidcheck.compat.Java8Api.toList(
            all.stream().filter(record -> "QUERY".equals(record.side())
                && person.equals(record.owner())
                && category.equals(record.category())));
        if (matchingCertificates.size() != 1 || matchingQueries.size() != 1) {
            return Collections.emptySet();
        }

        Record certificate = matchingCertificates.get(0);
        Record query = matchingQueries.get(0);
        String certificateNumber = number(certificate);
        String queryNumber = number(query);
        if (cn.bidcheck.compat.Java8Api.isBlank(certificateNumber)
                || !certificateNumber.equals(queryNumber)
                || !certificate.issues().isEmpty()
                || !query.issues().isEmpty()) {
            return Collections.emptySet();
        }
        return categories;
    }
    static List<QueryFragment> queryMaterialFragments(String text) {
        List<Integer> starts = new ArrayList<>();
        String[] lines = text.split("(?<=\\n)", -1);
        int cursor = 0;
        int current = -1;
        boolean hasUrl = false;

        for (String chunk : lines) {
            String line = chunk.replaceFirst("[\\r\\n]+$", "");
            boolean header = isQueryHeaderLine(line.trim());
            boolean url = independentUrl(line);

            if (header) {
                if (current >= 0 && cursor > current) {
                    starts.add(cursor);
                } else if (current < 0) {
                    starts.add(cursor);
                }
                current = cursor;
                hasUrl = false;
            }

            if (url) {
                if (current < 0) {
                    current = cursor;
                    starts.add(cursor);
                    hasUrl = true;
                } else if (hasUrl) {
                    starts.add(cursor);
                    current = cursor;
                    hasUrl = true;
                } else {
                    hasUrl = true;
                }
            }
            cursor += chunk.length();
        }

        if (current < 0) {
            return Collections.emptyList();
        }
        if (starts.isEmpty()) {
            starts.add(current);
        }

        List<Integer> sortedStarts = new ArrayList<>(starts.stream()
            .distinct()
            .sorted()
            .collect(java.util.stream.Collectors.<Integer>toList()));
        List<QueryFragment> fragments = new ArrayList<>();
        for (int i = 0; i < sortedStarts.size(); i++) {
            int start = sortedStarts.get(i);
            int end = i + 1 < sortedStarts.size() ? sortedStarts.get(i + 1) : text.length();
            if (end > start) {
                fragments.add(new QueryFragment(start, end, text.substring(start, end)));
            }
        }
        return fragments;
    }

    static boolean targetQueryFragment(String block, String owner, String certificateNumber) {
        String url = independentQueryUrl(block);
        boolean hasScreenshot = block.matches("(?s).*(?:查询截图|网址查询截图|证书查询结果).*");
        if (url.isEmpty() || !hasScreenshot) {
            return false;
        }

        String normalized = java.text.Normalizer.normalize(block, java.text.Normalizer.Form.NFKC)
            .replaceAll("\\s+", "")
            .toUpperCase(Locale.ROOT);
        int urlOffset = normalized.indexOf(normalizeQueryToken(url));
        int personOffset = normalized.indexOf(normalizeQueryToken(owner));
        int numberOffset = normalized.indexOf(normalizeQueryToken(certificateNumber));
        return urlOffset >= 0 && personOffset > urlOffset && numberOffset > urlOffset;
    }

    private static String independentQueryUrl(String block) {
        List<String> urls = new ArrayList<>();
        for (String line : block.split("\\R")) {
            String url = independentQueryUrlLine(line);
            if (!url.isEmpty()) {
                urls.add(url);
            }
        }
        return urls.size() == 1 ? urls.get(0) : "";
    }

    private static String independentQueryUrlLine(String line) {
        String trimmed = line.trim();
        Matcher matcher = QUERY_URL.matcher(trimmed);
        if (!matcher.find()) {
            return "";
        }

        String url = matcher.group();
        String queryUrlField = "(?i)^(?:(?:本次|证书)?查询网址|网址|URL)\\s*[:：]\\s*"
            + Pattern.quote(url) + "$";
        if (trimmed.matches(QUERY_URL.pattern()) || trimmed.matches(queryUrlField)) {
            return url;
        }
        return "";
    }

    private static boolean hasScreenshotUrl(String block) {
        boolean hasScreenshot = block.matches("(?s).*(?:查询截图|网址查询截图|证书查询结果).*");
        return hasScreenshot && QUERY_URL.matcher(block).find();
    }

    private static boolean hasCompleteIdentityRow(String block) {
        Pattern name = Pattern.compile("姓名[:：]?[^\\s|│]{2,12}");
        Pattern number = Pattern.compile("(?:证书编号|证书号|注册编号|注册证号|证件号码|身份证号)[:：]?[^\\s|│]{4,80}");
        return name.matcher(block).find() && number.matcher(block).find();
    }

    private static boolean isQueryHeaderLine(String line) {
        return line.matches("(?i).*(?:证书查询网址|查询网址(?:及网址)?查询截图|网址查询截图|查询截图|证书查询结果截图).*");
    }

    private static boolean independentUrl(String line) {
        return !independentQueryUrlLine(line).isEmpty();
    }

    private static boolean fieldInFragment(Record record, String key, String pageKey, int start, int end,
            EvidenceCorpus corpus) {
        Field field = record.fields().get(key);
        return field != null && fieldRange(field, pageKey, start, end, corpus);
    }

    private static boolean numberFieldInFragment(Record record, String pageKey, int start, int end,
            EvidenceCorpus corpus) {
        Field field = record.fields().get("CERTIFICATE_NUMBER");
        if (field == null) {
            field = record.fields().get("REGISTRATION_NUMBER");
        }
        return field != null && fieldRange(field, pageKey, start, end, corpus);
    }

    private static boolean fieldRange(Field field, String pageKey, int start, int end, EvidenceCorpus corpus) {
        Source source = corpus.byId.get(field.sourceId());
        if (source == null || field.evidence() == null || field.evidence().anchor() == null) {
            return false;
        }

        Evidence evidence = source.evidence();
        String sourcePageKey = EvidenceCorpus.pageKey(
            evidence.documentId(), evidence.page(), evidence.locationType());
        if (!sourcePageKey.equals(pageKey)) {
            return false;
        }

        Matcher span = Pattern.compile("chars:(\\d+)-(\\d+)").matcher(field.evidence().anchor());
        if (!span.matches()) {
            return false;
        }
        int fieldStart = Integer.parseInt(span.group(1));
        int fieldEnd = Integer.parseInt(span.group(2));
        return fieldStart >= start && fieldEnd <= end;
    }

    static final class QueryFragment {
        final int start;
        final int end;
        final String text;

        QueryFragment(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private static String normalizeQueryToken(String value) {
        return java.text.Normalizer.normalize(Objects.toString(value, ""), java.text.Normalizer.Form.NFKC)
            .replaceAll("\\s+", "")
            .toUpperCase(Locale.ROOT);
    }
    static boolean provenPersonnelQueryMaterial(Rule rule,Decision decision,RuleEvaluator evaluator){
        if(!"PASS".equals(decision.status())||!decision.gaps().isEmpty())return false;
        Decision fresh=personnelQueryMaterial(rule,evaluator);
        return fresh!=null&&"PASS".equals(fresh.status())&&fresh.gaps().isEmpty()
            &&decision.evidences().containsAll(fresh.evidences())
            &&Objects.equals(fresh.proof().get("certificateBindings"),certificateBindings(decision.proof()));
    }
    private static List<Map<String,Object>> certificateBindings(Object value){
        if(value instanceof Map<?,?>){Map<?,?> map=(Map<?,?>)value;Object own=map.get("certificateBindings");if(own instanceof List<?>)return RuleEvaluator.maps(own);
            List<Map<String,Object>> nested=new ArrayList<>();for(Object child:map.values())nested.addAll(certificateBindings(child));return nested;}
        if(value instanceof List<?>){List<Map<String,Object>> nested=new ArrayList<>();for(Object child:(List<?>)value)nested.addAll(certificateBindings(child));return nested;}
        return Collections.emptyList();
    }
    private static String number(Record record){String value=record.value("CERTIFICATE_NUMBER");return cn.bidcheck.compat.Java8Api.isBlank(value)?record.value("REGISTRATION_NUMBER"):value;}

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Field {
        private final String key;
        private final String label;
        private final String raw;
        private final String value;
        private final String sourceId;
        private final Evidence evidence;
        @com.fasterxml.jackson.annotation.JsonCreator
        Field(@com.fasterxml.jackson.annotation.JsonProperty("key") String key, @com.fasterxml.jackson.annotation.JsonProperty("label") String label, @com.fasterxml.jackson.annotation.JsonProperty("raw") String raw, @com.fasterxml.jackson.annotation.JsonProperty("value") String value, @com.fasterxml.jackson.annotation.JsonProperty("sourceId") String sourceId, @com.fasterxml.jackson.annotation.JsonProperty("evidence") Evidence evidence) { this.key = key; this.label = label; this.raw = raw; this.value = value; this.sourceId = sourceId; this.evidence = evidence; }
        public String key() { return key; }
        public String label() { return label; }
        public String raw() { return raw; }
        public String value() { return value; }
        public String sourceId() { return sourceId; }
        public Evidence evidence() { return evidence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Field that = (Field) other; return java.util.Objects.equals(this.key, that.key) && java.util.Objects.equals(this.label, that.label) && java.util.Objects.equals(this.raw, that.raw) && java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.sourceId, that.sourceId) && java.util.Objects.equals(this.evidence, that.evidence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.key); hash = 31 * hash + java.util.Objects.hashCode(this.label); hash = 31 * hash + java.util.Objects.hashCode(this.raw); hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.sourceId); hash = 31 * hash + java.util.Objects.hashCode(this.evidence); return hash; }
        @Override public String toString() { return "Field[" + "key=" + key + ", label=" + label + ", raw=" + raw + ", value=" + value + ", sourceId=" + sourceId + ", evidence=" + evidence + "]"; }

        Map<String,Object> audit(){return linked("field",key,"label",label,"rawValue",raw,"value",value,"sourceId",sourceId,"source",evidence);}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Record {
        private final String id;
        private final String side;
        private final String category;
        private final String owner;
        private final List<Source> sources;
        private final Map<String,Field> fields;
        private final List<String> issues;
        @com.fasterxml.jackson.annotation.JsonCreator
        Record(@com.fasterxml.jackson.annotation.JsonProperty("id") String id, @com.fasterxml.jackson.annotation.JsonProperty("side") String side, @com.fasterxml.jackson.annotation.JsonProperty("category") String category, @com.fasterxml.jackson.annotation.JsonProperty("owner") String owner, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<Source> sources, @com.fasterxml.jackson.annotation.JsonProperty("fields") Map<String,Field> fields, @com.fasterxml.jackson.annotation.JsonProperty("issues") List<String> issues) { this.id = id; this.side = side; this.category = category; this.owner = owner; this.sources = sources; this.fields = fields; this.issues = issues; }
        public String id() { return id; }
        public String side() { return side; }
        public String category() { return category; }
        public String owner() { return owner; }
        public List<Source> sources() { return sources; }
        public Map<String,Field> fields() { return fields; }
        public List<String> issues() { return issues; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Record that = (Record) other; return java.util.Objects.equals(this.id, that.id) && java.util.Objects.equals(this.side, that.side) && java.util.Objects.equals(this.category, that.category) && java.util.Objects.equals(this.owner, that.owner) && java.util.Objects.equals(this.sources, that.sources) && java.util.Objects.equals(this.fields, that.fields) && java.util.Objects.equals(this.issues, that.issues); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.id); hash = 31 * hash + java.util.Objects.hashCode(this.side); hash = 31 * hash + java.util.Objects.hashCode(this.category); hash = 31 * hash + java.util.Objects.hashCode(this.owner); hash = 31 * hash + java.util.Objects.hashCode(this.sources); hash = 31 * hash + java.util.Objects.hashCode(this.fields); hash = 31 * hash + java.util.Objects.hashCode(this.issues); return hash; }
        @Override public String toString() { return "Record[" + "id=" + id + ", side=" + side + ", category=" + category + ", owner=" + owner + ", sources=" + sources + ", fields=" + fields + ", issues=" + issues + "]"; }

        String value(String key){Field f=fields.get(key);return f==null?"":f.value();}
        Map<String,Object> audit(){return linked("recordId",id,"side",side,"category",category,"subject",owner,"sourceIds",cn.bidcheck.compat.Java8Api.toList(sources.stream().map(Source::id)),"fields",cn.bidcheck.compat.Java8Api.toList(fields.values().stream().map(Field::audit)),"issues",issues);}
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Compact {
        private final String text;
        private final int[] offsets;
        private final String original;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Compact(@com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("offsets") int[] offsets, @com.fasterxml.jackson.annotation.JsonProperty("original") String original) { this.text = text; this.offsets = offsets; this.original = original; }
        public String text() { return text; }
        public int[] offsets() { return offsets; }
        public String original() { return original; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Compact that = (Compact) other; return java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.offsets, that.offsets) && java.util.Objects.equals(this.original, that.original); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.offsets); hash = 31 * hash + java.util.Objects.hashCode(this.original); return hash; }
        @Override public String toString() { return "Compact[" + "text=" + text + ", offsets=" + offsets + ", original=" + original + "]"; }

        static Compact of(String text){StringBuilder compact=new StringBuilder();int[] temp=new int[text.length()];int count=0;for(int i=0;i<text.length();i++)if(!Character.isWhitespace(text.charAt(i))){temp[count++]=i;compact.append(text.charAt(i));}return new Compact(compact.toString(),Arrays.copyOf(temp,count),text);}
    }

    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        String target=SameFieldComparisonTarget.reference(rule);
        // The source-reference recognizer also handles money, project names and arbitrary originals.
        // Only certificate/query information has an executable schema here.
        if(!rule.sourceQuote().matches("(?s).*(?:查询截图|查询结果).*" )||!target.matches(".*(?:证明文件|证书|证照|注册信息|登记信息).*"))return SameFieldComparisonTarget.unresolved(rule,evaluator.match(rule),evaluator);
        List<String> names=evaluator.resolvedSubjects(rule);
        if(names.isEmpty()||names.stream().anyMatch(DomainBindings::role))return SameFieldComparisonTarget.unresolved(rule,evaluator.match(rule),evaluator);
        List<Record> records=records(evaluator.corpus);List<Decision> decisions=new ArrayList<>();
        for(String name:names)decisions.add(comparePerson(rule,name,records,evaluator));
        if(decisions.size()==1)return decisions.get(0);
        return RuleEvaluator.combine(rule.id(),rule.description(),"ALL",decisions);
    }

    private static Decision comparePerson(Rule rule,String name,List<Record> records,RuleEvaluator evaluator){
        List<Fact> roles=cn.bidcheck.compat.Java8Api.toList(evaluator.facts.stream().filter(f->cn.bidcheck.compat.Java8Api.setOf("PERSON_ROLE", "RELATION").contains(f.kind())&&"CURRENT_PROJECT".equals(f.scope())&&DomainBindings.currentRoleSource(f,evaluator.corpus.byId))
            .filter(f->name.equals(f.kind().equals("PERSON_ROLE")?f.value():DomainBindings.roleTarget(f,DomainBindings.sourceRole(f))))
            .filter(f->new ProofVerifier().issue(f,evaluator.corpus.byId)==null&&DomainBindings.issue(f,evaluator.corpus.byId,cn.bidcheck.compat.Java8Api.listOf())==null));
        List<Record> candidates=cn.bidcheck.compat.Java8Api.toList(records.stream().filter(r->ConfirmedSubjectBindings.recordMatches(name,r,evaluator)));
        if(candidates.isEmpty())return SameFieldComparisonTarget.unresolved(rule,evaluator.match(rule),evaluator);
        if(roles.isEmpty())return pending(rule,evaluator,"SUBJECT_RELATION_UNPROVEN","已发现同名证书候选，但尚无已核源的本次岗位任职，不能以同名材料替代当前人员",roles,candidates,cn.bidcheck.compat.Java8Api.mapOf());
        SubjectIdentity.Problem identity=evaluator.identities.issue(new Rule(rule.id(),rule.description(),"CERTIFICATE",identitySubject(rule),rule.field(),rule.operator(),rule.expected(),rule.unit(),rule.parameters(),rule.sourceQuote(),rule.logic(),cn.bidcheck.compat.Java8Api.listOf()),cn.bidcheck.compat.Java8Api.listOf());
        Set<String> categories=requiredCategories(rule,candidates,evaluator.context);
        boolean submissionOnlyComparison=false;
        if(categories.isEmpty()){
            String requirement=clean(requirementQuote(rule,evaluator.context));
            if(requirement.contains("安全生产考核合格证")
                &&clean(rule.sourceQuote()).matches(".*(?:查询截图|查询结果).*(?:信息|内容).*一致.*")){
                categories=submissionComparisonCategories(name,
                    cn.bidcheck.compat.Java8Api.toList(candidates.stream().filter(r->"CERTIFICATE".equals(r.side()))),
                    candidates);
                submissionOnlyComparison=!categories.isEmpty();
            }
        }
        if(identity!=null)return pending(rule,evaluator,identity.code(),identity.reason(),roles,candidates,cn.bidcheck.compat.Java8Api.mapOf());
        if(categories.isEmpty())return pending(rule,evaluator,"REQUIREMENT_COMPARISON_TARGET_UNRESOLVED","原条款要求的证照类型尚未与两端记录对应，不以另一类现有证照缩小检查范围",roles,candidates,cn.bidcheck.compat.Java8Api.mapOf());
        if(submissionOnlyComparison){
            Set<String> exactCategories=categories;
            List<Record> certificates=cn.bidcheck.compat.Java8Api.toList(candidates.stream().filter(r->"CERTIFICATE".equals(r.side())&&exactCategories.contains(r.category())));
            for(Record certificate:certificates){
                Fact selected=ConfirmedSubjectBindings.recordIdentity(certificate);
                if(selected==null||roles.stream().noneMatch(role->evaluator.identities.samePerson(role,selected)))
                    return pending(rule,evaluator,"ENTITY_IDENTITY_AMBIGUOUS","本次岗位人员与所引安全证原件的身份信息未能确认一致，不能将共同材料比对当作资格或身份确认",roles,candidates,cn.bidcheck.compat.Java8Api.mapOf());
            }
        }
        List<Decision> compared=new ArrayList<>();for(String category:categories)compared.add(compareCategory(rule,name,category,cn.bidcheck.compat.Java8Api.toList(candidates.stream().filter(r->r.category().equals(category))),roles,evaluator));
        return compared.size()==1?compared.get(0):RuleEvaluator.combine(rule.id(),rule.description(),"ALL",compared);
    }

    private static Decision compareCategory(Rule rule,String name,String category,List<Record> candidates,List<Fact> roles,RuleEvaluator evaluator){
        List<Record> left=cn.bidcheck.compat.Java8Api.toList(candidates.stream().filter(r->r.side().equals("CERTIFICATE"))),right=cn.bidcheck.compat.Java8Api.toList(candidates.stream().filter(r->r.side().equals("QUERY")));
        if(left.size()!=1||right.size()!=1)return pending(rule,evaluator,"COMPARISON_SOURCE_REQUIRED","需要本人员同类证书与查询记录各一个明确版本；缺少一端或存在多版时不选择性比较",roles,candidates,linked("certificateRecords",left.size(),"queryRecords",right.size()));
        Record certificate=left.get(0),query=right.get(0);
        if(!certificate.issues().isEmpty()||!query.issues().isEmpty())return pending(rule,evaluator,"COMPARISON_FIELD_MAPPING_REQUIRED","同一记录内字段缺少明确边界或出现多个取值，不能取第一个值作比较",roles,candidates,cn.bidcheck.compat.Java8Api.mapOf());
        Field selectedNumber=certificate.fields().get("CERTIFICATE_NUMBER");if(selectedNumber==null)selectedNumber=certificate.fields().get("REGISTRATION_NUMBER");
        if(selectedNumber==null)return pending(rule,evaluator,"COMPARISON_RECORD_IDENTITY_UNPROVEN","证书一端没有明确类型的编号，不能只凭姓名关联查询记录",roles,candidates,cn.bidcheck.compat.Java8Api.mapOf());
        Fact selected=ConfirmedSubjectBindings.recordIdentity(certificate);
        Rule identityRule=new Rule(rule.id(),rule.description(),"CERTIFICATE",identitySubject(rule),rule.field(),rule.operator(),rule.expected(),rule.unit(),rule.parameters(),rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
        SubjectIdentity.Problem roleIdentity=evaluator.identities.issue(identityRule,cn.bidcheck.compat.Java8Api.listOf(selected));
        if(roleIdentity!=null)return pending(rule,evaluator,roleIdentity.code(),roleIdentity.reason(),roles,candidates,cn.bidcheck.compat.Java8Api.mapOf("comparisonIdentityScope", "CURRENT_ROLE_TO_SELECTED_CERTIFICATE"));
        String certNumber=certificate.value("CERTIFICATE_NUMBER"),queryNumber=query.value("CERTIFICATE_NUMBER");
        boolean typedNumber=!cn.bidcheck.compat.Java8Api.isBlank(certNumber)&&certNumber.equals(queryNumber);
        boolean typedRegistration=!cn.bidcheck.compat.Java8Api.isBlank(certificate.value("REGISTRATION_NUMBER"))&&certificate.value("REGISTRATION_NUMBER").equals(query.value("REGISTRATION_NUMBER"));
        boolean personId=!cn.bidcheck.compat.Java8Api.isBlank(certificate.value("PERSON_ID"))&&certificate.value("PERSON_ID").equals(query.value("PERSON_ID"));
        if(!cn.bidcheck.compat.Java8Api.isBlank(certificate.value("PERSON_ID"))&&!cn.bidcheck.compat.Java8Api.isBlank(query.value("PERSON_ID"))&&!personId)return pending(rule,evaluator,"SUBJECT_IDENTITY_CONFLICT","同名证照与查询记录存在不同个人证件号码，不能合并为同一人",roles,candidates,cn.bidcheck.compat.Java8Api.mapOf());
        if(!typedNumber&&!typedRegistration&&!personId)return pending(rule,evaluator,"COMPARISON_RECORD_IDENTITY_UNPROVEN","同名同类证照尚未通过同类型证书编号、注册编号或完整个人证件号建立对应关系；执业印章号不作为证书编号",roles,candidates,cn.bidcheck.compat.Java8Api.mapOf());
        Set<String> requiredFields=requiredFields(rule);
        boolean generic=clean(rule.sourceQuote()).matches(".*(?:查询截图|查询结果).*(?:信息|内容).*一致.*");
        if(generic&&typedRegistration&&cn.bidcheck.compat.Java8Api.isBlank(certNumber)&&cn.bidcheck.compat.Java8Api.isBlank(queryNumber)){requiredFields.remove("CERTIFICATE_NUMBER");requiredFields.add("REGISTRATION_NUMBER");}
        if(clean(rule.sourceQuote()).matches(".*(?:查询截图|查询结果).*(?:信息|内容).*一致.*"))for(String field:cn.bidcheck.compat.Java8Api.listOf("PERSON_ID", "GENDER", "BIRTH_DATE", "PROFESSION", "VALIDITY_START"))if(certificate.fields().containsKey(field)&&query.fields().containsKey(field))requiredFields.add(field);
        List<Map<String,Object>> fieldResults=new ArrayList<>();boolean mismatch=false,missing=false;
        String requiredProfession=requiredProfession(rule,evaluator.context);
        for(String field:requiredFields){
            Field a=certificate.fields().get(field),b=query.fields().get(field);Map<String,Object> dateScope=cn.bidcheck.compat.Java8Api.mapOf();
            if(cn.bidcheck.compat.Java8Api.setOf("VALIDITY_START", "VALIDITY_END").contains(field)){
                DatePair pair=datePair(field,certificate,query,requiredProfession);a=pair.certificate();b=pair.query();dateScope=pair.audit();
                LOG.info("bidcheck.certificate_comparison_date ruleId={} field={} profession={} certificateRecord={} queryRecord={} certificateField={} queryField={} resolution={}",rule.id(),field,requiredProfession,certificate.id(),query.id(),a==null?"":a.key(),b==null?"":b.key(),str(dateScope,"resolution"));
            }
            Map<String,Object> confirmation=cn.bidcheck.compat.Java8Api.mapOf();if(field.equals("NAME")&&a!=null&&b!=null&&!a.value().equals(b.value())){Fact queried=ConfirmedSubjectBindings.recordIdentity(query);if(queried!=null)confirmation=ConfirmedSubjectBindings.edge(name,queried,evaluator.facts,evaluator.corpus);if(confirmation.isEmpty())confirmation=ConfirmedSubjectBindings.edge(name,selected,evaluator.facts,evaluator.corpus);}
            boolean sameValue=a!=null&&b!=null&&equivalentFieldValue(category,field,a.value(),b.value());
            String status=a==null||b==null?"UNDETERMINED":sameValue||!confirmation.isEmpty()?"PASS":"FAIL";
            missing|=status.equals("UNDETERMINED");mismatch|=status.equals("FAIL");
            Map<String,Object> compared=linked("field",field,"status",status,"certificate",a==null?cn.bidcheck.compat.Java8Api.mapOf():a.audit(),"query",b==null?cn.bidcheck.compat.Java8Api.mapOf():b.audit(),"identityConfirmation",confirmation);
            if(sameValue&&!a.value().equals(b.value()))compared.put("normalization","REGISTERED_BUILDER_BUILDING_PROFESSION_ALIAS");
            if(!dateScope.isEmpty())compared.put("dateScope",dateScope);fieldResults.add(compared);
        }
        Map<String,Object> proof=proof(rule,roles,candidates);proof.putAll(linked("subject",name,"category",category,"requiredFields",requiredFields,"fieldComparisons",fieldResults,"recordBinding",typedNumber?"CURRENT_APPOINTMENT_AND_NAME_CATEGORY_TYPED_CERTIFICATE_NUMBER":typedRegistration?"CURRENT_APPOINTMENT_AND_NAME_CATEGORY_TYPED_REGISTRATION_NUMBER":"CURRENT_APPOINTMENT_AND_NAME_CATEGORY_PERSON_ID","comparisonComplete",!missing,"proofScope","REFERENCED_CERTIFICATE_FIELDS","comparedRecordCount",2,"doesNotProveCurrentOnlineStatus",true,"identityBindings",evaluator.identities.bindings(cn.bidcheck.compat.Java8Api.listOf(selected))));
        List<Evidence> evidence=evidences(roles,candidates,evaluator);String status=mismatch?"FAIL":missing?"UNDETERMINED":"PASS";
        String reason=mismatch?"已绑定的同类证照与查询记录存在有原文的字段差异":missing?"已保留完成的字段比较；仍有必需字段缺失或不能对齐":"已按原文逐字段核对本人员的证照与查询记录，所要求字段一致";
        return new Decision(rule.id(),rule.description(),status,reason,evidence,missing?cn.bidcheck.compat.Java8Api.listOf(gap("COMPARISON_FIELD_MAPPING_REQUIRED",reason)):cn.bidcheck.compat.Java8Api.listOf(),proof);
    }

    private static String identitySubject(Rule rule) {
        String selector = str(rule.parameters(), "personnelRoleSelector");
        return cn.bidcheck.compat.Java8Api.isBlank(selector) ? rule.subject() : selector;
    }

    static boolean equivalentFieldValue(String category,String field,String left,String right){
        if(left.equals(right))return true;
        if(!"PROFESSION".equals(field)||!category.matches("[一二]级注册建造师"))return false;
        return cn.bidcheck.compat.Java8Api.setOf("建筑", "建筑工程").contains(left)&&cn.bidcheck.compat.Java8Api.setOf("建筑", "建筑工程").contains(right);
    }

    static String requirementQuote(Rule rule,Map<String,Object> context){
        String quote=rule.sourceQuote();Object bindings=rule.parameters().get("requirementBindings");
        if(bindings instanceof Map<?,?>&&((Map<?,?>) (bindings)).get("subject") instanceof Map<?,?>){String full=Objects.toString(((Map<?,?>) (((Map<?,?>) (bindings)).get("subject"))).get("sourceQuote"),"");if(full.contains(rule.sourceQuote())&&clean(full).contains(clean(rule.subject())))quote=full;}
        String compiledRequirement=str(rule.parameters(),"certificateRequirementQuote"),originalRequirement=str(context,"originalRequirement");
        if(!cn.bidcheck.compat.Java8Api.isBlank(compiledRequirement)&&compiledRequirement.contains(rule.sourceQuote())&&originalRequirement.contains(compiledRequirement))quote=compiledRequirement;
        return quote;
    }
    static Set<String> requiredCategories(Rule rule,List<Record> records,Map<String,Object> context){
        if(Boolean.TRUE.equals(rule.parameters().get("submissionOnlyExactCredentialPair"))){
            String requirement=clean(requirementQuote(rule,context));
            if(!requirement.contains("安全生产考核合格证")
                ||!clean(rule.sourceQuote()).matches(".*(?:查询截图|查询结果).*(?:信息|内容).*一致.*"))return Collections.emptySet();
            Set<String> people=new LinkedHashSet<>();
            records.stream().filter(r->"CERTIFICATE".equals(r.side())).map(Record::owner).filter(v->!cn.bidcheck.compat.Java8Api.isBlank(v)).forEach(people::add);
            if(people.size()!=1)return Collections.emptySet();
            String person=people.iterator().next();
            List<Record> certificates=cn.bidcheck.compat.Java8Api.toList(records.stream().filter(r->"CERTIFICATE".equals(r.side())));
            return submissionComparisonCategories(person,certificates,records);
        }
        String original=clean(requirementQuote(rule,context));Set<String> required=new LinkedHashSet<>();boolean scoped=false;
        for(String family:cn.bidcheck.compat.Java8Api.listOf("建造师", "建筑师", "监理工程师", "造价工程师"))if(original.contains(family)){
            scoped=true;Set<String> found=new LinkedHashSet<>();records.stream().filter(r->r.category().contains(family)).forEach(r->found.add(r.category()));
            if(found.size()!=1)return cn.bidcheck.compat.Java8Api.setOf();required.addAll(found);
        }
        if(original.contains("安全生产考核合格证")){
            scoped=true;Set<String> allowed=new LinkedHashSet<>();Matcher types=Pattern.compile("[（(]([AB]|C[123]?)(?:类|证)?[）)]").matcher(original);while(types.find())allowed.add("安全生产考核合格证书("+types.group(1)+")");
            Set<String> found=new LinkedHashSet<>();records.stream().filter(r->r.category().startsWith("安全生产考核合格证书")).filter(r->allowed.isEmpty()||allowed.contains(r.category())).forEach(r->found.add(r.category()));
            if(found.size()!=1)return cn.bidcheck.compat.Java8Api.setOf();required.addAll(found);
        }
        if(!scoped){records.forEach(r->required.add(r.category()));if(required.size()!=1)return cn.bidcheck.compat.Java8Api.setOf();}
        return required;
    }

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class DatePair{
        private final Field certificate;
        private final Field query;
        private final Map<String,Object> audit;
        @com.fasterxml.jackson.annotation.JsonCreator
        private DatePair(@com.fasterxml.jackson.annotation.JsonProperty("certificate") Field certificate, @com.fasterxml.jackson.annotation.JsonProperty("query") Field query, @com.fasterxml.jackson.annotation.JsonProperty("audit") Map<String,Object> audit) { this.certificate = certificate; this.query = query; this.audit = audit; }
        public Field certificate() { return certificate; }
        public Field query() { return query; }
        public Map<String,Object> audit() { return audit; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; DatePair that = (DatePair) other; return java.util.Objects.equals(this.certificate, that.certificate) && java.util.Objects.equals(this.query, that.query) && java.util.Objects.equals(this.audit, that.audit); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.certificate); hash = 31 * hash + java.util.Objects.hashCode(this.query); hash = 31 * hash + java.util.Objects.hashCode(this.audit); return hash; }
        @Override public String toString() { return "DatePair[" + "certificate=" + certificate + ", query=" + query + ", audit=" + audit + "]"; }
}
    private static DatePair datePair(String field,Record certificate,Record query,String required){
        boolean scoped=certificate.fields().keySet().stream().anyMatch(k->k.startsWith(field+":"))
            ||query.fields().keySet().stream().anyMatch(k->k.startsWith(field+":"));
        if(!scoped)return new DatePair(certificate.fields().get(field),query.fields().get(field),cn.bidcheck.compat.Java8Api.mapOf("resolution", "SAME_UNQUALIFIED_DATE_FIELD"));
        if(required.isEmpty() && query.value("PROFESSION").isEmpty() && query.fields().containsKey(field)){
            String profession=canonicalProfession(certificate.value("PROFESSION"));
            Field single=certificate.fields().get(field+":"+profession);
            long scopedDates=certificate.fields().keySet().stream().filter(k->k.startsWith(field+":")).count();
            if(!profession.isEmpty() && single!=null && scopedDates==1
                    && query.fields().keySet().stream().noneMatch(k->k.startsWith(field+":"))){
                return new DatePair(single,query.fields().get(field),linked("resolution","SINGLE_PROFESSION_SAME_RECORD",
                    "profession",profession,"comparisonScope","QUERY_RECORD_CONSISTENCY_ONLY"));
            }
        }
        Field left=null,right=null;
        if(!cn.bidcheck.compat.Java8Api.isBlank(required)){
            left=professionalDate(certificate,field,required);right=professionalDate(query,field,required);
        }
        if(left!=null&&right==null&&query.fields().containsKey(field)){
            Set<String> listed=new LinkedHashSet<>();
            for(String item:query.value("PROFESSION").split("[,，、/;；]+"))listed.add(canonicalProfession(item));
            if(listed.size()>1&&listed.contains(required)&&listed.stream().allMatch(p->certificate.fields().containsKey(field+":"+p))){
                Field aggregate=query.fields().get(field);
                for(String p:listed){
                    Field professional=certificate.fields().get(field+":"+p);
                    if(professional.value().equals(aggregate.value()))return new DatePair(professional,aggregate,
                        linked("resolution","MERGED_QUERY_DATE_MATCHES_LISTED_PROFESSION","matchingProfession",p,"requiredProfession",required,"comparisonScope","QUERY_RECORD_CONSISTENCY_ONLY"));
                }
                return new DatePair(left,aggregate,linked("resolution","MERGED_QUERY_DATE_DIFFERS_FROM_ALL_LISTED_PROFESSIONS","requiredProfession",required));
            }
        }
        return new DatePair(left,right,linked("resolution",cn.bidcheck.compat.Java8Api.isBlank(required)?"REQUIREMENT_PROFESSION_UNRESOLVED":left==null||right==null?"PROFESSION_DATE_SCOPE_NOT_ALIGNED":"SAME_SOURCE_BOUND_PROFESSION",
            "requiredProfession",required,"certificateAvailableFields",cn.bidcheck.compat.Java8Api.toList(certificate.fields().keySet().stream().filter(k->k.startsWith(field))),
            "queryAvailableFields",cn.bidcheck.compat.Java8Api.toList(query.fields().keySet().stream().filter(k->k.startsWith(field))),"certificateProfession",certificate.value("PROFESSION"),"queryProfession",query.value("PROFESSION"),
            "unqualifiedCertificateDate",certificate.fields().containsKey(field)?certificate.fields().get(field).audit():cn.bidcheck.compat.Java8Api.mapOf(),
            "unqualifiedQueryDate",query.fields().containsKey(field)?query.fields().get(field).audit():cn.bidcheck.compat.Java8Api.mapOf(),
            "noCrossProfessionDateSubstitution",true));
    }
    private static Field professionalDate(Record record,String field,String required){
        Field explicit=record.fields().get(field+":"+required);if(explicit!=null)return explicit;
        // A generic date belongs to a profession only when that record explicitly names one
        // profession. “建筑,机电” plus one date is not proof of either individual expiry.
        if(canonicalProfession(record.value("PROFESSION")).equals(required))return record.fields().get(field);
        return null;
    }
    private static String requiredProfession(Rule rule,Map<String,Object> context){
        String quote=clean(requirementQuote(rule,context));Set<String> values=new LinkedHashSet<>();
        Matcher matcher=Pattern.compile("(?:注册建造师[^。；;（）()]{0,24})[（(]([^（）()]{1,30})专业[）)]").matcher(quote);
        while(matcher.find())values.add(canonicalProfession(matcher.group(1)));
        return values.size()==1?values.iterator().next():"";
    }
    private static String canonicalProfession(String value){switch (clean(value)) {case "房屋建筑工程":case "建筑":return "建筑工程";case "机电":return "机电工程";default:return clean(value);}}

    static Set<String> requiredFields(Rule rule){
        // Only original comparison wording can narrow the set; model parameters cannot drop fields.
        String quote=clean(rule.sourceQuote());int start=quote.lastIndexOf("查询截图");if(start<0)start=quote.lastIndexOf("查询结果");String clause=start<0?quote:quote.substring(start).split("[。；;（）()]",2)[0];
        LinkedHashSet<String> fields=new LinkedHashSet<>();
        if(clause.matches(".*(?:信息|内容).*一致.*"))return new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.listOf("NAME", "EMPLOYER", "CERTIFICATE_NUMBER", "VALIDITY_END"));
        if(clause.contains("姓名")||clause.contains("持证人"))fields.add("NAME");
        if(clause.contains("单位"))fields.add("EMPLOYER");
        if(clause.contains("执业印章号"))fields.add("PRACTICE_SEAL_NUMBER");
        else if(clause.contains("编号")||clause.contains("证号"))fields.add("CERTIFICATE_NUMBER");
        if(clause.contains("有效期")){fields.add("VALIDITY_END");if(clause.contains("起止")||clause.contains("开始"))fields.add("VALIDITY_START");}
        if(clause.contains("身份证")||clause.contains("证件号码"))fields.add("PERSON_ID");
        return fields.isEmpty()?new LinkedHashSet<>(cn.bidcheck.compat.Java8Api.listOf("NAME", "EMPLOYER", "CERTIFICATE_NUMBER", "VALIDITY_END")):fields;
    }

    static List<Record> records(EvidenceCorpus corpus){
        Map<String,CertificateEvidenceRecords.ScopeResolver> resolvers=new HashMap<>();Map<String,List<Source>> pages=new LinkedHashMap<>();for(Source s:corpus.all)if(CertificateEvidenceRecords.usable(s,corpus,resolvers))pages.computeIfAbsent(EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()),k->new ArrayList<>()).add(s);
        List<Record> out=new ArrayList<>();for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Source>> entry:pages.entrySet()){
            cn.bidcheck.domain.Domain.Page page=corpus.pages.get(entry.getKey());if(page==null)continue;String text=Objects.toString(page.text(),"");List<Record> typed=PersonnelCertificateRecords.read(entry.getValue(),text);if(!typed.isEmpty())out.addAll(typed);else{Record record=read(entry.getValue(),text);if(record!=null)out.add(record);}
        }
        // Identical duplicates retain all source locations. Different versions never collapse.
        Map<String,Record> unique=new LinkedHashMap<>();for(Record r:out){String key=r.side()+r.category()+r.owner()+cn.bidcheck.compat.Java8Api.toList(r.fields().entrySet().stream().map(e->e.getKey()+":"+e.getValue().value()));Record old=unique.get(key);if(old==null)unique.put(key,r);else{List<Source> sources=new ArrayList<>(old.sources());sources.addAll(r.sources());unique.put(key,new Record(old.id(),old.side(),old.category(),old.owner(),cn.bidcheck.compat.Java8Api.toList(sources.stream().distinct()),old.fields(),old.issues()));}}
        return cn.bidcheck.compat.Java8Api.listCopyOf(unique.values());
    }
    private static Record read(List<Source> sources,String original){
        Compact c=Compact.of(original);String text=c.text();if(text.matches(".*(?:填写示例|示范文本|仅供参考|未取得|拟取得|证书模板).*"))return null;
        boolean query=text.matches(".*(?:查询截图|查询结果|执业注册信息|证书查询).*"),certificate;
        // Keep this check explicit; no arbitrary string containing the word '证书' is a record.
        // A qualification examination certificate is not the registration certificate shown by an
        // occupational registration query, even when both use the same profession name.
        certificate=!query&&(text.contains("注册证书")||text.contains("考核合格证书"));if(!query&&!certificate)return null;
        Set<String> categories=new LinkedHashSet<>();Matcher cat=CATEGORY.matcher(text);while(cat.find())categories.add(canonicalCategory(cat.group()));if(categories.size()!=1)return null;String category=categories.iterator().next();
        Map<String,Field> fields=new LinkedHashMap<>();List<String> issues=new ArrayList<>();Matcher owner=OWNER.matcher(text);while(owner.find())add(fields,issues,field("NAME","姓名",owner.group(1),owner.start(1),owner.end(1),c,sources));
        if(query&&!fields.containsKey("NAME")){Matcher header=PROFILE_OWNER.matcher(text);while(header.find())add(fields,issues,field("NAME","人员详情姓名",header.group(1),header.start(1),header.end(1),c,sources));}
        Field name=fields.get("NAME");if(name==null||issues.contains("MULTIPLE_VALUES:NAME"))return null;
        Matcher number=NUMBER.matcher(text);while(number.find()){
            String label=number.group(1),value=number.group(2),key;
            if(label.equals("证件号码")||label.equals("身份证号")){if(!value.matches("[0-9]{17}[0-9Xx]"))continue;key="PERSON_ID";}
            else if(label.contains("执业印章号"))key="PRACTICE_SEAL_NUMBER";
            else if(label.equals("注册编号")&&!category.contains("建筑师"))key="REGISTRATION_NUMBER";
            else key="CERTIFICATE_NUMBER";
            add(fields,issues,field(key,label,value,number.start(2),number.end(2),c,sources));
        }
        Matcher employer=EMPLOYER.matcher(text);while(employer.find())add(fields,issues,field("EMPLOYER",employer.group(1),employer.group(2),employer.start(2),employer.end(2),c,sources));
        Matcher period=PERIOD.matcher(text);while(period.find()){
            if(period.group(3)!=null)add(fields,issues,field("VALIDITY_START",period.group(1),period.group(2),period.start(2),period.end(2),c,sources));
            int group=period.group(3)==null?2:3;add(fields,issues,field("VALIDITY_END",period.group(1),period.group(group),period.start(group),period.end(group),c,sources));
        }
        for(String[] spec:cn.bidcheck.compat.Java8Api.listOf(new String[]{"GENDER","性别","性别[:：]?([男女])"}, new String[]{"BIRTH_DATE","出生日期","出生日期[:：]?("+DATE+")"}, new String[]{"PROFESSION","注册专业","注册专业[:：]?([^:：|│]{1,40}?)(?=有效期|注册有效期|$)"})){
            Matcher value=Pattern.compile(spec[2]).matcher(text);while(value.find())add(fields,issues,field(spec[0],spec[1],value.group(1),value.start(1),value.end(1),c,sources));
        }
        String id="CR"+identity(sources.get(0).id()+category+name.value()).substring(0,20);return new Record(id,query?"QUERY":"CERTIFICATE",category,name.value(),cn.bidcheck.compat.Java8Api.listCopyOf(sources),fields,cn.bidcheck.compat.Java8Api.listCopyOf(issues));
    }
    private static String canonicalCategory(String text){return text.replace('壹','一').replace('贰','二');}
    private static Field field(String key,String label,String value,int start,int end,Compact compact,List<Source> sources){
        if(start>=end)return null;int from=compact.offsets()[start],to=compact.offsets()[end-1]+1;String raw=compact.original().substring(from,to);Source source=sources.stream().filter(s->{String a=s.evidence().anchor();Matcher m=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(a);return m.matches()&&from>=Integer.parseInt(m.group(1))&&to<=Integer.parseInt(m.group(2));}).findFirst().orElse(null);if(source==null)return null;
        String normalized=clean(value).toUpperCase(Locale.ROOT);if(key.startsWith("VALIDITY_")||key.equals("BIRTH_DATE")){Matcher date=Pattern.compile("([0-9]{4})[年./-]([0-9]{1,2})[月./-]([0-9]{1,2})日?").matcher(value);if(!date.matches())return null;try{normalized=LocalDate.of(Integer.parseInt(date.group(1)),Integer.parseInt(date.group(2)),Integer.parseInt(date.group(3))).toString();}catch(Exception e){return null;}}
        Evidence origin=source.evidence();return new Field(key,label,raw,normalized,source.id(),new Evidence(origin.documentId(),origin.documentName(),origin.page(),origin.locationType(),raw,"chars:"+from+"-"+to,origin.confidence()));
    }
    private static void add(Map<String,Field> fields,List<String> issues,Field field){if(field==null)return;Field old=fields.putIfAbsent(field.key(),field);if(old!=null&&!old.value().equals(field.value()))issues.add("MULTIPLE_VALUES:"+field.key());}
    private static Map<String,Object> proof(Rule rule,List<Fact> roles,List<Record> records){return linked("comparisonVersion",VERSION,"comparisonOperandKind","SOURCE_REFERENCE","comparisonTargetReference",SameFieldComparisonTarget.reference(rule),"literalTargetComparisonRejected",true,"materialMissingProven",false,"records",cn.bidcheck.compat.Java8Api.toList(records.stream().map(Record::audit)),"roleBindings",cn.bidcheck.compat.Java8Api.toList(roles.stream().map(f->linked("factId",f.id(),"subject",f.subject(),"person",f.value(),"sourceIds",f.sourceIds()))),"requirementQuote",rule.sourceQuote());}
    private static List<Evidence> evidences(List<Fact> roles,List<Record> records,RuleEvaluator evaluator){List<Evidence> out=new ArrayList<>();roles.stream().flatMap(f->f.sourceIds().stream()).map(evaluator.corpus.byId::get).filter(Objects::nonNull).map(Source::evidence).forEach(out::add);records.stream().flatMap(r->r.fields().values().stream()).map(Field::evidence).forEach(out::add);return cn.bidcheck.compat.Java8Api.toList(out.stream().distinct());}
    private static Decision pending(Rule rule,RuleEvaluator evaluator,String code,String reason,List<Fact> roles,List<Record> records,Map<String,Object> extra){Map<String,Object> proof=proof(rule,roles,records);proof.putAll(extra);return new Decision(rule.id(),rule.description(),"UNDETERMINED",reason,evidences(roles,records,evaluator),cn.bidcheck.compat.Java8Api.listOf(new Gap(code,reason,"由系统补齐两端定位、证照身份或字段关系，不把未对齐解释为材料缺失",code.startsWith("REQUIREMENT_")?"REQUIREMENT_MAPPING_CHANGED":"INPUT_OR_EVIDENCE_CHANGED")),proof);}
}
