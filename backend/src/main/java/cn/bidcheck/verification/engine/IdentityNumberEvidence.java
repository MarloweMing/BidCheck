package cn.bidcheck.verification.engine;

import cn.bidcheck.verification.source.CitizenId;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** IDs split across unrelated text columns may corroborate an independently printed
 * number, but may never create a new standalone identity or a contradictory identity. */
public final class IdentityNumberEvidence {
    public enum Match { SAME, CONFLICT, INSUFFICIENT }
    private static final String LABEL="(?:身份证件号码(?:或证书编号)?|身份证号码|身份证号|身份证|公民身份号码|证件号码|社会保障号码)";
    private static final Pattern LABELLED=Pattern.compile(LABEL+"[\\s:：|*]*([0-9]{17}[0-9Xx])(?![0-9A-Za-z])");
    private static final Pattern SPLIT=Pattern.compile("(?<![0-9A-Za-z])([0-9]{5,17})(?![0-9])([^\\d]{1,80}?)(?<![0-9])([0-9]{1,12}[0-9Xx])(?![0-9A-Za-z])");
    private static final Pattern TABLE_ROW=Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>");
    private static final Pattern TABLE=Pattern.compile("(?is)<table\\b[^>]*>.*?(?:</table\\s*>|$)");
    private static final Pattern CELL=Pattern.compile("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]\\s*>");
    private static final Pattern OWNER=Pattern.compile("(?:姓\\s*名|持证人|参保人姓名)\\s*[:：|]?\\s*([\\p{IsHan}·*＊×]{2,12})");
    private IdentityNumberEvidence(){}

