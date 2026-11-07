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
    private static final Pattern NUMBER_LABEL=Pattern.compile("(?:证书编号|证书号码|注册编号|注册证号|资格证号)\\s*[:：]\\s*([^\\s;；，,。|<>]{2,80})");
    private static final Pattern VERSION_LABEL=Pattern.compile("(?:^|[；;。\\n])\\s*(旧证|新证|原证|续期证)(?=\\s*(?:注册|证书|使用|资格)?有效期)");

    /** Work inside the already selected holder. An ambiguous boundary yields no evidence,
     * and repeated validity labels without an explicit version marker stay ambiguous. */
    static String selectSegment(String holderBlock,Fact fact){
        String number=str(fact.attributes(),"number");
        if(holderBlock.isEmpty()||number.isEmpty())return holderBlock;
        Matcher marks=NUMBER_LABEL.matcher(holderBlock);
        List<Integer> starts=new ArrayList<>();
        List<String> numbers=new ArrayList<>();
        int previousEnd=0;
        while(marks.find()){
            int start=0;
            if(!numbers.isEmpty()){
                start=recordStart(holderBlock,marks.start(),previousEnd);
                if(start<0)return "";
            }
            starts.add(start);numbers.add(marks.group(1));previousEnd=marks.end();
        }
        if(numbers.isEmpty())return holderBlock;
        List<String> matches=new ArrayList<>();
        for(int i=0;i<numbers.size();i++){
            if(!number.equals(numbers.get(i)))continue;
            String record=holderBlock.substring(starts.get(i),i+1<starts.size()?starts.get(i+1):holderBlock.length());
            String selected=versionSegment(record,fact);
            if(!selected.isEmpty())matches.add(selected);
        }
        return matches.size()==1?matches.get(0):"";
    }

    private static int recordStart(String text,int at,int previousEnd){
        for(int i=at-1;i>=previousEnd;i--)if("；;。\n".indexOf(text.charAt(i))>=0)return i+1;
        return -1;
    }

    private static String versionSegment(String record,Fact fact){
        Matcher labels=VERSION_LABEL.matcher(record);List<Integer> starts=new ArrayList<>();
        while(labels.find())starts.add(labels.start(1));
        if(starts.size()<2)return record;
        String identity=record.substring(0,starts.get(0));
        String field=str(fact.attributes(),"validityField"),start=str(fact.attributes(),"start"),end=str(fact.attributes(),"end");
        if(field.isEmpty()||end.isEmpty())return "";
        List<java.time.LocalDate> expected=new ArrayList<>();
        if(!start.isEmpty())expected.add(ProofVerifier.date(start));
        expected.add(ProofVerifier.date(end));
        if(expected.contains(null))return "";
        String result="";
        for(int i=0;i<starts.size();i++){
            String version=record.substring(starts.get(i),i+1<starts.size()?starts.get(i+1):record.length());
            if(!cn.bidcheck.verification.source.DateFieldValues.read(version,field,2).equals(expected))continue;
            if(!result.isEmpty())return "";
            result=identity+version;
        }
        return result;
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Original{
        private final String text;
        private final List<Source> sources;
        @com.fasterxml.jackson.annotation.JsonCreator
        Original(@com.fasterxml.jackson.annotation.JsonProperty("text") String text, @com.fasterxml.jackson.annotation.JsonProperty("sources") List<Source> sources) { this.text = text; this.sources = sources; }
        public String text() { return text; }
        public List<Source> sources() { return sources; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Original that = (Original) other; return java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.sources, that.sources); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.text); hash = 31 * hash + java.util.Objects.hashCode(this.sources); return hash; }
        @Override public String toString() { return "Original[" + "text=" + text + ", sources=" + sources + "]"; }
}
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
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class PageIdentity{
        private final int length;
        private final String hash;
        @com.fasterxml.jackson.annotation.JsonCreator
        private PageIdentity(@com.fasterxml.jackson.annotation.JsonProperty("length") int length, @com.fasterxml.jackson.annotation.JsonProperty("hash") String hash) { this.length = length; this.hash = hash; }
        public int length() { return length; }
        public String hash() { return hash; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; PageIdentity that = (PageIdentity) other; return java.util.Objects.equals(this.length, that.length) && java.util.Objects.equals(this.hash, that.hash); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.length); hash = 31 * hash + java.util.Objects.hashCode(this.hash); return hash; }
        @Override public String toString() { return "PageIdentity[" + "length=" + length + ", hash=" + hash + "]"; }
}

    static List<CertificateSourceComparison.Record> records(EvidenceCorpus corpus,List<Source> selected){
        Set<String> selectedPages=new HashSet<>();for(Source s:selected)selectedPages.add(pageKey(s));
        Map<String,List<Source>> pages=new LinkedHashMap<>();
        for(Source s:corpus.all)if(selectedPages.contains(pageKey(s))&&!s.id().startsWith("V"))pages.computeIfAbsent(pageKey(s),k->new ArrayList<>()).add(s);
        List<CertificateSourceComparison.Record> out=new ArrayList<>();
        for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Source>> entry:pages.entrySet()){
            Original original=original(entry.getValue());if(original==null)continue;
            cn.bidcheck.domain.Domain.Page page=corpus.pages.get(entry.getKey());if(page==null||!original.text().equals(Objects.toString(page.text(),"")))continue;
            out.addAll(read(original));
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }

    /** A fresh source re-read is the authorization; a claimed bindingMethod/recordId is not. */
    static boolean matches(Fact fact,Map<String,Source> sources){return matching(fact,sources)!=null;}
    static CertificateSourceComparison.Record matching(Fact fact,Map<String,Source> sources){
        if(!(sources instanceof SourceIndex))return null;
        if(!fact.kind().equals("CERTIFICATE")||fact.sourceIds().isEmpty()||!DomainBindings.certificateIdentity(fact))return null;
        List<Source> refs=new ArrayList<>();for(String id:fact.sourceIds()){Source source=sources.get(id);if(source==null)return null;refs.add(source);}
        String page=pageKey(refs.get(0));if(refs.stream().anyMatch(s->!pageKey(s).equals(page)))return null;
        // Do not accept a selected prefix when the host has more blocks of this occurrence.
        Set<String> ids=new HashSet<>(fact.sourceIds());if(sources.values().stream().anyMatch(s->pageKey(s).equals(page)&&!s.id().startsWith("V")&&!ids.contains(s.id())))return null;
        Original original=original(refs);if(original==null||!((SourceIndex) (sources)).complete(page,original.text()))return null;
        for(cn.bidcheck.verification.engine.CertificateSourceComparison.Record record:read(original))if(matchesFields(fact,record))return record;
        return null;
    }

    static Set<String> validityFields(Fact fact,Map<String,Source> sources){
        cn.bidcheck.verification.engine.CertificateSourceComparison.Record record=matching(fact,sources);if(record==null)return cn.bidcheck.compat.Java8Api.setOf();
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
            "sourceIds",cn.bidcheck.compat.Java8Api.toList(record.sources().stream().map(Source::id)),"fields",cn.bidcheck.compat.Java8Api.toList(record.fields().values().stream().sorted(Comparator.comparing(CertificateSourceComparison.Field::key)).map(CertificateSourceComparison.Field::audit)),
            "proves","LOCAL_CERTIFICATE_FIELDS_ONLY","provesCurrentAppointment",false);
    }

    private static boolean matchesFields(Fact fact,CertificateSourceComparison.Record record){
        if(!record.issues().isEmpty()||!record.owner().equals(fact.subject())||!DomainBindings.sameCertificateCategory(record.category(),str(fact.attributes(),"category")))return false;
        String numberKey=numberKey(record,str(fact.attributes(),"numberField"));
        if(cn.bidcheck.compat.Java8Api.isBlank(numberKey)||!record.value(numberKey).equals(PersonnelCertificateRecords.normalNumber(str(fact.attributes(),"number"))))return false;
        if(!cn.bidcheck.compat.Java8Api.isBlank(fact.value())&&!PersonnelCertificateRecords.normalNumber(fact.value()).equals(record.value(numberKey)))return false;
        String grade=str(fact.attributes(),"grade"),profession=str(fact.attributes(),"profession"),employer=str(fact.attributes(),"employer");
        if(!cn.bidcheck.compat.Java8Api.isBlank(grade)&&!record.category().startsWith(grade))return false;
        if(!cn.bidcheck.compat.Java8Api.isBlank(employer)&&!record.value("EMPLOYER").equals(employer))return false;
        if(!cn.bidcheck.compat.Java8Api.isBlank(profession)&&!record.value("PROFESSION").equals(profession)&&!record.value("PROFESSION:"+profession).equals(profession))return false;
        String field=str(fact.attributes(),"validityField"),start=str(fact.attributes(),"start"),end=str(fact.attributes(),"end");
        if(cn.bidcheck.compat.Java8Api.isBlank(field))return cn.bidcheck.compat.Java8Api.isBlank(start)&&cn.bidcheck.compat.Java8Api.isBlank(end);
        String prefix=field.equals("使用有效期")?"VALIDITY_USE_":"VALIDITY_",suffix="";
        if(!prefix.equals("VALIDITY_USE_")&&!cn.bidcheck.compat.Java8Api.isBlank(profession)&&record.fields().containsKey("VALIDITY_END:"+profession))suffix=":"+profession;
        cn.bidcheck.verification.engine.CertificateSourceComparison.Field ending=record.fields().get(prefix+"END"+suffix);cn.bidcheck.verification.engine.CertificateSourceComparison.Field beginning=record.fields().get(prefix+"START"+suffix);
        if(ending==null||!(ending.label().equals(field)||!cn.bidcheck.compat.Java8Api.isBlank(suffix)&&field.equals("有效期")))return false;
        return (cn.bidcheck.compat.Java8Api.isBlank(start)||beginning!=null&&beginning.value().equals(start))&&(cn.bidcheck.compat.Java8Api.isBlank(end)||ending.value().equals(end));
    }

    static String numberKey(CertificateSourceComparison.Record record,String label){
        for(String key:cn.bidcheck.compat.Java8Api.listOf("CERTIFICATE_NUMBER", "REGISTRATION_NUMBER")){cn.bidcheck.verification.engine.CertificateSourceComparison.Field f=record.fields().get(key);if(f!=null&&f.label().equals(label))return key;}return "";
    }

    private static List<CertificateSourceComparison.Record> read(Original original){
        String text=original.text();
        if(cn.bidcheck.compat.Java8Extras.matchCount(TITLE.matcher(text))!=1||cn.bidcheck.compat.Java8Extras.matchCount(HOLDER.matcher(text))!=1)return cn.bidcheck.compat.Java8Api.listOf();
        List<CertificateSourceComparison.Record> out=new ArrayList<>();
        for(cn.bidcheck.verification.engine.CertificateSourceComparison.Record record:PersonnelCertificateRecords.read(original.sources(),text))if(record.side().equals("CERTIFICATE")&&record.issues().isEmpty()){
            Matcher title=TITLE.matcher(text);if(!title.find())continue;cn.bidcheck.verification.engine.CertificateSourceComparison.Field field=sourceField("CATEGORY","证书类别",record.category(),text,title.start(),title.end(),original.sources());if(field==null)continue;
            Map<String,CertificateSourceComparison.Field> fields=new LinkedHashMap<>(record.fields());fields.put("CATEGORY",field);
            out.add(new CertificateSourceComparison.Record(record.id(),record.side(),record.category(),record.owner(),record.sources(),cn.bidcheck.compat.Java8Api.mapCopyOf(fields),record.issues()));
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(out);
    }

    static CertificateSourceComparison.Field sourceField(String key,String label,String value,String text,int start,int end,List<Source> sources){
        for(Source source:sources){Matcher range=RANGE.matcher(source.evidence().anchor());if(!range.matches()||start<Integer.parseInt(range.group(1))||end>Integer.parseInt(range.group(2)))continue;
            cn.bidcheck.domain.Domain.Evidence e=source.evidence();String raw=text.substring(start,end);return new CertificateSourceComparison.Field(key,label,raw,value,source.id(),new cn.bidcheck.domain.Domain.Evidence(e.documentId(),e.documentName(),e.page(),e.locationType(),raw,"chars:"+start+"-"+end,e.confidence()));}
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
        return new Original(text.toString(),cn.bidcheck.compat.Java8Api.listCopyOf(ordered));
    }
    private static String pageKey(Source source){cn.bidcheck.domain.Domain.Evidence e=source.evidence();return EvidenceCorpus.pageKey(e.documentId(),e.page(),e.locationType());}
}
