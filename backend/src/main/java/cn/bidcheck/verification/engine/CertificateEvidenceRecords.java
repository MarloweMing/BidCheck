package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Evidence;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Source-validated certificate fields are reusable facts; query tables remain row-scoped records. */
final class CertificateEvidenceRecords {
    private static final Pattern GRADE=Pattern.compile("(甲级|乙级|丙级|丁级|一级|二级|三级|特级|壹级|贰级|叁级)[。.;；]?$"),URL=Pattern.compile("(?i)(?:https?://|www\\.)[A-Za-z0-9.-]+(?:/[^\\s<>|]*)?");
    private static final Pattern SOURCE_RANGE=Pattern.compile("chars:(\\d+)-(\\d+)");
    private CertificateEvidenceRecords(){}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class Bound{
        private final Source source;
        private final List<Evidence> evidences;
        private final Map<String,Object> fields;
        @com.fasterxml.jackson.annotation.JsonCreator
        Bound(@com.fasterxml.jackson.annotation.JsonProperty("source") Source source, @com.fasterxml.jackson.annotation.JsonProperty("evidences") List<Evidence> evidences, @com.fasterxml.jackson.annotation.JsonProperty("fields") Map<String,Object> fields) { this.source = source; this.evidences = evidences; this.fields = fields; }
        public Source source() { return source; }
        public List<Evidence> evidences() { return evidences; }
        public Map<String,Object> fields() { return fields; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Bound that = (Bound) other; return java.util.Objects.equals(this.source, that.source) && java.util.Objects.equals(this.evidences, that.evidences) && java.util.Objects.equals(this.fields, that.fields); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.source); hash = 31 * hash + java.util.Objects.hashCode(this.evidences); hash = 31 * hash + java.util.Objects.hashCode(this.fields); return hash; }
        @Override public String toString() { return "Bound[" + "source=" + source + ", evidences=" + evidences + ", fields=" + fields + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    static final class QueryRow{
        private final Source source;
        private final Evidence page;
        private final String company;
        private final String category;
        private final String number;
        private final List<String> urls;
        private final Map<String,Object> fields;
        @com.fasterxml.jackson.annotation.JsonCreator
        QueryRow(@com.fasterxml.jackson.annotation.JsonProperty("source") Source source, @com.fasterxml.jackson.annotation.JsonProperty("page") Evidence page, @com.fasterxml.jackson.annotation.JsonProperty("company") String company, @com.fasterxml.jackson.annotation.JsonProperty("category") String category, @com.fasterxml.jackson.annotation.JsonProperty("number") String number, @com.fasterxml.jackson.annotation.JsonProperty("urls") List<String> urls, @com.fasterxml.jackson.annotation.JsonProperty("fields") Map<String,Object> fields) { this.source = source; this.page = page; this.company = company; this.category = category; this.number = number; this.urls = urls; this.fields = fields; }
        public Source source() { return source; }
        public Evidence page() { return page; }
        public String company() { return company; }
        public String category() { return category; }
        public String number() { return number; }
        public List<String> urls() { return urls; }
        public Map<String,Object> fields() { return fields; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; QueryRow that = (QueryRow) other; return java.util.Objects.equals(this.source, that.source) && java.util.Objects.equals(this.page, that.page) && java.util.Objects.equals(this.company, that.company) && java.util.Objects.equals(this.category, that.category) && java.util.Objects.equals(this.number, that.number) && java.util.Objects.equals(this.urls, that.urls) && java.util.Objects.equals(this.fields, that.fields); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.source); hash = 31 * hash + java.util.Objects.hashCode(this.page); hash = 31 * hash + java.util.Objects.hashCode(this.company); hash = 31 * hash + java.util.Objects.hashCode(this.category); hash = 31 * hash + java.util.Objects.hashCode(this.number); hash = 31 * hash + java.util.Objects.hashCode(this.urls); hash = 31 * hash + java.util.Objects.hashCode(this.fields); return hash; }
        @Override public String toString() { return "QueryRow[" + "source=" + source + ", page=" + page + ", company=" + company + ", category=" + category + ", number=" + number + ", urls=" + urls + ", fields=" + fields + "]"; }
}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Cell{
        private final String raw;
        private final int start;
        private final int end;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Cell(@com.fasterxml.jackson.annotation.JsonProperty("raw") String raw, @com.fasterxml.jackson.annotation.JsonProperty("start") int start, @com.fasterxml.jackson.annotation.JsonProperty("end") int end) { this.raw = raw; this.start = start; this.end = end; }
        public String raw() { return raw; }
        public int start() { return start; }
        public int end() { return end; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Cell that = (Cell) other; return java.util.Objects.equals(this.raw, that.raw) && java.util.Objects.equals(this.start, that.start) && java.util.Objects.equals(this.end, that.end); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.raw); hash = 31 * hash + java.util.Objects.hashCode(this.start); hash = 31 * hash + java.util.Objects.hashCode(this.end); return hash; }
        @Override public String toString() { return "Cell[" + "raw=" + raw + ", start=" + start + ", end=" + end + "]"; }
String value(){return clean(raw.replace("*","").replace("`",""));}}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Field{
        private final String value;
        private final Source source;
        private final Evidence evidence;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Field(@com.fasterxml.jackson.annotation.JsonProperty("value") String value, @com.fasterxml.jackson.annotation.JsonProperty("source") Source source, @com.fasterxml.jackson.annotation.JsonProperty("evidence") Evidence evidence) { this.value = value; this.source = source; this.evidence = evidence; }
        public String value() { return value; }
        public Source source() { return source; }
        public Evidence evidence() { return evidence; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Field that = (Field) other; return java.util.Objects.equals(this.value, that.value) && java.util.Objects.equals(this.source, that.source) && java.util.Objects.equals(this.evidence, that.evidence); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.value); hash = 31 * hash + java.util.Objects.hashCode(this.source); hash = 31 * hash + java.util.Objects.hashCode(this.evidence); return hash; }
        @Override public String toString() { return "Field[" + "value=" + value + ", source=" + source + ", evidence=" + evidence + "]"; }
