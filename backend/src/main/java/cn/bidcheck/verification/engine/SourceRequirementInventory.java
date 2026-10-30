package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Finding;
import cn.bidcheck.domain.Domain.Page;
import cn.bidcheck.service.AgentBusinessGroupReuse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Expands named source inventories before evaluating their members; never treats one hit as all. */
public final class SourceRequirementInventory {
    public static final String VERSION="source-requirement-inventory-2-typed-source-dependencies";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Pattern CHAPTER=Pattern.compile("^第[一二三四五六七八九十百0-9]+章.{0,40}$");
    private SourceRequirementInventory() {}
    record Inventory(String kind,List<Member> members,List<Map<String,Object>> sources,List<String> issues,List<Map<String,Object>> referenceEdges) {
        Inventory(String kind,List<Member> members,List<Map<String,Object>> sources,List<String> issues){this(kind,members,sources,issues,List.of());}
    }
    record Member(String name,List<Map<String,Object>> sources,String nodeType) {
        Member(String name,List<Map<String,Object>> sources){this(name,sources,SourceObligationDirectory.type(name));}
        Map<String,Object> map(){return SourceObligationDirectory.node(name,sources,nodeType);}
    }

    static List<Rule> compile(Checkpoint cp,String source,RequirementSourceContext.Context sourceContext,Map<String,Object> context){
        String kind=kind(source);if(kind.isBlank()||kind.equals("SUMMARY"))return List.of();
        Map<String,Object> parameters=linked("sourceInventory",true,"inventoryVersion",VERSION,"inventoryKind",kind,
                "sourceCheckpoint",JSON.convertValue(cp,Map.class));
        Inventory inventory=resolve(cp,source,context);parameters.put("inventorySources",inventory.sources());
        parameters.put("inventoryMembers",inventory.members().stream().map(Member::map).toList());
        parameters.put("inventoryIssues",inventory.issues());
        return List.of(new Rule("INVENTORY-"+identity(source).substring(0,18),source,"SECTION","","sourceInventory","","","",parameters,source,"",List.of()));
    }
    static boolean applies(Rule rule){return Boolean.TRUE.equals(rule.parameters().get("sourceInventory"))&&VERSION.equals(rule.parameters().get("inventoryVersion"));}
    // Named forms read their exact current-source windows themselves and produce
    // page-bound visual requests. Retrieving the generic inventory label again
    // cannot improve that read and must not create three redundant search rounds.
    static boolean programOnly(Rule rule){return applies(rule);}
    static Rule dependencyRule(Checkpoint cp,String source,RequirementSourceContext.Context sourceContext,Map<String,Object> context){
        if(RequirementReferences.dependencies(sourceContext).isEmpty())return null;
        Map<String,Object> parameters=linked("sourceInventory",true,"inventoryVersion",VERSION,"inventoryKind","SUMMARY",
                "sourceCheckpoint",JSON.convertValue(cp,Map.class),"referenceDependencies",RequirementReferences.dependencies(sourceContext));
        return new Rule("SUMMARY-"+identity(source).substring(0,18),"引用业务事项的完整结果汇总","SECTION","","sourceInventory","","","",parameters,source,"",List.of());
    }
    /** This is an executable same-run dependency, not a document-section presence rule. */
    static boolean validatedDependency(Rule rule,String obligation,RequirementSourceContext.Context context){
        if(!applies(rule)||!"SECTION".equals(rule.predicate())||!"SUMMARY".equals(str(rule.parameters(),"inventoryKind"))
                ||!rule.id().equals("SUMMARY-"+identity(rule.sourceQuote()).substring(0,18))
                ||!RequirementReferences.sourceBound(rule.sourceQuote(),obligation,context))return false;
        List<Map<String,Object>> dependencies=RequirementReferences.dependencies(context);
        if(dependencies.isEmpty()||!dependencies.equals(RuleEvaluator.maps(rule.parameters().get("referenceDependencies"))))return false;
        return dependencies.stream().allMatch(d->"SUMMARY_OF".equals(d.get("referenceType"))
                &&"DEPENDENCY_SOURCE_RESOLVED".equals(d.get("status"))&&!str(d,"dependencyId").isBlank()
                &&d.get("sourceCount") instanceof Number count&&count.intValue()>0);
    }
    static boolean dependencyCovers(String fragment,Rule rule,RequirementSourceContext.Context context){
        if(!validatedDependency(rule,rule.sourceQuote(),context))return false;
        String exact=clean(fragment).replaceFirst("[。；;]+$","");
        return RequirementReferences.dependencies(context).stream().anyMatch(d->exact.equals(clean(str(d,"referenceQuote")).replaceFirst("[。；;]+$","")));
    }
    /** Used by the service after ordinary children finish, without another retrieval/model run. */
    public static boolean aggregateCheckpoint(Checkpoint checkpoint){return checkpoint!=null&&Set.of("STARRED","FORMAT","GENERAL","REJECTION","SUMMARY").contains(kind(Objects.toString(checkpoint.expected(),"")));}

