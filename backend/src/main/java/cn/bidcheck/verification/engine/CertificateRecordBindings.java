package cn.bidcheck.verification.engine;

import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Reconstructs one original credential occurrence; never concatenates a model's quotations.
 * A record is useful only when its complete available source range is continuous, overlaps
 * agree byte for byte, and one labelled holder belongs to one explicit certificate title. */
final class CertificateRecordBindings {
    static final String VERSION="certificate-record-spans-1";
    private static final Pattern RANGE=Pattern.compile("chars:(\\d+)-(\\d+)");
    private static final Pattern TITLE=Pattern.compile("(?m)^[\\t #*]*(?:中华人民共和国)?[^\\r\\n:：|<>]{0,50}(?:注册证书|安全生产考核合格证书?)[\\t *]*\\r?$");
    private static final Pattern HOLDER=Pattern.compile("(?m)^[\\t ]*(?:#{1,6}[\\t ]*)?(?:姓\\s*名|持证人)\\s*[:：]\\s*[\\p{IsHan}·*＊×●]{2,12}[\\t ]*\\r?$");
    private CertificateRecordBindings(){}
    record Original(String text,List<Source> sources){}
    /** Only the corpus constructor registers complete immutable pages. A model-disclosed map
     * has no such provenance and cannot authorize a cross-block certificate binding. */
    static final class SourceIndex extends LinkedHashMap<String,Source>{
        private final Map<String,PageIdentity> originalPages=new HashMap<>();
        void registerPage(String documentId,int page,String locationType,String text){originalPages.put(EvidenceCorpus.pageKey(documentId,page,locationType),new PageIdentity(text.length(),identity(text)));}
        boolean complete(String page,String text){PageIdentity expected=originalPages.get(page);return expected!=null&&expected.length()==text.length()&&expected.hash().equals(identity(text));}
        SourceIndex subset(Collection<Source> disclosed){
            SourceIndex result=new SourceIndex();result.originalPages.putAll(originalPages);
            if(disclosed!=null)for(Source source:disclosed)if(source!=null&&source.equals(get(source.id())))result.put(source.id(),source);
            return result;
        }
    }
    private record PageIdentity(int length,String hash){}

    static List<CertificateSourceComparison.Record> records(EvidenceCorpus corpus,List<Source> selected){
        Set<String> selectedPages=new HashSet<>();for(Source s:selected)selectedPages.add(pageKey(s));
        Map<String,List<Source>> pages=new LinkedHashMap<>();
        for(Source s:corpus.all)if(selectedPages.contains(pageKey(s))&&!s.id().startsWith("V"))pages.computeIfAbsent(pageKey(s),k->new ArrayList<>()).add(s);
        List<CertificateSourceComparison.Record> out=new ArrayList<>();
        for(var entry:pages.entrySet()){
            Original original=original(entry.getValue());if(original==null)continue;
            var page=corpus.pages.get(entry.getKey());if(page==null||!original.text().equals(Objects.toString(page.text(),"")))continue;
            out.addAll(read(original));
        }
        return List.copyOf(out);
    }

    /** A fresh source re-read is the authorization; a claimed bindingMethod/recordId is not. */
    static boolean matches(Fact fact,Map<String,Source> sources){return matching(fact,sources)!=null;}
    static CertificateSourceComparison.Record matching(Fact fact,Map<String,Source> sources){
        if(!(sources instanceof SourceIndex index))return null;
        if(!fact.kind().equals("CERTIFICATE")||fact.sourceIds().isEmpty()||!DomainBindings.certificateIdentity(fact))return null;
        List<Source> refs=new ArrayList<>();for(String id:fact.sourceIds()){Source source=sources.get(id);if(source==null)return null;refs.add(source);}
        String page=pageKey(refs.get(0));if(refs.stream().anyMatch(s->!pageKey(s).equals(page)))return null;
        // Do not accept a selected prefix when the host has more blocks of this occurrence.
        Set<String> ids=new HashSet<>(fact.sourceIds());if(sources.values().stream().anyMatch(s->pageKey(s).equals(page)&&!s.id().startsWith("V")&&!ids.contains(s.id())))return null;
        Original original=original(refs);if(original==null||!index.complete(page,original.text()))return null;
        for(var record:read(original))if(matchesFields(fact,record))return record;
        return null;
    }

    static Set<String> validityFields(Fact fact,Map<String,Source> sources){
        var record=matching(fact,sources);if(record==null)return Set.of();
        Set<String> labels=new LinkedHashSet<>();
        if(record.fields().containsKey("VALIDITY_USE_START")||record.fields().containsKey("VALIDITY_USE_END"))labels.add("使用有效期");
        if(record.fields().keySet().stream().anyMatch(k->k.startsWith("VALIDITY_")&&!k.startsWith("VALIDITY_USE_"))){
            String profession=str(fact.attributes(),"profession");
            if(record.fields().containsKey("VALIDITY_END:"+profession))labels.add("有效期");
            else if(record.fields().containsKey("VALIDITY_END"))labels.add(record.fields().get("VALIDITY_END").label());
        }
        return labels;
    }

    static Map<String,Object> audit(CertificateSourceComparison.Record record){
        return linked("version",VERSION,"recordId",record.id(),"binding","UNIQUE_LABELLED_HOLDER_AND_CERTIFICATE_TITLE_IN_CONTIGUOUS_ORIGINAL_RANGE",
            "sourceIds",record.sources().stream().map(Source::id).toList(),"fields",record.fields().values().stream().sorted(Comparator.comparing(CertificateSourceComparison.Field::key)).map(CertificateSourceComparison.Field::audit).toList(),
            "proves","LOCAL_CERTIFICATE_FIELDS_ONLY","provesCurrentAppointment",false);
    }

