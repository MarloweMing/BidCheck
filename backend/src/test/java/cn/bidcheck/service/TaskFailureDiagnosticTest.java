package cn.bidcheck.service;

import cn.bidcheck.ocr.OcrFailure;
import java.io.*;
import java.net.SocketTimeoutException;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

class TaskFailureDiagnosticTest {
    private static final String TASK="37fbeb30-2fbd-4a07-b23d-c1447317e329";
    private TaskFailureDiagnostic.Diagnostic inspect(Throwable failure){return TaskFailureDiagnostic.inspect(TASK,TaskFailureDiagnostic.Phase.EXECUTION,failure);}

    @Test void databaseLockTimeoutKeepsCodesButNeverSqlParametersOrMessages() {
        SQLException sql=new SQLException("UPDATE records SET body='SECRET_DOCUMENT' password=SECRET_KEY", "HY000",1205);
        var result=inspect(new DataAccessResourceFailureException("JDBC params SECRET_KEY",sql));
        assertThat(result.category()).isEqualTo(TaskFailureDiagnostic.Category.DATABASE_TIMEOUT);
        assertThat(result.userMessage()).contains("数据库操作超时","诊断编号：taskdiag-").doesNotContain("文件格式","SECRET");
        assertThat(result.causes()).hasSize(2);
        assertThat(result.causes().get(1).sqlState()).isEqualTo("HY000");assertThat(result.causes().get(1).vendorCode()).isEqualTo(1205);
        assertThat(result.logRecord().toString()).contains("java.sql.SQLException","HY000","1205").doesNotContain("UPDATE","SECRET","JDBC params");
    }
    @Test void sqlNextExceptionIsBoundedAndCanExplainDatabaseFailure() {
        SQLException outer=new SQLException("SECRET_ROOT","08006",0);
        SQLException next=new SQLTimeoutException("SECRET_NEXT","HYT00",0);outer.setNextException(next);
        var result=inspect(outer);
        assertThat(result.category()).isEqualTo(TaskFailureDiagnostic.Category.DATABASE_TIMEOUT);
        assertThat(result.causes().get(1).relation()).isEqualTo("SQL_NEXT");
        assertThat(result.logRecord().toString()).doesNotContain("SECRET_ROOT","SECRET_NEXT");
    }
    @Test void connectionAndNetworkFailuresAreNotMisrepresentedAsBadFiles() {
        assertThat(inspect(new SQLNonTransientConnectionException("jdbc:mysql://secret","08001",2003)).category()).isEqualTo(TaskFailureDiagnostic.Category.DATABASE_UNAVAILABLE);
        var timeout=inspect(new IOException("private file path",new SocketTimeoutException("secret provider body")));
        assertThat(timeout.category()).isEqualTo(TaskFailureDiagnostic.Category.TIMEOUT);
        assertThat(timeout.userMessage()).contains("请求超时").doesNotContain("文件格式","private","secret");
    }
    @Test void establishedBusinessMessageIsReconstructedFromFixedMappingAndHasTraceId() {
        String safe="检查引擎已升级，请重新发起检查，历史结果保持原样";
        var result=inspect(new IllegalArgumentException(safe));
        assertThat(result.userMessage()).startsWith(safe).contains(result.traceId());
        assertThat(result.causes().get(0).internalCode()).isEqualTo("ENGINE_VERSION_CHANGED");
        assertThat(result.logRecord().toString()).doesNotContain(safe);
        var scoped=inspect(new ResponseStatusException(HttpStatus.BAD_REQUEST,"资料不属于当前项目"));
        assertThat(scoped.userMessage()).startsWith("资料不属于当前项目");
    }
    @Test void untrustedExceptionTextAndArbitraryUppercaseCodesAreNeverEmitted() {
        var result=TaskFailureDiagnostic.inspect("SECRET_TASK\nINJECTED",TaskFailureDiagnostic.Phase.EXECUTION,new IllegalArgumentException("FACT_SECRET_KEY"));
        assertThat(result.taskId()).isEqualTo("UNAVAILABLE");assertThat(result.causes().get(0).internalCode()).isEmpty();
        assertThat(result.logRecord().toString()+result.userMessage()).doesNotContain("SECRET","INJECTED");
        assertThat(inspect(new IllegalStateException("FACT_COUNT_LIMIT")).causes().get(0).internalCode()).isEqualTo("FACT_COUNT_LIMIT");
    }
    @Test void throwableCyclesAndLongChainsCannotExpandDiagnosticWithoutBounds() {
        RuntimeException first=new RuntimeException("SECRET_1"),second=new RuntimeException("SECRET_2");first.initCause(second);second.initCause(first);
        assertThat(inspect(first).causes()).hasSize(2);
        Throwable chain=new RuntimeException("SECRET_LEAF");for(int i=0;i<20;i++)chain=new RuntimeException("SECRET_WRAPPER",chain);
        var result=inspect(chain);assertThat(result.causes()).hasSize(8);assertThat(result.chainTruncated()).isTrue();
        assertThat(result.logRecord().toString()).doesNotContain("SECRET");
    }
    @Test void framesOmitFileNamesForeignCodeAndUnsafeIdentifiers() {
        Exception failure=new Exception("SECRET_SOURCE_TEXT");
        List<StackTraceElement> frames=new ArrayList<>();
        frames.add(new StackTraceElement("evil.SecretClass","expose","C:/private/SECRET_KEY.txt",7));
        frames.add(new StackTraceElement("cn.bidcheck.SELECT secret","method","SECRET_DOC",9));
        for(int i=0;i<9;i++)frames.add(new StackTraceElement("cn.bidcheck.service.BidService","execute","SECRET_FILENAME.java",12+i));
        failure.setStackTrace(frames.toArray(StackTraceElement[]::new));
        var result=inspect(failure);
        assertThat(result.causes().get(0).frames()).hasSize(4).contains("cn.bidcheck.service.BidService#execute:12");
        assertThat(result.logRecord().toString()).doesNotContain("SECRET","evil.","SELECT",".java","C:/");
    }
    @Test void sqlStateIsValidatedInsteadOfBlindlyLogged() {
        var result=inspect(new SQLException("secret query","SECRET_SQL_STATE\nINJECTED",1064));
        assertThat(result.causes().get(0).sqlState()).isEqualTo("UNKNOWN");
        assertThat(result.causes().get(0).vendorCode()).isEqualTo(1064);
        assertThat(result.logRecord().toString()).doesNotContain("SECRET","INJECTED","secret query");
    }
    @Test void ocrProviderCodesAndResponseReasonsCannotLeakPayloads() {
        class ProviderFailure extends OcrFailure { ProviderFailure(String code,Integer status){super("SECRET_PROVIDER_BODY",code,status,false);} }
        var known=inspect(new ProviderFailure("QUEUE_FULL",null));
        assertThat(known.causes().get(0).internalCode()).isEqualTo("OCR_QUEUE_FULL");
        var unknown=inspect(new ProviderFailure("SECRET_PROVIDER_CODE",null));
        assertThat(unknown.causes().get(0).internalCode()).isEmpty();
        assertThat(inspect(new ProviderFailure(null,503)).causes().get(0).internalCode()).isEqualTo("OCR_HTTP_503");
        assertThat(unknown.logRecord().toString()+unknown.userMessage()).doesNotContain("SECRET");
        assertThat(inspect(new ResponseStatusException(HttpStatus.BAD_GATEWAY,"SECRET_PROVIDER_BODY")).userMessage()).doesNotContain("SECRET");
    }
    @Test void infrastructureCauseTakesPriorityOverAnOuterBusinessLookingMessage() {
        var result=inspect(new IllegalArgumentException("检查引擎已升级，请重新发起检查，历史结果保持原样",new SQLTimeoutException("SECRET")));
        assertThat(result.category()).isEqualTo(TaskFailureDiagnostic.Category.DATABASE_TIMEOUT);
        assertThat(result.userMessage()).doesNotContain("检查引擎已升级");
    }
    @Test void standaloneTransactionTimeoutIsADatabaseFailureWithoutNeedingSqlCause() {
        var result=inspect(new TransactionTimedOutException("SECRET_TRANSACTION_DETAILS"));
        assertThat(result.category()).isEqualTo(TaskFailureDiagnostic.Category.DATABASE_TIMEOUT);
        assertThat(result.userMessage()).contains("数据库操作超时").doesNotContain("文件格式","SECRET");
        assertThat(result.logRecord().toString()).contains("TransactionTimedOutException").doesNotContain("SECRET");
    }
    @Test void publicationIntegrityCodesArePreciselyAllowlistedWithoutAdmittingArbitraryPrefixes() {
        List<String> codes=List.of("FACT_PUBLICATION_INCOMPLETE","FACT_PUBLICATION_CHANGED","FACT_PUBLICATION_REJECTED",
                "FACT_SNAPSHOT_PREPARATION_OWNER_INVALID","CONSISTENCY_PUBLICATION_REQUIRES_NO_TRANSACTION",
                "CONSISTENCY_PUBLICATION_RUN_CHANGED","CONSISTENCY_PUBLICATION_INVALID","CONSISTENCY_BATCH_SCOPE_MISMATCH",
                "CONSISTENCY_BATCH_SET_CHANGED","CONSISTENCY_BATCH_CHANGED","CONSISTENCY_BATCH_PLAN_MISMATCH","CONSISTENCY_BATCH_POLICY_INVALID");
        for(String code:codes){var result=inspect(new IllegalStateException(code));
            assertThat(result.causes().get(0).internalCode()).as(code).isEqualTo(code);
            assertThat(result.category()).as(code).isEqualTo(TaskFailureDiagnostic.Category.SOURCE_VALIDATION);
            assertThat(result.userMessage()).doesNotContain(code,"文件格式");}
        for(String text:List.of("CONSISTENCY_BATCH_SECRET_SOURCE","CONSISTENCY_PUBLICATION_SECRET_KEY",
                "FACT_PUBLICATION_INCOMPLETE\nSECRET","FACT_PUBLICATION_CHANGED SECRET_SQL")){
            var result=inspect(new IllegalStateException(text));
            assertThat(result.causes().get(0).internalCode()).isEmpty();
            assertThat(result.logRecord().toString()+result.userMessage()).doesNotContain("SECRET");
        }
    }
}