    static Decision evaluate(Rule rule,RuleEvaluator evaluator){
        Checkpoint cp;
        try{cp=JSON.convertValue(rule.parameters().get("sourceCheckpoint"),Checkpoint.class);}catch(RuntimeException ex){return evaluator.unknown(rule,"REQUIREMENT_INVENTORY_SOURCE_INVALID","要求清单来源不完整",List.of());}
        Inventory inventory=resolve(cp,rule.sourceQuote(),evaluator.context);
        Map<String,Object> proof=linked("executor",VERSION,"inventoryKind",inventory.kind(),"inventorySources",inventory.sources(),
                "inventoryMembers",inventory.members().stream().map(Member::map).toList(),"inventoryIssues",inventory.issues(),
                "obligationDirectoryVersion",SourceObligationDirectory.VERSION,"obligationDirectory",inventory.members().stream().map(Member::map).toList(),"referenceEdges",inventory.referenceEdges());
        if(inventory.kind().equals("TECHNICAL_ATTACHMENT")&&inventory.members().isEmpty()&&inventory.issues().contains("技术规范书附件正文不在冻结招标文档内"))return evaluator.unknown(rule,"MISSING_TENDER_ATTACHMENT",
                "招标正文将具体清单指向《技术规范书》附件，当前冻结招标文件未包含该附件正文；须补充招标附件后才能确定要求，不能认定投标文件缺材料",List.of(),proof);
        if(inventory.kind().equals("COMMITMENTS")){
            if(!inventory.issues().isEmpty()||inventory.members().isEmpty())return evaluator.unknown(rule,"REQUIREMENT_INVENTORY_SOURCE_INCOMPLETE","引用的承诺全集尚未从招标原文完整展开",List.of(),proof);
            List<Decision> children=new ArrayList<>();
            Rule bidderBinding=new Rule("INVENTORY-BIDDER",rule.description(),"MATERIAL","投标人","","","","",Map.of(),rule.sourceQuote(),"",List.of());
            List<Fact> boundFacts=new ArrayList<>(evaluator.facts);boundFacts.addAll(EnterpriseSubjectBindings.extract(List.of(bidderBinding),evaluator.corpus.all));
            RuleEvaluator childEvaluator=new RuleEvaluator(boundFacts,evaluator.corpus,evaluator.context);
            for(Member member:inventory.members()){
                Rule child=TenderFormAudit.namedRule(cp,rule.sourceQuote(),member.name(),evaluator.context,false,rule.id()+":"+identity(member.name()).substring(0,12));
                children.add(childEvaluator.evaluate(child));
            }
            evaluator.visualRequests.addAll(childEvaluator.visualRequests);
            Decision out=RuleEvaluator.combine(rule.id(),rule.description(),"ALL",children);proof.putAll(out.proof());proof.put("expectedMemberCount",inventory.members().size());
            proof.put("proofScope","ALL_SOURCE_NAMED_FORMS_CONTENT_AND_SIGNATURE");
            return new Decision(out.id(),out.description(),out.status(),out.reason(),out.evidences(),out.gaps(),proof);
        }
        return aggregate(rule,cp,inventory,evaluator,proof);
    }

