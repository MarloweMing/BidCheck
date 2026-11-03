package cn.bidcheck.service;

import cn.bidcheck.document.OcrUnitObserver;
import cn.bidcheck.infrastructure.queue.TaskLeaseLostException;
import cn.bidcheck.ocr.OcrLayout;
import cn.bidcheck.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ocr-unit-journal;DB_CLOSE_DELAY=-1","bidcheck.tasks.poll-ms=600000"})
class OcrUnitJournalTest {
    @Autowired Store store;
    @Autowired PlatformTransactionManager manager;
    private final String project=Store.id(),document=Store.id(),source=cn.bidcheck.compat.Java8Api.repeat("a", 64),image=cn.bidcheck.compat.Java8Api.repeat("b", 64),task=Store.id();
    OcrUnitJournal journal(String doc,int version,String hash,String plan,String model,Runnable fence,String base) {
        return new OcrUnitJournal(store,new TransactionTemplate(manager),fence,project,doc,version,hash,plan,model,task,base);
    }
    OcrUnitJournal journal(){return journal(document,1,source,"plan","model",()->{},"");}
    OcrUnitObserver.Event event(String status,boolean cached) {
        return new OcrUnitObserver.Event("PAGE",2,1,status,image,"营业执照：测试企业","SUCCEEDED".equals(status)?OcrLayout.unavailable("无几何"):null,
            "SUCCEEDED".equals(status)?"[OCR已识别] 需复核":"[OCR未完成] 临时故障",25,cached,100,200,1024);
    }
    @Test void committedUnitSurvivesNewJournalAndStatusQueriesContainNoTranscript()throws Exception {
        cn.bidcheck.service.OcrUnitJournal first=journal();first.onState(event("RUNNING",false));first.onState(event("SUCCEEDED",false));
        assertThat(journal().restore("PAGE",2,1,image)).hasValueSatisfying(r->assertThat(r.text()).isEqualTo("营业执照：测试企业"));
        Map<String,Object> status=store.maps("ocr_unit_status",project,document).get(0);
        assertThat(status).containsEntry("attempts",1).containsEntry("status","SUCCEEDED").doesNotContainKeys("text","result","layout","warning");
        Map<String,Object> progress=store.getMap("ocr_progress","ocr-progress:"+document);
        assertThat(((Map<?,?>)progress.get("counts")).get("SUCCEEDED")).isEqualTo(1);
    }
    @Test void sourceModelPlanDocumentVersionAndImageAreAllBound()throws Exception {
        journal().onState(event("SUCCEEDED",false));
        assertThat(journal(document,2,source,"plan","model",()->{},"").restore("PAGE",2,1,image)).isEmpty();
        assertThat(journal(document,1,cn.bidcheck.compat.Java8Api.repeat("c", 64),"plan","model",()->{},"").restore("PAGE",2,1,image)).isEmpty();
        assertThat(journal(document,1,source,"changed","model",()->{},"").restore("PAGE",2,1,image)).isEmpty();
        assertThat(journal(document,1,source,"plan","other-model",()->{},"").restore("PAGE",2,1,image)).isEmpty();
        assertThat(journal(Store.id(),1,source,"plan","model",()->{},"").restore("PAGE",2,1,image)).isEmpty();
        assertThat(journal().restore("PAGE",2,1,cn.bidcheck.compat.Java8Api.repeat("c", 64))).isEmpty();
        assertThat(journal().restore("PAGE",3,1,image)).isEmpty();
    }
    @Test void explicitContinuationReusesOnlySameSourceAndParserFromPriorVersion()throws Exception {
        journal().onState(event("SUCCEEDED",false));String next=Store.id();
        cn.bidcheck.service.OcrUnitJournal resumed=journal(next,2,source,"full-plan","model",()->{},document);
        assertThat(resumed.restore("PAGE",2,1,image)).isPresent();resumed.onState(event("SUCCEEDED",true));
        assertThat(store.getMap("ocr_progress","ocr-progress:"+next)).containsEntry("cachedUnitCount",1);
        assertThat(journal(next,2,cn.bidcheck.compat.Java8Api.repeat("d", 64),"full-plan","model",()->{},document).restore("PAGE",2,1,image)).isEmpty();
        assertThat(journal(next,2,source,"full-plan","new-model",()->{},document).restore("PAGE",2,1,image)).isEmpty();
        assertThat(store.maps("ocr_unit_status",project,document)).hasSize(1);
    }
    @Test void failedAndPartialWorkAreNotRestoredAndTamperedSuccessIsRejected()throws Exception {
        journal().onState(event("FAILED",false));assertThat(journal().restore("PAGE",2,1,image)).isEmpty();
        journal().onState(event("PARTIAL_SUCCEEDED",false));assertThat(journal().restore("PAGE",2,1,image)).isEmpty();
        journal().onState(event("SUCCEEDED",false));
        Stored saved=store.mapper().list("ocr_unit_result",project,document).get(0);Map<String,Object> body=store.map(saved.body);
        body.put("result",new OcrUnitObserver.Result("被替换的文字","[OCR已识别]",OcrLayout.unavailable("无几何")));
        store.save("ocr_unit_result",saved.id,project,document,body);
        assertThat(journal().restore("PAGE",2,1,image)).isEmpty();
    }
    @Test void leaseLossStopsBothReuseAndWritesWithoutBecomingCacheMiss()throws Exception {
        journal().onState(event("SUCCEEDED",false));
        cn.bidcheck.service.OcrUnitJournal stale=journal(document,1,source,"plan","model",()->{throw new TaskLeaseLostException(task);},"");
        assertThatThrownBy(()->stale.restore("PAGE",2,1,image)).isInstanceOf(TaskLeaseLostException.class);
        assertThatThrownBy(()->stale.onState(event("FAILED",false))).isInstanceOf(TaskLeaseLostException.class);
        assertThat(store.maps("ocr_unit_status",project,document).get(0)).containsEntry("status","SUCCEEDED");
    }
    @Test void batchTraceAndPartialPageResultsSurviveIndependently()throws Exception {
        cn.bidcheck.service.OcrUnitJournal journal=journal();
        journal.onBatch(new cn.bidcheck.ocr.OcrBatch.Event("batch1","","RUNNING",cn.bidcheck.compat.Java8Api.listOf("PAGE:2:1", "PAGE:3:1"),"test-model","ocr-batch-1",source,40000,42,0,""));
        journal.onState(event("SUCCEEDED",false));
        journal.onBatch(new cn.bidcheck.ocr.OcrBatch.Event("batch1","","SPLITTING",cn.bidcheck.compat.Java8Api.listOf("PAGE:2:1", "PAGE:3:1"),"test-model","ocr-batch-1",source,40000,42,20,"MISSING_PAGE"));
        journal.onBatch(new cn.bidcheck.ocr.OcrBatch.Event("rejection1","batch1","ITEM_REJECTED",cn.bidcheck.compat.Java8Api.listOf("PAGE:3:1"),"test-model","ocr-batch-1",source,20000,21,0,"BATCH_MISSING_ITEM"));
        assertThat(journal().restore("PAGE",2,1,image)).isPresent();
        Map<String,Object> batch=store.maps("ocr_batch_status",project,document).get(0);
        assertThat(batch).containsEntry("taskId",task).containsEntry("documentId",document).containsEntry("status","SPLITTING").doesNotContainKeys("image","text","key","prompt");
        assertThat(store.getMap("ocr_progress","ocr-progress:"+document)).containsEntry("batchCount",1);
        assertThat(store.mapper().list("ocr_batch_status",project,document).get(0).id).hasSize(64);
        assertThat(store.maps("ocr_batch_event",project,document)).hasSize(3).anySatisfy(e->assertThat(e).containsEntry("status","ITEM_REJECTED").containsEntry("parentBatchId","batch1"));
        cn.bidcheck.service.OcrUnitJournal stale=journal(document,1,source,"plan","model",()->{throw new TaskLeaseLostException(task);},"");
        assertThatThrownBy(()->stale.onBatch(new cn.bidcheck.ocr.OcrBatch.Event("batch1","","FAILED",cn.bidcheck.compat.Java8Api.listOf("PAGE:3:1"),"test-model","ocr-batch-1",source,20000,21,30,"TIMEOUT"))).isInstanceOf(TaskLeaseLostException.class);
        assertThat(store.maps("ocr_batch_status",project,document).get(0)).containsEntry("status","SPLITTING");
        journal.onBatch(new cn.bidcheck.ocr.OcrBatch.Event("batch1","","RECOVERED",cn.bidcheck.compat.Java8Api.listOf("PAGE:2:1", "PAGE:3:1"),"test-model","ocr-batch-1",source,40000,42,40,"BATCH_RECOVERY_COMPLETED"));
        assertThat(store.maps("ocr_batch_status",project,document)).hasSize(1).allSatisfy(e->assertThat(e).containsEntry("status","RECOVERED"));
        assertThat(store.maps("ocr_batch_event",project,document)).hasSize(4).anySatisfy(e->assertThat(e).containsEntry("status","SPLITTING").containsEntry("elapsedMillis",20));
    }
}
