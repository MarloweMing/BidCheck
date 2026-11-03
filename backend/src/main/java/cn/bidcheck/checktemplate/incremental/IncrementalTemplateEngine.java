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
        Set<String> changed=new LinkedHashSet<>(changedNeedKeys==null?cn.bidcheck.compat.Java8Api.listOf():changedNeedKeys);Map<String,TemplateExecution> results=new LinkedHashMap<>(state.executions());Set<String> recalculated=new LinkedHashSet<>();
        for(Map.Entry<String,TemplateExecution> entry:state.executions().entrySet())if(entry.getValue().evidenceNeeds().stream().anyMatch(need->changed.contains(need.key()))){CheckSpec spec=specs.get(entry.getKey());if(spec==null)throw new IllegalStateException("CHECK_SPEC_MISSING:"+entry.getKey());
            results.put(entry.getKey(),executor.execute(spec,evidenceProvider.apply(spec)));recalculated.add(entry.getKey());}
        long revision=recalculated.isEmpty()?state.revision():state.revision()+1;State updated=new State(revision,results);Set<String> retained=new LinkedHashSet<>(results.keySet());retained.removeAll(recalculated);
        return new Batch(updated,cn.bidcheck.compat.Java8Api.setCopyOf(changed),cn.bidcheck.compat.Java8Api.setCopyOf(recalculated),cn.bidcheck.compat.Java8Api.setCopyOf(retained),recalculated.isEmpty());}

    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class State{
        private final long revision;
        private final Map<String,TemplateExecution> executions;
        public long revision() { return revision; }
        public Map<String,TemplateExecution> executions() { return executions; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; State that = (State) other; return java.util.Objects.equals(this.revision, that.revision) && java.util.Objects.equals(this.executions, that.executions); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.revision); hash = 31 * hash + java.util.Objects.hashCode(this.executions); return hash; }
        @Override public String toString() { return "State[" + "revision=" + revision + ", executions=" + executions + "]"; }
@com.fasterxml.jackson.annotation.JsonCreator
        public State(@com.fasterxml.jackson.annotation.JsonProperty("revision") long revision, @com.fasterxml.jackson.annotation.JsonProperty("executions") Map<String,TemplateExecution> executions) {if(revision<0)throw new IllegalArgumentException("revision must be non-negative");executions=executions==null?cn.bidcheck.compat.Java8Api.mapOf():cn.bidcheck.compat.Java8Api.mapCopyOf(executions);
            this.revision = revision; this.executions = executions;
        }}
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Batch{
        private final State state;
        private final Set<String> changedNeedKeys;
        private final Set<String> recalculatedCheckpointIds;
        private final Set<String> retainedCheckpointIds;
        private final boolean noOp;
        @com.fasterxml.jackson.annotation.JsonCreator
        public Batch(@com.fasterxml.jackson.annotation.JsonProperty("state") State state, @com.fasterxml.jackson.annotation.JsonProperty("changedNeedKeys") Set<String> changedNeedKeys, @com.fasterxml.jackson.annotation.JsonProperty("recalculatedCheckpointIds") Set<String> recalculatedCheckpointIds, @com.fasterxml.jackson.annotation.JsonProperty("retainedCheckpointIds") Set<String> retainedCheckpointIds, @com.fasterxml.jackson.annotation.JsonProperty("noOp") boolean noOp) { this.state = state; this.changedNeedKeys = changedNeedKeys; this.recalculatedCheckpointIds = recalculatedCheckpointIds; this.retainedCheckpointIds = retainedCheckpointIds; this.noOp = noOp; }
        public State state() { return state; }
        public Set<String> changedNeedKeys() { return changedNeedKeys; }
        public Set<String> recalculatedCheckpointIds() { return recalculatedCheckpointIds; }
        public Set<String> retainedCheckpointIds() { return retainedCheckpointIds; }
        public boolean noOp() { return noOp; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Batch that = (Batch) other; return java.util.Objects.equals(this.state, that.state) && java.util.Objects.equals(this.changedNeedKeys, that.changedNeedKeys) && java.util.Objects.equals(this.recalculatedCheckpointIds, that.recalculatedCheckpointIds) && java.util.Objects.equals(this.retainedCheckpointIds, that.retainedCheckpointIds) && java.util.Objects.equals(this.noOp, that.noOp); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.state); hash = 31 * hash + java.util.Objects.hashCode(this.changedNeedKeys); hash = 31 * hash + java.util.Objects.hashCode(this.recalculatedCheckpointIds); hash = 31 * hash + java.util.Objects.hashCode(this.retainedCheckpointIds); hash = 31 * hash + java.util.Objects.hashCode(this.noOp); return hash; }
        @Override public String toString() { return "Batch[" + "state=" + state + ", changedNeedKeys=" + changedNeedKeys + ", recalculatedCheckpointIds=" + recalculatedCheckpointIds + ", retainedCheckpointIds=" + retainedCheckpointIds + ", noOp=" + noOp + "]"; }
}
}