    private static Decision aggregate(Rule rule,Checkpoint cp,Inventory inventory,RuleEvaluator evaluator,Map<String,Object> proof){
        RequirementDependencyProof.Snapshot snapshot=RequirementDependencyProof.current(evaluator);
        List<Document> tenderDocuments=evaluator.context.get("tenderDocuments") instanceof Collection<?> docs
                ?docs.stream().filter(Document.class::isInstance).map(Document.class::cast).toList():List.of();
        List<Finding> findings=snapshot.findings();
        List<Decision> children=new ArrayList<>();List<String> unresolved=new ArrayList<>(inventory.issues());unresolved.addAll(snapshot.issues());Set<String> ids=new LinkedHashSet<>();
        if(inventory.members().isEmpty())unresolved.add("引用范围未形成非空、完整的要求成员集合");
        for(Member member:inventory.members()){
            List<Finding> matches=findings.stream().filter(f->!f.checkpoint().id().equals(cp.id())&&!aggregateCheckpoint(f.checkpoint()))
                    .filter(f->str(evaluator.context,"sourceRunId").equals(f.runId())).filter(f->sourceMatches(f.checkpoint(),member,tenderDocuments))
                    .filter(f->snapshot.verified()).toList();
            if(matches.isEmpty()&&Set.of(SourceObligationDirectory.EVENT,SourceObligationDirectory.EXTERNAL,SourceObligationDirectory.OPEN).contains(member.nodeType())){
                String code=SourceObligationDirectory.EVENT.equals(member.nodeType())?"WAIT_STAGE":"EXTERNAL_RECORD_REQUIRED";
                String detail=SourceObligationDirectory.EVENT.equals(member.nodeType())?"此义务依赖评标现场的事件或记录：":"此义务需要文件范围之外的事实及适用依据：";
                children.add(new Decision(rule.id()+":"+str(member.map(),"canonicalObligationId"),member.name(),"UNDETERMINED",detail+member.name(),List.of(),
                        List.of(new Gap(code,detail+member.name(),"带来源、适用范围和时点的评审或外部核验记录","RELEVANT_EXTERNAL_RECORD_CHANGED")),member.map()));
                continue;
            }
            if(SourceObligationDirectory.HEADING.equals(member.nodeType()))continue;
            if(SourceObligationDirectory.REFERENCE.equals(member.nodeType())){
                unresolved.add("引用须展开到具体业务义务："+member.name());continue;
            }
            if(matches.isEmpty()){unresolved.add("尚无完整子事项结果："+member.name());continue;}
            // The source quote must be covered, not merely a checkpoint sharing its paragraph.
            if(!sourceCovered(member,matches,tenderDocuments)){
                unresolved.add("子事项尚未覆盖完整来源："+member.name());continue;
            }
            for(Finding finding:matches)if(ids.add(finding.checkpoint().id())){
                List<Gap> gaps=new ArrayList<>();Object rawAtomic=finding.assessment()==null?null:finding.assessment().get("atomicCheck");
                if(rawAtomic instanceof Map<?,?> a)for(Map<String,Object> gapRow:RuleEvaluator.maps(a.get("gaps")))gaps.add(new Gap(str(gapRow,"code"),str(gapRow,"description"),str(gapRow,"requiredInput"),str(gapRow,"resumeTrigger")));
                if("PASS".equals(finding.evaluation().status())&&!gaps.isEmpty()){
                    unresolved.add("子事项仍有未完成义务："+finding.checkpoint().title());continue;
                }
                if(!Set.of("PASS","FAIL","NOT_APPLICABLE").contains(finding.evaluation().status())&&gaps.isEmpty())gaps.add(gap("DEPENDENCY_RESULT_PENDING",finding.evaluation().reason()));
                children.add(new Decision(finding.checkpoint().id(),finding.checkpoint().title(),finding.evaluation().status(),finding.evaluation().reason(),finding.evaluation().evidences(),gaps,
                        linked("sourceFindingId",finding.id(),"runId",finding.runId(),"sourceCheckpointId",finding.checkpoint().id())));
            }
        }
        proof.put("dependencyCheckpointIds",List.copyOf(ids));proof.put("unresolvedMembers",unresolved);
        proof.put("dependencyRevision",RequirementDependencyProof.relevantRevision(snapshot,ids));
        proof.put("inventorySourceRangeClosed",inventory.issues().isEmpty());
        proof.put("allDocumentMembersCovered",unresolved.isEmpty());
        proof.put("externalOrStageObligationCount",inventory.members().stream().filter(m->Set.of(SourceObligationDirectory.EVENT,SourceObligationDirectory.EXTERNAL,SourceObligationDirectory.OPEN).contains(m.nodeType())).count());
        if(!unresolved.isEmpty())children.add(new Decision(rule.id()+":coverage","来源清单完整性","UNDETERMINED","引用清单尚有未覆盖范围",List.of(),List.of(gap("REQUIREMENT_INVENTORY_COVERAGE_REQUIRED",String.join("；",unresolved))),Map.of("unresolvedMembers",unresolved)));
        Decision out=RuleEvaluator.combine(rule.id(),rule.description(),"ALL",children);proof.putAll(out.proof());
        return new Decision(out.id(),out.description(),out.status(),out.reason(),out.evidences(),out.gaps(),proof);
    }