    private static boolean matchesFields(Fact fact,CertificateSourceComparison.Record record){
        if(!record.issues().isEmpty()||!record.owner().equals(fact.subject())||!DomainBindings.sameCertificateCategory(record.category(),str(fact.attributes(),"category")))return false;
        String numberKey=numberKey(record,str(fact.attributes(),"numberField"));
        if(numberKey.isBlank()||!record.value(numberKey).equals(PersonnelCertificateRecords.normalNumber(str(fact.attributes(),"number"))))return false;
        if(!fact.value().isBlank()&&!PersonnelCertificateRecords.normalNumber(fact.value()).equals(record.value(numberKey)))return false;
        String grade=str(fact.attributes(),"grade"),profession=str(fact.attributes(),"profession"),employer=str(fact.attributes(),"employer");
        if(!grade.isBlank()&&!record.category().startsWith(grade))return false;
        if(!employer.isBlank()&&!record.value("EMPLOYER").equals(employer))return false;
        if(!profession.isBlank()&&!record.value("PROFESSION").equals(profession)&&!record.value("PROFESSION:"+profession).equals(profession))return false;
        String field=str(fact.attributes(),"validityField"),start=str(fact.attributes(),"start"),end=str(fact.attributes(),"end");
        if(field.isBlank())return start.isBlank()&&end.isBlank();
        String prefix=field.equals("使用有效期")?"VALIDITY_USE_":"VALIDITY_",suffix="";
        if(!prefix.equals("VALIDITY_USE_")&&!profession.isBlank()&&record.fields().containsKey("VALIDITY_END:"+profession))suffix=":"+profession;
        var ending=record.fields().get(prefix+"END"+suffix);var beginning=record.fields().get(prefix+"START"+suffix);
        if(ending==null||!(ending.label().equals(field)||!suffix.isBlank()&&field.equals("有效期")))return false;
        return (start.isBlank()||beginning!=null&&beginning.value().equals(start))&&(end.isBlank()||ending.value().equals(end));
    }

    static String numberKey(CertificateSourceComparison.Record record,String label){
        for(String key:List.of("CERTIFICATE_NUMBER","REGISTRATION_NUMBER")){var f=record.fields().get(key);if(f!=null&&f.label().equals(label))return key;}return "";
    }

    private static List<CertificateSourceComparison.Record> read(Original original){
        String text=original.text();
        if(TITLE.matcher(text).results().count()!=1||HOLDER.matcher(text).results().count()!=1)return List.of();
        List<CertificateSourceComparison.Record> out=new ArrayList<>();
        for(var record:PersonnelCertificateRecords.read(original.sources(),text))if(record.side().equals("CERTIFICATE")&&record.issues().isEmpty()){
            Matcher title=TITLE.matcher(text);if(!title.find())continue;var field=sourceField("CATEGORY","证书类别",record.category(),text,title.start(),title.end(),original.sources());if(field==null)continue;
            Map<String,CertificateSourceComparison.Field> fields=new LinkedHashMap<>(record.fields());fields.put("CATEGORY",field);
            out.add(new CertificateSourceComparison.Record(record.id(),record.side(),record.category(),record.owner(),record.sources(),Map.copyOf(fields),record.issues()));
        }
        return List.copyOf(out);
    }

    static CertificateSourceComparison.Field sourceField(String key,String label,String value,String text,int start,int end,List<Source> sources){
        for(Source source:sources){Matcher range=RANGE.matcher(source.evidence().anchor());if(!range.matches()||start<Integer.parseInt(range.group(1))||end>Integer.parseInt(range.group(2)))continue;
            var e=source.evidence();String raw=text.substring(start,end);return new CertificateSourceComparison.Field(key,label,raw,value,source.id(),new cn.bidcheck.domain.Domain.Evidence(e.documentId(),e.documentName(),e.page(),e.locationType(),raw,"chars:"+start+"-"+end,e.confidence()));}
        return null;
    }

    /** Anchor coverage also rejects reordered, mixed-revision and contradictory overlapping chunks. */
    private static Original original(List<Source> input){
        if(input.isEmpty()||input.size()>12)return null;
        Source first=input.get(0);String page=pageKey(first);
        List<Source> ordered=new ArrayList<>(input);Map<String,int[]> ranges=new HashMap<>();
        for(Source source:ordered){
            if(!CertificateEvidenceRecords.usable(source)||!page.equals(pageKey(source))||!source.documentHash().equals(first.documentHash())||source.documentVersion()!=first.documentVersion())return null;
            Matcher m=RANGE.matcher(source.evidence().anchor());if(!m.matches())return null;
            int from=Integer.parseInt(m.group(1)),to=Integer.parseInt(m.group(2));if(to<=from||to-from!=source.evidence().quote().length())return null;
            ranges.put(source.id(),new int[]{from,to});
        }
        ordered.sort(Comparator.comparingInt(s->ranges.get(s.id())[0]));StringBuilder text=new StringBuilder();
        for(Source source:ordered){int[] range=ranges.get(source.id());if(range[0]>text.length())return null;String quote=source.evidence().quote();int overlap=Math.min(text.length(),range[1])-range[0];
            if(overlap<0||!text.substring(range[0],range[0]+overlap).equals(quote.substring(0,overlap)))return null;
            if(range[1]>text.length())text.append(quote.substring(overlap));
        }
        return new Original(text.toString(),List.copyOf(ordered));
    }
    private static String pageKey(Source source){var e=source.evidence();return EvidenceCorpus.pageKey(e.documentId(),e.page(),e.locationType());}
}
