package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.source.DateFieldValues;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;
import static cn.bidcheck.verification.engine.ProofVerifier.*;

/** Exact, source-backed domain boundaries. Missing dimensions are never inferred from proximity. */
final class DomainBindings {
    private DomainBindings() {}
    private static final Set<String> ROLE_TO_NAME=Set.of("为","是","名称","对应主体","成员名称","：",":");
    private static final Set<String> NAME_TO_ROLE=Set.of("身份","角色","成员角色","作为","担任","为","是","拟在本合同任职","在本项目中担任职务","在本项目担任职务");
    private static final Pattern CERT_NUMBER=Pattern.compile("(?:证书编号|证书号码|注册编号|注册证号|资格证号|证件号码|证号)\\s*[:：]\\s*([^\\s;；，,。|<>]{2,80})");
    private static final Pattern VALIDITY_FIELD=Pattern.compile("注册有效期|使用有效期|证书有效期|资格有效期|有效截止日期|有效期限|有效期至|有效期");
    static boolean role(String value){return memberSelector(value)||PersonnelRoles.known(value);}
    static boolean memberSelector(String value){return clean(value).matches("(?:本项目|本次)?(?:联合体成员(?:单位)?|(?:联合体)?(?:各成员|所有成员|全部成员))");}
    static boolean known(String value){return !value.isBlank()&&!Set.of("UNKNOWN","UNRESOLVED","未知","待确认","未明确","-","/").contains(value);}
    static List<Fact> roleLinks(String selector,List<Fact> facts,EvidenceCorpus corpus){
        if(!role(selector))return List.of();
        return facts.stream().filter(f->f.scope().equals("CURRENT_PROJECT")&&currentRoleSource(f,corpus.byId))
            .filter(f->f.kind().equals("PERSON_ROLE")&&roleMatches(selector,f.subject(),f,corpus)&&!SubjectIdentity.person(f).isBlank()
                ||f.kind().equals("RELATION")&&(roleMatches(selector,f.subject(),f,corpus)&&ROLE_TO_NAME.contains(f.field())&&!role(str(f.attributes(),"object"))
                    ||roleMatches(selector,str(f.attributes(),"object"),f,corpus)&&NAME_TO_ROLE.contains(f.field())&&!role(f.subject())))
            .toList();
    }
    static boolean roleMatches(String selector,String observed,Fact f,EvidenceCorpus corpus){return selector.equals(observed)||PersonnelRoles.equivalent(selector,observed,f,corpus);}
    static String roleTarget(Fact f,String selector){return f.kind().equals("PERSON_ROLE")?f.value():ROLE_TO_NAME.contains(f.field())&&role(f.subject())?str(f.attributes(),"object"):f.subject();}
    static String sourceRole(Fact f){return f.kind().equals("PERSON_ROLE")||role(f.subject())?f.subject():str(f.attributes(),"object");}
    static List<String> subjects(String selector,List<Fact> facts,EvidenceCorpus corpus){
        if(selector.isBlank())return List.of();if(!role(selector))return List.of(selector);
        return roleLinks(selector,facts,corpus).stream().map(f->roleTarget(f,selector)).filter(DomainBindings::known).distinct().toList();
    }
    static List<Map<String,Object>> completeMemberSet(String selector,List<Fact> links,List<String> subjects,EvidenceCorpus corpus){
        if(!memberSelector(selector)||subjects.size()<2)return List.of();
        Set<String> expected=new LinkedHashSet<>(subjects);List<Map<String,Object>> proofs=new ArrayList<>();
        for(String id:links.stream().flatMap(f->f.sourceIds().stream()).distinct().toList()){
            Source source=corpus.byId.get(id);if(source==null||source.index()||source.historical())continue;
            for(String sentence:source.evidence().quote().split("[。；;]")){
                if(sentence.matches("(?s).*(?:拟由|不由|并非|不组成|示例|范本).*"))continue;
                String normalized=sentence.replaceAll("\\s+", "");
                Matcher list=Pattern.compile("(?:由([^。；;]{2,800}?)(?:共同)?组成(?:本项目|本次投标)?联合体|(?:^|[:：])([^。；;]{2,800}?)\\[所有成员单位名称\\]自愿组成[:：])").matcher(normalized);
                while(list.find()){
                    String names=Objects.toString(list.group(1),list.group(2));
                    // A form heading before the named complete list is not part of the first name.
                    if(list.group(2)!=null&&names.contains("联合体协议书"))names=names.substring(names.lastIndexOf("联合体协议书")+6);
                    List<String> named=Arrays.stream(names.split("[、,，]|以及|及|和|与")).map(String::strip).toList();
                    // Conflicting complete composition in a read source vetoes the whole set.
                    // An extracted subset cannot define which original members to ignore.
                    if(named.size()!=expected.size()||!new LinkedHashSet<>(named).equals(expected))return List.of();
                    if(expected.stream().anyMatch(name->links.stream().noneMatch(f->roleTarget(f,selector).equals(name)&&f.sourceIds().contains(id))))return List.of();
                    proofs.add(linked("sourceId",id,"quote",sentence,"members",named,"confirmation","EXPLICIT_COMPLETE_COMPOSITION"));
                }
            }
        }
        return proofs;
    }
    static boolean currentRoleSource(Fact f,Map<String,Source> sources){
        return f.sourceIds().stream().map(sources::get).filter(Objects::nonNull).anyMatch(s->EnterpriseSubjectBindings.companyRole(sourceRole(f))&&EnterpriseSubjectBindings.formalDocument(s)
            ?EnterpriseSubjectBindings.grounded(f,s):DocumentSubjectScope.appointment(s,f));
    }
    private static boolean labelRelation(String quote,String label,String value){Pattern direct=Pattern.compile(Pattern.quote(clean(label))+"[:：]"+Pattern.quote(clean(value))+"(?=$|[。；;,，、|（(])");return Arrays.stream(quote.split("[\\n。；;]")).anyMatch(line->direct.matcher(clean(line)).find());}
    static boolean employment(String relation){return relation.matches(".*(?:聘用|受聘|任职于|就职于|供职于|劳动关系|用人单位|雇佣|所属单位).*");}
    static String issue(Fact f,Map<String,Source> sources,List<Map<String,Object>> layout){
        List<Source> refs=f.sourceIds().stream().map(sources::get).filter(Objects::nonNull).toList();
        if(f.kind().equals("PERSON_ROLE")&&SubjectIdentity.person(f).isBlank())return "PERSON_ROLE_PERSON_UNPROVEN";
        if(f.kind().equals("PERSON_ROLE")&&f.scope().equals("CURRENT_PROJECT")&&!currentRoleSource(f,sources))return "CURRENT_ROLE_SOURCE_UNPROVEN";
        if(f.kind().equals("PERSON_ROLE"))for(String key:List.of("employer","lot"))if(known(str(f.attributes(),key))
            &&refs.stream().noneMatch(s->contains(subjectBlock(s.evidence().quote(),f.value()),str(f.attributes(),key))))return "APPOINTMENT_DIMENSION_UNPROVEN";
        if(f.kind().equals("RELATION")){
            if((role(f.subject())||role(str(f.attributes(),"object")))&&f.scope().equals("CURRENT_PROJECT")&&!currentRoleSource(f,sources))return "CURRENT_ROLE_SOURCE_UNPROVEN";
            if(employment(f.field())&&refs.stream().noneMatch(s->Arrays.stream(s.evidence().quote().split("[。；;\\n]"))
                .anyMatch(line->contains(line,f.subject())&&contains(line,str(f.attributes(),"object"))&&contains(line,f.field())
                    &&!line.matches(".*(?:拟聘|拟录用|中标后|不属于|未建立|不存在|非本单位|不受聘|未受聘|未聘用|不聘用|不任职|未任职|不是|并非).*"))))return "EMPLOYMENT_RELATION_UNPROVEN";
        }
        if(Set.of("CERTIFICATE","DATE_RANGE").contains(f.kind())){
            boolean structured=structuredCertificate(f,sources,layout);
            for(String key:List.of("category","number","numberField","employer"))if(!structured&&known(str(f.attributes(),key))
                &&refs.stream().noneMatch(s->contains(subjectBlock(s.evidence().quote(),f.subject()),str(f.attributes(),key))))return "CERTIFICATE_IDENTITY_FIELD_UNPROVEN";
            if(!structured&&certificateIdentity(f)&&refs.stream().noneMatch(s->certificateIdentityBound(f,s.evidence().quote())))return "CERTIFICATE_IDENTITY_BLOCK_AMBIGUOUS";
            String start=str(f.attributes(),"start"),end=str(f.attributes(),"end");
            if(!start.isBlank()||!end.isBlank()){
                String field=str(f.attributes(),f.kind().equals("CERTIFICATE")?"validityField":"field");
                if(!structured&&refs.stream().noneMatch(s->certificateDateBound(f,s.evidence().quote(),field,start,end)))return "CERTIFICATE_DATE_IDENTITY_UNPROVEN";
            }
        }
        if(Set.of("NUMBER","FIELD").contains(f.kind())&&money(f)){
            boolean tableAmount=LayoutBinding.amount(f,layout,sources);
            if(f.scope().equals("CURRENT_PROJECT")&&refs.stream().noneMatch(s->!s.historical()&&!s.index()&&(near(s.evidence().quote(),f.basis(),f.value())||tableAmount&&refs.stream().noneMatch(Source::historical))))return "PRICE_CURRENT_SOURCE_UNPROVEN";
            if(known(f.subject())&&!role(f.subject())&&refs.stream().noneMatch(s->contains(s.evidence().quote(),f.subject())))return "PRICE_SUBJECT_UNPROVEN";
            for(String key:List.of("lot","currency"))if(known(str(f.attributes(),key))&&refs.stream().noneMatch(s->{
                String local=priceBlock(s.evidence().quote(),f);return key.equals("currency")?currencyPresent(local,str(f.attributes(),key)):contains(local,str(f.attributes(),key));
            }))return "PRICE_DIMENSION_UNPROVEN";
            if(!tableAmount&&!f.basis().isBlank()&&refs.stream().noneMatch(s->near(s.evidence().quote(),f.basis(),f.value())))return "PRICE_VALUE_BASIS_UNPROVEN";
            if(!tableAmount&&!f.unit().isBlank()&&refs.stream().noneMatch(s->unitBound(numericContext(s.evidence().quote(),f.basis(),f.value()),f.value(),f.unit())))return "PRICE_UNIT_SOURCE_UNPROVEN";
            if(!str(f.attributes(),"taxBasis").isBlank()&&refs.stream().noneMatch(s->taxBound(s.evidence().quote(),f.basis(),f.value(),str(f.attributes(),"taxBasis"))))return "PRICE_TAX_SOURCE_UNPROVEN";
        }
        return null;
    }
    private static boolean structuredCertificate(Fact f,Map<String,Source> sources,List<Map<String,Object>> layout){
        if(layout.isEmpty()||strings(f.attributes().get("cellIds")).isEmpty()||!certificateIdentity(f))return false;
        var result=LayoutBinding.structure(f,layout,sources);if(!result.valid())return false;
        String field=str(f.attributes(),f.kind().equals("DATE_RANGE")?"field":"validityField");
        return result.rows().stream().anyMatch(row->row.name().equals(f.subject())
            &&str(f.attributes(),"category").equals(row.fields().get("CATEGORY"))
            &&str(f.attributes(),"number").equals(row.fields().getOrDefault(str(f.attributes(),"numberField"),row.fields().get("label:"+str(f.attributes(),"numberField"))))
            &&(!known(str(f.attributes(),"employer"))||str(f.attributes(),"employer").equals(row.employer()))
            &&(!known(str(f.attributes(),"start"))&&!known(str(f.attributes(),"end"))||!field.isBlank()
                &&datePresent(row.fields().getOrDefault("DATE:"+field,""),str(f.attributes(),"start"))
                &&datePresent(row.fields().getOrDefault("DATE:"+field,""),str(f.attributes(),"end"))));
    }
    private static String priceBlock(String text,Fact f){
        String block=known(f.subject())&&!role(f.subject())?subjectBlock(text,f.subject()):text;
        if(f.basis().isBlank()||!near(block,f.basis(),f.value()))return "";
        // Multiple identical price labels in one unstructured record cannot establish a single row.
        int first=block.indexOf(f.basis());return first<0||block.indexOf(f.basis(),first+f.basis().length())>=0?"":block;
    }
    private static boolean certificateIdentityBound(Fact f,String text){String local=subjectBlock(text,f.subject());
        if(!contains(local,str(f.attributes(),"category"))||!contains(local,str(f.attributes(),"number"))||!near(local,str(f.attributes(),"numberField"),str(f.attributes(),"number")))return false;
        Set<String> numbers=new HashSet<>();Matcher numbered=CERT_NUMBER.matcher(local);while(numbered.find())numbers.add(clean(numbered.group(1)));
        return numbers.size()<=1&&(numbers.isEmpty()||numbers.contains(clean(str(f.attributes(),"number"))));
    }
    private static boolean certificateDateBound(Fact f,String text,String field,String start,String end){
        String local=subjectBlock(text,f.subject());if(local.isBlank()||field.isBlank())return false;
        String number=str(f.attributes(),"number");Set<String> numbers=new HashSet<>();Matcher numbered=CERT_NUMBER.matcher(local);while(numbered.find())numbers.add(clean(numbered.group(1)));
        if(numbers.size()>1||known(number)&&!contains(local,number))return false;
        String category=str(f.attributes(),"category");if(known(category)&&!contains(local,category))return false;
        // Dates must follow this validity label in one local field, never an issue date or another certificate's label.
        var values=DateFieldValues.read(local,field,2);
        List<java.time.LocalDate> expected=new ArrayList<>();if(!start.isBlank())expected.add(date(start));if(!end.isBlank())expected.add(date(end));
        return !expected.isEmpty()&&!expected.contains(null)&&values.equals(expected);
    }
    static boolean money(Fact f){return Set.of("元","万元","亿元","CNY","RMB","USD","美元","欧元","EUR","港元","HKD").contains(f.unit())
        ||known(str(f.attributes(),"currency"))||f.field().matches("(?i).*(?:price|amount|报价|造价|金额|价款).*");}
    static boolean money(Rule r,List<Fact> facts){return Set.of("元","万元","亿元","CNY","RMB","USD","美元","欧元","EUR","港元","HKD").contains(r.unit())
        ||known(str(r.parameters(),"currency"))||r.field().matches("(?i).*(?:price|amount|报价|造价|金额|价款).*")||facts.stream().anyMatch(DomainBindings::money);}
    static String tax(String value){return switch(clean(value)){case "不含税","未税","税前","EXCLUDING_TAX","EXCLUSIVE"->"EXCLUDING_TAX";case "含税","税后","INCLUDING_TAX","INCLUSIVE"->"INCLUDING_TAX";default->"";};}
    static String currency(String value){return switch(clean(value).toUpperCase(Locale.ROOT)){case "人民币","CNY","RMB","￥","¥"->"CNY";case "美元","USD","US$"->"USD";case "欧元","EUR","€"->"EUR";case "港元","港币","HKD","HK$"->"HKD";default->"";};}
    static boolean unitCurrencyMatches(String unit,String currency){return switch(unit){case "元","万元","亿元","CNY","RMB"->currency.equals("CNY");case "美元","USD"->currency.equals("USD");case "欧元","EUR"->currency.equals("EUR");case "港元","HKD"->currency.equals("HKD");default->false;};}
    private static boolean currencyPresent(String text,String value){String code=currency(value);if(code.isBlank())return false;return switch(code){case "CNY"->text.matches("(?s).*(?:人民币|(?i:CNY|RMB)|[￥¥]).*");case "USD"->text.matches("(?s).*(?:美元|(?i:USD)|US\\$).*");case "EUR"->text.matches("(?s).*(?:欧元|(?i:EUR)|€).*");default->text.matches("(?s).*(?:港元|港币|(?i:HKD)|HK\\$).*");};}
    static String amountBasis(String value){return switch(clean(value)){case "投标总报价","投标总价","投标报价合计"->"BID_TOTAL";case "合同总价","合同金额"->"CONTRACT_TOTAL";case "最高投标限价","最高限价","招标控制价"->"PRICE_CEILING";default->clean(value);};}
    static String lot(String value){return switch(clean(value)){case "不划分标段","不分标段","本项目不划分标段","本项目不分标段"->"EXPLICIT_UNDIVIDED_PROJECT";default->clean(value);};}
    static Map<String,String> priceDimensions(Fact f){return Map.of("subject",f.subject(),"lot",lot(str(f.attributes(),"lot")),"amountBasis",amountBasis(f.basis()),"taxBasis",tax(str(f.attributes(),"taxBasis")),"currency",currency(str(f.attributes(),"currency")));}
    static String certificateKey(Fact f){return f.subject()+"|"+str(f.attributes(),"category")+"|"+str(f.attributes(),"number")+"|"+str(f.attributes(),f.kind().equals("DATE_RANGE")?"field":"validityField");}
    static boolean certificateIdentity(Fact f){return known(f.subject())&&!role(f.subject())&&known(str(f.attributes(),"category"))&&known(str(f.attributes(),"number"))&&known(str(f.attributes(),"numberField"));}
    static boolean validityField(String value){return VALIDITY_FIELD.matcher(value).matches();}
    static Set<String> validityFields(Fact f,Map<String,Source> sources,List<Map<String,Object>> layout){Set<String> labels=new LinkedHashSet<>();
        if(structuredCertificate(f,sources,layout)){var binding=LayoutBinding.structure(f,layout,sources);for(var row:binding.rows())for(String field:row.fields().keySet())if(field.startsWith("DATE:")&&validityField(field.substring(5)))labels.add(field.substring(5));return labels;}
        for(String id:f.sourceIds()){Source source=sources.get(id);if(source==null||!certificateIdentityBound(f,source.evidence().quote()))continue;Matcher matcher=VALIDITY_FIELD.matcher(subjectBlock(source.evidence().quote(),f.subject()));while(matcher.find())labels.add(matcher.group());}
        return labels;}
}
