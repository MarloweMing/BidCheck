package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.Pattern;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Counterevidence is indexed by typed credential number, never by a shared holder name.
 * The caller supplies a server-created selection, not an assertion read from proof JSON. */
final class SourceLocalPersonnelCredentialClosure {
    static final String VERSION="selected-personnel-credential-source-review-1";
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SourceLocalPersonnelCredentialClosure.class);
    private SourceLocalPersonnelCredentialClosure(){}

    static Map<String,Object> review(Rule rule,PersonnelCredentialProof.Selection selection,RuleEvaluator evaluator){
        return reviewRecords(rule,selection.records(),evaluator);
    }
    /** The caller must reconstruct and validate its selected records in this evaluator. */
    static Map<String,Object> reviewRecords(Rule rule,List<CertificateSourceComparison.Record> selectedRecords,RuleEvaluator evaluator){
        long started=System.nanoTime();List<Map<String,Object>> witnesses=new ArrayList<>();
        List<CertificateSourceComparison.Record> all=evaluator.personnelCredentialRecords();
        Map<String,CertificateEvidenceRecords.ScopeResolver> scopes=new HashMap<>();
        for(var selected:selectedRecords){
            String key=selected.category().contains("建造师")&&selected.fields().containsKey("REGISTRATION_NUMBER")||!selected.fields().containsKey("CERTIFICATE_NUMBER")?"REGISTRATION_NUMBER":"CERTIFICATE_NUMBER";
            String number=normal(selected.value(key)),family=family(selected.category());
            Set<String> examined=new LinkedHashSet<>();List<Map<String,Object>> records=new ArrayList<>();
            // Do not filter by owner: a different holder on the same typed number is a contradiction.
            List<CertificateSourceComparison.Record> related=all.stream().filter(r->family(r.category()).equals(family)&&normal(r.value(key)).equals(number)).toList();
            if(number.isBlank()||related.isEmpty())return unresolved("SAME_CREDENTIAL_COUNTEREVIDENCE_UNRESOLVED",examined);
            for(var record:related){
                record.sources().forEach(s->examined.add(s.id()));
                if(!record.issues().isEmpty())return unresolved("SAME_CREDENTIAL_COUNTEREVIDENCE_UNRESOLVED",examined);
                if(!selected.owner().equals(record.owner()))return unresolved("SAME_CREDENTIAL_HOLDER_CONFLICT",examined);
                for(String field:List.of("PERSON_ID","BIRTH_DATE"))if(differentKnown(selected,record,field))return unresolved("SAME_CREDENTIAL_IDENTITY_CONFLICT",examined);
                if(RegistrationEmployerProof.applies(rule)){
                    if(differentKnown(selected,record,"EMPLOYER"))return unresolved("SAME_CREDENTIAL_EMPLOYER_CONFLICT",examined);
                }else if(rule.predicate().equals("CERTIFICATE")){
                    if(!selected.category().equals(record.category()))return unresolved("SAME_CREDENTIAL_FIELDS_CONFLICT",examined);
                    String profession=str(rule.parameters(),"requiredProfession").replace("房屋建筑工程","建筑工程");
                    if(!profession.isBlank()&&record.side().equals("CERTIFICATE")&&!professionSet(selected).equals(professionSet(record)))return unresolved("SAME_CREDENTIAL_FIELDS_CONFLICT",examined);
                }else{
                    // A query's aggregate end date is not the end date of one selected profession.
                    // Only equally typed fields can be compared; the independent screenshot rule
                    // remains responsible for missing/ambiguous query-to-certificate field mappings.
                    String profession=str(rule.parameters(),"requiredProfession").replace("房屋建筑工程","建筑工程");
                    String suffix=selected.fields().containsKey("VALIDITY_END:"+profession)?":"+profession:"";
                    for(String field:List.of("VALIDITY_START"+suffix,"VALIDITY_END"+suffix,"VALIDITY_USE_START","VALIDITY_USE_END"))
                        if(differentKnown(selected,record,field))return unresolved("SAME_CREDENTIAL_VALIDITY_CONFLICT",examined);
                }
                if(revoked(record,key,number,evaluator))return unresolved("SAME_CREDENTIAL_REVOCATION_FOUND",examined);
                records.add(linked("recordId",record.id(),"side",record.side(),"sourceIds",record.sources().stream().map(Source::id).toList()));
            }
            // An unparsed competitor must not disappear just because the parser failed to assign
            // a holder. Read the complete original page, so split retrieval blocks keep headers.
            Set<String> checkedPages=new HashSet<>();
            for(Source source:evaluator.corpus.all){
                if(!CertificateEvidenceRecords.usable(source,evaluator.corpus,scopes))continue;
                var e=source.evidence();String pageKey=EvidenceCorpus.pageKey(e.documentId(),e.page(),e.locationType());
                if(!checkedPages.add(pageKey))continue;var page=evaluator.corpus.pages.get(pageKey);if(page==null)continue;
                String text=Objects.toString(page.text(),"");
                if(!containsTypedNumber(text,number)||!text.contains(family)||text.matches("(?s).*(?:填写示例|示范文本|证书模板).*"))continue;
                boolean recordLike=text.matches("(?s).*(?:注册证书|安全生产考核合格证|查询截图|查询结果|执业注册信息|证书查询).*" );
                if(!recordLike)continue;
                List<CertificateSourceComparison.Record> onPage=related.stream().filter(r->r.sources().stream().anyMatch(s->samePage(s,source))).toList();
                if(onPage.isEmpty()){examined.add(source.id());return unresolved("SAME_CREDENTIAL_COUNTEREVIDENCE_UNRESOLVED",examined);}
                // When a parsed row exists, the same page may still contain another malformed row.
                // Require each numbered table row to map to a typed number field on that row.
                int offset=0;for(String line:text.split("\n",-1)){
                    if(line.strip().startsWith("|")&&hasNumber(line,number)){
                        final int from=offset,to=offset+line.length();
                        boolean mapped=onPage.stream().anyMatch(r->fieldWithin(r.fields().get(key),from,to));
                        if(!mapped){examined.add(source.id());return unresolved("SAME_CREDENTIAL_COUNTEREVIDENCE_UNRESOLVED",examined);}
                    }
                    offset+=line.length()+1;
                }
            }
            witnesses.add(linked("selectedRecordId",selected.id(),"credentialKeyHash",identity(family+"|"+key+"|"+number),"numberField",key,
                "examinedSourceIds",List.copyOf(examined),"sameNumberRecords",records));
        }
        LOG.info("bidcheck.same_credential_review ruleId={} executor={} selectedRecords={} status=CLOSED elapsedMs={}",rule.id(),VERSION,witnesses.size(),(System.nanoTime()-started)/1_000_000);
        return linked("version",VERSION,"complete",true,"witnesses",witnesses,"counterevidenceScope","CURRENT_TYPED_SAME_NUMBER_RECORDS_IN_FROZEN_TEXT","unreadImageCoverageProven",false);
    }
    static Map<String,Object> closure(Rule rule,Map<String,Object> review,RuleEvaluator evaluator){
        return linked("version",VERSION,"basis","REVERIFIED_SELECTED_PERSONNEL_CREDENTIAL_AND_SAME_NUMBER_COUNTEREVIDENCE",
            "scope",rule.predicate().equals("VALIDITY")?"ONE_CREDENTIAL_VALIDITY_AT_TENDER_EVENT":"SELECTED_PERSONNEL_CREDENTIAL_FIELDS_ONLY",
            "exhaustiveCandidateReview",false,"sameCredentialReview",review,"sourceReadReceipt",ProgramSourceRead.receipt(evaluator.corpus,List.of()),
            "provesEmployment",false,"provesQueryScreenshot",false);
    }
    private static Map<String,Object> unresolved(String reason,Set<String> sources){return linked("complete",false,"reasonCode",reason,"examinedSourceIds",List.copyOf(sources));}
    private static boolean differentKnown(CertificateSourceComparison.Record a,CertificateSourceComparison.Record b,String key){return !a.value(key).isBlank()&&!b.value(key).isBlank()&&!a.value(key).equals(b.value(key));}
    private static Set<String> professionSet(CertificateSourceComparison.Record record){Set<String> values=new TreeSet<>();for(var entry:record.fields().entrySet())if(entry.getKey().equals("PROFESSION")||entry.getKey().startsWith("PROFESSION:"))values.add(entry.getValue().value());return values;}
    private static String family(String category){return category.contains("建造师")?"建造师":category.startsWith("安全生产考核合格证书")?"安全生产考核合格证书":category;}
    private static String normal(String text){return PersonnelCertificateRecords.normalNumber(text);}
    private static boolean hasNumber(String text,String number){return Pattern.compile("(?<![A-Z0-9])"+Pattern.quote(number)+"(?![A-Z0-9])").matcher(normal(text)).find();}
    private static boolean containsTypedNumber(String text,String number){
        if(!hasNumber(text,number))return false;
        return text.matches("(?s).*(?:注册编号|注册号|证书编号|电子证书编号|编号|执业印章号).*" );
    }
    private static boolean samePage(Source left,Source right){return left.evidence().documentId().equals(right.evidence().documentId())&&left.evidence().page()==right.evidence().page()&&left.evidence().locationType().equals(right.evidence().locationType());}
    private static boolean fieldWithin(CertificateSourceComparison.Field field,int from,int to){if(field==null)return false;var m=Pattern.compile("chars:(\\d+)-(\\d+)").matcher(field.evidence().anchor());return m.matches()&&Integer.parseInt(m.group(1))>=from&&Integer.parseInt(m.group(2))<=to;}
    private static boolean revoked(CertificateSourceComparison.Record record,String key,String number,RuleEvaluator evaluator){
        for(Source source:record.sources()){
            var e=source.evidence();var page=evaluator.corpus.pages.get(EvidenceCorpus.pageKey(e.documentId(),e.page(),e.locationType()));if(page==null)continue;
            String text=page.text();int offset=0;for(String line:text.split("\n",-1)){
                if(line.matches(".*(?:吊销|撤销|注销|作废|已失效).*" )&&hasNumber(line,number)&&fieldWithin(record.fields().get(key),offset,offset+line.length()))return true;
                offset+=line.length()+1;
            }
            if(record.side().equals("CERTIFICATE")&&text.matches("(?s).*(?:证书状态|注册状态)\\s*[:：]\\s*(?:吊销|撤销|注销|作废|已失效).*"))return true;
        }return false;
    }
}
