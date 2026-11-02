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
    record Bound(Source source,List<Evidence> evidences,Map<String,Object> fields){}
    record QueryRow(Source source,Evidence page,String company,String category,String number,List<String> urls,Map<String,Object> fields){}
    private record Cell(String raw,int start,int end){String value(){return clean(raw.replace("*","").replace("`",""));}}
    private record Field(String value,Source source,Evidence evidence){Map<String,Object> audit(){return linked("value",value,"sourceId",source.id(),"source",evidence);}}
    static final class ScopeResolver {
        private final cn.bidcheck.domain.Domain.Document document;
        private final DocumentScopeProof.Resolver ordinary;
        private final MappedSectionScope certificates;
        ScopeResolver(cn.bidcheck.domain.Domain.Document document){this.document=document;ordinary=DocumentScopeProof.forDocument(document);certificates=MappedSectionScope.forCertificateConsumer(document);}
        Map<String,Object> current(Evidence evidence){
            Map<String,Object> proof=ordinary.currentEvidence(evidence);Object scope=proof.get("sourceScope");
            if(Boolean.TRUE.equals(proof.get("verifiedCurrent"))&&scope instanceof Map<?,?> values&&"SERVER_MAPPED_SECTION_SCOPE".equals(values.get("derivedBy")))return proof;
            var pages=document.pages().stream().filter(p->p.number()==evidence.page()&&p.locationType().equals(evidence.locationType())).toList();if(pages.size()!=1)return Map.of();
            var page=pages.get(0);String text=Objects.toString(page.text(),""),quote=evidence.quote();int at=text.indexOf(quote);if(quote.isBlank()||at<0||text.indexOf(quote,at+1)>=0)return Map.of();
            Map<String,Object> local=certificates.currentEvidence(page,quote);return local.isEmpty()?Map.of():linked("status","CURRENT_BID_SECTION","verifiedCurrent",true,"sourceScope",local,"proofScope","CERTIFICATE_DOCUMENT_SECTION_ONLY");
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
        if(source==null||source.index()||source.id().startsWith("V"))return Map.of();
        String documentId=source.evidence().documentId();var document=corpus.documents.stream().filter(d->d.id().equals(documentId)).findFirst().orElse(null);if(document==null)return Map.of();
        return resolvers.computeIfAbsent(documentId,id->new ScopeResolver(document)).current(source.evidence());
    }
    static String category(String value){return switch(clean(value)){case "工程设计建筑装饰工程专项"->"建筑装饰工程设计专项";default->clean(value);};}
    static String grade(String value){Matcher m=GRADE.matcher(clean(value));return m.find()?CredentialGrades.normalize(m.group(1)):"";}
    static String categoryWithoutGrade(String value){String text=clean(value);Matcher m=GRADE.matcher(text);return category(m.find()?text.substring(0,m.start()):text);}

    /** A license query is a result-row record. Search-input company names, table headings and
     * the following row can never supply its owner or number. Header/row source chunks may
     * differ, but every field must be covered by the immutable page's own source anchors. */
    static List<QueryRow> corporateQueryRows(EvidenceCorpus corpus,String expectedCompany,String expectedCategory){
        if(!expectedCategory.equals("安全生产许可证"))return List.of();
        Map<String,ScopeResolver> resolvers=new HashMap<>();Map<String,List<Source>> pages=new LinkedHashMap<>();
        for(Source source:corpus.all)if(usable(source,corpus,resolvers))pages.computeIfAbsent(EvidenceCorpus.pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType()),k->new ArrayList<>()).add(source);
        List<QueryRow> result=new ArrayList<>();
        for(var entry:pages.entrySet()){
            var page=corpus.pages.get(entry.getKey());if(page==null)continue;String text=page.text();
            if(!text.matches("(?s).*安全生产许可证(?:信息公示|查询|查询结果).*"))continue;
            List<Field> urls=urls(text,entry.getValue());if(urls.isEmpty())continue;
            List<Cell> header=List.of();int owner=-1,number=-1,offset=0;boolean results=false;
            for(String line:text.split("\\n",-1)){
                if(line.matches(".*(?:查询结果|结果表格|公示结果).*"))results=true;
                List<Cell> row=cells(line,offset);offset+=line.length()+1;
                if(row.isEmpty()){header=List.of();continue;}
                int ownerColumn=column(row,Set.of("企业名称","单位名称")),numberColumn=column(row,Set.of("证号","证书编号","许可证号"));
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
                result.add(new QueryRow(origin,original,expectedCompany,expectedCategory,certificateNumber,urls.stream().map(Field::value).toList(),
                        linked("SUBJECT",ownerField.audit(),"CERTIFICATE_NUMBER",numberField.audit(),"NUMBER_HEADER",numberHeader.audit(),"binding","SAME_IMMUTABLE_QUERY_RESULT_ROW")));
            }
        }
        return List.copyOf(result);
    }
    static String normalizeCertificateNumber(String number){return clean(number).replace('（','(').replace('）',')').replace('〔','[').replace('〕',']').toUpperCase(Locale.ROOT);}

    static List<Bound> certificates(Fact fact,EvidenceCorpus corpus){
        if(!fact.kind().equals("CERTIFICATE")||!DomainBindings.certificateIdentity(fact)||new ProofVerifier().issue(fact,corpus.byId)!=null||DomainBindings.issue(fact,corpus.byId,List.of())!=null)return List.of();
        var record=CertificateRecordBindings.matching(fact,corpus.byId);
        if(record!=null){Map<String,Object> fields=new LinkedHashMap<>();record.fields().forEach((key,value)->fields.put(key,value.audit()));fields.put("RECORD_BINDING",CertificateRecordBindings.audit(record));
            return List.of(new Bound(record.sources().get(0),record.fields().values().stream().map(CertificateSourceComparison.Field::evidence).distinct().toList(),Map.copyOf(fields)));}
        List<Bound> out=new ArrayList<>();Map<String,ScopeResolver> resolvers=new HashMap<>();
        for(String id:fact.sourceIds()){
            Source source=corpus.byId.get(id);if(!usable(source,corpus,resolvers))continue;String text=source.evidence().quote();
            if(text.matches("(?s).*(?:查询截图|查询结果|证书列表|证书详情|资质查询|资质详情).*"))continue;
            if(!text.matches("(?s).*(?:资质证明|资格证明|资质证书|资格证书|注册证书|许可证|考核合格证书|职称证书|认证证书).*"))continue;
            // A fact is checked again against current immutable source IDs, not re-extracted with a
            // second stricter grammar. Attribute/holder co-location is already checked above.
            Map<String,Object> fields=new LinkedHashMap<>();List<Evidence> evidence=new ArrayList<>();boolean complete=true;
            Map<String,String> values=new LinkedHashMap<>();values.put("SUBJECT",fact.subject());values.put("CATEGORY",str(fact.attributes(),"category"));values.put("CERTIFICATE_NUMBER",str(fact.attributes(),"number"));
            for(String key:List.of("grade","profession","numberField","validityField"))if(!str(fact.attributes(),key).isBlank())values.put(key.toUpperCase(Locale.ROOT),str(fact.attributes(),key));
            for(var value:values.entrySet()){
                int from=text.indexOf(value.getValue());if(from<0){complete=false;break;}
                Evidence origin=source.evidence();Matcher range=SOURCE_RANGE.matcher(origin.anchor());if(!range.matches()){complete=false;break;}
                int offset=Integer.parseInt(range.group(1));Evidence field=new Evidence(origin.documentId(),origin.documentName(),origin.page(),origin.locationType(),value.getValue(),"chars:"+(offset+from)+"-"+(offset+from+value.getValue().length()),origin.confidence());
                fields.put(value.getKey(),linked("factId",fact.id(),"value",value.getValue(),"sourceId",source.id(),"source",field));evidence.add(field);
            }
            if(complete){if(source.scopeContext().blocked())fields.put("DOCUMENT_SCOPE",mappedScope(source,corpus,resolvers));out.add(new Bound(source,List.copyOf(evidence),Map.copyOf(fields)));}
        }return out;
    }

    static List<QualificationProofAssociation.Record> qualificationQueries(EvidenceCorpus corpus,String company,String requiredCategory){
        return qualificationQueries(corpus,company,requiredCategory,List.of());
    }
    static List<QualificationProofAssociation.Record> qualificationQueries(EvidenceCorpus corpus,String company,String requiredCategory,List<Map<String,Object>> layout){
        Map<String,ScopeResolver> resolvers=new HashMap<>();Map<String,List<Source>> pages=new LinkedHashMap<>();for(Source source:corpus.all)if(usable(source,corpus,resolvers))pages.computeIfAbsent(EvidenceCorpus.pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType()),k->new ArrayList<>()).add(source);
        List<QualificationProofAssociation.Record> result=new ArrayList<>();
        for(var entry:pages.entrySet()){
            var page=corpus.pages.get(entry.getKey());if(page==null)continue;String text=page.text();
            if(!text.matches("(?s).*(?:查询截图|查询结果|资质查询|资质详情).*"))continue;
            Field owner=owner(text,company,entry.getValue());if(owner==null)continue;
            result.addAll(structuredQueries(corpus,entry.getValue(),text,owner,company,requiredCategory,layout));
            List<Field> urls=urls(text,entry.getValue());List<Cell> header=List.of();int number=-1,name=-1,rank=-1,offset=0;
            for(String line:text.split("\\n",-1)){
                List<Cell> cells=cells(line,offset);offset+=line.length()+1;
                if(cells.isEmpty()){header=List.of();continue;}
                int candidateNumber=column(cells,Set.of("资质证书号","资质证书编号","证书编号","证书号码")),candidateName=column(cells,Set.of("资质名称","资质类别及等级"));
                if(candidateName<0)candidateName=column(cells,Set.of("资质类别","证书类别"));
                if(candidateNumber>=0&&candidateName>=0){header=cells;number=candidateNumber;name=candidateName;rank=column(cells,Set.of("资质等级","等级"));continue;}
                if(header.isEmpty()||cells.size()!=header.size()||cells.stream().allMatch(c->c.value().matches("[:—-]+")))continue;
                String certificateNumber=cells.get(number).value().toUpperCase(Locale.ROOT),rawCategory=cells.get(name).value();
                // Blank merged cells are not copied from the preceding row. That requires explicit
                // rowspan/row continuation evidence, not a neighbouring company's credential.
                if(!certificateNumber.matches("[A-Z0-9][A-Z0-9()（）/-]{3,79}"))continue;
                String actualCategory=categoryWithoutGrade(rawCategory),actualGrade=rank<0?grade(rawCategory):grade(cells.get(rank).value());
                if(!DomainBindings.sameCertificateCategory(actualCategory,category(requiredCategory))||actualGrade.isBlank())continue;
                Field num=field(certificateNumber,cells.get(number),text,entry.getValue()),cat=field(actualCategory,cells.get(name),text,entry.getValue()),grade=field(actualGrade,cells.get(rank<0?name:rank),text,entry.getValue());
                if(num==null||cat==null||grade==null)continue;
                List<Evidence> evidence=new ArrayList<>(List.of(owner.evidence(),num.evidence(),cat.evidence(),grade.evidence()));urls.forEach(u->evidence.add(u.evidence()));
                result.add(new QualificationProofAssociation.Record(num.source(),clean(company),actualCategory,certificateNumber,actualGrade,true,urls.stream().map(Field::value).toList(),evidence.stream().distinct().toList(),linked("SUBJECT",owner.audit(),"CATEGORY",cat.audit(),"CERTIFICATE_NUMBER",num.audit(),"GRADE",grade.audit(),"URL",urls.stream().map(Field::audit).toList(),"binding","EXPLICIT_QUERY_TABLE_ROW_AND_UNIQUE_PAGE_OWNER")));
            }
        }return result;
    }

    /** A source-bound navigation request, not evidence that a blank cell inherits another row.
     * The host must read this frozen page's image/geometry before the query can become a fact. */
    static List<Map<String,Object>> qualificationLayoutActions(EvidenceCorpus corpus,String company,String requiredCategory,String requiredNumber,String targetId){
        Map<String,ScopeResolver> resolvers=new HashMap<>();Map<String,List<Source>> pages=new LinkedHashMap<>();
        for(Source source:corpus.all)if(usable(source,corpus,resolvers))pages.computeIfAbsent(EvidenceCorpus.pageKey(source.evidence().documentId(),source.evidence().page(),source.evidence().locationType()),k->new ArrayList<>()).add(source);
        List<Map<String,Object>> actions=new ArrayList<>();String expectedNumber=normalizeCertificateNumber(requiredNumber);
        for(var entry:pages.entrySet()){
            var page=corpus.pages.get(entry.getKey());if(page==null)continue;String text=page.text();
            if(!text.matches("(?s).*(?:查询截图|查询结果|资质查询|资质详情).*"))continue;
            Field owner=owner(text,company,entry.getValue());if(owner==null)continue;
            List<Field> numberFields=new ArrayList<>(),categoryFields=new ArrayList<>();List<Cell> header=List.of();int number=-1,name=-1,offset=0;
            for(String line:text.split("\\n",-1)){
                List<Cell> row=cells(line,offset);offset+=line.length()+1;if(row.isEmpty()){header=List.of();continue;}
                int n=column(row,Set.of("资质证书号","资质证书编号","证书编号","证书号码")),c=column(row,Set.of("资质名称","资质类别及等级"));
                if(c<0)c=column(row,Set.of("资质类别","证书类别"));
                if(n>=0&&c>=0){header=row;number=n;name=c;continue;}
                if(header.isEmpty()||row.size()!=header.size())continue;
                if(normalizeCertificateNumber(row.get(number).value()).equals(expectedNumber)){Field value=field(expectedNumber,row.get(number),text,entry.getValue());if(value!=null)numberFields.add(value);}
                if(row.get(number).value().isBlank()&&DomainBindings.sameCertificateCategory(categoryWithoutGrade(row.get(name).value()),category(requiredCategory))){Field value=field(row.get(name).value(),row.get(name),text,entry.getValue());if(value!=null)categoryFields.add(value);}
            }
            if(numberFields.isEmpty()||categoryFields.isEmpty())continue;
            Source source=categoryFields.get(0).source();Evidence e=source.evidence();
            actions.add(linked("actionId","qualification-layout-"+identity(targetId+entry.getKey()+expectedNumber+source.documentHash()+source.documentVersion()).substring(0,24),
                    "tool","read_table_record","targetId",targetId,"sourceId",source.id(),"sourceIds",entry.getValue().stream().map(Source::id).distinct().toList(),
                    "arguments",linked("targetId",targetId,"sourceId",source.id()),"reasonCode","QUALIFICATION_QUERY_MERGED_CELL_UNVERIFIED",
                    "documentId",e.documentId(),"documentHash",source.documentHash(),"documentVersion",source.documentVersion(),"page",e.page(),"locationType",e.locationType(),
                    "certificateNumber",expectedNumber,"category",requiredCategory,"evidenceDiscovery",linked("owner",owner.audit(),"category",categoryFields.stream().map(Field::audit).toList(),"numberOnPage",numberFields.stream().map(Field::audit).toList(),"establishesMergedRelation",false)));
        }
        return List.copyOf(actions);
    }

    private static List<QualificationProofAssociation.Record> structuredQueries(EvidenceCorpus corpus,List<Source> sources,String text,Field owner,String company,String category,List<Map<String,Object>> layout){
        if(layout.isEmpty())return List.of();Fact origin=new Fact("query-layout","FIELD",company,"","","","","","CURRENT_PROJECT",sources.stream().map(Source::id).toList(),Map.of());
        var cells=cn.bidcheck.verification.facts.TableStructure.validatedCells(layout,LayoutBinding.sources(origin,corpus.byId));List<QualificationProofAssociation.Record> result=new ArrayList<>();
        for(Map<String,Object> categoryCell:cells){if(!Set.of("资质名称","资质类别及等级","资质类别","证书类别").contains(cellLabel(categoryCell,cells)))continue;
            String rawCategory=str(categoryCell,"text"),actualCategory=categoryWithoutGrade(rawCategory);if(!DomainBindings.sameCertificateCategory(actualCategory,category(category)))continue;
            int row=RunSession.number(categoryCell.get("row"),0);List<Map<String,Object>> members=cells.stream().filter(c->sameGrid(categoryCell,c)&&!Boolean.TRUE.equals(c.get("header"))&&RunSession.number(c.get("row"),0)<=row&&RunSession.number(c.get("row"),0)+RunSession.number(c.get("rowSpan"),1)>row).toList();
            var numbers=members.stream().filter(c->Set.of("资质证书号","资质证书编号","证书编号","证书号码").contains(cellLabel(c,cells))).toList();
            if(numbers.size()!=1)continue;var numberCell=numbers.get(0);String number=normalizeCertificateNumber(str(numberCell,"text"));if(!number.matches("[A-Z0-9][A-Z0-9()/\\[\\]-]{3,79}"))continue;
            var ranks=members.stream().filter(c->Set.of("等级","资质等级").contains(cellLabel(c,cells))).toList();if(ranks.size()>1)continue;var rankCell=ranks.isEmpty()?categoryCell:ranks.get(0);String rank=grade(str(rankCell,"text"));if(rank.isBlank())continue;
            List<String> refs=new ArrayList<>();for(var cell:List.of(categoryCell,numberCell,rankCell))refs.addAll(ProofVerifier.strings(cell.get("_sourceIds")));
            List<Source> used=refs.stream().distinct().map(corpus.byId::get).filter(Objects::nonNull).toList();if(used.isEmpty())continue;List<Field> urls=urls(text,sources);
            List<Evidence> evidence=new ArrayList<>(List.of(owner.evidence()));used.forEach(s->evidence.add(s.evidence()));urls.forEach(u->evidence.add(u.evidence()));
            result.add(new QualificationProofAssociation.Record(used.get(0),clean(company),actualCategory,number,rank,true,urls.stream().map(Field::value).toList(),evidence.stream().distinct().toList(),
                linked("SUBJECT",owner.audit(),"CATEGORY",categoryCell,"CERTIFICATE_NUMBER",numberCell,"GRADE",rankCell,"binding","SOURCE_BOUND_TABLE_ROW_WITH_EXPLICIT_SPAN","row",row,"inheritedCertificateNumber",RunSession.number(numberCell.get("row"),0)<row,"crossPageInheritance",false)));
        }return result;
    }
    private static boolean sameGrid(Map<String,Object> left,Map<String,Object> right){return List.of("documentId","documentHash","documentVersion","locationType","location","tableId","frameId").stream().allMatch(k->Objects.equals(left.get(k),right.get(k)));}
    private static String cellLabel(Map<String,Object> cell,List<Map<String,Object>> cells){
        if(Boolean.TRUE.equals(cell.get("header"))||RunSession.number(cell.get("columnSpan"),1)!=1)return "";List<String> refs=ProofVerifier.strings(cell.get("headerCellIds"));if(refs.isEmpty())return "";
        List<Map<String,Object>> headers=cells.stream().filter(h->sameGrid(cell,h)&&Boolean.TRUE.equals(h.get("header"))&&refs.contains(str(h,"cellId"))&&RunSession.number(h.get("row"),0)+RunSession.number(h.get("rowSpan"),1)<=RunSession.number(cell.get("row"),0)&&RunSession.number(h.get("column"),0)<=RunSession.number(cell.get("column"),0)&&RunSession.number(h.get("column"),0)+RunSession.number(h.get("columnSpan"),1)>RunSession.number(cell.get("column"),0)).toList();
        return headers.size()==1?clean(str(headers.get(0),"text")):"";
    }

    private static Field owner(String text,String expected,List<Source> sources){
        List<Cell> values=new ArrayList<>();int offset=0;
        for(String line:text.split("\\n",-1)){
            String plain=clean(line.replace("*","").replace("`",""));
            Matcher heading=Pattern.compile("^#{1,6}([^#|:：]{2,100}(?:有限公司|有限责任公司|公司|研究院|设计院))$").matcher(plain);
            Matcher label=Pattern.compile("^(?:企业名称|单位名称|组织名称|持证单位)[:：]([^|]+)$").matcher(plain);
            if(heading.matches()||label.matches()){String name=heading.matches()?heading.group(1):label.group(1);int start=line.indexOf(name);if(start>=0)values.add(new Cell(name,offset+start,offset+start+name.length()));}
            List<Cell> row=cells(line,offset);if(row.size()==2&&Set.of("企业名称","单位名称","组织名称","持证单位").contains(row.get(0).value()))values.add(row.get(1));
            offset+=line.length()+1;
        }
        Set<String> names=new LinkedHashSet<>();values.forEach(c->names.add(c.value()));
        if(!names.equals(Set.of(clean(expected))))return null;return values.isEmpty()?null:field(clean(expected),values.get(0),text,sources);
    }
    private static List<Field> urls(String text,List<Source> sources){List<Field> out=new ArrayList<>();Matcher matcher=URL.matcher(text);while(matcher.find())if(!matcher.group().matches("(?i).*(?:example\\.(?:com|org)|placeholder|image_url).*")){Field f=field(matcher.group(),new Cell(matcher.group(),matcher.start(),matcher.end()),text,sources);if(f!=null)out.add(f);}return out;}
    private static Field field(String value,Cell cell,String text,List<Source> sources){for(Source source:sources){Matcher range=SOURCE_RANGE.matcher(source.evidence().anchor());if(!range.matches()||cell.start()<Integer.parseInt(range.group(1))||cell.end()>Integer.parseInt(range.group(2)))continue;Evidence e=source.evidence();return new Field(value,source,new Evidence(e.documentId(),e.documentName(),e.page(),e.locationType(),text.substring(cell.start(),cell.end()),"chars:"+cell.start()+"-"+cell.end(),e.confidence()));}return null;}
    private static int column(List<Cell> cells,Set<String> labels){int found=-1;for(int i=0;i<cells.size();i++)if(labels.contains(cells.get(i).value())){if(found>=0)return -1;found=i;}return found;}
    private static List<Cell> cells(String line,int offset){if(!line.strip().startsWith("|"))return List.of();List<Cell> out=new ArrayList<>();int from=line.indexOf('|')+1,to;while((to=line.indexOf('|',from))>=0){out.add(new Cell(line.substring(from,to),offset+from,offset+to));from=to+1;}return out;}
}
