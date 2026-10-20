package cn.bidcheck.checktemplate.incremental;

import cn.bidcheck.checktemplate.CheckSpec;
import cn.bidcheck.checktemplate.EvidenceView;
import cn.bidcheck.checktemplate.TemplateCheckExecutor;
import cn.bidcheck.checktemplate.TemplateExecution;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Pure dependency-level recomputation: unchanged checkpoints retain their prior execution. */
public final class IncrementalTemplateEngine {
    private final TemplateCheckExecutor executor;
    public IncrementalTemplateEngine(TemplateCheckExecutor executor){this.executor=Objects.requireNonNull(executor,"executor");}

    public Batch recompute(State state,long expectedRevision,Collection<String> changedNeedKeys,
                           Map<String,CheckSpec> specs,Function<CheckSpec,EvidenceView> evidenceProvider){
        Objects.requireNonNull(state,"state");if(state.revision()!=expectedRevision)throw new IllegalStateException("RESULT_REVISION_CHANGED:"+state.revision());
        Set<String> changed=new LinkedHashSet<>(changedNeedKeys==null?List.of():changedNeedKeys);Map<String,TemplateExecution> results=new LinkedHashMap<>(state.executions());Set<String> recalculated=new LinkedHashSet<>();
        for(Map.Entry<String,TemplateExecution> entry:state.executions().entrySet())if(entry.getValue().evidenceNeeds().stream().anyMatch(need->changed.contains(need.key()))){CheckSpec spec=specs.get(entry.getKey());if(spec==null)throw new IllegalStateException("CHECK_SPEC_MISSING:"+entry.getKey());
            results.put(entry.getKey(),executor.execute(spec,evidenceProvider.apply(spec)));recalculated.add(entry.getKey());}
        long revision=recalculated.isEmpty()?state.revision():state.revision()+1;State updated=new State(revision,results);Set<String> retained=new LinkedHashSet<>(results.keySet());retained.removeAll(recalculated);
        return new Batch(updated,Set.copyOf(changed),Set.copyOf(recalculated),Set.copyOf(retained),recalculated.isEmpty());}

    public record State(long revision,Map<String,TemplateExecution> executions){public State{if(revision<0)throw new IllegalArgumentException("revision must be non-negative");executions=executions==null?Map.of():Map.copyOf(executions);}}
    public record Batch(State state,Set<String> changedNeedKeys,Set<String> recalculatedCheckpointIds,Set<String> retainedCheckpointIds,boolean noOp){}
}
