package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Local, typed two-document comparison. Every field is read again from immutable original text. */
final class CertificateSourceComparison {
    static final String VERSION="certificate-source-comparison-2-typed-query-rows";
    private static final String DATE="(?:19|20)[0-9]{2}[年./-][0-9]{1,2}[月./-][0-9]{1,2}日?";
    private static final Pattern CATEGORY=Pattern.compile("([一二壹贰]级注册建筑师|[一二壹贰]级注册建造师|注册监理工程师|注册造价工程师|安全生产考核合格证书)");
    private static final Pattern OWNER=Pattern.compile("(?:姓名[:：]?|持证人[:：])([\\p{IsHan}·]{2,12}?)(?=性别|年龄|身份证|证件|出生|注册|证书|聘用|执业|[|│]|$)");
    private static final Pattern PROFILE_OWNER=Pattern.compile("(?:手机查看器?|人员详情|个人信息)[:：]?([\\p{IsHan}·]{2,12}?)(?=证件类型|证件号码|性别|注册证书)");
    private static final Pattern NUMBER=Pattern.compile("(电子证书编号|注册编号/执业印章号|执业印章号|证书编号|注册证号|注册编号|资格证号|证件号码|身份证号)[:：]?([A-Za-z0-9()（）-]{4,80})(?![A-Za-z0-9])");
    private static final Pattern EMPLOYER=Pattern.compile("(注册证书所在单位名称|注册单位|聘用单位)[:：]?([^:：|│]{2,100}?)(?=电子证书编号|注册编号|执业印章号|注册专业|注册有效期|有效期|执业注册信息|证书编号|签名|发证日期|$)");
    private static final Pattern PERIOD=Pattern.compile("(注册有效期|证书有效期|有效期限|有效期至|有效期)[:：]?("+DATE+")(?:[-至—~～]("+DATE+"))?");
    private CertificateSourceComparison() {}