Map<String,Object> audit(){return linked("value",value,"sourceId",source.id(),"source",evidence);}}
    static final class ScopeResolver {
        private final cn.bidcheck.domain.Domain.Document document;
        private final DocumentScopeProof.Resolver ordinary;
        private final MappedSectionScope certificates;
        ScopeResolver(cn.bidcheck.domain.Domain.Document document){this.document=document;ordinary=DocumentScopeProof.forDocument(document);certificates=MappedSectionScope.forCertificateConsumer(document);}
        Map<String,Object> current(Evidence evidence){
            Map<String,Object> proof=ordinary.currentEvidence(evidence);Object scope=proof.get("sourceScope");
            if(Boolean.TRUE.equals(proof.get("verifiedCurrent"))&&scope instanceof Map<?,?>&&"SERVER_MAPPED_SECTION_SCOPE".equals(((Map<?,?>) (scope)).get("derivedBy")))return proof;
            java.util.List<cn.bidcheck.domain.Domain.Page> pages=cn.bidcheck.compat.Java8Api.toList(document.pages().stream().filter(p->p.number()==evidence.page()&&p.locationType().equals(evidence.locationType())));if(pages.size()!=1)return cn.bidcheck.compat.Java8Api.mapOf();
            cn.bidcheck.domain.Domain.Page page=pages.get(0);String text=Objects.toString(page.text(),""),quote=evidence.quote();int at=text.indexOf(quote);if(cn.bidcheck.compat.Java8Api.isBlank(quote)||at<0||text.indexOf(quote,at+1)>=0)return cn.bidcheck.compat.Java8Api.mapOf();
            Map<String,Object> local=certificates.currentEvidence(page,quote);return local.isEmpty()?cn.bidcheck.compat.Java8Api.mapOf():linked("status","CURRENT_BID_SECTION","verifiedCurrent",true,"sourceScope",local,"proofScope","CERTIFICATE_DOCUMENT_SECTION_ONLY");
        }
    }

    static boolean usable(Source source){return source!=null&&!source.index()&&!source.historical()&&!source.scopeContext().blocked()&&!source.id().startsWith("V");}
    /** A bounded certificate section is a local document proof, never a global role/person reset. */
    static boolean usable(Source source,EvidenceCorpus corpus,Map<String,ScopeResolver> resolvers){
        if(usable(source))return true;
        return !mappedScope(source,corpus,resolvers).isEmpty();
    }
    static Map<String,Object> mappedScope(Source source,EvidenceCorpus corpus,Map<String,ScopeResolver> resolvers){
        // A historical flag inherited from a preceding attachment is not cleared. This
        // consumer may use only a fresh bounded proof whose entire range rejects history.
        if(source==null||source.index()||source.id().startsWith("V"))return cn.bidcheck.compat.Java8Api.mapOf();
        String documentId=source.evidence().documentId();cn.bidcheck.domain.Domain.Document document=corpus.documents.stream().filter(d->d.id().equals(documentId)).findFirst().orElse(null);if(document==null)return cn.bidcheck.compat.Java8Api.mapOf();
        return resolvers.computeIfAbsent(documentId,id->new ScopeResolver(document)).current(source.evidence());
    }
    static String category(String value){switch (clean(value)) {case "工程设计建筑装饰工程专项":return "建筑装饰工程设计专项";default:return clean(value);}}
    static String grade(String value){Matcher m=GRADE.matcher(clean(value));return m.find()?CredentialGrades.normalize(m.group(1)):"";}
    static String categoryWithoutGrade(String value){String text=clean(value);Matcher m=GRADE.matcher(text);return category(m.find()?text.substring(0,m.start()):text);}

    /** A license query is a result-row record. Search-input company names, table headings and
     * the following row can never supply its owner or number. Header/row source chunks may
     * differ, but every field must be covered by the immutable page's own source anchors. */
    static List<QueryRow> corporateQueryRows(EvidenceCorpus corpus,String expectedCompany,String expectedCategory){
        if(!expectedCategory.equals("安全生产许可证"))return cn.bidcheck.compat.Java8Api.listOf();
        Map<String,ScopeResolver> resolvers=new HashMap<>();Map<String,List<Source>> pages=new LinkedHashMap<>();
        for(Source source:corpus.all)if(usable(source,corpus,resolvers))pages.computeIfAbsent(EvidenceCorpus.pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType()),k->new ArrayList<>()).add(source);
        List<QueryRow> result=new ArrayList<>();
        for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Source>> entry:pages.entrySet()){
            cn.bidcheck.domain.Domain.Page page=corpus.pages.get(entry.getKey());if(page==null)continue;String text=page.text();
            if(!text.matches("(?s).*安全生产许可证(?:信息公示|查询|查询结果).*"))continue;
            List<Field> urls=urls(text,entry.getValue());if(urls.isEmpty())continue;
            List<Cell> header=cn.bidcheck.compat.Java8Api.listOf();int owner=-1,number=-1,offset=0;boolean results=false;
            for(String line:text.split("\\n",-1)){
                if(line.matches(".*(?:查询结果|结果表格|公示结果).*"))results=true;
                List<Cell> row=cells(line,offset);offset+=line.length()+1;
                if(row.isEmpty()){header=cn.bidcheck.compat.Java8Api.listOf();continue;}
                int ownerColumn=column(row,cn.bidcheck.compat.Java8Api.setOf("企业名称", "单位名称")),numberColumn=column(row,cn.bidcheck.compat.Java8Api.setOf("证号", "证书编号", "许可证号"));
                if(ownerColumn>=0&&numberColumn>=0&&results){header=row;owner=ownerColumn;number=numberColumn;continue;}
                if(header.isEmpty()||row.size()!=header.size()||row.stream().allMatch(c->c.value().matches("[:—-]+")))continue;
                if(!row.get(owner).value().equals(clean(expectedCompany)))continue;
                String certificateNumber=normalizeCertificateNumber(row.get(number).value());
                if(!certificateNumber.matches("[\\p{IsHan}A-Z0-9()\\[\\]/-]{4,80}"))continue;
                Field ownerField=field(clean(expectedCompany),row.get(owner),text,entry.getValue()),numberField=field(certificateNumber,row.get(number),text,entry.getValue());
                Field numberHeader=field(header.get(number).value(),header.get(number),text,entry.getValue());
                if(ownerField==null||numberField==null||numberHeader==null)continue;
                Source origin=numberField.source();Evidence e=origin.evidence();
                Evidence original=new Evidence(e.documentId(),e.documentName(),e.page(),e.locationType(),text,"chars:0-"+text.length(),e.confidence());
                result.add(new QueryRow(origin,original,expectedCompany,expectedCategory,certificateNumber,cn.bidcheck.compat.Java8Api.toList(urls.stream().map(Field::value)),
                        linked("SUBJECT",ownerField.audit(),"CERTIFICATE_NUMBER",numberField.audit(),"NUMBER_HEADER",numberHeader.audit(),"binding","SAME_IMMUTABLE_QUERY_RESULT_ROW")));
            }
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(result);
    }
    static String normalizeCertificateNumber(String number){return clean(number).replace('（','(').replace('）',')').replace('〔','[').replace('〕',']').toUpperCase(Locale.ROOT);}

    static List<Bound> certificates(Fact fact,EvidenceCorpus corpus){
        if(!fact.kind().equals("CERTIFICATE")||!DomainBindings.certificateIdentity(fact)||new ProofVerifier().issue(fact,corpus.byId)!=null||DomainBindings.issue(fact,corpus.byId,cn.bidcheck.compat.Java8Api.listOf())!=null)return cn.bidcheck.compat.Java8Api.listOf();
        cn.bidcheck.verification.engine.CertificateSourceComparison.Record record=CertificateRecordBindings.matching(fact,corpus.byId);
        if(record!=null){Map<String,Object> fields=new LinkedHashMap<>();record.fields().forEach((key,value)->fields.put(key,value.audit()));fields.put("RECORD_BINDING",CertificateRecordBindings.audit(record));
            return cn.bidcheck.compat.Java8Api.listOf(new Bound(record.sources().get(0),cn.bidcheck.compat.Java8Api.toList(record.fields().values().stream().map(CertificateSourceComparison.Field::evidence).distinct()),cn.bidcheck.compat.Java8Api.mapCopyOf(fields)));}
        List<Bound> out=new ArrayList<>();Map<String,ScopeResolver> resolvers=new HashMap<>();
        for(String id:fact.sourceIds()){
            Source source=corpus.byId.get(id);if(!usable(source,corpus,resolvers))continue;String text=source.evidence().quote();
            if(text.matches("(?s).*(?:查询截图|查询结果|证书列表|证书详情|资质查询|资质详情).*"))continue;
            if(!text.matches("(?s).*(?:资质证明|资格证明|资质证书|资格证书|注册证书|许可证|考核合格证书|职称证书|认证证书).*"))continue;
            // A fact is checked again against current immutable source IDs, not re-extracted with a
            // second stricter grammar. Attribute/holder co-location is already checked above.
            Map<String,Object> fields=new LinkedHashMap<>();List<Evidence> evidence=new ArrayList<>();boolean complete=true;
            Map<String,String> values=new LinkedHashMap<>();values.put("SUBJECT",fact.subject());values.put("CATEGORY",str(fact.attributes(),"category"));values.put("CERTIFICATE_NUMBER",str(fact.attributes(),"number"));
            for(String key:cn.bidcheck.compat.Java8Api.listOf("grade", "profession", "numberField", "validityField"))if(!cn.bidcheck.compat.Java8Api.isBlank(str(fact.attributes(),key)))values.put(key.toUpperCase(Locale.ROOT),str(fact.attributes(),key));
            for(java.util.Map.Entry<java.lang.String, java.lang.String> value:values.entrySet()){
                int from=text.indexOf(value.getValue());if(from<0){complete=false;break;}
                Evidence origin=source.evidence();Matcher range=SOURCE_RANGE.matcher(origin.anchor());if(!range.matches()){complete=false;break;}
                int offset=Integer.parseInt(range.group(1));Evidence field=new Evidence(origin.documentId(),origin.documentName(),origin.page(),origin.locationType(),value.getValue(),"chars:"+(offset+from)+"-"+(offset+from+value.getValue().length()),origin.confidence());
                fields.put(value.getKey(),linked("factId",fact.id(),"value",value.getValue(),"sourceId",source.id(),"source",field));evidence.add(field);
            }
            if(complete){if(source.scopeContext().blocked())fields.put("DOCUMENT_SCOPE",mappedScope(source,corpus,resolvers));out.add(new Bound(source,cn.bidcheck.compat.Java8Api.listCopyOf(evidence),cn.bidcheck.compat.Java8Api.mapCopyOf(fields)));}
        }return out;
    }

    static List<QualificationProofAssociation.Record> qualificationQueries(EvidenceCorpus corpus,String company,String requiredCategory){
        return qualificationQueries(corpus,company,requiredCategory,cn.bidcheck.compat.Java8Api.listOf());
    }
    static List<QualificationProofAssociation.Record> qualificationQueries(EvidenceCorpus corpus,String company,String requiredCategory,List<Map<String,Object>> layout){
        return qualificationQueries(corpus,company,requiredCategory,layout,"");
    }
    static List<QualificationProofAssociation.Record> qualificationQueries(EvidenceCorpus corpus,String company,String requiredCategory,List<Map<String,Object>> layout,String ambiguousNumber){
        Map<String,ScopeResolver> resolvers=new HashMap<>();Map<String,List<Source>> pages=new LinkedHashMap<>();for(Source source:corpus.all)if(usable(source,corpus,resolvers))pages.computeIfAbsent(EvidenceCorpus.pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType()),k->new ArrayList<>()).add(source);
        List<QualificationProofAssociation.Record> result=new ArrayList<>();
        for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Source>> entry:pages.entrySet()){
            cn.bidcheck.domain.Domain.Page page=corpus.pages.get(entry.getKey());if(page==null)continue;String text=page.text();
            if(!text.matches("(?s).*(?:查询截图|查询结果|资质查询|资质详情).*"))continue;
            Field owner=owner(text,company,entry.getValue());if(owner==null)continue;
            if(ambiguousNumber.isEmpty())result.addAll(structuredQueries(corpus,entry.getValue(),text,owner,company,requiredCategory,layout));
            List<Field> urls=urls(text,entry.getValue());List<Cell> header=cn.bidcheck.compat.Java8Api.listOf();int number=-1,name=-1,rank=-1,offset=0; Cell soleNumber=null;
            for(String line:text.split("\\n",-1)){
                List<Cell> cells=cells(line,offset);offset+=line.length()+1;
                if(cells.isEmpty()){header=cn.bidcheck.compat.Java8Api.listOf();continue;}
                int candidateNumber=column(cells,cn.bidcheck.compat.Java8Api.setOf("资质证书号", "资质证书编号", "证书编号", "证书号码")),candidateName=column(cells,cn.bidcheck.compat.Java8Api.setOf("资质名称", "资质类别及等级"));
                if(candidateName<0)candidateName=column(cells,cn.bidcheck.compat.Java8Api.setOf("资质类别", "证书类别"));
                if(candidateNumber>=0&&candidateName>=0){header=cells;number=candidateNumber;name=candidateName;rank=column(cells,cn.bidcheck.compat.Java8Api.setOf("资质等级", "等级"));
                    Map<String,Cell> tableNumbers=numberCells(text,offset,number,header.size());
                    soleNumber=ambiguousNumber.isEmpty()?(layout.isEmpty()&&tableNumbers.size()==1?tableNumbers.values().iterator().next():null)
                        :(tableNumbers.size()>1?tableNumbers.get(normalizeCertificateNumber(ambiguousNumber)):null);
                    continue;}
                if(header.isEmpty()||cells.size()!=header.size()||cells.stream().allMatch(c->c.value().matches("[:—-]+")))continue;
                String certificateNumber=cells.get(number).value().toUpperCase(Locale.ROOT),rawCategory=cells.get(name).value();
                boolean inherited=certificateNumber.isEmpty()&&soleNumber!=null;
                if(!ambiguousNumber.isEmpty()&&!inherited)continue;
                Cell numberCell=inherited?soleNumber:cells.get(number);
                certificateNumber=numberCell.value().toUpperCase(Locale.ROOT);
                if(!certificateNumber.matches("[A-Z0-9][A-Z0-9()（）/-]{3,79}"))continue;
                String actualCategory=categoryWithoutGrade(rawCategory),actualGrade=rank<0?grade(rawCategory):grade(cells.get(rank).value());
                if(!DomainBindings.sameCertificateCategory(actualCategory,category(requiredCategory))||cn.bidcheck.compat.Java8Api.isBlank(actualGrade))continue;
                Field num=field(certificateNumber,numberCell,text,entry.getValue()),cat=field(actualCategory,cells.get(name),text,entry.getValue()),grade=field(actualGrade,cells.get(rank<0?name:rank),text,entry.getValue());
                if(num==null||cat==null||grade==null)continue;
                List<Evidence> evidence=new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(owner.evidence(), num.evidence(), cat.evidence(), grade.evidence()));urls.forEach(u->evidence.add(u.evidence()));
                result.add(new QualificationProofAssociation.Record(num.source(),clean(company),actualCategory,certificateNumber,actualGrade,true,cn.bidcheck.compat.Java8Api.toList(urls.stream().map(Field::value)),cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()),linked("SUBJECT",owner.audit(),"CATEGORY",cat.audit(),"CERTIFICATE_NUMBER",num.audit(),"GRADE",grade.audit(),"URL",cn.bidcheck.compat.Java8Api.toList(urls.stream().map(Field::audit)),"binding",!ambiguousNumber.isEmpty()?"ROW_NUMBER_ASSOCIATION_REQUIRES_HUMAN_CONFIRMATION":inherited?"UNIQUE_NUMBER_IN_SAME_TABLE_AND_PAGE_OWNER":"EXPLICIT_QUERY_TABLE_ROW_AND_UNIQUE_PAGE_OWNER","inheritedCertificateNumber",inherited&&ambiguousNumber.isEmpty())));
            }
        }return result;
    }

    private static Cell soleNumberCell(String text,int offset,int column,int width){
        Map<String,Cell> numbers=numberCells(text,offset,column,width);
        return numbers.size()==1?numbers.values().iterator().next():null;
    }
    private static Map<String,Cell> numberCells(String text,int offset,int column,int width){
        Map<String,Cell> numbers=new LinkedHashMap<>();
        for(String line:text.substring(Math.min(offset,text.length())).split("\n",-1)){
            List<Cell> row=cells(line,offset);offset+=line.length()+1;
            if(row.size()!=width)break;
            String value=row.get(column).value();
            if(value.isEmpty()||value.matches("[:—-]+"))continue;
            if(!value.matches("[A-Za-z0-9][A-Za-z0-9()（）/-]{3,79}"))break;
            numbers.putIfAbsent(normalizeCertificateNumber(value),row.get(column));
        }
        return numbers;
    }

    /** A source-bound navigation request, not evidence that a blank cell inherits another row.
     * The host must read this frozen page's image/geometry before the query can become a fact. */
    static List<Map<String,Object>> qualificationLayoutActions(EvidenceCorpus corpus,String company,String requiredCategory,String requiredNumber,String targetId){
        Map<String,ScopeResolver> resolvers=new HashMap<>();Map<String,List<Source>> pages=new LinkedHashMap<>();
        for(Source source:corpus.all)if(usable(source,corpus,resolvers))pages.computeIfAbsent(EvidenceCorpus.pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType()),k->new ArrayList<>()).add(source);
        List<Map<String,Object>> actions=new ArrayList<>();String expectedNumber=normalizeCertificateNumber(requiredNumber);
        for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Source>> entry:pages.entrySet()){
            cn.bidcheck.domain.Domain.Page page=corpus.pages.get(entry.getKey());if(page==null)continue;String text=page.text();
            if(!text.matches("(?s).*(?:查询截图|查询结果|资质查询|资质详情).*"))continue;
            Field owner=owner(text,company,entry.getValue());if(owner==null)continue;
            List<Field> numberFields=new ArrayList<>(),categoryFields=new ArrayList<>();List<Cell> header=cn.bidcheck.compat.Java8Api.listOf();int number=-1,name=-1,offset=0;
            for(String line:text.split("\\n",-1)){
                List<Cell> row=cells(line,offset);offset+=line.length()+1;if(row.isEmpty()){header=cn.bidcheck.compat.Java8Api.listOf();continue;}
                int n=column(row,cn.bidcheck.compat.Java8Api.setOf("资质证书号", "资质证书编号", "证书编号", "证书号码")),c=column(row,cn.bidcheck.compat.Java8Api.setOf("资质名称", "资质类别及等级"));
                if(c<0)c=column(row,cn.bidcheck.compat.Java8Api.setOf("资质类别", "证书类别"));
                if(n>=0&&c>=0){header=row;number=n;name=c;continue;}
                if(header.isEmpty()||row.size()!=header.size())continue;
                if(normalizeCertificateNumber(row.get(number).value()).equals(expectedNumber)){Field value=field(expectedNumber,row.get(number),text,entry.getValue());if(value!=null)numberFields.add(value);}
                if(cn.bidcheck.compat.Java8Api.isBlank(row.get(number).value())&&DomainBindings.sameCertificateCategory(categoryWithoutGrade(row.get(name).value()),category(requiredCategory))){Field value=field(row.get(name).value(),row.get(name),text,entry.getValue());if(value!=null)categoryFields.add(value);}
            }
            if(numberFields.isEmpty()||categoryFields.isEmpty())continue;
            Source source=categoryFields.get(0).source();Evidence e=source.evidence();
            actions.add(linked("actionId","qualification-layout-"+identity(targetId+entry.getKey()+expectedNumber+source.documentHash()+source.documentVersion()).substring(0,24),
                    "tool","read_table_record","targetId",targetId,"sourceId",source.id(),"sourceIds",cn.bidcheck.compat.Java8Api.toList(entry.getValue().stream().map(Source::id).distinct()),
                    "arguments",linked("targetId",targetId,"sourceId",source.id()),"reasonCode","QUALIFICATION_QUERY_MERGED_CELL_UNVERIFIED",
                    "documentId",e.documentId(),"documentHash",source.documentHash(),"documentVersion",source.documentVersion(),"page",e.page(),"locationType",e.locationType(),
                    "certificateNumber",expectedNumber,"category",requiredCategory,"evidenceDiscovery",linked("owner",owner.audit(),"category",cn.bidcheck.compat.Java8Api.toList(categoryFields.stream().map(Field::audit)),"numberOnPage",cn.bidcheck.compat.Java8Api.toList(numberFields.stream().map(Field::audit)),"establishesMergedRelation",false)));
        }
        return cn.bidcheck.compat.Java8Api.listCopyOf(actions);
    }

    private static List<QualificationProofAssociation.Record> structuredQueries(EvidenceCorpus corpus,List<Source> sources,String text,Field owner,String company,String category,List<Map<String,Object>> layout){
        if(layout.isEmpty())return cn.bidcheck.compat.Java8Api.listOf();Fact origin=new Fact("query-layout","FIELD",company,"","","","","","CURRENT_PROJECT",cn.bidcheck.compat.Java8Api.toList(sources.stream().map(Source::id)),cn.bidcheck.compat.Java8Api.mapOf());
        java.util.List<java.util.Map<java.lang.String, java.lang.Object>> cells=cn.bidcheck.verification.facts.TableStructure.validatedCells(layout,LayoutBinding.sources(origin,corpus.byId));List<QualificationProofAssociation.Record> result=new ArrayList<>();
        for(Map<String,Object> categoryCell:cells){if(!cn.bidcheck.compat.Java8Api.setOf("资质名称", "资质类别及等级", "资质类别", "证书类别").contains(cellLabel(categoryCell,cells)))continue;
            String rawCategory=str(categoryCell,"text"),actualCategory=categoryWithoutGrade(rawCategory);if(!DomainBindings.sameCertificateCategory(actualCategory,category(category)))continue;
            int row=RunSession.number(categoryCell.get("row"),0);List<Map<String,Object>> members=cn.bidcheck.compat.Java8Api.toList(cells.stream().filter(c->sameGrid(categoryCell,c)&&!Boolean.TRUE.equals(c.get("header"))&&RunSession.number(c.get("row"),0)<=row&&RunSession.number(c.get("row"),0)+RunSession.number(c.get("rowSpan"),1)>row));
            java.util.List<java.util.Map<java.lang.String, java.lang.Object>> numbers=cn.bidcheck.compat.Java8Api.toList(members.stream().filter(c->cn.bidcheck.compat.Java8Api.setOf("资质证书号", "资质证书编号", "证书编号", "证书号码").contains(cellLabel(c,cells))));
            if(numbers.isEmpty()){
                List<Map<String,Object>> sameTable=new ArrayList<>();
                for(Map<String,Object> cell:cells)if(sameGrid(categoryCell,cell)&&
                    cn.bidcheck.compat.Java8Api.setOf("资质证书号","资质证书编号","证书编号","证书号码").contains(cellLabel(cell,cells))&&
                    !clean(str(cell,"text")).isEmpty())sameTable.add(cell);
                // A cropped layout does not prove that the full table has only one number.
                // For a missing rowspan require the complete, contiguous source table as well.
                if(!sameTable.isEmpty()&&sameTable.stream().map(c->normalizeCertificateNumber(str(c,"text"))).distinct().count()==1
                        &&literalTableOwnsUniqueNumber(text,rawCategory,str(sameTable.get(0),"text")))
                    numbers=Collections.singletonList(sameTable.get(0));
            }
            if(numbers.size()!=1)continue;java.util.Map<java.lang.String, java.lang.Object> numberCell=numbers.get(0);String number=normalizeCertificateNumber(str(numberCell,"text"));if(!number.matches("[A-Z0-9][A-Z0-9()/\\[\\]-]{3,79}"))continue;
            java.util.List<java.util.Map<java.lang.String, java.lang.Object>> ranks=cn.bidcheck.compat.Java8Api.toList(members.stream().filter(c->cn.bidcheck.compat.Java8Api.setOf("等级", "资质等级").contains(cellLabel(c,cells))));if(ranks.size()>1)continue;java.util.Map<java.lang.String, java.lang.Object> rankCell=ranks.isEmpty()?categoryCell:ranks.get(0);String rank=grade(str(rankCell,"text"));if(cn.bidcheck.compat.Java8Api.isBlank(rank))continue;
            List<String> refs=new ArrayList<>();for(java.util.Map<java.lang.String, java.lang.Object> cell:cn.bidcheck.compat.Java8Api.listOf(categoryCell, numberCell, rankCell))refs.addAll(ProofVerifier.strings(cell.get("_sourceIds")));
            List<Source> used=cn.bidcheck.compat.Java8Api.toList(refs.stream().distinct().map(corpus.byId::get).filter(Objects::nonNull));if(used.isEmpty())continue;List<Field> urls=urls(text,sources);
            List<Evidence> evidence=new ArrayList<>(cn.bidcheck.compat.Java8Api.listOf(owner.evidence()));used.forEach(s->evidence.add(s.evidence()));urls.forEach(u->evidence.add(u.evidence()));
            result.add(new QualificationProofAssociation.Record(used.get(0),clean(company),actualCategory,number,rank,true,cn.bidcheck.compat.Java8Api.toList(urls.stream().map(Field::value)),cn.bidcheck.compat.Java8Api.toList(evidence.stream().distinct()),
                linked("SUBJECT",owner.audit(),"CATEGORY",categoryCell,"CERTIFICATE_NUMBER",numberCell,"GRADE",rankCell,"binding",members.contains(numberCell)?"SOURCE_BOUND_TABLE_ROW_WITH_EXPLICIT_SPAN":"UNIQUE_NUMBER_IN_SAME_VALIDATED_GRID","row",row,"inheritedCertificateNumber",RunSession.number(numberCell.get("row"),0)<row,"crossPageInheritance",false)));
        }return result;
    }
    private static boolean sameGrid(Map<String,Object> left,Map<String,Object> right){return cn.bidcheck.compat.Java8Api.listOf("documentId", "documentHash", "documentVersion", "locationType", "location", "tableId", "frameId").stream().allMatch(k->Objects.equals(left.get(k),right.get(k)));}
    private static boolean literalTableOwnsUniqueNumber(String text,String rawCategory,String expected){
        int offset=0,col=-1,category=-1,width=0;
        Cell only=null;
        for(String line:text.split("\n",-1)){
            List<Cell> row=cells(line,offset);offset+=line.length()+1;
            if(row.isEmpty()){
                only=null;
                if(!line.contains("|")&&line.matches(".*(?:证书编号|资质证书号|证书号码).*资质(?:名称|类别).*")){
                    StringBuilder grid=new StringBuilder();
                    for(String body:text.substring(Math.min(offset,text.length())).split("\n",-1)){
                        if(body.trim().isEmpty()||body.contains("://")||body.contains("证书编号")||body.contains("|"))break;
                        grid.append(body).append('\n');
                    }
                    Set<String> numbers=new HashSet<>();
                    Matcher number=Pattern.compile("(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9-]*[0-9][A-Za-z0-9-]{2,})(?![A-Za-z0-9])").matcher(grid);
                    while(number.find())numbers.add(normalizeCertificateNumber(number.group(1)));
                    if(clean(grid.toString()).contains(clean(rawCategory))&&numbers.equals(Collections.singleton(normalizeCertificateNumber(expected))))return true;
                }
                continue;
            }
            int num=column(row,cn.bidcheck.compat.Java8Api.setOf("证书编号","资质证书号","资质证书编号","证书号码"));
            if(num>=0){
                col=num;width=row.size();category=column(row,cn.bidcheck.compat.Java8Api.setOf("资质名称","资质类别及等级","资质类别","证书类别"));
                only=soleNumberCell(text,offset,col,width);continue;
            }
            if(only!=null&&row.size()==width&&category>=0&&row.get(category).value().equals(clean(rawCategory))&&row.get(col).value().isEmpty()
                    &&normalizeCertificateNumber(only.value()).equals(normalizeCertificateNumber(expected)))return true;
        }
        return false;
    }
    private static String cellLabel(Map<String,Object> cell,List<Map<String,Object>> cells){
        if(Boolean.TRUE.equals(cell.get("header"))||RunSession.number(cell.get("columnSpan"),1)!=1)return "";List<String> refs=ProofVerifier.strings(cell.get("headerCellIds"));if(refs.isEmpty())return "";
        List<Map<String,Object>> headers=cn.bidcheck.compat.Java8Api.toList(cells.stream().filter(h->sameGrid(cell,h)&&Boolean.TRUE.equals(h.get("header"))&&refs.contains(str(h,"cellId"))&&RunSession.number(h.get("row"),0)+RunSession.number(h.get("rowSpan"),1)<=RunSession.number(cell.get("row"),0)&&RunSession.number(h.get("column"),0)<=RunSession.number(cell.get("column"),0)&&RunSession.number(h.get("column"),0)+RunSession.number(h.get("columnSpan"),1)>RunSession.number(cell.get("column"),0)));
        return headers.size()==1?clean(str(headers.get(0),"text")):"";
    }

    private static Field owner(String text,String expected,List<Source> sources){
        List<Cell> values=new ArrayList<>();int offset=0;
        for(String line:text.split("\\n",-1)){
            String plain=clean(line.replace("*","").replace("`",""));
            Matcher heading=Pattern.compile("^#{1,6}([^#|:：]{2,100}(?:有限公司|有限责任公司|公司|研究院|设计院))$").matcher(plain);
            Matcher label=Pattern.compile("^(?:企业名称|单位名称|组织名称|持证单位)[:：]([^|]+)$").matcher(plain);
            if(heading.matches()||label.matches()){String name=heading.matches()?heading.group(1):label.group(1);int start=line.indexOf(name);if(start>=0)values.add(new Cell(name,offset+start,offset+start+name.length()));}
            List<Cell> row=cells(line,offset);if(row.size()==2&&cn.bidcheck.compat.Java8Api.setOf("企业名称", "单位名称", "组织名称", "持证单位").contains(row.get(0).value()))values.add(row.get(1));
            offset+=line.length()+1;
        }
        Set<String> names=new LinkedHashSet<>();values.forEach(c->names.add(c.value()));
        if(!names.equals(cn.bidcheck.compat.Java8Api.setOf(clean(expected))))return null;return values.isEmpty()?null:field(clean(expected),values.get(0),text,sources);
    }
    private static List<Field> urls(String text,List<Source> sources){List<Field> out=new ArrayList<>();Matcher matcher=URL.matcher(text);while(matcher.find())if(!matcher.group().matches("(?i).*(?:example\\.(?:com|org)|placeholder|image_url).*")){Field f=field(matcher.group(),new Cell(matcher.group(),matcher.start(),matcher.end()),text,sources);if(f!=null)out.add(f);}return out;}
    private static Field field(String value,Cell cell,String text,List<Source> sources){for(Source source:sources){Matcher range=SOURCE_RANGE.matcher(source.evidence().anchor());if(!range.matches()||cell.start()<Integer.parseInt(range.group(1))||cell.end()>Integer.parseInt(range.group(2)))continue;Evidence e=source.evidence();return new Field(value,source,new Evidence(e.documentId(),e.documentName(),e.page(),e.locationType(),text.substring(cell.start(),cell.end()),"chars:"+cell.start()+"-"+cell.end(),e.confidence()));}return null;}
    private static int column(List<Cell> cells,Set<String> labels){int found=-1;for(int i=0;i<cells.size();i++)if(labels.contains(cells.get(i).value())){if(found>=0)return -1;found=i;}return found;}
    private static List<Cell> cells(String line,int offset){if(!cn.bidcheck.compat.Java8Api.strip(line).startsWith("|"))return cn.bidcheck.compat.Java8Api.listOf();List<Cell> out=new ArrayList<>();int from=line.indexOf('|')+1,to;while((to=line.indexOf('|',from))>=0){out.add(new Cell(line.substring(from,to),offset+from,offset+to));from=to+1;}return out;}
}
