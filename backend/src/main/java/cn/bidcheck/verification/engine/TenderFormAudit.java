package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Checkpoint;
import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Evidence;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** A submitted form is not proof that its prescribed fields and content were completed. */
final class TenderFormAudit {
    static final String VERSION="tender-form-audit-4-typed-identity-template-fields";
    private static final Pattern NAME=Pattern.compile("《([^》]{0,50}(?:投标函|偏离表|承诺书|委托书|证明书))》");
    private static final Pattern SLOT=Pattern.compile("(?:XX(?:公司)?[ \\t]*)?\\[(?:投标人法定代表人[（(]负责人[）)]姓名|投标人单位名称|投标人名称|委托代理人姓名|招标项目名称)\\]|[（(](?:招标人|投标人名称|项目名称|招标编号|其他补充说明)[）)]|[ _＿]{2,}");
    private TenderFormAudit() {}

    /** This source offers one project invoice undertaking, not one promise per consortium member.
     * The executor still verifies the document's actual issuer and its own signer/company link. */
    static boolean independentInvoiceIssuer(Rule rule,String sourceContext){return Boolean.TRUE.equals(rule.parameters().get("templateFormAudit"))
            &&Boolean.TRUE.equals(rule.parameters().get("invoiceCommitmentOnly"))&&"增值税专用发票承诺书".equals(str(rule.parameters(),"documentType"))
            &&QualificationBidderContext.neutralSource(rule.sourceQuote())&&!allMembersRequired(sourceContext);}
    private static boolean allMembersRequired(String source){return clean(source).matches("(?s).*(?:联合体(?:各成员|各方|全部成员|全体成员)|各联合体成员)[^。；;]{0,35}(?:分别提供|均须提供|均应提供|分别提交|均须提交|均应提交|均须签|均应签|分别签|均须盖章|均应盖章|分别盖章|分别在各自的文件上加盖各自的单位公章).*");}

    static List<Rule> compile(Checkpoint cp,String requirement,Map<String,Object> context){
        boolean prescribed=requirement.matches("(?s).*(?:按照|按).{0,20}(?:规定|格式).{0,8}填报.*");
        boolean commitmentReference=requirement.matches("(?s).*(?:须|应|需)(?:提供|提交)《[^》]*承诺书》[（(]见第[^）)]*《[^》]*承诺书》[）)].*")&&!requirement.matches("(?s).*(?:提供|提交)《[^》]*承诺书》(?:或|或者).*");
        if(!prescribed&&!commitmentReference)return cn.bidcheck.compat.Java8Api.listOf();
        Set<String> names=new LinkedHashSet<>();Matcher named=NAME.matcher(requirement);while(named.find())names.add(named.group(1));
        if(names.isEmpty())return cn.bidcheck.compat.Java8Api.listOf(new Rule("FORM-COVERAGE","规定格式与必填项未展开","UNSUPPORTED","","formFormat","","","",
                cn.bidcheck.compat.Java8Api.mapOf("deterministicFamily", "FORM_TEMPLATE_REFERENCE_REQUIRED"),requirement,"",cn.bidcheck.compat.Java8Api.listOf()));
        List<Rule> result=new ArrayList<>();
        for(String name:names)result.add(namedRule(cp,requirement,name,context,false,"FORM-"+identity(name).substring(0,12)));
        return result;
    }

    static Rule namedRule(Checkpoint cp,String source,String name,Map<String,Object> context,boolean optionalFormat,String id){
        List<Document> documents=new ArrayList<>();if(context.get("tenderDocuments") instanceof List<?>)for(Object raw:((List<?>) (context.get("tenderDocuments"))))
            if(raw instanceof Document&&"TENDER".equals(((Document) (raw)).role())&&cp.source()!=null&&((Document) (raw)).id().equals(cp.source().documentId()))documents.add(((Document) (raw)));
        List<Map<String,Object>> template=documents.size()==1?locate(documents.get(0),name):cn.bidcheck.compat.Java8Api.listOf();
        Map<String,Object> p=new LinkedHashMap<>();p.put("templateFormAudit",true);p.put("templateAuditVersion",VERSION);p.put("documentType",name);p.put("templateSources",template);
        p.put("deterministicFamily",template.isEmpty()?"FORM_TEMPLATE_REFERENCE_REQUIRED":"FORM_TEMPLATE_COVERAGE");
        if(optionalFormat)p.put("invoiceCommitmentOnly",true);
        return new Rule(id,name+(optionalFormat?"本项目开票承诺及签署位置":"规定内容、必填项和签署位置"),template.isEmpty()?"UNSUPPORTED":"MATERIAL","","formFormat","",name,"",p,source,"",cn.bidcheck.compat.Java8Api.listOf());
    }