    record Field(String key,String label,String raw,String value,String sourceId,Evidence evidence) {
        Map<String,Object> audit(){return linked("field",key,"label",label,"rawValue",raw,"value",value,"sourceId",sourceId,"source",evidence);}
    }
    record Record(String id,String side,String category,String owner,List<Source> sources,Map<String,Field> fields,List<String> issues) {
        String value(String key){Field f=fields.get(key);return f==null?"":f.value();}
        Map<String,Object> audit(){return linked("recordId",id,"side",side,"category",category,"subject",owner,"sourceIds",sources.stream().map(Source::id).toList(),"fields",fields.values().stream().map(Field::audit).toList(),"issues",issues);}
    }
    private record Compact(String text,int[] offsets,String original) {
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
        List<Fact> roles=evaluator.facts.stream().filter(f->Set.of("PERSON_ROLE","RELATION").contains(f.kind())&&"CURRENT_PROJECT".equals(f.scope())&&DomainBindings.currentRoleSource(f,evaluator.corpus.byId))
            .filter(f->name.equals(f.kind().equals("PERSON_ROLE")?f.value():DomainBindings.roleTarget(f,DomainBindings.sourceRole(f))))
            .filter(f->new ProofVerifier().issue(f,evaluator.corpus.byId)==null&&DomainBindings.issue(f,evaluator.corpus.byId,List.of())==null).toList();
        List<Record> candidates=records.stream().filter(r->r.owner().equals(name)).toList();
        if(candidates.isEmpty())return SameFieldComparisonTarget.unresolved(rule,evaluator.match(rule),evaluator);
        if(roles.isEmpty())return pending(rule,evaluator,"SUBJECT_RELATION_UNPROVEN","已发现同名证书候选，但尚无已核源的本次岗位任职，不能以同名材料替代当前人员",roles,candidates,Map.of());
        SubjectIdentity.Problem identity=evaluator.identities.issue(new Rule(rule.id(),rule.description(),"CERTIFICATE",rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),rule.parameters(),rule.sourceQuote(),rule.logic(),List.of()),List.of());
        if(identity!=null)return pending(rule,evaluator,identity.code(),identity.reason(),roles,candidates,Map.of());
        Set<String> categories=requiredCategories(rule,candidates,evaluator.context);
        if(categories.isEmpty())return pending(rule,evaluator,"REQUIREMENT_COMPARISON_TARGET_UNRESOLVED","原条款要求的证照类型尚未与两端记录对应，不以另一类现有证照缩小检查范围",roles,candidates,Map.of());
        List<Decision> compared=new ArrayList<>();for(String category:categories)compared.add(compareCategory(rule,name,category,candidates.stream().filter(r->r.category().equals(category)).toList(),roles,evaluator));
        return compared.size()==1?compared.get(0):RuleEvaluator.combine(rule.id(),rule.description(),"ALL",compared);
    }

    private static Decision compareCategory(Rule rule,String name,String category,List<Record> candidates,List<Fact> roles,RuleEvaluator evaluator){
        List<Record> left=candidates.stream().filter(r->r.side().equals("CERTIFICATE")).toList(),right=candidates.stream().filter(r->r.side().equals("QUERY")).toList();
        if(left.size()!=1||right.size()!=1)return pending(rule,evaluator,"COMPARISON_SOURCE_REQUIRED","需要本人员同类证书与查询记录各一个明确版本；缺少一端或存在多版时不选择性比较",roles,candidates,linked("certificateRecords",left.size(),"queryRecords",right.size()));
        Record certificate=left.get(0),query=right.get(0);
        if(!certificate.issues().isEmpty()||!query.issues().isEmpty())return pending(rule,evaluator,"COMPARISON_FIELD_MAPPING_REQUIRED","同一记录内字段缺少明确边界或出现多个取值，不能取第一个值作比较",roles,candidates,Map.of());
        Field selectedNumber=certificate.fields().get("CERTIFICATE_NUMBER");if(selectedNumber==null)selectedNumber=certificate.fields().get("REGISTRATION_NUMBER");
        if(selectedNumber==null)return pending(rule,evaluator,"COMPARISON_RECORD_IDENTITY_UNPROVEN","证书一端没有明确类型的编号，不能只凭姓名关联查询记录",roles,candidates,Map.of());
        Fact selected=new Fact("source-comparison:"+certificate.id(),"CERTIFICATE",name,"证书",selectedNumber.raw(),selectedNumber.value(),"","","UNRESOLVED",certificate.sources().stream().map(Source::id).toList(),Map.of("category",category,"number",selectedNumber.value(),"numberField",selectedNumber.label()));
        Rule identityRule=new Rule(rule.id(),rule.description(),"CERTIFICATE",rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),rule.parameters(),rule.sourceQuote(),"",List.of());
        SubjectIdentity.Problem roleIdentity=evaluator.identities.issue(identityRule,List.of(selected));
        if(roleIdentity!=null)return pending(rule,evaluator,roleIdentity.code(),roleIdentity.reason(),roles,candidates,Map.of("comparisonIdentityScope","CURRENT_ROLE_TO_SELECTED_CERTIFICATE"));
        String certNumber=certificate.value("CERTIFICATE_NUMBER"),queryNumber=query.value("CERTIFICATE_NUMBER");
        boolean typedNumber=!certNumber.isBlank()&&certNumber.equals(queryNumber);
        boolean typedRegistration=!certificate.value("REGISTRATION_NUMBER").isBlank()&&certificate.value("REGISTRATION_NUMBER").equals(query.value("REGISTRATION_NUMBER"));
        boolean personId=!certificate.value("PERSON_ID").isBlank()&&certificate.value("PERSON_ID").equals(query.value("PERSON_ID"));
        if(!certificate.value("PERSON_ID").isBlank()&&!query.value("PERSON_ID").isBlank()&&!personId)return pending(rule,evaluator,"SUBJECT_IDENTITY_CONFLICT","同名证照与查询记录存在不同个人证件号码，不能合并为同一人",roles,candidates,Map.of());
        if(!typedNumber&&!typedRegistration&&!personId)return pending(rule,evaluator,"COMPARISON_RECORD_IDENTITY_UNPROVEN","同名同类证照尚未通过同类型证书编号、注册编号或完整个人证件号建立对应关系；执业印章号不作为证书编号",roles,candidates,Map.of());
        Set<String> requiredFields=requiredFields(rule);
        boolean generic=clean(rule.sourceQuote()).matches(".*(?:查询截图|查询结果).*(?:信息|内容).*一致.*");
        if(generic&&typedRegistration&&certNumber.isBlank()&&queryNumber.isBlank()){requiredFields.remove("CERTIFICATE_NUMBER");requiredFields.add("REGISTRATION_NUMBER");}
        if(clean(rule.sourceQuote()).matches(".*(?:查询截图|查询结果).*(?:信息|内容).*一致.*"))for(String field:List.of("PERSON_ID","GENDER","BIRTH_DATE","PROFESSION","VALIDITY_START"))if(certificate.fields().containsKey(field)&&query.fields().containsKey(field))requiredFields.add(field);
        List<Map<String,Object>> fieldResults=new ArrayList<>();boolean mismatch=false,missing=false;
        for(String field:requiredFields){Field a=certificate.fields().get(field),b=query.fields().get(field);String status=a==null||b==null?"UNDETERMINED":a.value().equals(b.value())?"PASS":"FAIL";missing|=status.equals("UNDETERMINED");mismatch|=status.equals("FAIL");fieldResults.add(linked("field",field,"status",status,"certificate",a==null?Map.of():a.audit(),"query",b==null?Map.of():b.audit()));}
        Map<String,Object> proof=proof(rule,roles,candidates);proof.putAll(linked("subject",name,"category",category,"requiredFields",requiredFields,"fieldComparisons",fieldResults,"recordBinding",typedNumber?"CURRENT_APPOINTMENT_AND_NAME_CATEGORY_TYPED_CERTIFICATE_NUMBER":typedRegistration?"CURRENT_APPOINTMENT_AND_NAME_CATEGORY_TYPED_REGISTRATION_NUMBER":"CURRENT_APPOINTMENT_AND_NAME_CATEGORY_PERSON_ID","comparisonComplete",!missing,"proofScope","REFERENCED_CERTIFICATE_FIELDS","comparedRecordCount",2,"doesNotProveCurrentOnlineStatus",true,"identityBindings",evaluator.identities.bindings(List.of(selected))));
        List<Evidence> evidence=evidences(roles,candidates,evaluator);String status=mismatch?"FAIL":missing?"UNDETERMINED":"PASS";
        String reason=mismatch?"已绑定的同类证照与查询记录存在有原文的字段差异":missing?"已保留完成的字段比较；仍有必需字段缺失或不能对齐":"已按原文逐字段核对本人员的证照与查询记录，所要求字段一致";
        return new Decision(rule.id(),rule.description(),status,reason,evidence,missing?List.of(gap("COMPARISON_FIELD_MAPPING_REQUIRED",reason)):List.of(),proof);
    }

    private static Set<String> requiredCategories(Rule rule,List<Record> records,Map<String,Object> context){
        String quote=rule.sourceQuote();Object bindings=rule.parameters().get("requirementBindings");
        if(bindings instanceof Map<?,?> all&&all.get("subject") instanceof Map<?,?> subject){String full=Objects.toString(subject.get("sourceQuote"),"");if(full.contains(rule.sourceQuote())&&clean(full).contains(clean(rule.subject())))quote=full;}
        String compiledRequirement=str(rule.parameters(),"certificateRequirementQuote"),originalRequirement=str(context,"originalRequirement");
        if(!compiledRequirement.isBlank()&&compiledRequirement.contains(rule.sourceQuote())&&originalRequirement.contains(compiledRequirement))quote=compiledRequirement;
        String original=clean(quote);Set<String> required=new LinkedHashSet<>();boolean scoped=false;
        for(String family:List.of("建造师","建筑师","监理工程师","造价工程师"))if(original.contains(family)){
            scoped=true;Set<String> found=new LinkedHashSet<>();records.stream().filter(r->r.category().contains(family)).forEach(r->found.add(r.category()));
            if(found.size()!=1)return Set.of();required.addAll(found);
        }
        if(original.contains("安全生产考核合格证")){
            scoped=true;Set<String> allowed=new LinkedHashSet<>();Matcher types=Pattern.compile("[（(]([AB]|C[123]?)(?:类|证)?[）)]").matcher(original);while(types.find())allowed.add("安全生产考核合格证书("+types.group(1)+")");
            Set<String> found=new LinkedHashSet<>();records.stream().filter(r->r.category().startsWith("安全生产考核合格证书")).filter(r->allowed.isEmpty()||allowed.contains(r.category())).forEach(r->found.add(r.category()));
            if(found.size()!=1)return Set.of();required.addAll(found);
        }
        if(!scoped){records.forEach(r->required.add(r.category()));if(required.size()!=1)return Set.of();}
        return required;
    }

    static Set<String> requiredFields(Rule rule){
        // Only original comparison wording can narrow the set; model parameters cannot drop fields.
        String quote=clean(rule.sourceQuote());int start=quote.lastIndexOf("查询截图");if(start<0)start=quote.lastIndexOf("查询结果");String clause=start<0?quote:quote.substring(start).split("[。；;（）()]",2)[0];
        LinkedHashSet<String> fields=new LinkedHashSet<>();
        if(clause.matches(".*(?:信息|内容).*一致.*"))return new LinkedHashSet<>(List.of("NAME","EMPLOYER","CERTIFICATE_NUMBER","VALIDITY_END"));
        if(clause.contains("姓名")||clause.contains("持证人"))fields.add("NAME");
        if(clause.contains("单位"))fields.add("EMPLOYER");
        if(clause.contains("执业印章号"))fields.add("PRACTICE_SEAL_NUMBER");
        else if(clause.contains("编号")||clause.contains("证号"))fields.add("CERTIFICATE_NUMBER");
        if(clause.contains("有效期")){fields.add("VALIDITY_END");if(clause.contains("起止")||clause.contains("开始"))fields.add("VALIDITY_START");}
        if(clause.contains("身份证")||clause.contains("证件号码"))fields.add("PERSON_ID");
        return fields.isEmpty()?new LinkedHashSet<>(List.of("NAME","EMPLOYER","CERTIFICATE_NUMBER","VALIDITY_END")):fields;
    }

    static List<Record> records(EvidenceCorpus corpus){
        Map<String,CertificateEvidenceRecords.ScopeResolver> resolvers=new HashMap<>();Map<String,List<Source>> pages=new LinkedHashMap<>();for(Source s:corpus.all)if(CertificateEvidenceRecords.usable(s,corpus,resolvers))pages.computeIfAbsent(EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()),k->new ArrayList<>()).add(s);
        List<Record> out=new ArrayList<>();for(var entry:pages.entrySet()){
            var page=corpus.pages.get(entry.getKey());if(page==null)continue;String text=Objects.toString(page.text(),"");List<Record> typed=PersonnelCertificateRecords.read(entry.getValue(),text);if(!typed.isEmpty())out.addAll(typed);else{Record record=read(entry.getValue(),text);if(record!=null)out.add(record);}
        }
        // Identical duplicates retain all source locations. Different versions never collapse.
        Map<String,Record> unique=new LinkedHashMap<>();for(Record r:out){String key=r.side()+r.category()+r.owner()+r.fields().entrySet().stream().map(e->e.getKey()+":"+e.getValue().value()).toList();Record old=unique.get(key);if(old==null)unique.put(key,r);else{List<Source> sources=new ArrayList<>(old.sources());sources.addAll(r.sources());unique.put(key,new Record(old.id(),old.side(),old.category(),old.owner(),sources.stream().distinct().toList(),old.fields(),old.issues()));}}
        return List.copyOf(unique.values());
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
        for(String[] spec:List.of(new String[]{"GENDER","性别","性别[:：]?([男女])"},new String[]{"BIRTH_DATE","出生日期","出生日期[:：]?("+DATE+")"},new String[]{"PROFESSION","注册专业","注册专业[:：]?([^:：|│]{1,40}?)(?=有效期|注册有效期|$)"})){
            Matcher value=Pattern.compile(spec[2]).matcher(text);while(value.find())add(fields,issues,field(spec[0],spec[1],value.group(1),value.start(1),value.end(1),c,sources));
        }
        String id="CR"+identity(sources.get(0).id()+category+name.value()).substring(0,20);return new Record(id,query?"QUERY":"CERTIFICATE",category,name.value(),List.copyOf(sources),fields,List.copyOf(issues));
    }
    private static String canonicalCategory(String text){return text.replace('壹','一').replace('贰','二');}
    private static Field field(String key,String label,String value,int start,int end,Compact compact,List<Source> sources){
        if(start>=end)return null;int from=compact.offsets()[start],to=compact.offsets()[end-1]+1;String raw=compact.original().substring(from,to);Source source=sources.stream().filter(s->{String a=s.evidence().anchor();Matcher m=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(a);return m.matches()&&from>=Integer.parseInt(m.group(1))&&to<=Integer.parseInt(m.group(2));}).findFirst().orElse(null);if(source==null)return null;
        String normalized=clean(value).toUpperCase(Locale.ROOT);if(key.startsWith("VALIDITY_")||key.equals("BIRTH_DATE")){Matcher date=Pattern.compile("([0-9]{4})[年./-]([0-9]{1,2})[月./-]([0-9]{1,2})日?").matcher(value);if(!date.matches())return null;try{normalized=LocalDate.of(Integer.parseInt(date.group(1)),Integer.parseInt(date.group(2)),Integer.parseInt(date.group(3))).toString();}catch(Exception e){return null;}}
        Evidence origin=source.evidence();return new Field(key,label,raw,normalized,source.id(),new Evidence(origin.documentId(),origin.documentName(),origin.page(),origin.locationType(),raw,"chars:"+from+"-"+to,origin.confidence()));
    }
    private static void add(Map<String,Field> fields,List<String> issues,Field field){if(field==null)return;Field old=fields.putIfAbsent(field.key(),field);if(old!=null&&!old.value().equals(field.value()))issues.add("MULTIPLE_VALUES:"+field.key());}
    private static Map<String,Object> proof(Rule rule,List<Fact> roles,List<Record> records){return linked("comparisonVersion",VERSION,"comparisonOperandKind","SOURCE_REFERENCE","comparisonTargetReference",SameFieldComparisonTarget.reference(rule),"literalTargetComparisonRejected",true,"materialMissingProven",false,"records",records.stream().map(Record::audit).toList(),"roleBindings",roles.stream().map(f->linked("factId",f.id(),"subject",f.subject(),"person",f.value(),"sourceIds",f.sourceIds())).toList(),"requirementQuote",rule.sourceQuote());}
    private static List<Evidence> evidences(List<Fact> roles,List<Record> records,RuleEvaluator evaluator){List<Evidence> out=new ArrayList<>();roles.stream().flatMap(f->f.sourceIds().stream()).map(evaluator.corpus.byId::get).filter(Objects::nonNull).map(Source::evidence).forEach(out::add);records.stream().flatMap(r->r.fields().values().stream()).map(Field::evidence).forEach(out::add);return out.stream().distinct().toList();}
    private static Decision pending(Rule rule,RuleEvaluator evaluator,String code,String reason,List<Fact> roles,List<Record> records,Map<String,Object> extra){Map<String,Object> proof=proof(rule,roles,records);proof.putAll(extra);return new Decision(rule.id(),rule.description(),"UNDETERMINED",reason,evidences(roles,records,evaluator),List.of(new Gap(code,reason,"由系统补齐两端定位、证照身份或字段关系，不把未对齐解释为材料缺失",code.startsWith("REQUIREMENT_")?"REQUIREMENT_MAPPING_CHANGED":"INPUT_OR_EVIDENCE_CHANGED")),proof);}
}