    private static boolean sourceMatches(Checkpoint cp,Member member,List<Document> documents){
        if(cp.source()==null)return false;
        return member.sources().stream().anyMatch(s->cp.source().documentId().equals(str(s,"documentId"))
                &&(cp.source().locationType().equals(str(s,"locationType"))&&cp.source().page()==RunSession.number(s.get("location"),-1)
                    &&(clean(cp.source().quote()).contains(clean(str(s,"sourceQuote")))||clean(str(s,"sourceQuote")).contains(clean(cp.source().quote())))
                    ||SourceObligationDirectory.sameMeaning(cp.source().quote(),str(s,"sourceQuote"))&&sameFrozenBusinessScope(cp,s,documents)));
    }
    private static boolean sameFrozenBusinessScope(Checkpoint cp,Map<String,Object> target,List<Document> documents){
        Document document=documents.stream().filter(d->d.id().equals(cp.source().documentId())).findFirst().orElse(null);
        if(document==null)return false;
        Evidence source=new Evidence(document.id(),document.name(),RunSession.number(target.get("location"),-1),str(target,"locationType"),str(target,"sourceQuote"),"",1);
        Checkpoint other=new Checkpoint(cp.id()+":inventory-source",cp.title(),cp.category(),cp.severity(),cp.type(),cp.field(),cp.operator(),source.quote(),cp.unit(),cp.required(),cp.confirmed(),cp.enabled(),source);
        // Identical phrases can belong to different lots, joint-venture members or review purposes.
        // Reuse the existing frozen-business identity rather than inventing a weaker directory key.
        return AgentBusinessGroupReuse.plan(List.of(cp,other),document,c->true).reusableGroupCount()==1;
    }
    /** Source spans form a union; database/completion order cannot decide coverage. */
    static boolean sourceCovered(Member member,List<Finding> findings){
        return sourceCovered(member,findings,List.of());
    }
    static boolean sourceCovered(Member member,List<Finding> findings,List<Document> documents){
        for(Map<String,Object> source:member.sources()){
            String target=clean(str(source,"sourceQuote"));boolean[] covered=new boolean[target.length()];
            if(target.isBlank())return false;
            boolean equivalent=false;
            for(Finding finding:findings){
                if(!sourceMatches(finding.checkpoint(),new Member(member.name(),List.of(source)),documents))continue;
                var selected=RequirementScope.resolve(finding.checkpoint(),finding.checkpoint().source().quote());
                if(!selected.resolved())continue;
                if(SourceObligationDirectory.sameMeaning(selected.obligation(),str(source,"sourceQuote"))){equivalent=true;break;}
                String quote=clean(selected.obligation());int at=target.indexOf(quote);
                if(!quote.isBlank()&&at>=0&&target.indexOf(quote,at+1)<0)Arrays.fill(covered,at,at+quote.length(),true);
            }
            if(equivalent)continue;
            StringBuilder remainder=new StringBuilder();for(int i=0;i<covered.length;i++)if(!covered[i])remainder.append(target.charAt(i));
            if(!remainder.toString().matches("[★*。；;，,：:、]*"))return false;
        }return !member.sources().isEmpty();
    }