    /** Require an actual body after the heading; adjacent table-of-contents titles are rejected. */
    static List<Map<String,Object>> locate(Document document,String name){
        List<List<Map<String,Object>>> candidates=new ArrayList<>();List<Page> pages=document.pages();
        for(int i=0;i<pages.size();i++){
            if(!sameForm(heading(pages.get(i).text()),name))continue;
            if(i>0&&sameForm(heading(pages.get(i-1).text()),name))continue;
            List<Map<String,Object>> body=new ArrayList<>();int chars=0;
            for(int j=i+1;j<pages.size()&&j<i+70;j++){
                Page p=pages.get(j);String raw=p.text(),text=clean(raw);
                if(sameForm(text,name))continue;
                if(isNextHeading(raw))break;
                if(!cn.bidcheck.compat.Java8Api.isBlank(text)){body.add(linked("documentId",document.id(),"documentHash",document.sha256(),"documentVersion",document.version(),"locationType",p.locationType(),"location",p.number(),"sourceQuote",raw));chars+=text.length();}
            }
            if(chars>=80&&body.stream().anyMatch(row->str(row,"sourceQuote").matches("(?s).*(?:填写|项目名称|投标人|条款号).*")))candidates.add(body);
        }
        if(candidates.size()!=1)return cn.bidcheck.compat.Java8Api.listOf();return cn.bidcheck.compat.Java8Api.listCopyOf(candidates.get(0));
    }
    private static String heading(String value){return clean(value).replaceFirst("^[一二三四五六七八九十0-9]+[、.．]","");}
    /** Only the tender's named legal-representative parenthesis variants are equivalent. */
    static String canonicalForm(String name){String value=clean(name).replace('（','(').replace('）',')');
        if(value.matches("法定代表人(?:\\(负责人\\))?(?:身份证明|授权委托书)"))return value.replace("(负责人)","");return value;}
    static boolean sameForm(String left,String right){return canonicalForm(left).equals(canonicalForm(right));}
    private static boolean isNextHeading(String raw){String text=clean(raw);if(cn.bidcheck.compat.Java8Api.setOf("其他承诺", "其他承诺书", "其他投标人认为有必要的资料", "法定代表人身份证明", "法定代表人授权委托书").contains(canonicalForm(text)))return true;return text.length()>1&&text.length()<=35&&!text.matches(".*[：:，,。；;（）()《》\\t].*")
            &&!cn.bidcheck.compat.Java8Api.setOf("我方承诺如下内容", "编制及签署要求").contains(text)&&text.matches(".*(?:文件|证明|情况|承诺书|偏离表|投标函|协议书|委托书|索引表|信息一览表|方案|书)$");}