    static final class Numbers {
        final Set<String> complete=new LinkedHashSet<>(), fragments=new LinkedHashSet<>();
        private void add(String value,boolean fragment){
            if(CitizenId.valid(value))(fragment?fragments:complete).add(identity(value.toUpperCase(Locale.ROOT)));
        }
    }
    private static void readCells(String row,String name,Numbers out){
        Matcher cells=CELL.matcher(row);
        List<String> values=new ArrayList<>();
        while(cells.find())values.add(cells.group(1).replaceAll("(?is)<[^>]*>","").replaceAll("[\\s\\p{Z}]+",""));
        int index=values.indexOf(name);
        if(index>=0&&index==values.lastIndexOf(name)&&index+1<values.size())out.add(values.get(index+1),false);
    }
    private static void readText(String name,String quote,Numbers out){
        Matcher rows=TABLE_ROW.matcher(quote);while(rows.find())readCells(rows.group(1),name,out);
        for(String row:quote.split("\\R"))if(row.contains("|")){
            String[] cells=row.split("\\|",-1);for(int i=0;i+1<cells.length;i++)
                if(name.equals(cells[i].trim()))out.add(cells[i+1].replaceAll("\\s+",""),false);
        }
        Matcher owners=OWNER.matcher(quote);List<int[]> windows=new ArrayList<>();
        int start=-1;while(owners.find()){
            if(start>=0)windows.add(new int[]{start,owners.start()});
            start=name.equals(owners.group(1))?owners.end():-1;
        }
        if(start>=0)windows.add(new int[]{start,quote.length()});
        for(int[] w:windows){
            String local=quote.substring(w[0],Math.min(w[1],w[0]+300));
            Matcher boundary=Pattern.compile("(?m)^\\s*(?:主要工作经历|工作经历|毕业学校|参加过的类似项目)").matcher(local);
            if(boundary.find())local=local.substring(0,boundary.start());
            Matcher complete=LABELLED.matcher(local);
            while(complete.find())out.add(complete.group(1),false);
            Matcher split=SPLIT.matcher(local);while(split.find()){
                String bridge=split.group(2);
                // Plain-text table reconstruction needs a labelled, bounded ID column and
                // an independent complete counterpart, even when both fragments are digits.
                if(!bridge.matches("(?s).*(?:身份证|公民身份号码|证件号码).*")&&!local.substring(Math.max(0,split.start()-15),split.start()).matches("(?s).*"+LABEL+"[\\s:：|]*"))continue;
                if(bridge.matches("(?s).*(?:[；;。]|电\\s*话|联系人|姓\\s*名|持证人|出生日期).*"))continue;
                out.add(split.group(1)+split.group(3),true);
            }
        }
        // A named inline statement can lack a printed 姓名 label.
        Matcher inline=Pattern.compile(Pattern.quote(name)+"[ \\t,，:：]*"+LABEL+"[ \\t:：]*([0-9]{17}[0-9Xx])(?![0-9A-Za-z])").matcher(quote);
        while(inline.find())out.add(inline.group(1),false);
    }
    static Numbers read(Fact fact,EvidenceCorpus corpus){
        String name=SubjectIdentity.person(fact);if(name.isEmpty()&&"姓名".equals(fact.field()))name=fact.subject();
        if(name.isEmpty()||fact.sourceIds().isEmpty())return new Numbers();
        if(SocialInsuranceEvidenceRecords.claims(fact)){
            Numbers selected=new Numbers();
            if(SocialInsuranceEvidenceRecords.issue(fact,corpus.byId)==null){
                SocialInsuranceEvidenceRecords.Record row=SocialInsuranceEvidenceRecords.matching(fact,corpus.byId);
                if(row!=null&&!row.identifierFingerprint().isEmpty())selected.complete.add(row.identifierFingerprint());
            }
            return selected;
        }
        String key=fact.kind()+"|"+name+"|"+fact.sourceIds();
        final String person=name;
        return corpus.identityNumbers.computeIfAbsent(key,unused->{
            Numbers out=new Numbers();
            for(String id:fact.sourceIds()){
                Source source=corpus.byId.get(id);
                if(source==null||source.historical()||source.index()||source.scopeContext().blocked())return new Numbers();
                String text=source.evidence().quote();
                readText(person,"PERSON_ROLE".equals(fact.kind())?appointmentText(text):text,out);
            }
            return out;
        });
    }
    /** An appointment and a separate payment table may share the same physical source. */
    private static String appointmentText(String text){
        Matcher tables=TABLE.matcher(text);StringBuffer appointment=new StringBuffer();
        while(tables.find()){
            String table=tables.group();
            if(table.contains("险种")||table.contains("本单位实际缴费月数")||table.contains("养老保险"))tables.appendReplacement(appointment," ");
        }
        return tables.appendTail(appointment).toString();
    }
    static Match compare(Fact a,Fact b,EvidenceCorpus corpus){
        if(a.sourceIds().isEmpty()||b.sourceIds().isEmpty())return Match.INSUFFICIENT;
        Set<String> documents=new HashSet<>();
        for(Fact f:Arrays.asList(a,b))for(String id:f.sourceIds()){
            Source s=corpus.byId.get(id);if(s==null||s.index()||s.historical()||s.scopeContext().blocked())return Match.INSUFFICIENT;
            documents.add(s.evidence().documentId()+":"+s.documentHash()+":"+s.documentVersion());
        }
        if(documents.size()!=1)return Match.INSUFFICIENT;
        return compare(read(a,corpus),read(b,corpus));
    }
    static Match compare(Numbers left,Numbers right){
        if(left.complete.size()>1||right.complete.size()>1)return Match.INSUFFICIENT;
        if(!left.complete.isEmpty()&&!right.complete.isEmpty())return left.complete.equals(right.complete)?Match.SAME:Match.CONFLICT;
        if(left.fragments.size()==1&&left.fragments.equals(right.complete)||right.fragments.size()==1&&right.fragments.equals(left.complete))return Match.SAME;
        return Match.INSUFFICIENT;
    }
    static Decision attach(Rule rule,Decision d,List<Fact> facts,EvidenceCorpus corpus){
        if(!ProgrammaticInferenceCandidates.needsIdentity(d))return d;
        List<Map<String,Object>> edges=new ArrayList<>();boolean conflict=false;
        for(Fact role:facts){
            if(!"PERSON_ROLE".equals(role.kind())||!"CURRENT_PROJECT".equals(role.scope())||!(rule.subject().equals(role.value())||DomainBindings.roleMatches(rule.subject(),role.subject(),role,corpus))||!DomainBindings.currentRoleSource(role,corpus.byId))continue;
            for(Fact proof:facts){
                if(!Arrays.asList("CERTIFICATE","DATE_RANGE","MONTHS").contains(proof.kind())||!role.value().equals(SubjectIdentity.person(proof))
                    ||!ProgrammaticInferenceCandidates.certificateRelevant(rule,proof)||!Collections.disjoint(role.sourceIds(),proof.sourceIds())&&!SocialInsuranceEvidenceRecords.claims(proof))continue;
                if("MONTHS".equals(rule.predicate())&&!"MONTHS".equals(proof.kind())||"CERTIFICATE".equals(rule.predicate())&&!"CERTIFICATE".equals(proof.kind()))continue;
                Match match=compare(role,proof,corpus);if(match==Match.INSUFFICIENT)continue;
                edges.add(linked("method",match==Match.SAME?"ID_NUMBER_MATCH":"ID_NUMBER_PAIR_CONFLICT","businessProof",false,
                    "appointmentSourceIds",role.sourceIds(),"proofSourceIds",proof.sourceIds()));
                conflict|=match==Match.CONFLICT;
            }
        }
        if(edges.isEmpty())return d;
        Map<String,Object> audit=new LinkedHashMap<>(d.proof());audit.put("identityNumberRelations",edges);
        if(!conflict)return new Decision(d.id(),d.description(),d.status(),d.reason(),d.evidences(),d.gaps(),audit);
        List<Gap> gaps=new ArrayList<>(d.gaps());
        String reason="同一拟派人和证明材料中可验证的身份证号码不同，需核对资料归属";
        if(gaps.stream().noneMatch(g->"SUBJECT_IDENTITY_CONFLICT".equals(g.code())))gaps.add(gap("SUBJECT_IDENTITY_CONFLICT",reason));
        gaps.add(gap("ID_NUMBER_PAIR_CONFLICT",reason));
        return new Decision(d.id(),d.description(),"UNDETERMINED",reason,d.evidences(),gaps,audit);
    }
    /** Recheck a persisted question using its original unmasked cited pair prior to export. */
    public static Match citedPair(Map<String,Object> claim){
        if(!"PERSON_IDENTITY".equals(str(claim,"inferenceType")))return Match.INSUFFICIENT;
        if(!(claim.get("proposedFact") instanceof Map<?,?>))return Match.INSUFFICIENT;
        Map<?,?> fact=(Map<?,?>)claim.get("proposedFact");
        String name=Objects.toString(fact.get("subject"),"");if(name.isEmpty()||!name.equals(fact.get("value")))return Match.INSUFFICIENT;
        List<Map<String,Object>> citations=RuleEvaluator.maps(claim.get("evidences"));
        if(citations.size()!=2)return Match.INSUFFICIENT;
        Map<String,Object> left=citations.get(0),right=citations.get(1);
        if(!str(left,"documentId").equals(str(right,"documentId"))||!str(left,"documentHash").equals(str(right,"documentHash"))||!Objects.equals(left.get("documentVersion"),right.get("documentVersion")))return Match.INSUFFICIENT;
        Numbers a=new Numbers(),b=new Numbers();readText(name,appointmentText(str(left,"quote")),a);readText(name,str(right,"quote"),b);
        return compare(a,b);
    }
}