    static Inventory resolve(Checkpoint cp,String source,Map<String,Object> context){
        String kind=kind(source);Document doc=document(cp,context);
        if(doc==null)return new Inventory(kind,List.of(),List.of(),List.of("未验证冻结招标原文来源"));
        if(kind.equals("COMMITMENTS"))return commitments(doc);
        if(kind.equals("TECHNICAL_ATTACHMENT")){
            List<Document> attachments=new ArrayList<>();
            if(context.get("tenderDocuments") instanceof Collection<?> values)for(Object raw:values)if(raw instanceof Document attached&&attached.role().equals("TENDER")&&attached.projectId().equals(doc.projectId())&&!attached.id().equals(doc.id())&&attached.name().contains("技术规范书"))attachments.add(attached);
            if(attachments.isEmpty())return new Inventory(kind,List.of(),doc.pages().stream().filter(p->p.text().contains("招标文件附件：技术规范书")).map(p->source(doc,p)).toList(),List.of("技术规范书附件正文不在冻结招标文档内"));
            if(attachments.size()!=1)return new Inventory(kind,List.of(),List.of(),List.of("技术规范书存在多个版本，需明确适用版本"));
            Document attached=attachments.get(0);List<Member> members=new ArrayList<>();List<Map<String,Object>> sources=new ArrayList<>();
            for(Page page:attached.pages()){sources.add(source(attached,page));if(page.text().matches("(?s).*(?:投标人|投标文件).{0,35}(?:须|应|需|必须).{0,8}(?:提供|提交|包含).*"))members.add(new Member(page.text(),List.of(source(attached,page))));}
            // A full attachment can contain tables, conditional duties or secondary references.
            // Mere regex hits cannot certify that all of those obligations were expanded.
            return new Inventory(kind,members,sources,List.of("已取得技术规范书；须完成附件全范围要求映射后汇总，不能以部分命中作为完整清单"));
        }
        if(kind.equals("STARRED")){
            List<Member> members=new ArrayList<>();List<Map<String,Object>> sources=new ArrayList<>();List<String> issues=new ArrayList<>();
            for(Page page:doc.pages()){
                String text=page.text().strip();
                if(text.matches("^[★*].+")&&!text.matches(".*\\t[0-9]+$")&&text.length()>16){Map<String,Object> s=source(doc,page);sources.add(s);members.add(new Member(text,List.of(s)));}
                if(text.contains("实质性条款包括")&&text.contains("附件全部条款")){sources.add(source(doc,page));issues.add("原文还要求合同、技术规范书及附件的全部条款，须取得对应附件并展开");}
            }
            return new Inventory(kind,List.copyOf(members),List.copyOf(sources),List.copyOf(issues));
        }
        if(kind.equals("FORMAT"))return format(doc);
        if(kind.equals("SUMMARY")){
            var sourceContext=RequirementSourceContext.resolve(cp,cp.source().quote(),context);
            var expansion=RequirementReferences.expand(cp,source,sourceContext,context);
            List<Map<String,Object>> rows=expansion.context().sources().stream().filter(s->"REFERENCE_DEPENDENCY".equals(s.get("kind"))).toList();
            return typedInventory(doc,kind,rows,expansion.issues());
        }
        if(kind.equals("GENERAL"))return general(cp,source,doc,context);
        if(kind.equals("REJECTION")){
            List<Member> members=new ArrayList<>();List<Map<String,Object>> sources=new ArrayList<>();boolean start=false;
            for(Page page:doc.pages()){
                String text=page.text().strip();if(text.matches("^3\\.1\\.2[ \\t].*否决.*")){start=true;sources.add(source(doc,page));continue;}
                if(start&&text.matches("^(?:3\\.1\\.[3-9]|3\\.[2-9]|第[一二三四五六七八九十]+章).*"))break;
                if(start&&!text.isBlank()){Map<String,Object> s=source(doc,page);sources.add(s);members.add(new Member(text,List.of(s)));}
            }
            if(sources.isEmpty())return new Inventory(kind,List.of(),List.of(),List.of("未定位否决条件的编号正文，不能推定来源清单"));
            Inventory typed=typedInventory(doc,kind,sources,List.of());
            if(source.contains("法律规定")){
                List<Member> all=new ArrayList<>(typed.members());
                Map<String,Object> origin=checkpointSource(cp,doc);
                all.add(new Member("法律规定的其他否决情形",List.of(origin),SourceObligationDirectory.OPEN));
                return new Inventory(kind,List.copyOf(all),typed.sources(),typed.issues(),typed.referenceEdges());
            }
            return typed;
        }
        return new Inventory(kind,List.of(),List.of(),List.of("未定义引用清单"));
    }

