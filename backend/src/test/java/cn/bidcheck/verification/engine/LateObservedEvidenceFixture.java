package cn.bidcheck.verification.engine;

import cn.bidcheck.domain.Domain.Document;
import cn.bidcheck.domain.Domain.Page;
import java.util.*;
import java.lang.reflect.*;
import static cn.bidcheck.verification.engine.VerificationTypes.*;

/** Synthetic bridge to the actual engine dependency generator, never real bid data. */
public final class LateObservedEvidenceFixture {
    public static Map<String,Object> dependency(boolean selected, boolean executed) throws Exception {
        return dependency(selected, executed, false, Collections.emptyMap());
    }
    public static Map<String,Object> consortium(boolean complete, boolean leadDependency) throws Exception {
        return dependency(true, true, false, Collections.emptyMap(), true, complete, leadDependency);
    }
    public static Map<String,Object> dependency(boolean selected, boolean executed, boolean scalar, Map<String,Object> previous) throws Exception {
        return dependency(selected, executed, scalar, previous, false, false, false);
    }
    public static Map<String,Object> dependency(boolean selected, boolean executed, boolean scalar, Map<String,Object> previous, boolean joint, boolean complete, boolean leadDependency) throws Exception {
        Document doc = new Document("doc", "project", "BID", "synthetic.txt", 1, "hash-1", "now",
            Arrays.asList(new Page(1, "投标文件\n项目名称：合成中心综合楼二次装修工程\n投标人：合成建设有限公司。\n投标报价：150元。", "PAGE", "PDF_TEXT", "")), Collections.emptyList());
        if (joint) doc = new Document("doc", "project", "BID", "synthetic.txt", 1, "hash-1", "now",
            Arrays.asList(new Page(1, "投标文件\n项目名称：合成中心综合楼二次装修工程", "PAGE", "PDF_TEXT", ""), new Page(2, "联合体协议书\n"
                + (complete ? "合成建设有限公司、合成设计有限公司[所有成员单位名称]自愿组成：合成建设有限公司和合成设计有限公司联合体，共同投标合成中心综合楼二次装修工程/标段。\n" : "本项目采用联合体投标。\n")
                + "合成建设有限公司为联合体牵头人。\n投标人：合成建设有限公司。\n投标人：合成设计有限公司。", "PAGE", "PDF_TEXT", "")), Collections.emptyList());
        EvidenceCorpus corpus = new EvidenceCorpus(Arrays.asList(doc));
        Source source = corpus.all.get(corpus.all.size()-1);
        Fact role = new Fact("role", "RELATION", "投标人", "名称", "合成建设有限公司", "合成建设有限公司", "", "", "CURRENT_PROJECT",
            Arrays.asList(source.id()), linked("object", "合成建设有限公司"));
        Rule rule = new Rule("price", "本项目报价", "NUMBER", "投标人", "投标报价", "<=", "100", "元", Collections.emptyMap(), "投标报价不超过100元", "", Collections.emptyList());
        if (scalar) {
            role = new Fact("amount", "NUMBER", "合成建设有限公司", "投标报价", "150", "150", "元", "投标报价", "CURRENT_PROJECT", Arrays.asList(source.id()), Collections.emptyMap());
            rule = new Rule("price", "本项目报价", "NUMBER", "合成建设有限公司", "投标报价", "<=", "100", "元", Collections.emptyMap(), "投标报价不超过100元", "", Collections.emptyList());
        }
        if (leadDependency) rule = new Rule("price", "牵头人的报价", "NUMBER", "联合体牵头人", "投标报价", "<=", "100", "元", Collections.emptyMap(), "牵头人报价不超过100元", "", Collections.emptyList());
        List<Fact> facts = joint ? EnterpriseSubjectBindings.extract(Arrays.asList(rule), corpus.all) : Arrays.asList(role);
        RuleEvaluator evaluator = new RuleEvaluator(facts, corpus, Collections.emptyMap());
        if (executed) evaluator.evaluate(rule);
        SharedFactReuse shared = new SharedFactReuse(corpus, Collections.emptyMap(), "model", VERSION);
        Field field = SharedFactReuse.class.getDeclaredField("metadata");
        field.setAccessible(true);
        Map<String,Object> context = linked("ordinaryRecoveryObservedRevision", 0L);
        if (!previous.isEmpty()) context.put("previousAtomicCheck", linked("recoveryEvidenceDependencies", Arrays.asList(previous)));
        List<Rule> active = selected ? Arrays.asList(rule) : Collections.emptyList();
        List<Map<String,Object>> dependencies;
        try {
            Method method = VerificationEngine.class.getDeclaredMethod("evidenceDependencies", List.class, List.class, List.class, Map.class, String.class, RuleEvaluator.class, Map.class);
            method.setAccessible(true);
            dependencies = (List<Map<String,Object>>) method.invoke(null, Arrays.asList(rule), active, Collections.emptyList(), context, "model", evaluator, field.get(shared));
        } catch (NoSuchMethodException originalImplementation) {
            dependencies = VerificationEngine.evidenceDependencies(Arrays.asList(rule), active, Collections.emptyList(), context, "model", evaluator);
        }
        Map<String,Object> result = new LinkedHashMap<>(dependencies.get(0));
        result.put("fixtureChange", linked("itemId", "late-role", "recordType", "FACT", "fact", SharedFactReuse.value(role),
            "sources", Arrays.asList(((Map<String,Map<String,Object>>) field.get(shared)).get(source.id()))));
        if (joint) {
            List<Rule> memberRules = Arrays.asList(
                new Rule("members", "", "RELATION", "联合体成员", "名称", "", "", "", Collections.emptyMap(), "", "", Collections.emptyList()),
                new Rule("lead", "", "RELATION", "联合体牵头人", "名称", "", "", "", Collections.emptyMap(), "", "", Collections.emptyList()));
            List<Map<String,Object>> changes = new ArrayList<>();
            // Native facts have the exact production three-event shape, using invented companies only.
            for (Fact fact : EnterpriseSubjectBindings.extract(memberRules, corpus.all))
                changes.add(linked("itemId", fact.id(), "recordType", "FACT", "fact", SharedFactReuse.value(fact),
                    "sources", Arrays.asList(((Map<String,Map<String,Object>>) field.get(shared)).get(source.id()))));
            result.put("fixtureChanges", changes);
        }
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(result, Map.class);
    }
}
