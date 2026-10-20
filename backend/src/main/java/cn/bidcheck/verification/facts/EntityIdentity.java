package cn.bidcheck.verification.facts;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.facts.LedgerValues.*;

/** Names are candidates only. A strong key requires a labelled identifier bound to that name in one source window. */
public final class EntityIdentity {
    private EntityIdentity(){}
    /** Re-derived from currently validated source text; model attributes never establish a person identifier. */
    public static Map<String,Object> resolve(Map<String,Object> fact,List<Map<String,Object>> sources){
        String kind=text(fact,"kind"),name=kind.equals("PERSON_ROLE")?text(fact,"value"):text(fact,"subject");Map<String,Object> attrs=map(fact.get("attributes"));
        String type=kind.equals("CONTRACT")?"CONTRACT":kind.equals("CERTIFICATE")?"CERTIFICATE":kind.equals("PERSON_ROLE")?"PERSON":name.matches(".*(?:公司|集团|事务所|合作社|合伙企业)$")?"COMPANY":Set.of("PERSON","COMPANY","VEHICLE").contains(text(attrs,"entityType"))?text(attrs,"entityType"):"UNRESOLVED";
        if(kind.equals("CONTRACT"))name=text(attrs,"contractName").isBlank()?name:text(attrs,"contractName");
        List<Map<String,Object>> identifiers=new ArrayList<>();String strong="";
        for(Map<String,Object> source:sources){String quote=text(evidence(source.get("evidence")),"quote"),id=text(source,"id");
            if(type.equals("PERSON")){Matcher m=Pattern.compile("(?:身份证(?:号码|号)?|公民身份号码)[：:\\s]*([0-9]{17}[0-9Xx])(?![0-9])").matcher(quote);while(m.find())if(directlyBound(quote,name,m.start())){String normalized=m.group(1).toUpperCase(Locale.ROOT);strong="PERSON:CN_ID_SHA256:"+hash(normalized);identifiers.add(of("field","CN_ID","value",normalized,"sourceIds",List.of(id)));}}
            if(type.equals("COMPANY")){Matcher m=Pattern.compile("(?:统一社会信用代码|社会信用代码)[：:\\s]*([0-9A-Z]{18})(?![0-9A-Z])").matcher(quote);while(m.find())if(directlyBound(quote,name,m.start())){strong="COMPANY:CREDIT_CODE:"+m.group(1);identifiers.add(of("field","CREDIT_CODE","value",m.group(1),"sourceIds",List.of(id)));}}
            if(type.equals("VEHICLE")){
                // A nearby VIN alone is insufficient: require one bounded vehicle record and a genuine 17-character VIN.
                Matcher vins=Pattern.compile("(?i)(?:VIN|车架号码?|车辆识别代号|车辆识别代码)[：:=\\s]*([A-HJ-NPR-Z0-9]{17})(?![A-Z0-9])").matcher(quote);
                Set<String> values=new LinkedHashSet<>();while(vins.find())values.add(vins.group(1).toUpperCase(Locale.ROOT));
                Matcher plates=Pattern.compile("(?:车牌号码?|号牌号码|车牌)[：:=\\s]*([\\u4e00-\\u9fff][A-Za-z][A-Za-z0-9·・]{5,7})").matcher(quote);
                Set<String> plateValues=new HashSet<>();while(plates.find())plateValues.add(plates.group(1).toUpperCase(Locale.ROOT));
                String raw=text(fact,"rawValue");
                if(values.size()==1&&plateValues.size()<=1&&quote.length()<=900&&quote.lines().count()<=8&&!raw.isBlank()&&clean(quote).contains(clean(raw))){
                    String vin=values.iterator().next();
                    // A native extractor's proposed key must agree with the evidence; model attributes cannot override it.
                    if(!name.startsWith("VIN:")||name.substring(4).equalsIgnoreCase(vin)){
                        strong="VEHICLE:VIN:"+vin;identifiers.add(of("field","VIN","value",vin,"sourceIds",List.of(id)));
                    }
                }
            }
            if(type.equals("CERTIFICATE")){String number=text(attrs,"number"),field=text(attrs,"numberField"),category=text(attrs,"category");
                if(!number.isBlank()&&!field.isBlank()&&!category.isBlank()&&number.length()<=100&&sameStatement(quote,name,number)&&local(quote,field,number)&&local(quote,category,number)){strong="CERTIFICATE:"+clean(category)+":"+clean(field)+":"+clean(number);identifiers.add(of("field",field,"value",number,"category",category,"sourceIds",List.of(id)));}}
            if(type.equals("CONTRACT")){String number=text(attrs,"contractNumber"),party=text(attrs,"party");
                if(!number.isBlank()&&!party.isBlank()&&local(quote,name,number)&&local(quote,party,number)){strong="CONTRACT:"+clean(party)+":"+clean(name)+":"+clean(number);identifiers.add(of("field","contractNumber","value",number,"sourceIds",List.of(id)));}}
        }
        // Multiple different identifiers in the cited record are ambiguous; do not choose the last one.
        if(identifiers.stream().map(i->text(i,"field")+":"+text(i,"value")).distinct().count()>1)strong="";
        String localSources=sources.stream().map(s->text(s,"sourceFingerprint").isBlank()?hash(canonical(s)):text(s,"sourceFingerprint")).sorted().reduce("",(a,b)->a+":"+b);
        for(Map<String,Object> identifier:identifiers){String value=text(identifier,"value");identifier.put("value",redact(value));identifier.put("valueFingerprint",hash(value));}
        return of("id",dbKey("entity",strong.isBlank()?type+":"+name+":"+localSources:strong),"type",type,"label",redact(name),"displayName",redact(name),"identityStrength",strong.isBlank()?"SOURCE_LOCAL":"STRONG","identifiers",identifiers,"identityClaims",identifiers.stream().map(i->of("field",i.get("field"),"valueFingerprint",i.get("valueFingerprint"),"sourceIds",i.get("sourceIds"))).toList(),"candidateName",redact(clean(name)));
    }
    /** Person identity for certificate/month facts, without confusing the certificate's own identity with its holder. */
    public static Map<String,Object> resolveSubject(Map<String,Object> fact,List<Map<String,Object>> sources){
        Map<String,Object> person=new LinkedHashMap<>(fact);person.put("kind","PERSON");person.put("subject","PERSON_ROLE".equals(text(fact,"kind"))?text(fact,"value"):text(fact,"subject"));person.put("attributes",Map.of("entityType","PERSON"));return resolve(person,sources);
    }
    public static Map<String,Object> resolvePerson(String name,List<Map<String,Object>> sources){return resolve(of("kind","PERSON","subject",name,"attributes",Map.of("entityType","PERSON")),sources);}
    /** Structure is supplied by the trusted layout service and rechecked against every cited original source. */
    public static Map<String,Object> resolvePerson(String name,List<Map<String,Object>> sources,List<Map<String,Object>> layout,List<String> cellIds){
        Map<String,Object> result=new LinkedHashMap<>(resolvePerson(name,sources));
        TableStructure.Result structure=TableStructure.analyze(name,cellIds,layout,sources);
        List<Map<String,Object>> claims=new ArrayList<>(maps(result.get("identityClaims")));
        for(TableStructure.Row row:structure.rows())if(row.strongIdentity())claims.add(of("field","CN_ID","valueFingerprint",row.identifierFingerprint(),"sourceIds",row.sourceIds()));
        claims=claims.stream().distinct().toList();Set<String> fingerprints=new HashSet<>();claims.forEach(c->fingerprints.add(text(c,"valueFingerprint")));
        result.put("identityClaims",claims);result.put("structureProof",structure.proof());
        if(fingerprints.size()==1&&(structure.valid()||structure.rows().isEmpty())){result.put("identityStrength","STRONG");result.put("id",dbKey("entity","PERSON:CN_ID_SHA256:"+fingerprints.iterator().next()));}
        else if(fingerprints.size()>1||!structure.rows().isEmpty()&&!structure.valid())result.put("identityStrength","SOURCE_LOCAL");
        return result;
    }
    public static Map<String,Object> resolve(Map<String,Object> fact,List<Map<String,Object>> sources,List<Map<String,Object>> layout){
        String kind=text(fact,"kind");Map<String,Object> attrs=map(fact.get("attributes"));
        String name=kind.equals("PERSON_ROLE")?text(fact,"value"):kind.equals("MONTHS")?text(attrs,"person"):text(fact,"subject");
        Map<String,Object> base=resolve(fact,sources);
        if(Set.of("PERSON_ROLE","MONTHS","DATE_RANGE","CERTIFICATE").contains(kind)||"PERSON".equals(base.get("type"))){
            Map<String,Object> person=resolvePerson(name,sources,layout,strings(attrs.get("cellIds")));
            if(kind.equals("CERTIFICATE")){base.put("holderIdentity",person);base.put("structureProof",person.get("structureProof"));return base;}
            return person;
        }
        return base;
    }
    static boolean local(String quote,String a,String b){if(a.isBlank()||b.isBlank())return false;int at=quote.indexOf(a);while(at>=0){String window=quote.substring(Math.max(0,at-120),Math.min(quote.length(),at+a.length()+180));if(window.contains(b))return true;at=quote.indexOf(a,at+1);}return false;}
    /** Only the immediately preceding name field owns a labelled ID. Other people/attributes require a separate binding proof. */
    private static boolean directlyBound(String quote,String name,int labelAt){
        if(name.isBlank())return false;int nameAt=quote.lastIndexOf(name,labelAt);if(nameAt<0||nameAt+name.length()>labelAt||labelAt-nameAt>120)return false;
        String between=quote.substring(nameAt+name.length(),labelAt);
        // A single name followed by its ID label may use a comma/table separator; never cross a sentence, line, or another name/field.
        return !between.matches("(?s).*[\\r\\n；;。].*")&&between.replaceAll("[\\s，,:：|｜（）()]+","").isEmpty();
    }
    private static boolean sameStatement(String quote,String a,String b){if(a.isBlank()||b.isBlank())return false;return Arrays.stream(quote.split("[\\r\\n；;。]")).anyMatch(line->line.length()<=320&&line.contains(a)&&line.contains(b));}
    private static String redact(String text){return text.replaceAll("(?<![0-9])[0-9]{17}[0-9Xx](?![0-9])","[证件号码已隐藏]").replaceAll("(?<![0-9])1[3-9][0-9]{9}(?![0-9])","[手机号已隐藏]");}
    static String conflictKey(Map<String,Object> fact,Map<String,Object> entity,List<Map<String,Object>> sources){
        if(!"STRONG".equals(entity.get("identityStrength"))||text(fact,"field").isBlank()||!"CURRENT_PROJECT".equals(text(fact,"scope")))return "";
        Map<String,Object> attrs=map(fact.get("attributes"));String time=text(attrs,"referenceDate");if(time.isBlank())time=text(attrs,"effectiveAt");if(time.isBlank())time=text(attrs,"period");
        // Unknown time is not equal to a proven common time. Keep both occurrences without asserting conflict.
        String statedTime=time;if(statedTime.isBlank()||sources.stream().map(s->text(evidence(s.get("evidence")),"quote")).noneMatch(q->q.contains(statedTime)))return "";
        return canonical(List.of(text(entity,"id"),text(fact,"kind"),text(fact,"field"),text(fact,"unit"),text(fact,"basis"),text(attrs,"taxBasis"),time,text(fact,"scope")));
    }
}
