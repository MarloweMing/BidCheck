package cn.bidcheck.ocr;

import java.util.*;

/** Bounded process metrics; no image data or document text is retained. */
public final class OcrBatchMetrics {
    private static final long START=System.nanoTime();
    private static final Map<String,Set<String>> SEEN=new LinkedHashMap<>();
    private static final ArrayDeque<Long> LATENCIES=new ArrayDeque<>();
    private static long batches,splits,succeeded,failed,images,pixels,tokens;
    private OcrBatchMetrics(){}
    public static synchronized void record(OcrBatch.Event event) {
        Set<String> states=SEEN.computeIfAbsent(event.id(),id->new HashSet<>());
        if(!states.add(event.status()))return;
        if(SEEN.size()>2048)SEEN.remove(SEEN.keySet().iterator().next());
        switch(event.status()) {
            case "RUNNING"->{batches++;images+=event.inputIds().size();pixels+=Math.max(0,event.pixels());tokens+=Math.max(0,event.estimatedTokens());}
            case "SPLITTING"->{splits++;outcome(states,false,event.elapsedMillis());}
            case "SUCCEEDED"->outcome(states,true,event.elapsedMillis());
            case "FAILED","PARTIAL_FAILED"->outcome(states,false,event.elapsedMillis());
            default->{}
        }
    }
    private static void outcome(Set<String> states,boolean success,long elapsed){
        // Local size splitting/cancelled admission has no service result; recovered parents have already been counted.
        if(!states.contains("RUNNING")||!states.add("OUTCOME_COUNTED"))return;
        if(success)succeeded++;else failed++;
        sample(elapsed);
    }
    private static void sample(long duration){if(LATENCIES.size()==512)LATENCIES.removeFirst();LATENCIES.addLast(Math.max(0,duration));}
    public static synchronized Map<String,Object> summary(){
        List<Long> values=LATENCIES.stream().sorted().toList();
        return Map.ofEntries(Map.entry("requests",batches),Map.entry("inputImagesIncludingRetries",images),Map.entry("splits",splits),
            Map.entry("succeededBatches",succeeded),Map.entry("failedBatches",failed),Map.entry("inputPixels",pixels),
            Map.entry("estimatedVisualTokens",tokens),Map.entry("latencySamples",values.size()),
            Map.entry("p50Millis",percentile(values,.50)),Map.entry("p95Millis",percentile(values,.95)),Map.entry("p99Millis",percentile(values,.99)),
            Map.entry("uptimeMillis",(System.nanoTime()-START)/1_000_000),Map.entry("boundary","最近512个完成批次的延迟；输入数含拆分重试，Token为估算，不是账单"));
    }
    private static long percentile(List<Long> values,double fraction){return values.isEmpty()?0:values.get(Math.min(values.size()-1,(int)Math.ceil(values.size()*fraction)-1));}
}