    static Decision evaluate(Rule rule,List<Fact> matched,RuleEvaluator evaluator){
        List<Map<String,Object>> template=RuleEvaluator.maps(rule.parameters().get("templateSources"));
        if(template.isEmpty())return evaluator.unknown(rule,"FORM_TEMPLATE_REFERENCE_REQUIRED","系统尚未定位引用格式正文，材料存在不能代替格式与必填项核验",matched);
        String form=str(rule.parameters(),"documentType");
        Document tender=null;
        if(evaluator.context.get("tenderDocuments") instanceof List<?>)for(Object raw:((List<?>) (evaluator.context.get("tenderDocuments"))))if(raw instanceof Document
                &&"TENDER".equals(((Document) (raw)).role())&&template.get(0).get("documentId").equals(((Document) (raw)).id())&&template.get(0).get("documentHash").equals(((Document) (raw)).sha256())
                &&Objects.equals(template.get(0).get("documentVersion"),((Document) (raw)).version())&&template.equals(locate(((Document) (raw)),form)))tender=((Document) (raw));
        if(tender==null)return evaluator.unknown(rule,"FORM_REQUIREMENT_SOURCE_REQUIRED","引用模板与当前冻结招标文档尚未重新核源",matched);
        if(Boolean.TRUE.equals(rule.parameters().get("signaturePositionsOnly"))&&template.stream().noneMatch(row->str(row,"sourceQuote").matches("(?s).*(?:盖章|公章|签章|签字|签名).*")))
            return new Decision(rule.id(),rule.description(),"NOT_APPLICABLE","该已核源格式未指定签署位置；材料是否需提交另行核验",cn.bidcheck.compat.Java8Api.listOf(),cn.bidcheck.compat.Java8Api.listOf(),linked("executor",VERSION,"proofScope","SOURCE_HAS_NO_SIGNATURE_POSITION","templateSources",template));
        Map<String,DocumentScopeProof.Resolver> resolvers=new HashMap<>();
        Map<String,Map<String,Object>> sourceScopes=new LinkedHashMap<>();
        List<Source> sources=cn.bidcheck.compat.Java8Api.toList(evaluator.corpus.all.stream().filter(s->!s.index()).filter(s->canonicalForm(s.opening()).contains(canonicalForm(form))||cn.bidcheck.compat.Java8Extras.lines(s.opening()).anyMatch(line->sameForm(heading(line),form)))
            .filter(s->{Page page=evaluator.corpus.pages.get(EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()));
                return page!=null&&bodyInstance(page.text(),form);}).filter(s->{
            DocumentScopeProof.Resolver resolver=resolvers.computeIfAbsent(s.evidence().documentId(),id->evaluator.corpus.documents.stream().filter(d->d.id().equals(id)).findFirst().map(DocumentScopeProof::forDocument).orElse(null));if(resolver==null)return false;
            Map<String,Object> local=resolver.currentEvidence(s.evidence());if(!Boolean.TRUE.equals(local.get("verifiedCurrent")))return false;
            sourceScopes.put(s.id(),local);return true;
        }));
        if(sources.isEmpty())return evaluator.unknown(rule,"FORM_BODY_REQUIRED","未定位当前项目的表单正文，目录或其他文书不能代替",matched);
        Document verifiedTender=tender;
        if(sources.stream().anyMatch(s->cn.bidcheck.compat.Java8Api.isBlank(scopeProject(sourceScopes.get(s.id())))
                ||verifiedTender.pages().stream().limit(12).noneMatch(p->clean(p.text()).contains(clean(scopeProject(sourceScopes.get(s.id())))))))
            return evaluator.unknown(rule,"FORM_PROJECT_BINDING_REQUIRED","表单尚未对应当前招标项目，不能借其他项目的完整模板证明本项",matched);
        if(Boolean.TRUE.equals(rule.parameters().get("signaturePositionsOnly")))return signaturePositions(rule,evaluator,form,template,sources,sourceScopes);
        long instances=sources.stream().map(s->s.evidence().documentId()+":"+s.evidence().page()).distinct().count();
        if(instances!=1)return evaluator.unknown(rule,"FORM_INSTANCE_AMBIGUOUS","发现多个同名表单位置，需先确定同一完整文书；不能拼接两份各有缺口的表单判通过",matched);
        Set<String> pages=new LinkedHashSet<>();sources.forEach(s->pages.add(EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType())));
        String text=String.join("\n",cn.bidcheck.compat.Java8Api.toList(pages.stream().map(evaluator.corpus.pages::get).filter(Objects::nonNull).map(Page::text).map(raw->formWindow(raw,form))));
        if(cn.bidcheck.compat.Java8Api.isBlank(text))return evaluator.unknown(rule,"FORM_BODY_REQUIRED","未能确定表单标题与正文边界，不能混入同页其他表单字段",matched);
        String formIssuer="";
        if(form.endsWith("承诺书")){
            Matcher issuer=Pattern.compile("投标人[（(](?:盖章|公章)[）)][:：]([^\\r\\n]+)").matcher(text);
            List<String> bidders=DomainBindings.subjects("投标人",evaluator.facts,evaluator.corpus);
            if(!issuer.find()||!bidders.contains(clean(issuer.group(1))))return evaluator.unknown(rule,"FORM_SUBJECT_BINDING_REQUIRED","承诺文书署名尚未对应本次投标主体，不能借其他单位承诺",matched);
            formIssuer=clean(issuer.group(1));
        }
        List<Map<String,Object>> checks=new ArrayList<>();List<String> pending=new ArrayList<>();
        boolean invoiceOnly=Boolean.TRUE.equals(rule.parameters().get("invoiceCommitmentOnly"));
        if(invoiceOnly){
            if(!rule.sourceQuote().contains("一般纳税人证明文件或可开具增值税专用发票承诺书"))return evaluator.unknown(rule,"FORM_REQUIREMENT_SOURCE_REQUIRED","开票承诺替代路径尚未得到当前条款授权",matched);
            String actual=clean(text);boolean committed=invoicePromise(actual);
            checks.add(linked("kind","CURRENT_PROJECT_INVOICE_COMMITMENT","complete",committed,"optionalReferenceWording",true));if(!committed)pending.add("本项目增值税专用发票开具承诺");
        }
        for(Map<String,Object> row:template){String line=str(row,"sourceQuote");if(instruction(line)||signer(line)||sameForm(line,form))continue;
            if(invoiceOnly)continue;
            if(FormIdentityAttachment.applies(line,form)){
                Map<String,Object> attachment=FormIdentityAttachment.verify(line,form,text);boolean complete=Boolean.TRUE.equals(attachment.get("complete"));
                checks.add(linked("kind","NAMED_PERSON_IDENTITY_ATTACHMENT","complete",complete,"source",row,"proof",attachment));
                if(!complete)pending.add("本表单具名人员的身份证正反面字段");continue;
            }
            if(line.contains("\t")){
                List<String> fields=cn.bidcheck.compat.Java8Api.toList(Arrays.stream(line.split("\\t")).map(cn.bidcheck.compat.Java8Api::strip).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s)));
                boolean complete=fields.stream().allMatch(f->clean(text).contains(clean(f)));
                checks.add(linked("kind","TABLE_COLUMNS","fields",fields,"complete",complete,"source",row));if(!complete)pending.add("表格列头");continue;
            }
            String label=clean(line);if(label.matches("(?:地址|电子邮箱|电话|日期|成立时间|项目名称)[:：].*")){
                String key=label.substring(0,label.indexOf(label.contains("：")?"：":":"));
                Matcher value=Pattern.compile(Pattern.quote(key)+"[ \\t]*[:：][ \\t]*([^\\r\\n]+)").matcher(text);
                boolean complete=value.find()&&filledLabel(key,value.group(1));
                checks.add(linked("kind","FILLED_LABEL","field",key,"complete",complete,"source",row));if(!complete)pending.add(key);continue;
            }
            if(label.length()<8)continue;
            boolean complete=filledContent(line,text);
            checks.add(linked("kind","TEMPLATE_CONTENT","complete",complete,"source",row));if(!complete)pending.add("格式正文第"+row.get("location")+"处");
        }
        if(form.contains("偏离表")){
            String compact=clean(text);int header=compact.indexOf("说明"),notes=compact.indexOf("注：",Math.max(0,header));
            String answer=header>=0&&notes>header?compact.substring(header+2,notes):"";
            boolean filled=!placeholder(answer);
            checks.add(linked("kind","DEVIATION_RESPONSE","complete",filled));if(!filled)pending.add("偏离应答正文");
        }
        Map<String,Object> proof=linked("executor",VERSION,"proofScope",invoiceOnly?"CURRENT_PROJECT_INVOICE_COMMITMENT_AND_SIGNATURE":"PRESCRIBED_FORM_CONTENT_AND_SIGNATURE","form",form,"templateSources",template,"fieldCoverage",checks,"unverifiedFields",pending,"sourceScopeProofs",sourceScopes);
        List<Evidence> evidence=new ArrayList<>();
        for(String key:pages){Page page=evaluator.corpus.pages.get(key);String window=formWindow(page.text(),form);if(cn.bidcheck.compat.Java8Api.isBlank(window))continue;
            Source origin=sources.stream().filter(s->EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()).equals(key)).findFirst().orElseThrow(java.util.NoSuchElementException::new);
            Evidence e=origin.evidence();int start=page.text().indexOf(window);evidence.add(new Evidence(e.documentId(),e.documentName(),e.page(),e.locationType(),window,"chars:"+start+"-"+(start+window.length()),e.confidence()));
        }
        if(checks.isEmpty()||!pending.isEmpty())return new Decision(rule.id(),rule.description(),"UNDETERMINED","已找到表单，但规定内容或必填值尚未全部对应；不会仅凭材料存在判通过",evidence,
                cn.bidcheck.compat.Java8Api.listOf(gap("FORM_CONTENT_COVERAGE_REQUIRED","需继续对应："+String.join("、",pending))),proof);
        String instructions=template.stream().map(r->str(r,"sourceQuote")).collect(java.util.stream.Collectors.joining("\n"));
        if(allMembersRequired(rule.sourceQuote())||allMembersRequired(instructions)){
            proof.put("requiredPartyScope","ALL_CONSORTIUM_MEMBERS");
            return new Decision(rule.id(),rule.description(),"UNDETERMINED","原文要求联合体各成员分别提供或签署，单份署名文书不能代表全部成员已完成",evidence,
                    cn.bidcheck.compat.Java8Api.listOf(gap("FORM_CONDITION_COVERAGE_REQUIRED","需逐一对应完整成员集合、各成员文书及要求签署位置")),proof);
        }
        for(Map<String,Object> row:template){String instruction=clean(str(row,"sourceQuote"));
            if(!instruction.startsWith("编制要求：")&&!instruction.startsWith("编制要求:"))continue;
            boolean namedConsortium=instruction.matches("编制要求[:：]按照本格式编制[。.]如投标人为联合体[，,]则投标人名称应为联合体全部成员[，,]如[:：].*");
            if(!namedConsortium&&!instruction.matches("编制要求[:：]按照本格式编制(?:[，,]身份证可为复印件或扫描件)?[。.]?"))
                return new Decision(rule.id(),rule.description(),"UNDETERMINED","引用模板另有编制条件尚未展开，已核字段不能代表整项满足",evidence,
                    cn.bidcheck.compat.Java8Api.listOf(gap("FORM_CONDITION_COVERAGE_REQUIRED","需对应原模板编制条件")),proof);
            if(namedConsortium&&EnterpriseSubjectBindings.consortiumDeclared(sources)){
                Rule party=new Rule("FORM-PARTIES","投标主体","MATERIAL","投标人","","","","",cn.bidcheck.compat.Java8Api.mapOf(),"","",cn.bidcheck.compat.Java8Api.listOf());
                List<Fact> parties=EnterpriseSubjectBindings.extract(cn.bidcheck.compat.Java8Api.listOf(party),evaluator.corpus.all);
                List<String> names=DomainBindings.subjects("投标人",parties,evaluator.corpus);
                List<Map<String,Object>> membership=EnterpriseSubjectBindings.completeSet(names,evaluator.corpus);
                String normalized=normalize(text);int boundary=normalized.indexOf("以下称");String nameField=boundary<0?"":normalized.substring(0,boundary);
                boolean complete=names.size()>1&&!membership.isEmpty()&&names.stream().allMatch(name->nameField.contains(normalize(name)));
                proof.put("consortiumNameCoverage",linked("source",row,"members",names,"memberSetProof",membership,"complete",complete));
                if(!complete)return new Decision(rule.id(),rule.description(),"UNDETERMINED","模板要求联合体投标人名称包含全部成员，成员集合及名称栏尚未逐一核全",evidence,
                        cn.bidcheck.compat.Java8Api.listOf(gap("FORM_CONDITION_COVERAGE_REQUIRED","需核对完整联合体成员与投标人名称栏")),proof);
            }
        }
        if(canonicalForm(form).equals("法定代表人授权委托书")){
            Decision positions=signaturePositions(rule,evaluator,form,template,sources,sourceScopes);proof.put("signatureCoverage",positions.map());
            List<Evidence> allEvidence=new ArrayList<>(evidence);allEvidence.addAll(positions.evidences());
            return new Decision(rule.id(),rule.description(),positions.status(),positions.status().equals("PASS")?"授权正文及本单位法定代表人、代理人各自签署位置已核对":positions.reason(),allEvidence,positions.gaps(),proof);
        }
        List<Decision> signatures=new ArrayList<>();
        if(instructions.matches("(?s).*(?:盖章|公章|签章|签字).*")&&pages.stream().map(evaluator.corpus.pages::get).filter(Objects::nonNull).anyMatch(p->multipleForms(p.text())))
            return new Decision(rule.id(),rule.description(),"UNDETERMINED","同页存在多份独立文书，尚未将原图签署区域对应本表单，不能借同页其他文书的章或签名",evidence,cn.bidcheck.compat.Java8Api.listOf(gap("FORM_VISUAL_REGION_REQUIRED","需明确同页表单及签署区域对应关系")),proof);
        if(instructions.matches("(?s).*(?:盖章|公章|签章).*"))signatures.add(visual(rule,evaluator,form,"SEAL",sealSubject(instructions,text,evaluator),sources));
        if(instructions.contains("签字")){
            List<Decision> alternatives=new ArrayList<>();
            if(!cn.bidcheck.compat.Java8Api.isBlank(formIssuer)){
                List<Map<String,Object>> bindings=signerBindings(formIssuer,scopeProject(sourceScopes.get(sources.get(0).id())),evaluator);proof.put("signerCompanyBindings",bindings);
                for(Map<String,Object> binding:bindings){String person=str(binding,"person");alternatives.add(visual(rule,evaluator,form,"SIGNATURE",person,sources));
                    if(str(binding,"role").equals("法定代表人")&&instructions.contains("签字或人名章"))alternatives.add(visual(rule,evaluator,form,"SEAL",person,sources));}
                if(alternatives.isEmpty())alternatives.add(evaluator.unknown(rule,"SIGNER_COMPANY_BINDING_REQUIRED","签署人尚未与该承诺书署名单位建立本项目授权关系，不能使用其他联合体成员的签署人",matched));
            }else{
                alternatives.add(visual(rule,evaluator,form,"SIGNATURE","法定代表人",sources));alternatives.add(visual(rule,evaluator,form,"SIGNATURE","委托代理人",sources));
                if(instructions.contains("签字或人名章"))alternatives.add(visual(rule,evaluator,form,"SEAL","法定代表人",sources));
            }
            signatures.add(RuleEvaluator.combine(rule.id()+"-SIGN",form+"签字位置","ANY",alternatives));
        }
        if(!signatures.isEmpty()){
            Decision signed=RuleEvaluator.combine(rule.id()+"-SIGNATURES",form+"签署要求","ALL",signatures);proof.put("signatureCoverage",signed.map());
            evidence=new ArrayList<>(evidence);evidence.addAll(signed.evidences());
            if(!signed.status().equals("PASS"))return new Decision(rule.id(),rule.description(),signed.status(),
                    signed.status().equals("FAIL")?"规定内容已核对，指定位置未观察到要求签章":signed.reason(),evidence,signed.gaps(),proof);
        }
        return new Decision(rule.id(),rule.description(),"PASS",invoiceOnly?"本项目开票承诺内容和要求签署位置已核对；未用历史资格或任意承诺书替代":"已按引用格式逐段核对正文、必填值及要求签署位置；材料存在未单独作为整项通过依据",evidence,cn.bidcheck.compat.Java8Api.listOf(),proof);
    }
    private static Decision visual(Rule rule,RuleEvaluator evaluator,String form,String type,String subject,List<Source> sources){
        Map<String,Object> p=new LinkedHashMap<>(rule.parameters());p.put("visualType",type);p.put("documentType",form);
        sources.stream().flatMap(s->cn.bidcheck.compat.Java8Extras.lines(s.opening())).map(TenderFormAudit::heading).filter(line->sameForm(line,form)).findFirst().ifPresent(actual->p.put("documentType",actual));
        p.put("visualSourceIds",cn.bidcheck.compat.Java8Api.toList(sources.stream().map(Source::id)));
        Rule visual=new Rule(rule.id(),form+(type.equals("SEAL")?"盖章位置":"签字位置"),"VISUAL",subject,type.equals("SEAL")?"seal":"signature","","","",p,rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
        List<Fact> facts=new ArrayList<>(evaluator.facts);facts.addAll(VisualSubjectBindings.extract(cn.bidcheck.compat.Java8Api.listOf(visual),evaluator.corpus.all));
        RuleEvaluator local=new RuleEvaluator(facts,evaluator.corpus,evaluator.context);Decision decision=local.visual(visual);evaluator.visualRequests.addAll(local.visualRequests);return decision;
    }
    /** Separate content and position obligations; every accepted mark stays on its own form page. */
    private static Decision signaturePositions(Rule rule,RuleEvaluator evaluator,String form,List<Map<String,Object>> template,List<Source> sources,Map<String,Map<String,Object>> scopes){
        String instructions=template.stream().map(row->str(row,"sourceQuote")).collect(java.util.stream.Collectors.joining("\n"));
        boolean seal=instructions.matches("(?s).*(?:盖章|公章|签章).*"),sign=instructions.matches("(?s).*(?:签字|签名).*"),all=allMembersRequired(instructions)||form.equals("联合体协议书");
        if(!seal&&!sign)return new Decision(rule.id(),rule.description(),"NOT_APPLICABLE","该格式原文未要求签署位置",cn.bidcheck.compat.Java8Api.toList(template.stream().map(row->new Evidence(str(row,"documentId"),"招标文件",((Number)row.get("location")).intValue(),str(row,"locationType"),str(row,"sourceQuote"),"",1))),cn.bidcheck.compat.Java8Api.listOf(),linked("executor",VERSION,"proofScope","SOURCE_HAS_NO_SIGNATURE_POSITION","templateSources",template));
        boolean joint=EnterpriseSubjectBindings.consortiumDeclared(evaluator.corpus.all);
        boolean leadOnly=joint&&!all&&(rule.sourceQuote().matches("(?s).*其余内容和资料只需.*牵头单位(?:签字盖章|签章).*")
                ||clean(instructions).contains("如投标人为联合体，可由联合体牵头人签署。"));
        String partyRole=all?"联合体成员":leadOnly?"联合体牵头人":"投标人";
        Rule parties=new Rule(rule.id()+"-parties","投标主体","RELATION",partyRole,"名称","","","",cn.bidcheck.compat.Java8Api.mapOf(),rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
        List<Fact> facts=new ArrayList<>(evaluator.facts);facts.addAll(EnterpriseSubjectBindings.extract(cn.bidcheck.compat.Java8Api.listOf(parties),evaluator.corpus.all));
        RuleEvaluator local=new RuleEvaluator(facts,evaluator.corpus,evaluator.context);List<String> companies=DomainBindings.subjects(partyRole,facts,evaluator.corpus);
        if(companies.isEmpty()||all&&joint&&EnterpriseSubjectBindings.completeSet(companies,evaluator.corpus).isEmpty())return evaluator.unknown(rule,"SIGNATURE_PARTY_SET_REQUIRED","签署主体集合尚未闭合；联合体各方与牵头单位例外分别判断",cn.bidcheck.compat.Java8Api.listOf());
        List<Decision> checked=new ArrayList<>();Map<String,List<Source>> pages=new LinkedHashMap<>();for(Source s:sources)pages.computeIfAbsent(EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()),key->new ArrayList<>()).add(s);
        for(String company:companies){boolean found=false;
            for(java.util.Map.Entry<java.lang.String, java.util.List<cn.bidcheck.verification.engine.VerificationTypes.Source>> entry:pages.entrySet()){
                Page page=evaluator.corpus.pages.get(entry.getKey());String body=formWindow(page.text(),form);if(!signedParty(body,company,form))continue;found=true;
                if(multipleForms(page.text())){checked.add(evaluator.unknown(rule,"FORM_VISUAL_REGION_REQUIRED","同页多份表单尚未建立签署区与文书的对应关系",cn.bidcheck.compat.Java8Api.listOf()));continue;}
                Rule scoped=new Rule(rule.id()+":"+identity(company+entry.getKey()).substring(0,12),form+"签署位置",rule.predicate(),rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),rule.parameters(),rule.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
                if(seal)checked.add(visual(scoped,local,form,"SEAL",company,entry.getValue()));
                if(sign){
                    List<Map<String,Object>> bindings=signerBindings(company,scopeProject(scopes.get(entry.getValue().get(0).id())),local);
                    boolean both=canonicalForm(form).equals("法定代表人授权委托书")&&normalize(instructions).contains("委托代理人(签字)");
                    if(Boolean.TRUE.equals(rule.parameters().get("legalRepresentativeDirectOnly")))bindings=cn.bidcheck.compat.Java8Api.toList(bindings.stream().filter(b->str(b,"role").equals("法定代表人")));
                    List<Decision> alternatives=new ArrayList<>();for(String role:both?cn.bidcheck.compat.Java8Api.listOf("法定代表人", "委托代理人"):cn.bidcheck.compat.Java8Api.listOf("允许签署人")){
                        List<Decision> people=new ArrayList<>();for(java.util.Map<java.lang.String, java.lang.Object> binding:bindings)if(!both||str(binding,"role").equals(role)){String name=str(binding,"person");people.add(visual(scoped,local,form,"SIGNATURE",name,entry.getValue()));if(str(binding,"role").equals("法定代表人")&&instructions.contains("签字或人名章"))people.add(visual(scoped,local,form,"SEAL",name,entry.getValue()));}
                        alternatives.add(people.isEmpty()?evaluator.unknown(scoped,"SIGNER_COMPANY_BINDING_REQUIRED","本表单要求的签署人尚未与该单位建立明确关系",cn.bidcheck.compat.Java8Api.listOf()):RuleEvaluator.combine(scoped.id()+":"+role,role+"签署位置","ANY",people));
                    }checked.add(RuleEvaluator.combine(scoped.id()+"-sign",form+"签字位置",both?"ALL":"ANY",alternatives));
                }
            }if(!found)checked.add(evaluator.unknown(rule,"FORM_SUBJECT_BINDING_REQUIRED","未定位签署单位对应的本次表单："+company,cn.bidcheck.compat.Java8Api.listOf()));
        }
        evaluator.visualRequests.addAll(local.visualRequests);Decision result=RuleEvaluator.combine(rule.id(),rule.description(),"ALL",checked);Map<String,Object> proof=new LinkedHashMap<>(result.proof());proof.putAll(linked("executor",VERSION,"proofScope","SOURCE_FORM_SIGNATURE_POSITIONS_ONLY","form",form,"requiredCompanies",companies,"allMembersRequired",all,"sourceScopeProofs",scopes,"templateSources",template,"doesNotProveFormContent",true,"signatureAuthenticityCheckRequired",false));
        return new Decision(result.id(),result.description(),result.status(),result.reason(),result.evidences(),result.gaps(),proof);
    }
    /** A company named in narrative or another party's authorization is not the form's issuer. */
    static boolean signedParty(String body,String company,String form){
        String text=clean(body),name=Pattern.quote(clean(company));
        if(canonicalForm(form).equals("法定代表人授权委托书"))return Pattern.compile("本人[\\p{IsHan}·]{2,12}系"+name+"的法定代表人(?:[（(]负责人[）)])?[，,]现委托").matcher(text).find();
        if(form.equals("联合体协议书"))return Pattern.compile("(?:牵头人|联合体成员|成员单位)(?:名称)?(?:[（(](?:公章|盖章)[）)])?[:：]"+name+"(?=[\\s。；;]|$)").matcher(body).find();
        return Pattern.compile("(?:投标人|投标单位)(?:名称)?(?:[（(](?:盖章|公章)[）)])?[:：]"+name+"(?=[\\s。；;]|$)").matcher(body).find();
    }
    private static String scopeProject(Map<String,Object> proof){return proof!=null&&proof.get("sourceScope") instanceof Map<?,?>?Objects.toString(((Map<?,?>) (proof.get("sourceScope"))).get("projectName"),""):"";}
    static boolean visualSourceCurrent(Rule rule,Source source,EvidenceCorpus corpus,Map<String,DocumentScopeProof.Resolver> resolvers){
        if(!Boolean.TRUE.equals(rule.parameters().get("templateFormAudit"))||!ProofVerifier.strings(rule.parameters().get("visualSourceIds")).contains(source.id()))return false;
        DocumentScopeProof.Resolver resolver=resolvers.computeIfAbsent(source.evidence().documentId(),id->corpus.documents.stream().filter(d->d.id().equals(id)).findFirst().map(DocumentScopeProof::forDocument).orElse(null));
        return resolver!=null&&Boolean.TRUE.equals(resolver.currentEvidence(source.evidence()).get("verifiedCurrent"));
    }
    private static String sealSubject(String source,String body,RuleEvaluator evaluator){
        if(source.contains("牵头人签署")&&!DomainBindings.subjects("联合体牵头人",evaluator.facts,evaluator.corpus).isEmpty())return "联合体牵头人";
        Matcher named=Pattern.compile("投标人[（(](?:盖章|公章)[）)][:：]([^\\r\\n]+)").matcher(body);
        if(named.find()){String company=clean(named.group(1));if(DomainBindings.subjects("投标人",evaluator.facts,evaluator.corpus).contains(company))return company;}
        return "投标人";
    }
    private static boolean signer(String text){return text.matches("(?s)^\\s*(?:投标人|法定代表人|委托代理人).*(?:签字|公章|盖章|人名章).*[:：]\\s*$");}
    /** Only explicit affirmative undertaking syntax establishes the alternative, not keyword proximity. */
    private static boolean invoicePromise(String text){
        String project="(?:本项目|该项目|本次项目)",invoice="开具(?:符合要求的)?增值税专用发票";
        String undertaking="(?:我方|我公司|本公司)(?:现|郑重)?承诺[，,:：]?";
        String conditional=undertaking+"具备增值税专用发票开具能力[，,](?:如果|如|若)(?:在)?"+project+"(?:中)?中标[，,](?:将|会)?为"+project+invoice+"(?=[，,。；;]|$)";
        String direct=undertaking+"(?:中标后)?(?:将|会)?为"+project+invoice+"(?=[，,。；;]|$)";
        boolean affirmative=Pattern.compile(conditional+"|"+direct).matcher(text).find();
        // A second contradictory statement cannot be erased by a preceding affirmative sentence.
        boolean contradiction=Pattern.compile("(?:不|未|无|拒绝)[^。；;]{0,60}(?:开具|开票|增值税专用发票)|(?:本项目|该项目|本次项目)[^。；;]{0,60}(?:开具|开票|增值税专用发票)[^。；;]{0,30}(?:不予|不作|不做|不承诺|未承诺|拒绝)").matcher(text).find();
        return affirmative&&!contradiction;
    }
    private static List<Map<String,Object>> signerBindings(String company,String project,RuleEvaluator evaluator){
        List<Map<String,Object>> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Source source:evaluator.corpus.all){if(source.index())continue;String text=clean(source.evidence().quote());if(text.matches("(?s).*(?:填写示例|示范文本|空白模板).*"))continue;
            boolean namedProject=!cn.bidcheck.compat.Java8Api.isBlank(project)&&text.contains(clean(project));
            Matcher authority=Pattern.compile("本人([\\p{IsHan}·]{2,12})系"+Pattern.quote(company)+"的法定代表人(?:[（(]负责人[）)])?[,，]现委托([\\p{IsHan}·]{2,12})为我方代理人").matcher(text);
            if(namedProject&&source.opening().contains("授权委托书")&&authority.find()
                    &&text.substring(authority.end(),Math.min(text.length(),authority.end()+240)).contains(clean(project))){
                for(int i=1;i<=2;i++){String role=i==1?"法定代表人":"委托代理人",person=authority.group(i);if(seen.add(role+person))result.add(linked("company",company,"role",role,"person",person,"sourceId",source.id(),"basis","CURRENT_NAMED_PROJECT_AUTHORIZATION"));}
            }
            if(source.historical()||source.scopeContext().blocked()||!namedProject||!EnterpriseSubjectBindings.formalDocument(source))continue;
            Matcher owner=Pattern.compile("(?:投标人|投标单位)(?:名称)?[:：]([^。；;\\r\\n]+)").matcher(source.evidence().quote());Set<String> owners=new HashSet<>();while(owner.find())owners.add(clean(owner.group(1)));
            if(!owners.equals(cn.bidcheck.compat.Java8Api.setOf(company)))continue;
            Matcher person=Pattern.compile("(?:本项目|本次投标)?法定代表人[:：]([\\p{IsHan}·]{2,12})(?=$|[\\s。；;])").matcher(source.evidence().quote());
            while(person.find())if(seen.add("法定代表人"+person.group(1)))result.add(linked("company",company,"role","法定代表人","person",person.group(1),"sourceId",source.id(),"basis","SAME_CURRENT_FORM_COMPANY_AND_SIGNER"));
        }return result;
    }
    private static boolean multipleForms(String text){
        Set<String> names=new HashSet<>();for(String line:text.split("\\R")){String name=clean(line).replaceFirst("^[0-9一二三四五六七八九十.、（）()]+","");
            if(name.length()<=45&&name.matches("[^：:，,。；;（）()《》]*(?:承诺书|委托书|投标函|偏离表)"))names.add(name);}
        return names.size()>1;
    }
    private static boolean instruction(String text){return text.matches("(?s)^\\s*(?:编制|签署)(?:及签署)?要求[:：].*")||cn.bidcheck.compat.Java8Api.strip(text).equals("注：");}
    private static String normalize(String text){return clean(text).replace('（','(').replace('）',')').replace('，',',').replace('：',':');}
    static String formWindow(String page,String name){
        Matcher lines=Pattern.compile("[^\\r\\n]+").matcher(page);int start=-1,end=page.length();
        while(lines.find()){
            String line=heading(lines.group());
            if(sameForm(line,name)){if(start<0)start=lines.start();continue;}
            if(start>=0&&isNextHeading(line)){end=lines.start();break;}
        }
        return start<0?"":page.substring(start,end);
    }
    static boolean bodyInstance(String page,String name){
        String opening=cn.bidcheck.compat.Java8Extras.lines(page).map(cn.bidcheck.compat.Java8Api::strip).filter(s->!cn.bidcheck.compat.Java8Api.isBlank(s)).limit(3).collect(java.util.stream.Collectors.joining("\n"));
        if(opening.matches("(?s).*(?:评审索引表|评审因素索引|^目录(?:\\s|$)).*"))return false;
        String window=formWindow(page,name);if(cn.bidcheck.compat.Java8Api.isBlank(window))return false;
        String body=cn.bidcheck.compat.Java8Extras.lines(window).filter(s->!sameForm(heading(s),name)).collect(java.util.stream.Collectors.joining("\n"));
        return clean(body).length()>=30&&body.matches("(?s).*(?:投标人|我方|我公司|条款号|响应情况|致[：:]|地址[：:]|项目名称[：:]).*");
    }
    private static String contentPattern(String template){
        Matcher slots=SLOT.matcher(template);List<int[]> positions=new ArrayList<>();while(slots.find())positions.add(new int[]{slots.start(),slots.end()});
        StringBuilder pattern=new StringBuilder();int last=0;boolean previousSlot=false;
        for(int index=0;index<positions.size();index++){
            int[] slot=positions.get(index);String token=template.substring(slot[0],slot[1]);String fixed=normalize(template.substring(last,slot[0]));
            boolean named=token.startsWith("（")||token.startsWith("(");
            boolean adjacentNamed=named&&(index>0&&positions.get(index-1)[1]==slot[0]&&(template.charAt(positions.get(index-1)[0])=='（'||template.charAt(positions.get(index-1)[0])=='(')
                    ||index+1<positions.size()&&positions.get(index+1)[0]==slot[1]&&(template.charAt(positions.get(index+1)[0])=='（'||template.charAt(positions.get(index+1)[0])=='('));
            // Adjacent named/spacing slots are one fill region. Two free captures
            // could split a literal placeholder and incorrectly make it look filled.
            if(named||!previousSlot||!cn.bidcheck.compat.Java8Api.isBlank(fixed))pattern.append(Pattern.quote(fixed)).append(adjacentNamed?"(\\([^()]{1,180}\\))":"(.{1,180}?)");
            last=slot[1];previousSlot=true;
        }
        pattern.append(Pattern.quote(normalize(template.substring(last))));return pattern.toString();
    }
    static boolean filledContent(String template,String actual){
        Matcher matched=Pattern.compile(contentPattern(template),Pattern.DOTALL).matcher(normalize(actual));
        if(!matched.find())return false;
        for(int i=1;i<=matched.groupCount();i++){
            String value=matched.group(i);
            if(placeholder(value))return false;
        }return true;
    }
    static boolean filledLabel(String key,String raw){
        if(placeholder(raw))return false;String value=clean(raw);
        if(key.equals("日期")||key.equals("成立时间"))return value.matches("(?:19|20)[0-9]{2}[年./-][0-9]{1,2}[月./-][0-9]{1,2}日?")&&ProofVerifier.date(value)!=null;
        if(key.equals("电话"))return value.matches("[+0-9()（）-]{5,30}");
        if(key.equals("电子邮箱"))return value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
        return true;
    }
    private static boolean placeholder(String raw){
        String value=clean(raw);
        return cn.bidcheck.compat.Java8Api.isBlank(value)||value.matches("[_＿.。…-]+")||value.matches("[（(]?(?:招标人|投标人名称|项目名称|招标编号|其他补充说明)[）)]?")
                ||value.matches("(?i)(?:XX)?\\[.*(?:姓名|名称).*\\]")||value.equalsIgnoreCase("XX")
                ||value.matches(".*[（(](?:招标人|投标人名称|项目名称|招标编号|其他补充说明)[）)].*")
                ||value.matches("(?i).*(?:XX公司|某某公司|待填写|待填|填写示例|[（(]填写[^）)]*[）)]).*" );
    }
}