    private static Inventory typedInventory(Document doc,String kind,List<Map<String,Object>> sources,List<String> issues){
        SourceObligationDirectory.Expansion expansion=SourceObligationDirectory.expand(doc,sources);
        List<String> allIssues=new ArrayList<>(issues);allIssues.addAll(expansion.issues());
        List<Member> members=new ArrayList<>();List<Map<String,Object>> directorySources=new ArrayList<>();
        boolean correctionProcedure=false;
        for(Map<String,Object> source:expansion.rows()){
            String quote=str(source,"sourceQuote"),type=SourceObligationDirectory.type(quote);
            if(quote.strip().matches("^[0-9]+(?:[.．][0-9]+)+.*"))correctionProcedure=false;
            if(quote.contains("评标委员会按照以下原则")&&quote.contains("修正"))correctionProcedure=true;
            if(correctionProcedure)type=SourceObligationDirectory.EVENT;
            Map<String,Object> annotated=new LinkedHashMap<>(source);annotated.put("nodeType",type);directorySources.add(annotated);
            if(!SourceObligationDirectory.HEADING.equals(type))members.add(new Member(quote,List.of(source),type));
        }
        return new Inventory(kind,List.copyOf(members),List.copyOf(directorySources),List.copyOf(allIssues),expansion.edges());
    }

    /** Enumerate known document duties but retain the source's open legal/onsite boundary as its own node. */
    private static Inventory general(Checkpoint cp,String quote,Document doc,Map<String,Object> context){
        List<Member> members=new ArrayList<>();List<Map<String,Object>> sources=new ArrayList<>();Set<String> seen=new HashSet<>();
        Object raw=context.get("_requirementDependencySnapshot");
        if(raw instanceof java.util.function.Supplier<?> supplier&&supplier.get() instanceof Map<?,?> snapshot
                &&snapshot.get("checkpoints") instanceof Collection<?> checkpoints){
            for(Object item:checkpoints)if(item instanceof Checkpoint child&&!child.id().equals(cp.id())&&child.enabled()&&child.confirmed()&&child.required()
                    &&!aggregateCheckpoint(child)&&SourceObligationDirectory.checkpointSourceCurrent(child,List.of(doc))){
                RequirementScope.Selection scope=RequirementScope.resolve(child,child.source().quote());if(!scope.resolved())continue;
                Map<String,Object> origin=checkpointSource(child,doc);origin.put("sourceQuote",scope.obligation());
                String type=SourceObligationDirectory.type(scope.obligation());
                if(SourceObligationDirectory.HEADING.equals(type)||!seen.add(SourceObligationDirectory.id(type,scope.obligation(),List.of(origin))))continue;
                sources.add(origin);members.add(new Member(scope.obligation(),List.of(origin),type));
            }
        }
        Map<String,Object> origin=checkpointSource(cp,doc);sources.add(origin);
        String boundary=quote.contains("现场")?SourceObligationDirectory.EVENT:SourceObligationDirectory.OPEN;
        members.add(new Member(quote,List.of(origin),boundary));
        return new Inventory("GENERAL",List.copyOf(members),List.copyOf(sources),List.of());
    }

