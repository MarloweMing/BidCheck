package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Compiles a closed expression tree, never executable model-generated code. */
final class RequirementCompiler {
    private static final Set<String> LOGICS=Set.of("ALL","ANY","CONDITIONAL","FOR_EACH");
    Plan compile(Checkpoint cp,RunSession session)throws IOException {
        // Global review requires complete object/image coverage; model-selected excerpts cannot establish it.
        if("FULL_TEXT_REVIEW".equals(cp.type())) {
            return new Plan("ALL",List.of(),"NATIVE",List.of("全文检查须保留完整覆盖与人工复核，不能将模型抽样结果视为全文通过"));
        }
        String requirement=Objects.toString(cp.expected(),"");String original=str(session.context,"originalRequirement");
        RequirementScope.Selection scope=RequirementScope.resolve(cp,original);
        if(!scope.resolved())return new Plan("ALL",List.of(),"SCOPE_UNRESOLVED",List.of(scope.issue()),List.of(),scope.map());
        boolean focused=Boolean.TRUE.equals(session.context.get("focusedCheck"));
        Set<String> focusedDomains=focused?new LinkedHashSet<>(ProofVerifier.strings(session.context.get("atomicDomains"))):Set.of();
        String predicate=switch(Objects.toString(cp.type(),"")){case "VALUE_COMPARE"->"NUMBER";case "ENTITY_FIELD_CONSISTENCY"->"SAME_FIELD";case "DATE_VALIDITY"->"VALIDITY";default->"";};
        if(!predicate.isEmpty()&&!focused){Rule rule=new Rule("O1",cp.title(),predicate,"",Objects.toString(cp.field(),""),Objects.toString(cp.operator(),""),requirement,Objects.toString(cp.unit(),""),Map.of(),scope.obligation(),"",List.of());return new Plan("ALL",List.of(rule),"TYPED_RULE",List.of(),List.of(),scope.map());}
        RequirementSourceContext.Context sourceContext=RequirementSourceContext.resolve(cp,original,session.context);
        RequirementReferences.Expansion expansion=RequirementReferences.expand(cp,scope.obligation(),sourceContext,session.context);
        sourceContext=expansion.context();
        // Local recovery revalidates the original tree and IDs; only a fresh compile may
        // introduce the closed source-derived price rule.
        Rule sourcePrice=RecoveryReplay.localOnly(session.context)?null:TenderRequirementConstants.sourcePriceRule(cp,scope.obligation());
        if(sourcePrice!=null&&RequirementReferences.issues(sourceContext).isEmpty()&&sourceContext.sources().stream().allMatch(s->"OBLIGATION".equals(s.get("kind")))){
            Rule verified=focused?focusedRule(sourcePrice,focusedDomains,new RequirementContextBindings(scope.obligation(),"ORIGINAL_REQUIREMENT")):sourcePrice;
            if(!"UNSUPPORTED".equals(verified.predicate())){
                Map<String,Object> metadata=new LinkedHashMap<>(scope.map());metadata.put("sourceContext",sourceContext.map());metadata.put("tenderConstants",TenderRequirementConstants.extract(scope.obligation()));
                Plan sourcePlan=new Plan("ALL",List.of(withSourceContext(verified,sourceContext.map())),"SOURCE_COMPILED_PRICE",List.of(),List.of(),metadata);
                session.compilationAudit=CompilationRecovery.audit(sourcePlan,0,List.of(linked("state","SOURCE_COMPILED_PRICE","networkCalls",0,"sourceHash",identity(scope.obligation()))));
                return sourcePlan;
            }
        }
        if(RecoveryReplay.localOnly(session.context)){
            JsonNode previous=RecoveryReplay.plan(session.context,session.json);
            if(previous==null)return new Plan("ALL",List.of(),"RECOVERY_PLAN_MISSING",List.of("补查缺少可重新验证的规则计划，未再次调用模型"));
            Plan replay=compileResponse(cp,previous,original,focused,focusedDomains,sourceContext);
            session.compilationAudit=CompilationRecovery.audit(replay,0,List.of(linked("state","LOCAL_REVALIDATION","networkCalls",0)));
            return replay;
        }
        String groundedRequirement=RequirementReferences.requirementText(scope.obligation(),sourceContext);
        Plan referenceTimePlan=referenceTimePlan(cp,groundedRequirement,sourceContext,scope);
        if(referenceTimePlan!=null){
            session.compilationAudit=CompilationRecovery.audit(referenceTimePlan,0,List.of(linked(
                    "state","DETERMINISTIC_REFERENCE_TIME","networkCalls",0,
                    "policy","SERVER_OWNED_RULE")));
            return referenceTimePlan;
        }
        DeterministicRequirementCompiler.Result deterministic=new DeterministicRequirementCompiler(session.json)
                .compile(cp,groundedRequirement,sourceContext,session.context);
        // Rule authoring is server-owned. The model compiler is retained only as an explicit
        // compatibility/test path; absence of the flag must therefore mean disabled.
        boolean modelRuleCompilationAllowed=Boolean.TRUE.equals(session.context.get("allowModelRuleCompilation"));
        Plan deterministicPlan=deterministic.identify(
                compileResponse(cp,deterministic.root(),original,focused,focusedDomains,sourceContext),
                deterministic.complete()?"DETERMINISTIC_TEMPLATE_COMPILED":"DETERMINISTIC_TEMPLATE_PARTIAL");
        List<Map<String,Object>> deterministicDiagnostics=CompilationRecovery.diagnostics(deterministicPlan);
        if(!modelRuleCompilationAllowed&&deterministic.complete()&&deterministicDiagnostics.isEmpty()){
            session.compilationAudit=CompilationRecovery.audit(deterministicPlan,0,List.of(linked(
                    "state","DETERMINISTIC_TEMPLATE_COMPILED","networkCalls",0,
                    "compilerVersion",DeterministicRequirementCompiler.VERSION,
                    "coverageDigest",deterministic.digest())));
            return deterministicPlan;
        }
        // In production the model is an evidence extractor, never a rule author. Preserve every
        // deterministic rule and turn uncovered source spans into explicit, source-bound pending
        // nodes. This is materially different from silently dropping a clause or treating a model
        // generated rule as executable policy.
        if(!modelRuleCompilationAllowed){
            Plan safe=deterministic.identify(
                    compileResponse(cp,deterministic.rootWithUnsupportedGaps(),original,focused,focusedDomains,sourceContext),
                    "DETERMINISTIC_TEMPLATE_PARTIAL");
            session.compilationAudit=CompilationRecovery.audit(safe,0,List.of(linked(
                    "state","MODEL_RULE_COMPILATION_DISABLED","networkCalls",0,
                    "compilerVersion",DeterministicRequirementCompiler.VERSION,
                    "coverageDigest",deterministic.digest(),"gapCount",deterministic.gaps().size(),
                    "policy","MODEL_FACT_EXTRACTION_ONLY")));
            return safe;
        }
        if(!session.model.enabled()){
            if(!deterministic.rules().isEmpty())return deterministicPlan;
            Plan plan=fallback(cp);return new Plan(plan.logic(),plan.rules(),plan.method(),plan.issues(),plan.policyNotes(),scope.map());
        }
        Map<String,Object> input=linked("title",cp.title(),"requirement",groundedRequirement,"originalRequirement",original.isBlank()?"":groundedRequirement,"sourceContext",original,"requirementScope",scope.map(),"reviewedExpected",requirement,"category",cp.category(),"ruleVersion",VERSION,
            "scopeInstructions","只对requirement中的当前义务编译并检查覆盖。sourceContext用于定位标题、主体和定义，不得把相邻独立要求重新加入本项；sourceQuote仍须逐字来自requirement。纯逻辑组合使用predicate=GROUP；有业务含义的谓词必须自身核验，REFERENCE_TIME等辅助条件不能替代证书效期、材料或人数要求。");
        input.put("deterministicCompilerVersion",DeterministicRequirementCompiler.VERSION);
        input.put("deterministicCoverage",deterministic.audit());
        input.put("lockedRules",List.of());
        input.put("uncoveredSpans",List.of(linked("sourceQuote",groundedRequirement,"reasonCode","EXPLICIT_LEGACY_MODEL_COMPILER")));
        input.put("gapOnlyInstructions","该调用仅供显式兼容模式使用。生产检查不会调用模型生成规则；返回值只描述规则，不得给出通过、不通过或业务结论。");
        input.put("compilerPolicyVersion",CompilationRecovery.VERSION);
        input.put("verifiedSourceContext",sourceContext.map());
        input.put("resolvedReferences",expansion.references());
        input.put("referenceInstructions","数字条款引用已由服务器从同一冻结招标文件展开。必须核验展开正文的具体义务；不得把‘符合第X条’编为SECTION/MATERIAL去投标文件寻找。sourceQuote只能来自某一段已提供的原文，不能拼接跨来源句子；各字段引用保留其原文，补充条款按明确增加/替换关系处理。");
        input.put("tenderConstants",TenderRequirementConstants.extract(groundedRequirement));
        if(focused){input.put("focusedDomains",List.copyOf(focusedDomains));input.put("focusedCheckVersion",session.context.get("focusedCheckVersion"));
            input.put("checkpointType",cp.type());input.put("checkpointField",cp.field());input.put("checkpointOperator",cp.operator());input.put("checkpointUnit",cp.unit());
            input.put("focusedInstructions","保留当前义务的且、或、条件和逐主体分支，不裁掉复合要求，也不把sourceContext中相邻独立要求塞回。focusedDomains仅为检索提示，不能据此将有支持谓词的子义务变为UNSUPPORTED。材料/声明/字段比较/数值/人员/证书/原图VISUAL/外部EXTERNAL分别编译，由对应执行器完成。提供网址截图与字段一致性不是实时联网查询；只有原文明确要求外部状态时才使用EXTERNAL。普通盖章只编译要求位置有无盖章，不新增真伪鉴定。主体、标段、币种、金额/税口径、证书类别/证号/有效期字段、单位必须引用原文；未知不补默认值。referenceEvent只有原文明确的BID_DEADLINE或OPENING_DATE，不能把开标日替代投标截止日。数值检查保留原比较符、门槛和单位。报价的amountBasis是被比较的投标金额口径，不是门槛名称；例如原文‘含税投标总报价不得超过最高限价500万元’，amountBasis取原文‘投标总报价’，expected为500、unit为万元、taxBasis为含税，不能把amountBasis写成最高限价。sourceQuote应完整包含必要定义与比较句，不只摘最后数字；示例不是当前条款事实。");}
        RequirementArtifact artifact=new RequirementArtifact(session,input,sourceContext);
        try {if(artifact.enabled())artifact.lock.lockInterruptibly();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("编译共享任务已中断");}
        try {
        Map<String,Object> shared=artifact.lookup();
        JsonNode sharedRaw=shared.isEmpty()?null:session.json.valueToTree(shared.get("rawResponse"));
        if(sharedRaw!=null){JsonNode merged=CompilationRecovery.normalize(sharedRaw);Plan cached=compileResponse(cp,merged,original,focused,focusedDomains,sourceContext);
            boolean delayed=shared.get("retryNotBeforeEpochMillis") instanceof Number n&&n.longValue()>artifact.now();
            if(!"REPAIR_RETRY_PENDING".equals(shared.get("state"))||delayed){
                // A supposedly valid artifact must still pass the current server validator.
                if(!"VALIDATED".equals(shared.get("state"))||CompilationRecovery.diagnostics(cached).isEmpty()){
                    session.resumedSteps++;artifact.auditReuse(cached,shared);return artifact.identify(cached);
                }sharedRaw=null;
            }
        }
        JsonNode saved=session.prior("COMPILED_PLAN",session.fingerprint("COMPILED_PLAN",input));
        if(saved!=null){JsonNode merged=CompilationRecovery.normalize(saved);Plan cached=compileResponse(cp,merged,original,focused,focusedDomains,sourceContext);if(CompilationRecovery.diagnostics(cached).isEmpty()){session.resumedSteps++;session.compilationAudit=CompilationRecovery.audit(cached,0,List.of(linked("state","REUSED_VALIDATED_PLAN")));artifact.publish(merged,cached,false,0);return artifact.identify(cached);}}
        JsonNode originalResponse=sharedRaw==null?session.model("COMPILE",input):sharedRaw,root=CompilationRecovery.normalize(originalResponse);
        Plan plan=compileResponse(cp,root,original,focused,focusedDomains,sourceContext);
        List<Map<String,Object>> diagnostics=CompilationRecovery.diagnostics(plan),history=new ArrayList<>();
        if(sharedRaw==null)session.completeCompilation(input,root,diagnostics);history.add(linked("stage",sharedRaw==null?"INITIAL_VALIDATION":"REUSED_FAILED_NODE_INPUT","diagnostics",diagnostics));
        int repairs=0;boolean repairFailed=false;
        if(CompilationRecovery.repairable(plan)){
            Map<String,Object> repair=new LinkedHashMap<>(input);repair.put("task","REPAIR_COMPILED_REQUIREMENT");
            repair.put("previousResponse",root);repair.put("validationErrors",diagnostics);
            List<Object> locked=new ArrayList<>(CompilationRecovery.lockedRules(plan));repair.put("lockedRules",locked);
            repair.put("repairInstructions","仅修复validationErrors涉及的原有节点，返回完整logic/rules JSON。保留所有原义务ID及已核对sourceQuote；lockedRules及其中子树不可更改或删除。可添加原文中被遗漏的义务，不得添加原文没有的义务。无法修复的节点原样保留为UNSUPPORTED。不能用REFERENCE_TIME、INPUT或空集合代替业务要求；不得为通过校验删掉失败条件。");
            repairs++;
            try{
                JsonNode repairResponse=session.model("COMPILE",repair),amended=CompilationRecovery.normalize(repairResponse);
                Plan candidate=compileResponse(cp,amended,original,focused,focusedDomains,sourceContext);
                List<String> preservation=CompilationRecovery.repairIssues(plan,candidate);
                List<Map<String,Object>> checked=new ArrayList<>(CompilationRecovery.diagnostics(candidate));
                preservation.forEach(issue->checked.add(linked("obligationId","","reasonCode","REPAIR_OBLIGATION_CHANGED","need",issue)));
                session.completeCompilation(repair,repairResponse,checked);history.add(linked("stage","REPAIR_VALIDATION","diagnostics",checked));
                if(preservation.isEmpty()&&checked.size()<diagnostics.size()){plan=candidate;root=amended;diagnostics=checked;}
                else history.add(linked("stage","REPAIR_STOPPED","reasonCode",preservation.isEmpty()?"NO_VALIDATION_PROGRESS":"ORIGINAL_OBLIGATION_PROTECTION"));
            }catch(InterruptedIOException interrupted){throw interrupted;}
            catch(IOException unavailable){repairFailed=true;history.add(linked("stage","REPAIR_TRANSPORT_FAILED","diagnostic",RunSession.failure(unavailable)));}
        }
        if(diagnostics.isEmpty())session.record("COMPILED_PLAN",input,root);
        session.compilationAudit=CompilationRecovery.audit(plan,repairs,history);
        artifact.publish(root,plan,repairFailed,repairs);
        return artifact.identify(plan);
        } finally {if(artifact.enabled())artifact.lock.unlock();}
    }
    /**
     * A reference-time checkpoint is an input binding, not a business obligation. Compile only
     * the narrow label itself here so a model can neither invent the event nor turn a date lookup
     * into a certificate/material pass. Clauses that contain comparisons or obligation language
     * continue through the normal deterministic template compiler.
     */
    private Plan referenceTimePlan(Checkpoint cp,String requirement,RequirementSourceContext.Context sourceContext,RequirementScope.Selection scope){
        String normalized=clean(requirement).replaceAll("[：:；;，,。.]$","");
        if(normalized.length()>24||normalized.matches(".*(?:须|应|不得|不应|至少|不超过|不少于|早于|晚于|之前|之后|前|后|以内|一致).*"))return null;
        String event;
        if(normalized.matches("(?:参考)?(?:投标|文件递交|投标文件递交|递交|提交)?截止(?:时间|日期|时点)?"))event="BID_DEADLINE";
        else if(normalized.matches("(?:参考)?开标(?:时间|日期|时点)?"))event="OPENING_DATE";
        else return null;
        Map<String,Object> parameters=Map.of("referenceEvent",event,"inputRole","TENDER_REFERENCE_TIME");
        Rule rule=new Rule("REFERENCE_TIME_1",cp.title(),"REFERENCE_TIME","",event,"","","",parameters,requirement,"",List.of());
        Map<String,Object> metadata=new LinkedHashMap<>(scope.map());metadata.put("sourceContext",sourceContext.map());
        return new Plan("ALL",List.of(withSourceContext(rule,sourceContext.map())),"DETERMINISTIC_REFERENCE_TIME",List.of(),List.of(),metadata);
    }
    /** Deterministic post-processing is also usable for offline replay of a saved compiler response. */
    Plan compileResponse(Checkpoint cp,JsonNode root,String original,boolean focused,Set<String> focusedDomains){
        return compileResponse(cp,root,original,focused,focusedDomains,RequirementSourceContext.Context.empty(original));
    }
    private Plan compileResponse(Checkpoint cp,JsonNode root,String original,boolean focused,Set<String> focusedDomains,RequirementSourceContext.Context sourceContext){
        root=CompilationRecovery.normalize(root);String requirement=Objects.toString(cp.expected(),"");
        RequirementScope.Selection scope=RequirementScope.resolve(cp,original);
        if(!scope.resolved())return new Plan("ALL",List.of(),"SCOPE_UNRESOLVED",List.of(scope.issue()),List.of(),scope.map());
        String groundedRequirement=RequirementReferences.requirementText(scope.obligation(),sourceContext);
        RequirementContextBindings bindings=new RequirementContextBindings(original.isBlank()?requirement:sourceContext.bindingSource(),original.isBlank()?"REVIEWED_REQUIREMENT":"ORIGINAL_REQUIREMENT",sourceContext.sources().size()>1);
        List<String> issues=new ArrayList<>(RequirementReferences.issues(sourceContext));String logic=root.path("logic").asText("ALL");if(!Set.of("ALL","ANY").contains(logic))issues.add("顶层组合不是 ALL 或 ANY");
        List<Rule> rules=new ArrayList<>();Set<String> ids=new HashSet<>();int[] count={0};
        List<Map<String,Object>> constants=TenderRequirementConstants.extract(groundedRequirement);
        if(root.path("rules").isArray())for(JsonNode row:root.path("rules")){Rule r=parse(row,groundedRequirement,issues,ids,0,count,bindings,List.of(),"ALL".equals(logic)?constants:List.of(),Map.of());if(r!=null){r=referenceGrounding(r,scope.obligation(),sourceContext,issues);rules.add(focused?focusedRule(r,focusedDomains,bindings):r);}}
        rules=new ArrayList<>(TenderRequirementConstants.businessRules(rules,logic,constants));
        if(!sourceContext.sources().isEmpty())rules=new ArrayList<>(rules.stream().map(r->withSourceContext(r,sourceContext.map())).toList());
        if(rules.isEmpty())issues.add("未编译出可执行义务");
        String topExpected=RequirementLogic.expected(groundedRequirement,rules);
        if(!topExpected.isEmpty()&&!topExpected.equals(logic))issues.add("TOP_LEVEL_LOGIC_SOURCE:"+topExpected+"；"+RequirementLogic.issue(groundedRequirement,logic,rules));
        issues.addAll(RequirementCoverage.issues(groundedRequirement,rules));
        String requiredPredicate=switch(Objects.toString(cp.type(),"")){case "DATE_VALIDITY"->"VALIDITY";case "VALUE_COMPARE"->"NUMBER";case "ENTITY_FIELD_CONSISTENCY"->"SAME_FIELD";default->"";};
        if(!requiredPredicate.isBlank()&&leaves(rules).stream().noneMatch(r->r.predicate().equals(requiredPredicate)||requiredPredicate.equals("NUMBER")&&r.predicate().equals("ENTITY_COUNT")&&EntityCountRequirement.issue(r)==null&&"家".equals(cp.unit())))issues.add("检查项所需业务谓词未保留："+requiredPredicate+"；辅助条件不能代替业务要求");
        List<Map<String,Object>> policies=new ArrayList<>();if(root.path("policyNotes").isArray())for(JsonNode row:root.path("policyNotes")){String quote=row.path("sourceQuote").asText();if(!quote.isBlank()&&groundedRequirement.contains(quote))policies.add(linked("sourceQuote",quote,"interpretation",row.path("interpretation").asText(),"separatedFromOriginalObligation",!original.isBlank()));}
        Map<String,Object> scopeMetadata=new LinkedHashMap<>(scope.map());scopeMetadata.put("sourceContext",sourceContext.map());scopeMetadata.put("tenderConstants",constants);
        return new Plan(Set.of("ALL","ANY").contains(logic)?logic:"ALL",rules,"MODEL_COMPILED",issues,policies,scopeMetadata);
    }
    private Rule referenceGrounding(Rule rule,String obligation,RequirementSourceContext.Context context,List<String> issues){
        List<Rule> children=rule.children().stream().map(c->referenceGrounding(c,obligation,context,issues)).toList();
        Rule current=new Rule(rule.id(),rule.description(),rule.predicate(),rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),rule.parameters(),rule.sourceQuote(),rule.logic(),children);
        if(!rule.sourceQuote().isBlank()&&!RequirementReferences.sourceBound(rule.sourceQuote(),obligation,context))return rejected(current,"义务引用跨越原文来源边界，须逐来源保留证据",issues);
        if(RequirementReferences.referenceOnly(current))return rejected(current,"条款引用须执行已展开正文的具体义务，不能当投标章节存在性核验",issues);
        return current;
    }
    private Rule withSourceContext(Rule r,Map<String,Object> sourceContext){Map<String,Object> p=new LinkedHashMap<>(r.parameters());
        // The full original clause lives once on the plan; do not repeat it in every extraction rule.
        List<?> inherited=sourceContext.get("sources") instanceof List<?> all?all.stream().filter(v->v instanceof Map<?,?> m&&!"OBLIGATION".equals(m.get("kind"))).toList():List.of();
        p.put("requirementSourceContext",linked("version",sourceContext.get("version"),"identity",sourceContext.get("identity"),"sources",inherited));
        return new Rule(r.id(),r.description(),r.predicate(),r.subject(),r.field(),r.operator(),r.expected(),r.unit(),p,r.sourceQuote(),r.logic(),r.children().stream().map(c->withSourceContext(c,sourceContext)).toList());}
    /** Source restrictions apply to every routed capability; routing hints never remove a supported obligation. */
    private Rule focusedRule(Rule rule,Set<String> domains,RequirementContextBindings bindings){
        List<Rule> children=rule.children().stream().map(child->focusedRule(child,domains,bindings)).toList();
        if(!children.isEmpty()){
            Map<String,Object> parameters=new LinkedHashMap<>(rule.parameters());
            if(RuleObligations.hasOwnPredicate(rule)){
                String ownIssue=focusedIssue(rule,domains,bindings);
                if(ownIssue!=null){parameters.put("originalPredicate",rule.predicate());parameters.put("focusedScopeIssue",ownIssue);
                    return new Rule(rule.id(),rule.description(),"UNSUPPORTED",rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),parameters,rule.sourceQuote(),rule.logic(),children);}
            }
            return new Rule(rule.id(),rule.description(),rule.predicate(),rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),parameters,rule.sourceQuote(),rule.logic(),children);
        }
        String problem=focusedIssue(rule,domains,bindings);
        if(problem==null)return rule;
        Map<String,Object> parameters=new LinkedHashMap<>(rule.parameters());parameters.putIfAbsent("originalPredicate",rule.predicate());parameters.put("focusedScopeIssue",problem);
        return new Rule(rule.id(),rule.description(),"UNSUPPORTED",rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),parameters,rule.sourceQuote(),"",List.of());
    }
    private String focusedIssue(Rule rule,Set<String> domains,RequirementContextBindings bindings){
        String predicate=rule.predicate(),quote=rule.sourceQuote();Map<String,Object> parameters=rule.parameters();
        for(String key:List.of("lot","currency","amountBasis","taxBasis","category","number","validityField","employer")){
            String value=str(parameters,key);
            if(!value.isBlank()&&(Set.of("UNKNOWN","UNRESOLVED","CURRENT_PROJECT").contains(value)||!ProofVerifier.contains(quote,value)&&!bindings.bound(rule,key,value)))return "条件参数缺少该原条款直接依据："+key;
        }
        if(!rule.subject().isBlank()&&!ProofVerifier.contains(quote,rule.subject())&&!bindings.bound(rule,"subject",rule.subject()))return "条件主体未对应原条款，不能把模型推测的人员或单位写入要求";
        String event=str(parameters,"referenceEvent");
        boolean deadline=quote.matches("(?s).*(?:投标|递交|提交).{0,12}截止.*"),opening=quote.contains("开标");
        if(!event.isBlank()&&!(event.equals("BID_DEADLINE")&&deadline||event.equals("OPENING_DATE")&&opening))return "参考时点与原条款未明确对应";
        if(Set.of("VALIDITY","REFERENCE_TIME").contains(predicate)&&opening&&event.isBlank())return "条款明确开标时点但未绑定该时点，不能默认替换为投标截止日";
        return null;
    }
    private Rule parse(JsonNode n,String requirement,List<String> issues,Set<String> ids,int depth,int[] count,RequirementContextBindings bindings,List<RequirementContextBindings.Parent> parents,List<Map<String,Object>> constants,Map<String,Object> inactiveBranch){
        if(depth>4||++count[0]>40){issues.add("单次条款的条件规模或层级超过结构协议限制，未展开条件保留待核");return null;}
        String id=n.path("id").asText("O"+count[0]),quote=n.path("sourceQuote").asText(),predicate=n.path("predicate").asText(),logic=n.path("logic").asText();
        if(id.isBlank()||id.endsWith("::own")||!ids.add(id)){issues.add("义务标识重复、为空或使用了系统保留后缀");return null;}
        if(predicate.equals("NOOP"))predicate="NOT_APPLICABLE";
        if(predicate.equals("NOT_APPLICABLE")&&inactiveBranch.isEmpty())return rejected(n,id,quote,List.of(),"不适用节点只能属于有原文依据的条件假分支",issues);
        if(quote.isBlank()||!requirement.contains(quote))return rejected(n,id,"",List.of(),"规则依据未能逐字对应检查点要求",issues);
        List<RequirementContextBindings.Parent> next=new ArrayList<>(parents);next.add(new RequirementContextBindings.Parent(id,n.path("subject").asText(),quote,logic));
        boolean pureAll=Set.of("","GROUP").contains(predicate)&&"ALL".equals(logic)&&!constants.isEmpty();
        List<Rule> children=new ArrayList<>();int issuesBeforeChildren=issues.size();
        if(n.path("children").isArray())for(int childIndex=0;childIndex<n.path("children").size();childIndex++){JsonNode child=n.path("children").get(childIndex);Rule r=parse(child,requirement,issues,ids,depth+1,count,bindings,next,pureAll?constants:List.of(),ConditionalRequirement.inactiveBranch(n,childIndex,requirement));if(r!=null)children.add(r);}
        // Empty source-proven definition containers are removable only along ALL ancestry.
        // Parse errors and alternative/conditional/business branches can never disappear here.
        if(pureAll&&n.path("children").isArray()&&!n.path("children").isEmpty()&&children.isEmpty()&&issues.size()==issuesBeforeChildren)return null;
        if(!children.isEmpty()&&!LOGICS.contains(logic)&&!"SCORE_COUNT".equals(predicate))return rejected(n,id,quote,children,"组合类型无效",issues);
        if(!children.isEmpty()&&!predicate.isBlank()&&!predicate.equals("GROUP")&&!PREDICATES.contains(predicate))return rejected(n,id,quote,children,"组合包含尚不支持的业务原语",issues);
        if(children.isEmpty()&&!PREDICATES.contains(predicate))return rejected(n,id,quote,children,"原语尚不支持",issues);
        if("CONDITIONAL".equals(logic)&&children.size()!=3)return rejected(n,id,quote,children,"条件规则缺少完整分支",issues);
        Map<String,Object> params=n.path("parameters").isObject()?new ObjectMapper().convertValue(n.path("parameters"),Map.class):new LinkedHashMap<>();
        params.remove("expectedSourceLogic"); // Only the server's source connector check may authorize a logical repair.
        // Prior server diagnostics are recalculated from the current frozen source, never inherited.
        for(String diagnostic:List.of("focusedScopeIssue","compilationIssue","requirementSourceVerified","originalPredicate",RequirementPrimitiveSemantics.UNIQUENESS_KEY,"primitiveRepair"))params.remove(diagnostic);
        params.remove("requirementSourceContext");params.remove("obligationIdentity");params.remove("inactiveBranchProof");
        if(predicate.equals("NOT_APPLICABLE"))params.put("inactiveBranchProof",inactiveBranch);
        if(params.values().stream().anyMatch(Objects::isNull))return rejected(n,id,quote,children,"包含空的规则参数",issues);
        for(String key:List.of("amountMinimum","pointsPerItem","maxScore")){if(params.containsKey(key)&&!ProofVerifier.numberPresent(quote,str(params,key)))return rejected(n,id,quote,children,"数值参数缺少原条款依据："+key,issues);}
        if(logic.equals("FOR_EACH")){List<String> subjects=ProofVerifier.strings(params.get("subjects"));boolean complete=RequirementGrounding.completeSubjects(quote,subjects)&&subjects.stream().allMatch(s->children.stream().anyMatch(c->c.subject().equals(s)));params.put("subjectSetComplete",complete);}
        Rule rule=new Rule(id,n.path("description").asText(quote),predicate,n.path("subject").asText(),n.path("field").asText(),n.path("operator").asText(),n.path("expected").asText(),n.path("unit").asText(),params,quote,logic,children);
        // Source-proven tender inputs are not bidder obligations. Separate them before business
        // grounding: a definition has no bidder tax/subject proof to validate or repair.
        // Only pure ALL ancestry receives constants; alternative/conditional paths stay intact.
        if(TenderRequirementConstants.isDefinition(rule,constants))return null;
        if(Set.of("","GROUP").contains(predicate)&&Set.of("ALL","ANY").contains(logic)){
            String logicIssue=RequirementLogic.issue(quote,logic,children);
            if(!logicIssue.isBlank()){Map<String,Object> proof=new LinkedHashMap<>(params);proof.put("expectedSourceLogic",RequirementLogic.expected(quote,children));
                return rejected(new Rule(id,rule.description(),predicate,rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),proof,quote,logic,children),logicIssue,issues);}
        }
        rule=bindings.bind(rule,parents);
        rule=EntityCountRequirement.normalize(rule);
        rule=PersonCountRequirement.normalize(rule,bindings);
        rule=PriceRequirementBinding.resolve(rule,bindings);
        rule=SameFieldComparisonTarget.normalize(rule);
        rule=RequirementPrimitiveSemantics.normalize(rule);
        String primitiveIssue=RequirementPrimitiveSemantics.issue(rule);
        if(primitiveIssue!=null)return rejected(rule,primitiveIssue,issues);
        if(rule.predicate().equals("NUMBER")&&!ProofVerifier.numberPresent(quote,rule.expected())&&!PriceRequirementBinding.bound(rule,bindings.source()))return rejected(rule,"比较门槛缺少原条款依据",issues);
        String binding=RequirementGrounding.issue(rule,bindings.source());return binding==null?rule:rejected(rule,binding,issues);
    }
    private Rule rejected(JsonNode n,String id,String quote,List<Rule> children,String reason,List<String> issues){
        Map<String,Object> parameters=new LinkedHashMap<>();if(quote.isBlank())parameters.put("unverifiedSourceQuote",n.path("sourceQuote").asText());
        return rejected(new Rule(id,n.path("description").asText("条款尚未可靠解析"),n.path("predicate").asText(),n.path("subject").asText(),n.path("field").asText(),n.path("operator").asText(),n.path("expected").asText(),n.path("unit").asText(),parameters,quote,n.path("logic").asText(),children),reason,issues);
    }
    private Rule rejected(Rule rule,String reason,List<String> issues){
        issues.add(rule.id()+"："+reason);Map<String,Object> parameters=new LinkedHashMap<>(rule.parameters());
        parameters.put("originalPredicate",rule.predicate());parameters.put("compilationIssue",reason);parameters.put("requirementSourceVerified",!rule.sourceQuote().isBlank());
        return new Rule(rule.id(),rule.description(),"UNSUPPORTED",rule.subject(),rule.field(),rule.operator(),rule.expected(),rule.unit(),parameters,rule.sourceQuote(),rule.logic(),rule.children());
    }
    private Plan fallback(Checkpoint cp){
        String text=Objects.toString(cp.expected(),""),kind="UNSUPPORTED";
        // Only already-typed material presence is compiled offline; compound natural-language policy is not silently reduced.
        if("MATERIAL_EXISTENCE".equals(cp.type())&&!text.matches("(?s).*(?:且|以及|分别|全部|现场|查询|签章|签字|盖章).*"))kind="MATERIAL";
        Rule rule=new Rule("O1",cp.title(),kind,"",Objects.toString(cp.field(),""),"",text,"",Map.of(),text,"",List.of());
        return new Plan("ALL",List.of(rule),"NATIVE",kind.equals("UNSUPPORTED")?List.of("结构化规则编译服务未配置，该自然语言条件不能可靠简化"):List.of());
    }
    static List<Rule> leaves(List<Rule> rules){List<Rule> out=new ArrayList<>();for(Rule r:rules){if(r.children().isEmpty()||r.predicate().equals("SCORE_COUNT"))out.add(r);else {if(RuleObligations.hasOwnPredicate(r))out.add(RuleObligations.own(r));out.addAll(leaves(r.children()));}}return out;}
}
