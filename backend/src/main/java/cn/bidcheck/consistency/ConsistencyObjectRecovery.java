package cn.bidcheck.consistency;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.DocumentScopeProof;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.consistency.ConsistencyRunSupport.*;

/** Evidence-derived object plans. These are children of, and never replacements for, original findings. */
final class ConsistencyObjectRecovery {
    static final String VERSION="consistency-object-recovery-5-source-relations-consumed";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Pattern CERTIFICATE_ID=Pattern.compile("(?:证书编号|证书号码)\\s*[:：]\\s*([\\p{IsHan}A-Za-z0-9()（）/\\-]{5,50})");
    private static final Pattern INVOICE_ID=Pattern.compile("发票(?:号码|编号)\\s*[:：]\\s*([0-9]{8,30})(?![0-9])");
    private static final Pattern CONTRACT_LABEL=Pattern.compile("(?:合同编号|合同号码)\\s*[:：]");
    // A bounded token may contain Chinese components. Unknown punctuation or overlong values
    // invalidate the field; they must never turn a common ASCII prefix into an identity.
    private static final Pattern CONTRACT_ID=Pattern.compile("(?:合同编号|合同号码)[\\h]*[:：][\\h]*([\\p{IsHan}A-Za-z0-9()（）/._\\-]{5,120})(?=$|[\\s|<>])");
    private static final Pattern HOLDER=Pattern.compile("(?:企业名称|单位名称|公司名称|持证人|姓名|姓\\s+名)\\s*[:：]\\s*([^\\n|<>；;]{2,80})");
    private static final String CERTIFICATE_TYPES="工程设计资质证书|建筑业企业资质证书|安全生产许可证|安全生产考核合格证书|职称证书|注册建造师证书|注册建筑师证书";
    private static final Pattern CREDENTIAL_HEADING=Pattern.compile("(?m)(?:^|(?<=>))\\s*(?:#{1,6}\\s*)?("+CERTIFICATE_TYPES+"|资质证书)\\s*(?=$|<)");
    private static final Pattern DOCUMENT_BOUNDARY=Pattern.compile("(?m)(?:^|(?<=>))\\s*(?:#{1,6}\\s*)?(?:"+CERTIFICATE_TYPES+"|资质证书|人员情况表|人员简历表|个人简历|主要人员简历表|拟投入[^\\n<>]{0,30}人员[^\\n<>]{0,15}表|附件(?:扫描件|材料)?|(?:投标人|供应商|承包人)基本情况表)\\s*(?=$|<)");
    private final Document document;
    private final Map<String,Page> pages=new HashMap<>();
    private final DocumentScopeProof.Resolver scope;
    private final Map<String,Binding> bindings=new LinkedHashMap<>();
    record Binding(String key,String kind,String anchor,boolean strong,Map<String,Object> proof,List<Evidence> evidence,List<String> missing,String value) {}
    ConsistencyObjectRecovery(Document document,Collection<Map<String,Object>> occurrences) {
        this.document=document;this.scope=DocumentScopeProof.forDocument(document);
        document.pages().forEach(p->pages.put(p.locationType()+":"+p.number(),p));
        for(Map<String,Object> occurrence:occurrences)bindings.put(string(occurrence,"id"),bind(occurrence));
    }
    Map<String,Object> plan(Map<String,Object> group,Map<String,Map<String,Object>> occurrences) {
        List<String> requested=group.get("occurrenceIds") instanceof List<?> ids?ids.stream().map(Object::toString).toList():List.of();
        Map<String,List<String>> grouped=new LinkedHashMap<>();
        for(String id:requested){Binding binding=bindings.get(id);String key=binding==null?"unresolved:"+id:binding.key();grouped.computeIfAbsent(key,x->new ArrayList<>()).add(id);}
        List<Map<String,Object>> objects=new ArrayList<>();
        for(var entry:grouped.entrySet()) {
            Binding binding=bindings.get(entry.getValue().get(0));if(binding==null)continue;
            List<String> matched=new ArrayList<>(entry.getValue());
            if(binding.strong())for(var other:bindings.entrySet())if(other.getValue().key().equals(binding.key())&&!matched.contains(other.getKey())
                &&string(occurrences.getOrDefault(other.getKey(),Map.of()),"field").equals(string(group,"field")))matched.add(other.getKey());
            Set<String> values=new LinkedHashSet<>();List<Evidence> evidence=new ArrayList<>();List<String> missing=new ArrayList<>();
            for(String id:matched){Binding b=bindings.get(id);values.add(b.value());evidence.addAll(b.evidence());missing.addAll(b.missing());}
            values.remove("");missing=missing.stream().distinct().toList();evidence=unique(evidence);
            boolean distinct=values.size()>1,ready=binding.strong()&&missing.isEmpty()&&distinct&&evidence.size()<=24&&evidence.stream().allMatch(e->e.quote().length()<=1800);
            String state=ready?"COMPARABLE_PAIR_READY":!binding.strong()?"OBJECT_IDENTITY_PENDING":!missing.isEmpty()?"OBJECT_CONTEXT_PENDING":values.size()<2?"NO_CONFLICT_IN_OBSERVED_VALUES":"OBJECT_PACKET_REQUIRED";
            String query=binding.strong()?binding.anchor()+" "+string(group,"fieldLabel")+" "+String.join(" ",missing):"";
            objects.add(values("id",id(document.id()+document.sha256()+string(group,"id")+entry.getKey()),"objectKey",binding.key(),"objectKind",binding.kind(),
                "originalOccurrenceIds",List.copyOf(entry.getValue()),"occurrenceIds",List.copyOf(matched),"values",List.copyOf(values),"evidences",evidence,
                "identityProof",binding.proof(),"identityVerified",binding.strong(),"missingDimensions",missing,"comparisonReady",ready,
                "status",state,"coverageComplete",false,"searchQuery",query,"searchAllowed",binding.strong()&&!query.isBlank(),
                "reason",ready?"已证明同一对象及比较口径，可按该对象复核差异。":!binding.strong()?"先定位同一对象的明确身份，不能用同名、型号相似或所在投标章节替代。":!missing.isEmpty()?"对象已定位，但仍缺 "+String.join("、",missing)+"；仅定向补查该对象。":"目前可验证取值未形成不同值比较；这不证明全文覆盖完整。"));
        }
        return values("policyVersion",VERSION,"parentGroupId",string(group,"id"),"sourceDocumentId",document.id(),"sourceDocumentHash",document.sha256(),
            "originalOccurrenceIds",requested,"objects",objects,"originalRecordsPreserved",true,"automaticParentPass",false,"comparisonCoverageComplete",false,
            "stopPolicy","每个已证明对象最多一次定向检索；相同输入及策略不重复。未证明对象身份时停止，不把未命中认定为缺料。");
    }
    private Binding bind(Map<String,Object> occurrence) {
        String field=string(occurrence,"field"),identifier=string(occurrence,"id"),value=string(occurrence,"value");Evidence e=evidence(occurrence.get("evidence"));
        if(!valid(e))return unresolved(identifier,"SOURCE_UNVERIFIED",List.of(),value);
        Page page=pages.get(e.locationType()+":"+e.page());String text=page.text();int at=position(occurrence,e,text);
        if(at<0)return unresolved(identifier,"SOURCE_POSITION_AMBIGUOUS",List.of(e),value);
        List<Evidence> refs=new ArrayList<>();refs.add(e);
        if(occurrence.get("contextEvidences") instanceof List<?> references)for(Object reference:references){Evidence context=evidence(reference);if(valid(context))refs.add(context);}
        // Actual invoice boundaries take precedence over the outer submission chapter.
        Segment invoice=segment(text,at,INVOICE_ID);
        if(invoice!=null&&Set.of("unitPrice","taxAmount","quantity","productModel","totalPrice","amount","taxRate").contains(field)) {
            String row=rowQuote(occurrence,text,at);Map<String,Object> table=map(occurrence.get("tableAssociation"));
            InvoiceRowProof.Proof columns=InvoiceRowProof.locate(text,at,field);
            boolean tableVerified=columns!=null&&columns.rowStart()>invoice.labelEnd()
                &&InvoiceRowProof.sameValue(value,columns.fieldValue(),field);
            List<String> missing=new ArrayList<>();if(!tableVerified)missing.add("发票明细行及表头对应");
            Set<String> codes=matches(Pattern.compile("发票代码\\s*[:：]\\s*([0-9]{10,12})(?![0-9])"),invoice.text());String code=codes.size()==1?codes.iterator().next():"";
            if(invoice.id().length()<20&&code.isBlank())missing.add("短发票号码对应的发票代码");
            String subject=tableVerified?columns.item():string(occurrence,"subject");
            if(subject.isBlank()||subject.equals("UNRESOLVED")||!plain(row).contains(plain(subject)))missing.add("发票明细对象");
            String unit=tableVerified?columns.unit():string(occurrence,"unit");boolean money=Set.of("unitPrice","taxAmount","totalPrice","amount").contains(field);
            String currency=tableVerified?columns.currency():"",basis=tableVerified?columns.basis():"";
            if(money&&currency.isBlank())missing.add("币种");
            if(field.equals("unitPrice")&&basis.isBlank())missing.add("单价含税口径");
            if(field.equals("quantity")&&unit.isBlank())missing.add("数量单位");
            if(tableVerified&&columns.duplicateItem())missing.add("同一发票重复货项行的独立标识");
            String rowKey=tableVerified?columns.itemKey():identifier;
            int rowStart=text.lastIndexOf("<tr",at);
            String key="INVOICE:"+code+":"+invoice.id()+":ROW:"+rowKey+":"+field+":"+currency+":"+basis;
            refs.add(source(page,invoice.labelStart(),invoice.labelEnd()));if(!row.isBlank())refs.add(source(page,rowStart,rowStart+row.length()));
            if(tableVerified)refs.add(source(page,columns.headerStart(),columns.headerEnd()));
            return new Binding(key,"INVOICE_TRANSACTION",invoice.id(),true,
                values("derivedBy","SERVER_SOURCE_BOUNDARY","instanceId",invoice.id(),"invoiceCode",code,"sourcePage",page.number(),"sourceOffset",at,"locationType",page.locationType(),
                    "submissionScope",string(occurrence,"scope"),"transactionScope","INVOICE_TRANSACTION","currentBidPrice",false,
                    "rowVerified",tableVerified,"row",rowKey,"subject",subject,"unit",unit,"currency",currency,"basis",basis,"field",field,
                    "columnArithmetic",tableVerified?columns.arithmetic():Map.of()),unique(refs),missing,value);
        }
        if(Set.of("certificateNumber","qualificationGrade").contains(field)) {
            Segment certificate=segment(text,at,CERTIFICATE_ID);
            if(certificate!=null) {
                String number=certificate.id();if(number.chars().noneMatch(Character::isDigit))return unresolved(identifier,"CERTIFICATE_IDENTIFIER_UNPROVEN",refs,value);
                // Page-level uniqueness is not a relationship: a neighboring personnel form can contain the only name.
                CertificateRecord record=certificateRecord(text,number);String holder=record==null?"":record.holder();
                if(field.equals("certificateNumber")&&!e.quote().contains(number))return unresolved(identifier,"CERTIFICATE_NUMBER_NOT_IN_OCCURRENCE",refs,value);
                if(field.equals("qualificationGrade")&&(record==null||at<record.start()||at+e.quote().length()>record.end()))return unresolved(identifier,"CERTIFICATE_GRADE_OUTSIDE_RECORD",refs,value);
                if(field.equals("qualificationGrade")&&(value.isBlank()||!plain(e.quote()).contains(plain(value))))return unresolved(identifier,"CERTIFICATE_GRADE_VALUE_UNGROUNDED",refs,value);
                List<String> missing=new ArrayList<>();if(holder.isBlank())missing.add("证书同页或同文书持有人");
                String certificateType=record==null?"":record.type();if(certificateType.isBlank())missing.add("证照类型");
                String boundedValue=field.equals("certificateNumber")?number:value;
                refs.add(source(page,certificate.labelStart(),certificate.labelEnd()));
                if(record!=null&&!holder.isBlank())refs.add(source(page,record.start(),record.end()));
                return new Binding("CERTIFICATE:"+number+":"+holder+":"+certificateType+":"+field,"CERTIFICATE_INSTANCE",number,true,
                    values("derivedBy","SERVER_SOURCE_BOUNDARY","instanceId",number,"holder",holder,"certificateType",certificateType,"sourcePage",page.number(),"scope","CREDENTIAL_ITSELF",
                        "doesNotProveCurrentAppointment",true,"originalValue",value,"boundedFieldValue",boundedValue),unique(refs),missing,boundedValue);
            }
        }
        if(field.equals("contractNumber")) {
            Segment contract=segment(text,at,CONTRACT_ID);
            if(contract!=null){
                int valueStart=contract.labelEnd()-contract.id().length();
                if(at>valueStart||at+e.quote().length()<contract.labelEnd())return unresolved(identifier,"CONTRACT_IDENTIFIER_NOT_FULLY_IN_OCCURRENCE",refs,value);
                Set<String> buyers=matches(Pattern.compile("(?:甲方|发包人|买方)\\s*[:：]\\s*([^\\n|<>；;]{2,80})"),contract.text());
                Set<String> sellers=matches(Pattern.compile("(?:乙方|承包人|卖方)\\s*[:：]\\s*([^\\n|<>；;]{2,80})"),contract.text());
                String parties=contractLabelCount(contract.text())==1&&buyers.size()==1&&sellers.size()==1?buyers.iterator().next()+"|"+sellers.iterator().next():"";
                refs.add(source(page,contract.labelStart(),contract.labelEnd()));return new Binding("CONTRACT:"+contract.id()+":"+parties,"CONTRACT_INSTANCE",contract.id(),true,
                    values("derivedBy","SERVER_SOURCE_BOUNDARY","instanceId",contract.id(),"parties",parties,"scope","CONTRACT_ITSELF","currentBidPrice",false),unique(refs),parties.isBlank()?List.of("合同双方及对应文书"):List.of(),contract.id());}
        }
        if(field.equals("projectName")) {
            Map<String,Object> proof=scope.currentEvidence(e);
            if(Boolean.TRUE.equals(proof.get("verifiedCurrent"))&&plain(e.quote()).contains(plain(value))&&!value.isBlank())return new Binding("CURRENT_DOCUMENT_PROJECT:"+document.id(),"CURRENT_PROJECT_NAME",value,true,proof,unique(refs),List.of(),value);
            if(Set.of("HISTORICAL_DOCUMENT","HISTORICAL_REGION","NAVIGATION_ONLY").contains(string(proof,"status")))
                return new Binding("EXCLUDED_PROJECT_SOURCE:"+identifier,"EXCLUDED_PROJECT_SOURCE","",true,proof,unique(refs),List.of(),value);
        }
        Map<String,Object> table=map(occurrence.get("tableAssociation"));String row=rowQuote(occurrence,text,at);
        if(verifiedTable(table,e,text,at)&&!row.isBlank()) {
            int rowStart=text.lastIndexOf("<tr",at);refs.add(source(page,rowStart,rowStart+row.length()));
            // A table row is a safe partition, not a cross-page identity. Do not merge identical product names.
            String key="TABLE_ROW:"+string(table,"tableId")+":"+Objects.toString(table.get("row"),"")+":"+field;
            return new Binding(key,"TABLE_ROW_CANDIDATE","",false,values("derivedBy","SERVER_TABLE_SOURCE","rowVerified",true,"tableId",string(table,"tableId"),"row",table.get("row")),unique(refs),List.of("跨来源的对象标识及适用范围"),value);
        }
        return unresolved(identifier,"OBJECT_IDENTITY_UNPROVEN",refs,value);
    }
    /** Convert exact-source candidates into narrow relations; never promote a single observed value to full coverage. */
    Map<String,Object> verifyRelations(Map<String,Object> object,List<Evidence> candidates){
        Map<String,Object> prior=map(object.get("identityProof"));String kind=string(object,"objectKind"),anchor=string(prior,"instanceId");
        if("EXCLUDED_PROJECT_SOURCE".equals(kind)){
            List<Map<String,Object>> exclusions=new ArrayList<>();
            for(Binding binding:bindings.values())if(binding.key().equals(string(object,"objectKey")))for(Evidence ref:binding.evidence()){
                Map<String,Object> proof=scope.currentEvidence(ref);
                if(Set.of("HISTORICAL_DOCUMENT","HISTORICAL_REGION","NAVIGATION_ONLY").contains(string(proof,"status")))
                    exclusions.add(values("type","OUTSIDE_CURRENT_PROJECT_COMPARISON","source",ref,"scopeProof",proof));
            }
            return values("policyVersion",VERSION,"status",exclusions.isEmpty()?"RELATION_UNPROVEN":"EXCLUDED_FROM_CURRENT_PROJECT",
                "relations",exclusions,"missingDimensions",exclusions.isEmpty()?List.of("排除范围未被原文确认"):List.of(),
                "exclusionValidated",!exclusions.isEmpty(),"contextResolved",false,"coverageComplete",false,"automaticParentPass",false);
        }
        List<Map<String,Object>> relations=new ArrayList<>();Set<String> holders=new LinkedHashSet<>(),types=new LinkedHashSet<>(),parties=new LinkedHashSet<>();
        if(!string(prior,"holder").isBlank())holders.add(string(prior,"holder"));
        if(!string(prior,"certificateType").isBlank())types.add(string(prior,"certificateType"));
        if(!string(prior,"parties").isBlank())parties.add(string(prior,"parties"));
        for(Evidence candidate:unique(candidates)){
            if(!valid(candidate))continue;Page page=pages.get(candidate.locationType()+":"+candidate.page());String original=page.text();
            if("CERTIFICATE_INSTANCE".equals(kind)&&!anchor.isBlank()){
                CertificateRecord record=certificateRecord(original,anchor);
                if(record==null||record.holder().isBlank()||record.type().isBlank())continue;
                String holder=record.holder(),type=record.type();holders.add(holder);types.add(type);
                Evidence proof=source(page,record.start(),record.end());
                relations.add(values("type","CERTIFICATE_HOLDER","subject",anchor,"object",holder,"certificateType",type,"source",proof,
                    "verifiedBy","LOCAL_CERTIFICATE_RECORD_FIELDS","scope","CREDENTIAL_ITSELF","provesCurrentAppointment",false));
            }else if("CONTRACT_INSTANCE".equals(kind)&&!anchor.isBlank()){
                Set<String> ids=matches(CONTRACT_ID,original);if(contractLabelCount(original)!=1||ids.size()!=1||!ids.contains(anchor))continue;
                Set<String> buyers=matches(Pattern.compile("(?:甲方|发包人|买方)\\s*[:：]\\s*([^\\n|<>；;]{2,80})"),original);
                Set<String> sellers=matches(Pattern.compile("(?:乙方|承包人|卖方)\\s*[:：]\\s*([^\\n|<>；;]{2,80})"),original);
                if(buyers.size()!=1||sellers.size()!=1)continue;
                String pair=buyers.iterator().next()+"|"+sellers.iterator().next();parties.add(pair);
                relations.add(values("type","CONTRACT_PARTIES","subject",anchor,"object",pair,"source",source(page,0,original.length()),
                    "verifiedBy","LOCAL_EXPLICIT_FIELDS","scope","CONTRACT_ITSELF","currentBidPrice",false));
            }else if("INVOICE_TRANSACTION".equals(kind)&&!anchor.isBlank()){
                // The binding was reconstructed from immutable source, never supplied by the model.
                for(Binding binding:bindings.values())if(binding.key().equals(string(object,"objectKey"))&&binding.kind().equals(kind)){
                    Map<String,Object> p=binding.proof();if(!Boolean.TRUE.equals(p.get("rowVerified"))||!binding.missing().isEmpty())continue;
                    if(!(p.get("sourcePage") instanceof Number n)||page.number()!=n.intValue()||!page.locationType().equals(p.get("locationType")))continue;
                    int offset=((Number)p.get("sourceOffset")).intValue();InvoiceRowProof.Proof row=InvoiceRowProof.locate(original,offset,string(p,"field"));
                    Segment invoice=segment(original,offset,INVOICE_ID);
                    if(row==null||invoice==null||!invoice.id().equals(anchor)||row.rowStart()<=invoice.labelEnd()
                        ||!candidate.quote().contains(original.substring(row.rowStart(),row.rowEnd())))continue;
                    relations.add(values("type","INVOICE_ITEM_FIELD","subject",anchor,"object",row.item(),"field",p.get("field"),
                        "value",row.fieldValue(),"unit",row.unit(),"currency",row.currency(),"basis",row.basis(),
                        "source",source(page,row.rowStart(),row.rowEnd()),"headerSource",source(page,row.headerStart(),row.headerEnd()),
                        "invoiceSource",source(page,invoice.labelStart(),invoice.labelEnd()),"verifiedBy","LOCAL_INVOICE_NUMBER_ROW_COLUMN",
                        "columnArithmetic",row.arithmetic(),"scope","INVOICE_TRANSACTION","currentBidPrice",false));
                }
            }else if("CURRENT_PROJECT_NAME".equals(kind)&&Boolean.TRUE.equals(scope.currentEvidence(candidate).get("verifiedCurrent"))){
                Set<String> names=matches(Pattern.compile("项目名称\\s*[:：]\\s*([^\\n|<>；;]{3,160})"),candidate.quote());
                if(names.size()!=1)continue;
                relations.add(values("type","DOCUMENT_PROJECT_NAME","subject",document.id(),"object",names.iterator().next(),"source",candidate,
                    "verifiedBy","LOCAL_LABEL_AND_DOCUMENT_SCOPE","scope","CURRENT_PROJECT"));
            }
        }
        boolean conflict=holders.size()>1||types.size()>1||parties.size()>1;
        List<String> missing=object.get("missingDimensions") instanceof List<?> xs?new ArrayList<>(xs.stream().map(Object::toString).toList()):new ArrayList<>();
        if(!conflict&&!relations.isEmpty()){
            if(holders.size()==1)missing.remove("证书同页或同文书持有人");if(types.size()==1)missing.remove("证照类型");
            if(parties.size()==1)missing.remove("合同双方及对应文书");
        }
        if(conflict)missing.add("同编号的持有人、证照类型或合同双方存在冲突");
        return values("policyVersion",VERSION,"status",conflict?"RELATION_CONFLICT":relations.isEmpty()?"RELATION_UNPROVEN":"VERIFIED",
            "relations",conflict?List.of():relations,"conflictingCandidates",conflict?relations:List.of(),"missingDimensions",missing.stream().distinct().toList(),
            "contextResolved",!conflict&&!relations.isEmpty()&&missing.isEmpty(),"coverageComplete",false,"automaticParentPass",false);
    }
    private record CertificateRecord(String holder,String type,int start,int end) {}
    /** Scope each certificate to its own table/plain-text document block before reading owner labels. */
    private CertificateRecord certificateRecord(String text,String number){
        Matcher identifiers=CERTIFICATE_ID.matcher(text);int idStart=-1,count=0;
        while(identifiers.find())if(identifiers.group(1).equals(number)){idStart=identifiers.start();count++;}
        if(count!=1)return null;
        int start=0,end=text.length();boolean enclosed=false;
        Matcher tags=Pattern.compile("(?i)<table\\b[^>]*>|</table\\s*>").matcher(text);Deque<Integer> opened=new ArrayDeque<>();
        while(tags.find()){
            if(!tags.group().startsWith("</"))opened.push(tags.start());
            else if(!opened.isEmpty()){
                int tableStart=opened.pop();
                if(tableStart<=idStart&&idStart<tags.end()&&(!enclosed||tableStart>start)){start=tableStart;end=tags.end();enclosed=true;}
            }else if(tags.end()<=idStart)start=tags.end();
        }
        if(!opened.isEmpty())return null;
        if(!enclosed){
            tags.reset();while(tags.find()){if(tags.group().startsWith("</")&&tags.end()<=idStart)start=Math.max(start,tags.end());if(!tags.group().startsWith("</")&&tags.start()>idStart){end=tags.start();break;}}
        }
        String region=text.substring(start,end);int offset=idStart-start;
        Matcher heading=CREDENTIAL_HEADING.matcher(region);int titleStart=-1;String type="";
        while(heading.find())if(heading.start()<=offset){titleStart=heading.start();type=heading.group(1);}
        if(titleStart<0)return null;
        int blockStart=titleStart,blockEnd=region.length();Matcher boundaries=DOCUMENT_BOUNDARY.matcher(region);
        while(boundaries.find())if(boundaries.start()>offset){blockEnd=boundaries.start();break;}
        if("资质证书".equals(type)){
            // Some printed qualifications place their title after the enterprise/grade fields.
            // Admit that layout only inside one uninterrupted credential field cluster, never across a form/table.
            int previousBoundaryEnd=0;boundaries.reset();while(boundaries.find())if(boundaries.end()<=titleStart)previousBoundaryEnd=boundaries.end();
            String prefix=region.substring(previousBoundaryEnd,titleStart);
            if(!prefix.contains("企业名称")||!prefix.matches("(?s).*资质等级\\s*[:：]\\s*建筑装饰工程设计专项[^\\n<>]*.*"))return null;
            blockStart=previousBoundaryEnd;type="工程设计资质证书";
        }
        String block=region.substring(blockStart,blockEnd);Set<String> blockIds=matches(CERTIFICATE_ID,block);
        if(blockIds.size()!=1||!blockIds.contains(number))return null;
        Set<String> names=matches(HOLDER,block);String holder=names.size()==1?names.iterator().next():"";
        if(holder.matches("(?s).*(?:模糊|无法|不清|缺失|未填写|空白|未识别|详见|[:：]).*"))holder="";
        return new CertificateRecord(holder,type,start+blockStart,start+blockEnd);
    }
    private record Segment(String id,String text,int labelStart,int labelEnd) {}
    private Segment segment(String text,int offset,Pattern pattern) {
        Matcher m=pattern.matcher(text);List<int[]> ranges=new ArrayList<>();List<String> ids=new ArrayList<>();
        while(m.find()){if(pattern==CONTRACT_ID&&!completeContractId(m.group(1)))continue;ranges.add(new int[]{m.start(),m.end()});ids.add(m.group(1));}
        if(ranges.isEmpty())return null;
        // Multiple instances on a page require a preceding explicit identifier; a future ID cannot claim the row.
        int chosen=-1;for(int i=0;i<ranges.size();i++)if(ranges.get(i)[0]<=offset)chosen=i;
        if(chosen<0){if(ranges.size()!=1||ranges.get(0)[0]-offset>500)return null;chosen=0;}
        int start=chosen==0?0:ranges.get(chosen-1)[1],end=chosen+1<ranges.size()?ranges.get(chosen+1)[0]:text.length();
        // Header/holder data can precede the ID, but do not absorb the preceding invoice's items.
        if(pattern==INVOICE_ID){int heading=Math.max(text.lastIndexOf("电子发票",ranges.get(chosen)[0]),text.lastIndexOf("增值税专用发票",ranges.get(chosen)[0]));if(heading>=start)start=heading;else start=ranges.get(chosen)[0];}
        return new Segment(ids.get(chosen),text.substring(start,end),ranges.get(chosen)[0],ranges.get(chosen)[1]);
    }
    private int position(Map<String,Object> occurrence,Evidence e,String text){
        Object raw=occurrence.get("sourceOffset");int at=raw instanceof Number n?n.intValue():-1;
        if(at>=0&&at+e.quote().length()<=text.length()&&text.startsWith(e.quote(),at))return at;
        at=text.indexOf(e.quote());return at>=0&&text.indexOf(e.quote(),at+1)<0?at:-1;
    }
    private boolean verifiedTable(Map<String,Object> table,Evidence e,String text,int at){
        Map<String,Object> source=map(table.get("source"));if(!"RESOLVED".equals(table.get("status"))||!document.id().equals(source.get("documentId"))||!document.sha256().equals(source.get("documentHash")))return false;
        if(!(source.get("documentVersion") instanceof Number version)||version.intValue()!=document.version())return false;
        if(!(source.get("start") instanceof Number a)||!(source.get("end") instanceof Number b)||!(source.get("page") instanceof Number p))return false;
        List<Map<String,Object>> headers=maps(table.get("headerSources"));if(headers.isEmpty()||!(table.get("headerPath") instanceof List<?> labels)||headers.size()!=labels.size())return false;
        for(int i=0;i<headers.size();i++){
            Map<String,Object> header=headers.get(i);if(!document.id().equals(header.get("documentId"))||!document.sha256().equals(header.get("documentHash")))return false;
            Page hp=pages.get(string(header,"locationType")+":"+header.get("page"));
            if(hp==null||!(header.get("start") instanceof Number x)||!(header.get("end") instanceof Number y)||x.intValue()<0||y.intValue()>hp.text().length()||y.intValue()<x.intValue())return false;
            if(!plain(hp.text().substring(x.intValue(),y.intValue())).equals(plain(labels.get(i).toString())))return false;
        }
        return p.intValue()==e.page()&&e.locationType().equals(source.get("locationType"))&&a.intValue()==at&&b.intValue()>=a.intValue()&&b.intValue()<=text.length()&&text.substring(a.intValue(),b.intValue()).equals(e.quote());
    }
    private String rowQuote(Map<String,Object> occurrence,String text,int at){
        int start=text.lastIndexOf("<tr",at),end=text.indexOf("</tr>",at);if(start<0||end<0||text.indexOf("<tr",start+3)>=0&&text.indexOf("<tr",start+3)<at)return "";
        String row=text.substring(start,end+5);return row.length()<=1800?row:"";
    }
    private Binding unresolved(String id,String reason,List<Evidence> refs,String value){return new Binding("UNRESOLVED:"+id,"UNRESOLVED","",false,Map.of("reasonCode",reason,"derivedBy","SERVER_SOURCE_BOUNDARY"),unique(refs),List.of("可证明的同一对象身份"),value);}
    private Evidence source(Page page,int start,int end){return new Evidence(document.id(),document.name(),page.number(),page.locationType(),page.text().substring(start,end),"p"+page.number()+"-s"+start,0);}
    private boolean valid(Evidence e){if(e==null||!document.id().equals(e.documentId())||e.quote()==null||e.quote().isBlank())return false;Page p=pages.get(e.locationType()+":"+e.page());return p!=null&&p.text()!=null&&p.text().contains(e.quote());}
    static Evidence evidence(Object raw){try{return raw instanceof Evidence e?e:JSON.convertValue(raw,Evidence.class);}catch(IllegalArgumentException ignored){return null;}}
    static List<Evidence> unique(List<Evidence> input){Map<String,Evidence> unique=new LinkedHashMap<>();for(Evidence e:input)if(e!=null)unique.putIfAbsent(e.documentId()+":"+e.locationType()+":"+e.page()+":"+e.quote(),e);return List.copyOf(unique.values());}
    private static boolean completeContractId(String value){
        if(value.chars().noneMatch(Character::isDigit)||value.matches(".*[\\-/_\\.]$"))return false;
        Deque<Character> opened=new ArrayDeque<>();for(char c:value.toCharArray()){
            if(c=='('||c=='（')opened.push(c);
            else if(c==')'||c=='）'){if(opened.isEmpty()||opened.pop()!=(c==')'?'(':'（'))return false;}
        }return opened.isEmpty();
    }
    private static long contractLabelCount(String text){return CONTRACT_LABEL.matcher(text).results().count();}
    private static Set<String> matches(Pattern p,String text){Set<String> result=new LinkedHashSet<>();Matcher m=p.matcher(text);while(m.find()){String value=m.group(1).strip();if(p!=CONTRACT_ID||completeContractId(value))result.add(value);}return result;}
    private static String plain(String text){return text.replaceAll("<[^>]+>"," ").replaceAll("\\s+","");}
}