    private static Map<String,Object> checkpointSource(Checkpoint cp,Document doc){
        var s=cp.source();return linked("documentId",doc.id(),"documentHash",doc.sha256(),"documentVersion",doc.version(),
                "locationType",s.locationType(),"location",s.page(),"sourceQuote",s.quote());
    }

    static Inventory commitments(Document doc){
        List<Inventory> candidates=new ArrayList<>();List<Page> pages=doc.pages();
        for(int i=0;i<pages.size();i++){
            if(!heading(pages.get(i).text()).equals("承诺书"))continue;
            List<Member> members=new ArrayList<>();List<Map<String,Object>> sources=new ArrayList<>();List<String> issues=new ArrayList<>();sources.add(source(doc,pages.get(i)));Set<String> seen=new LinkedHashSet<>();boolean ended=false;
            for(int j=i+1;j<pages.size();j++){
                Page p=pages.get(j);String h=heading(p.text());
                if(h.matches("其他承诺(?:书)?|其他投标人认为有必要的资料|第[一二三四五六七八九十0-9]+分册.*")||CHAPTER.matcher(h).matches()){sources.add(source(doc,p));ended=true;break;}
                if(h.length()<=35&&h.matches("[\\p{IsHan}A-Za-z0-9]+(?:承诺书|合规承诺)")&&!h.equals("承诺书")&&seen.add(h)){
                    List<Map<String,Object>> template=TenderFormAudit.locate(doc,h);
                    members.add(new Member(h,List.of(source(doc,p))));sources.add(source(doc,p));
                    if(template.isEmpty())issues.add("引用表单正文尚未完整定位："+h);
                }
            }
            if(!ended)issues.add("承诺清单末尾边界尚未明确");
            if(!members.isEmpty())candidates.add(new Inventory("COMMITMENTS",List.copyOf(members),List.copyOf(sources),List.copyOf(issues)));
        }
        return candidates.size()==1?candidates.get(0):new Inventory("COMMITMENTS",List.of(),List.of(),List.of(candidates.isEmpty()?"引用承诺书正文清单未定位":"多个承诺书正文清单，尚不能确定引用范围"));
    }

