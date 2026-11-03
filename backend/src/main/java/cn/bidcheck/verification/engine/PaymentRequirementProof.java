package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import cn.bidcheck.verification.engine.VerificationTypes.Fact;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Source-bound deposit checks. A bid-letter promise is not a bank payment receipt. */
final class PaymentRequirementProof {
    static final String VERSION="payment-requirement-proof-1";
    private PaymentRequirementProof(){}
    static Rule compile(Checkpoint cp,String source){
        if(!clean(source).matches("投标保证金按照招标文件规定缴纳并提交凭证[。.]?"))return null;
        return new Rule("PAYMENT-"+identity(source).substring(0,16),"投标保证金金额、缴纳主体、收款人与时间","MATERIAL","","paymentObligation","","投标保证金凭证","",
                linked("paymentProofVersion",VERSION,"tenderDocumentId",cp.source()==null?"":cp.source().documentId()),source,"",cn.bidcheck.compat.Java8Api.listOf());
    }
    static boolean applies(Rule r){return VERSION.equals(r.parameters().get("paymentProofVersion"));}
    static Decision evaluate(Rule r,RuleEvaluator e){
        List<Document> tender=new ArrayList<>();if(e.context.get("tenderDocuments") instanceof Collection<?>)for(Object o:((Collection<?>) (e.context.get("tenderDocuments"))))if(o instanceof Document&&((Document) (o)).role().equals("TENDER")&&((Document) (o)).id().equals(str(r.parameters(),"tenderDocumentId"))&&e.corpus.documents.stream().allMatch(b->b.projectId().equals(((Document) (o)).projectId())))tender.add(((Document) (o)));
        if(tender.size()!=1||!tender.get(0).pages().stream().anyMatch(p->p.text().contains(r.sourceQuote())))return e.unknown(r,"PAYMENT_REQUIREMENT_SOURCE_REQUIRED","尚未定位同一冻结招标文件的保证金缴纳要求",cn.bidcheck.compat.Java8Api.listOf());
        Document doc=tender.get(0);List<Page> amountPages=cn.bidcheck.compat.Java8Api.toList(doc.pages().stream().filter(p->p.text().matches("(?s)^\\s*[0-9]+(?:\\.[0-9]+)*\\s*\\t投标保证金金额\\t.*")));
        List<Page> methodPages=cn.bidcheck.compat.Java8Api.toList(doc.pages().stream().filter(p->p.text().matches("(?s)^\\s*[0-9]+(?:\\.[0-9]+)*\\s*\\t投标保证金形式\\t.*")));
        if(amountPages.size()!=1||methodPages.size()!=1)return e.unknown(r,"PAYMENT_REQUIREMENT_SOURCE_REQUIRED","前附表保证金金额或缴纳形式不是唯一完整来源，不能拿通用比例或履约保证金替代",cn.bidcheck.compat.Java8Api.listOf());
        String methods=methodPages.get(0).text();Matcher expectedAmount=Pattern.compile("投标保证金金额[：:]\\s*(?:人民币)?\\s*([0-9,.]+)\\s*(万元|元)").matcher(amountPages.get(0).text());
        if(!expectedAmount.find())return e.unknown(r,"PAYMENT_AMOUNT_REQUIREMENT_REQUIRED","原文保证金金额未能规范为数值和原单位",cn.bidcheck.compat.Java8Api.listOf());
        BigDecimal amountValue=ProofVerifier.decimal(expectedAmount.group(1));
        if(amountValue==null)return e.unknown(r,"PAYMENT_AMOUNT_REQUIREMENT_REQUIRED","保证金金额格式不唯一或不合法",cn.bidcheck.compat.Java8Api.listOf());
        BigDecimal amount=RuleEvaluator.normalize(amountValue,expectedAmount.group(2));
        if(expectedAmount.find())return e.unknown(r,"PAYMENT_AMOUNT_REQUIREMENT_REQUIRED","保证金金额来源包含多个数值要求，不能取第一个",cn.bidcheck.compat.Java8Api.listOf());
        String receiver=label(methods,"单位名称"),account=label(methods,"帐号|账号");
        Map<String,Object> proof=linked("executor",VERSION,"expectedAmount",amount,"currency","CNY","requirements",cn.bidcheck.compat.Java8Api.listOf(source(doc,amountPages.get(0)), source(doc,methodPages.get(0))),"declarationIsPaymentProof",false);
        List<Source> receipts=new ArrayList<>();Map<String,Object> scopes=new LinkedHashMap<>();Map<String,DocumentScopeProof.Resolver> resolvers=new HashMap<>();
        for(Source s:e.corpus.all){
            String text=s.evidence().quote();if(s.index()||!text.contains("投标保证金")||!text.matches("(?s).*(?:付款回单|转账回单|银行回单|支付凭证).*"))continue;
            DocumentScopeProof.Resolver resolver=resolvers.computeIfAbsent(s.evidence().documentId(),id->e.corpus.documents.stream().filter(d->d.id().equals(id)).findFirst().map(DocumentScopeProof::forDocument).orElse(null));
            Map<String,Object> scope=resolver==null?cn.bidcheck.compat.Java8Api.mapOf():resolver.currentEvidence(s.evidence());
            if(!Boolean.TRUE.equals(scope.get("verifiedCurrent")))continue;
            receipts.add(s);scopes.put(s.id(),scope);
        }
        // Source windows from the same page are one receipt; different pages are not silently merged.
        Map<String,Source> pages=new LinkedHashMap<>();for(Source s:receipts)pages.merge(EvidenceCorpus.pageKey(s.evidence().documentId(),s.evidence().page(),s.evidence().locationType()),s,(a,b)->a.evidence().quote().length()>=b.evidence().quote().length()?a:b);
        proof.put("receiptScopeProofs",scopes);
        if(pages.size()!=1)return e.unknown(r,pages.isEmpty()?"PAYMENT_RECEIPT_REQUIRED":"PAYMENT_RECEIPT_IDENTITY_REQUIRED",pages.isEmpty()?"尚未定位本项目实际支付凭证；投标函承诺和索引不能证明已缴纳。保函须另核受益人、有效期及查验/递交记录":"存在多份凭证，需先核对同一付款或替换关系，不能拼接各自有缺口的付款",cn.bidcheck.compat.Java8Api.listOf(),proof);
        Source receipt=pages.values().iterator().next();String text=receipt.evidence().quote();List<Map<String,Object>> checks=new ArrayList<>();List<String> missing=new ArrayList<>(),deviations=new ArrayList<>();
        Matcher paid=Pattern.compile("(?m)(?:^|\\n)[ *]*金额[：:]\\s*(?:CNY|人民币|￥|¥)\\s*([0-9,]+(?:\\.[0-9]+)?)").matcher(text);
        BigDecimal actual=paid.find()?ProofVerifier.decimal(paid.group(1)):null;if(paid.find())actual=null;
        compare(checks,missing,deviations,"金额",actual==null?null:actual.compareTo(amount)==0,actual,amount);
        String receivedName=label(text,"收款人名称"),receivedAccount=label(text,"收款人账号");
        compare(checks,missing,deviations,"收款单位",cn.bidcheck.compat.Java8Api.isBlank(receiver)||cn.bidcheck.compat.Java8Api.isBlank(receivedName)?null:clean(receiver).equals(clean(receivedName)),receivedName,receiver);
        compare(checks,missing,deviations,"收款账号",cn.bidcheck.compat.Java8Api.isBlank(account)||cn.bidcheck.compat.Java8Api.isBlank(receivedAccount)?null:clean(account).equals(clean(receivedAccount)),receivedAccount,account);
        List<Fact> roles=new ArrayList<>(e.facts);Rule selector=new Rule(r.id()+"-PAYER","缴款单位","RELATION","联合体牵头人","名称","","","",cn.bidcheck.compat.Java8Api.mapOf(),r.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf());
        roles.addAll(EnterpriseSubjectBindings.extract(cn.bidcheck.compat.Java8Api.listOf(selector, new Rule(r.id()+"-BIDDER","投标人","RELATION","投标人","名称","","","",cn.bidcheck.compat.Java8Api.mapOf(),r.sourceQuote(),"",cn.bidcheck.compat.Java8Api.listOf())),e.corpus.all));
        boolean joint=EnterpriseSubjectBindings.consortiumDeclared(e.corpus.all),leaderRequired=methods.matches("(?s).*(?:应由|须由|必须由)(?:投标人)?(?:联合体)?牵头单位缴纳.*");
        List<String> payers=DomainBindings.subjects(joint&&leaderRequired?"联合体牵头人":"投标人",roles,e.corpus);
        String payer=label(text,"付款人名称");compare(checks,missing,deviations,"缴款主体",payers.size()!=1||cn.bidcheck.compat.Java8Api.isBlank(payer)||joint&&!leaderRequired?null:payers.get(0).equals(clean(payer)),payer,payers);
        if(!methods.matches("(?s).*投标保证金形式[：:][^。/]*(?:银行电汇|转账).*"))missing.add("原文允许的支付方式");
        if(methods.contains("基本账户")){
            String paidAccount=label(text,"付款人账号");boolean basic=e.corpus.all.stream().filter(s->!s.index()).anyMatch(s->s.evidence().quote().contains("基本存款账户")
                &&!cn.bidcheck.compat.Java8Api.isBlank(payer)&&!cn.bidcheck.compat.Java8Api.isBlank(paidAccount)&&s.evidence().quote().contains(payer)&&s.evidence().quote().contains(paidAccount)
                &&e.corpus.documents.stream().filter(d->d.id().equals(s.evidence().documentId())).anyMatch(d->Boolean.TRUE.equals(DocumentScopeProof.currentEvidence(d,s.evidence()).get("verifiedCurrent"))));
            compare(checks,missing,deviations,"基本账户对应",basic?true:null,paidAccount,payer);
        }
        String purpose=label(text,"用途|附言");String project="";Object receiptScope=scopes.get(receipt.id());if(receiptScope instanceof Map<?,?>&&((Map<?,?>) (receiptScope)).get("sourceScope") instanceof Map<?,?>)project=Objects.toString(((Map<?,?>) (((Map<?,?>) (receiptScope)).get("sourceScope"))).get("projectName"),"");
        String expectedProject=clean(project);boolean projectSource=!cn.bidcheck.compat.Java8Api.isBlank(expectedProject)&&doc.pages().stream().limit(15).map(Page::text).map(VerificationTypes::clean).anyMatch(p->p.contains(expectedProject));
        // Exact current project spelling is executable; an unexplained abbreviation is a missing
        // alias relation, not a proven foreign project and never an automatic pass.
        compare(checks,missing,deviations,"凭证项目对应",projectSource&&!cn.bidcheck.compat.Java8Api.isBlank(purpose)&&clean(purpose).contains(clean(project))?true:null,purpose,project);
        if(methods.contains("标段")){
            Set<String> lots=new LinkedHashSet<>();for(Page p:cn.bidcheck.compat.Java8Api.toList(doc.pages().stream().limit(30))){String lot=label(p.text(),"标段号|标段编号");if(!cn.bidcheck.compat.Java8Api.isBlank(lot))lots.add(clean(lot));}
            boolean noLots=doc.pages().stream().anyMatch(p->clean(p.text()).matches("(?s).*(?:本项目|本次招标)(?:不划分|未划分|不分)标段.*"));
            if(lots.size()==1)compare(checks,missing,deviations,"凭证标段对应",clean(purpose).contains(lots.iterator().next())?true:null,purpose,lots);
            else if(!noLots)missing.add("是否划分标段及凭证对应关系");
        }
        String receiptStatus=label(text,"交易状态|处理状态");if(!cn.bidcheck.compat.Java8Api.isBlank(receiptStatus)&&!cn.bidcheck.compat.Java8Api.setOf("交易成功", "成功", "已入账", "支付成功", "处理成功").contains(clean(receiptStatus)))missing.add("银行凭证的交易完成状态");
        List<String> unmapped=unmappedPolicies(methods);missing.addAll(cn.bidcheck.compat.Java8Api.toList(unmapped.stream().map(s->"尚未映射的缴纳政策："+s)));proof.put("unmappedPolicyClauses",unmapped);
        LocalDate deadline=e.referenceDate(r),paymentDate=date(label(text,"付款日期|交易日期|日期")),arrival=date(label(text,"到账日期|入账日期"));
        if(deadline==null)missing.add("已核源的投标截止时点");
        else if(arrival!=null)compare(checks,missing,deviations,"到账日期",arrival.equals(deadline)?null:arrival.isBefore(deadline),arrival,deadline);
        else if(paymentDate!=null&&paymentDate.isAfter(deadline))compare(checks,missing,deviations,"付款晚于截止日期",false,paymentDate,deadline);
        else missing.add("代理机构实际到账时间；付款时间早于截止不能代替到账证明");
        if(clean(methods).contains("投标保证金有效期与投标有效期一致")){
            boolean duration=e.corpus.all.stream().filter(s->!s.index()&&!s.historical()&&!s.scopeContext().blocked()).anyMatch(s->clean(s.opening()).contains("投标函")
                &&s.evidence().quote().contains(payer)&&s.evidence().quote().matches("(?s).*投标保证金.{0,160}有效期同投标有效期.*"));
            compare(checks,missing,deviations,"保证金有效期承诺",duration?true:null,duration,"与投标有效期一致");
        }
        proof.put("fieldChecks",checks);proof.put("missingProof",missing);proof.put("deviations",deviations);proof.put("paymentDateDoesNotProveArrival",true);
        List<Gap> gaps=cn.bidcheck.compat.Java8Api.toList(missing.stream().map(m->gap("PAYMENT_PROOF_REQUIRED",m)));
        if(!deviations.isEmpty())return new Decision(r.id(),r.description(),"FAIL","缴款凭证存在有原文的明确偏差："+String.join("、",deviations)+"；其余条件单独保留",cn.bidcheck.compat.Java8Api.listOf(receipt.evidence()),gaps,proof);
        return new Decision(r.id(),r.description(),missing.isEmpty()?"PASS":"UNDETERMINED",missing.isEmpty()?"保证金缴纳要求逐项得到原文证明":"已核实际凭证字段，尚有缴纳条件未取得完整证明",cn.bidcheck.compat.Java8Api.listOf(receipt.evidence()),gaps,proof);
    }
    static String label(String text,String label){Matcher m=Pattern.compile("(?:^|[\\n/])[ *]*"+"(?:"+label+")"+"[ \\t]*[:：][ \\t]*([^\\r\\n/]+)").matcher(text.replace("帐 号","帐号").replace("帐号","账号"));Set<String> values=new LinkedHashSet<>();while(m.find())values.add(cn.bidcheck.compat.Java8Api.strip(m.group(1)).replaceAll("[ *]+$",""));return values.size()==1?values.iterator().next():"";}
    static List<String> unmappedPolicies(String methods){List<String> out=new ArrayList<>();for(String raw:methods.split(" / |\\n")){String clause=clean(raw).replaceFirst("^[★*]+","");if(cn.bidcheck.compat.Java8Api.isBlank(clause))continue;
        boolean known=clause.matches("[0-9.]+投标保证金形式投标保证金形式[：:](?:银行电汇或转账|银行电汇|转账)(?:[（(]★?由投标人基本账户转出，退款遵从[“\"]谁付款退款给谁[”\"]的原则[）)])?(?:、支票、银行汇票、保函或其他合法担保形式)?。(?:投标保证金有效期与投标有效期一致。)?")
                ||clause.matches("凭证标明项目名称[（(]或简称[）)]及标段号[（(]如有[）)][，,。]?")||clause.matches("扫描并入投标文件提交[。]?")
                ||clause.matches("投标保证金提交时间[：:]投标截止时间前[（(]以代理机构到账日期为准[）)][。]?")||clause.matches("提交投标保证金提交至[：:]")
                ||clause.matches("(?:单位名称|开户行|账号|帐号)[：:][^。；;]+")
                ||clause.matches("保函中标明项目名称及标段号[（(]如有[）)]，有效期同投标有效期[；;。]?")
                ||clause.matches("纸质保函原件在投标截止时间前递交至代理机构[（(]地址[：:][^）)]+[）)][。]?")
                ||clause.matches("电子保函必须提供线上查验方法[（(]如提供验证保函真伪说明、查询网页链接地址、查询结果截图等[）)][。]?")
                ||clause.matches("保函经扫描并入投标文件提交[；;。]?")
                ||clause.equals("接受邮寄方式递交，但因邮寄时间延误、损坏、遗失等造成的否决等后果，由投标人自行承担。")
                ||clause.matches("保函的受益人为[：:][^；;]+[；;]保函必须注明保函的适用范围[（(]参与投标的项目名称、标段等信息[）)]，且为无条件的见索即付保函[。]?")
                ||clause.matches("如项目接受联合体，应由投标人牵头单位缴纳或开具保函[。]?");
        if(!known)out.add(cn.bidcheck.compat.Java8Api.strip(raw));
    }return cn.bidcheck.compat.Java8Api.listCopyOf(out);}
    private static LocalDate date(String value){Matcher m=Pattern.compile("(20[0-9]{2})[年./-]([0-9]{1,2})[月./-]([0-9]{1,2})日?").matcher(clean(value));try{return m.find()?LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))):null;}catch(RuntimeException invalid){return null;}}
    private static void compare(List<Map<String,Object>> checks,List<String> missing,List<String> deviations,String field,Boolean ok,Object actual,Object expected){checks.add(linked("field",field,"status",ok==null?"UNKNOWN":ok?"PASS":"FAIL","actual",actual==null?"":wireValue(actual),"expected",wireValue(expected)));if(ok==null)missing.add(field);else if(!ok)deviations.add(field);}
    private static Object wireValue(Object value){return value instanceof java.time.temporal.TemporalAccessor?value.toString():value;}
    private static Map<String,Object> source(Document d,Page p){return linked("documentId",d.id(),"documentHash",d.sha256(),"documentVersion",d.version(),"location",p.number(),"locationType",p.locationType(),"sourceQuote",p.text());}
}