    static Inventory format(Document doc){
        List<Page> pages=doc.pages();List<Inventory> candidates=new ArrayList<>();
        for(int i=0;i<pages.size();i++){
            String chapter=heading(pages.get(i).text());
            if(!CHAPTER.matcher(chapter).matches()||!chapter.endsWith("投标文件格式")||pages.get(i).text().matches(".*\\t[0-9]+$"))continue;
            // A chapter listed alongside other chapters is a document index, not the form body.
            if(i>0&&CHAPTER.matcher(heading(pages.get(i-1).text())).matches()||i+1<pages.size()&&CHAPTER.matcher(heading(pages.get(i+1).text())).matches())continue;
            List<Member> members=new ArrayList<>();List<Map<String,Object>> sources=new ArrayList<>();Set<String> books=new LinkedHashSet<>();String firstForm="";boolean ended=false,bookSeen=false;
            sources.add(source(doc,pages.get(i)));
            for(int j=i+1;j<pages.size();j++){
                Page page=pages.get(j);String title=heading(page.text());
                if(title.isBlank()||title.equals("投标文件格式"))continue;
                if(CHAPTER.matcher(title).matches())break;
                if(title.matches("第[一二三四五六七八九十0-9]+分册.*")){
                    if(!books.add(title)){
                        // The repeated book is only a boundary when followed by a catalogued form and its body.
                        String next=j+1<pages.size()?heading(pages.get(j+1).text()):"";
                        ended=members.stream().anyMatch(m->m.name().equals(next))&&j+2<pages.size()&&formBody(pages.get(j+2).text());
                        if(ended)sources.add(source(doc,page));break;
                    }
                    bookSeen=true;sources.add(source(doc,page));continue;
                }
                if(!bookSeen)break;
                if(firstForm.isEmpty())firstForm=title;
                // Single-volume catalogues may transition directly to the first named form.
                if(title.equals(firstForm)&&!members.isEmpty()&&j+1<pages.size()&&formBody(pages.get(j+1).text())){ended=true;sources.add(source(doc,page));break;}
                if(!formTitle(title))break;
                Map<String,Object> origin=source(doc,page);sources.add(origin);
                if(!title.equals("其他投标人认为有必要的资料"))members.add(new Member(title,List.of(origin),title.contains("承诺")?SourceObligationDirectory.DECLARATION:SourceObligationDirectory.DOCUMENT));
            }
            if(!members.isEmpty())candidates.add(new Inventory("FORMAT",List.copyOf(members),List.copyOf(sources),ended?List.of():List.of("投标文件格式目录与表单正文边界尚未闭合")));
        }
        List<Inventory> closed=candidates.stream().filter(c->c.issues().isEmpty()).toList();
        if(closed.size()==1)return closed.get(0);
        if(closed.size()>1)return new Inventory("FORMAT",List.of(),List.of(),List.of("存在多个闭合的格式目录，须确定适用版本"));
        if(candidates.size()==1)return candidates.get(0);
        return new Inventory("FORMAT",List.of(),List.of(),List.of("未定位可验证的投标文件格式目录及其正文边界"));
    }
    private static boolean formTitle(String value){return value.length()<=65&&!value.matches("(?s).*[：:。；;].*")&&!value.contains("另有要求")&&!value.contains("前附表")&&!value.contains("（项目名称")&&!value.contains("(项目名称");}
    private static boolean formBody(String text){return text.matches("(?s).*(?:[（(]项目名称[）)]|招标项目编号|投标人名称[：:]|投标人[：:（(]|致[：:]|序号\\t|编制要求|签署要求|(?:我方|投标人)承诺[^。]{15,}).*" );}
    private static Document document(Checkpoint cp,Map<String,Object> context){
        if(cp==null||cp.source()==null||!(context.get("tenderDocuments") instanceof Collection<?> docs))return null;
        List<Document> matches=docs.stream().filter(Document.class::isInstance).map(Document.class::cast)
                .filter(d->d.role().equals("TENDER")&&d.id().equals(cp.source().documentId()))
                .filter(d->d.pages().stream().anyMatch(p->p.number()==cp.source().page()&&p.locationType().equals(cp.source().locationType())&&p.text().contains(cp.source().quote()))).toList();
        return matches.size()==1?matches.get(0):null;
    }
    private static String kind(String source){
        if(source.matches("(?s).*(?:清单详见|清单见).*《承诺书》.*"))return "COMMITMENTS";
        if(source.matches("(?s)^技术规范书中明确要求投标人提供的其他相关文件[。]?$"))return "TECHNICAL_ATTACHMENT";
        if(source.contains("投标文件格式")&&source.contains("无缺失"))return "FORMAT";
        if(source.contains("标识")&&source.contains("★")&&source.contains("实质性条款"))return "STARRED";
        if(source.contains("3.1.2")&&source.contains("否决"))return "REJECTION";
        if(source.matches("(?s).*3[.．]1(?:[.．]2)?(?:条|款|项|\\s)*初步评审.*"))return "SUMMARY";
        if(source.matches("(?s).*符合法律[、，,]法规及招标文件规定的其他实质性内容.*"))return "GENERAL";
        return "";
    }
    private static String heading(String text){return clean(text).replaceFirst("^[★*]+","").replaceFirst("^[一二三四五六七八九十0-9]+[、.．]","");}
    private static Map<String,Object> source(Document d,Page p){return linked("documentId",d.id(),"documentHash",d.sha256(),"documentVersion",d.version(),"locationType",p.locationType(),"location",p.number(),"start",0,"end",p.text().length(),"sourceQuote",p.text());}
}
