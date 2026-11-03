package cn.bidcheck.service;

import cn.bidcheck.ocr.OcrFailure;
import cn.bidcheck.storage.StorageUnavailableException;
import java.io.*;
import java.net.SocketTimeoutException;
import cn.bidcheck.compat.http.HttpTimeoutException;
import java.nio.file.NoSuchFileException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.server.ResponseStatusException;

/** Only bounded diagnostic metadata crosses this boundary; never serialize or log a Throwable. */
public final class TaskFailureDiagnostic {
    private TaskFailureDiagnostic() { }
    public enum Phase { EXECUTION, FAILURE_RECORD, LEASE_RENEWAL, LEASE_RECOVERY, DISPATCH, LEASE_CLEAR }
    public enum Category { DATABASE_TIMEOUT, DATABASE_UNAVAILABLE, DATABASE_ERROR, TIMEOUT, INTERRUPTED,
        STORAGE_UNAVAILABLE, OCR_SERVICE, SOURCE_VALIDATION, INPUT_OR_CONFIGURATION, FILE_OR_IO, INTERNAL }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Cause {
        private final String type;
        private final String relation;
        private final String internalCode;
        private final String sqlState;
        private final Integer vendorCode;
        private final List<String> frames;
        public String type() { return type; }
        public String relation() { return relation; }
        public String internalCode() { return internalCode; }
        public String sqlState() { return sqlState; }
        public Integer vendorCode() { return vendorCode; }
        public List<String> frames() { return frames; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Cause that = (Cause) other; return java.util.Objects.equals(this.type, that.type) && java.util.Objects.equals(this.relation, that.relation) && java.util.Objects.equals(this.internalCode, that.internalCode) && java.util.Objects.equals(this.sqlState, that.sqlState) && java.util.Objects.equals(this.vendorCode, that.vendorCode) && java.util.Objects.equals(this.frames, that.frames); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.type); hash = 31 * hash + java.util.Objects.hashCode(this.relation); hash = 31 * hash + java.util.Objects.hashCode(this.internalCode); hash = 31 * hash + java.util.Objects.hashCode(this.sqlState); hash = 31 * hash + java.util.Objects.hashCode(this.vendorCode); hash = 31 * hash + java.util.Objects.hashCode(this.frames); return hash; }
        @Override public String toString() { return "Cause[" + "type=" + type + ", relation=" + relation + ", internalCode=" + internalCode + ", sqlState=" + sqlState + ", vendorCode=" + vendorCode + ", frames=" + frames + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Cause(@com.fasterxml.jackson.annotation.JsonProperty("type") String type, @com.fasterxml.jackson.annotation.JsonProperty("relation") String relation, @com.fasterxml.jackson.annotation.JsonProperty("internalCode") String internalCode, @com.fasterxml.jackson.annotation.JsonProperty("sqlState") String sqlState, @com.fasterxml.jackson.annotation.JsonProperty("vendorCode") Integer vendorCode, @com.fasterxml.jackson.annotation.JsonProperty("frames") List<String> frames) { frames=cn.bidcheck.compat.Java8Api.listCopyOf(frames);
            this.type = type; this.relation = relation; this.internalCode = internalCode; this.sqlState = sqlState; this.vendorCode = vendorCode; this.frames = frames;
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public static final class Diagnostic {
        private final String traceId;
        private final String taskId;
        private final Phase phase;
        private final Category category;
        private final List<Cause> causes;
        private final boolean chainTruncated;
        private final String userMessage;
        public String traceId() { return traceId; }
        public String taskId() { return taskId; }
        public Phase phase() { return phase; }
        public Category category() { return category; }
        public List<Cause> causes() { return causes; }
        public boolean chainTruncated() { return chainTruncated; }
        public String userMessage() { return userMessage; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Diagnostic that = (Diagnostic) other; return java.util.Objects.equals(this.traceId, that.traceId) && java.util.Objects.equals(this.taskId, that.taskId) && java.util.Objects.equals(this.phase, that.phase) && java.util.Objects.equals(this.category, that.category) && java.util.Objects.equals(this.causes, that.causes) && java.util.Objects.equals(this.chainTruncated, that.chainTruncated) && java.util.Objects.equals(this.userMessage, that.userMessage); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.traceId); hash = 31 * hash + java.util.Objects.hashCode(this.taskId); hash = 31 * hash + java.util.Objects.hashCode(this.phase); hash = 31 * hash + java.util.Objects.hashCode(this.category); hash = 31 * hash + java.util.Objects.hashCode(this.causes); hash = 31 * hash + java.util.Objects.hashCode(this.chainTruncated); hash = 31 * hash + java.util.Objects.hashCode(this.userMessage); return hash; }
        @Override public String toString() { return "Diagnostic[" + "traceId=" + traceId + ", taskId=" + taskId + ", phase=" + phase + ", category=" + category + ", causes=" + causes + ", chainTruncated=" + chainTruncated + ", userMessage=" + userMessage + "]"; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public Diagnostic(@com.fasterxml.jackson.annotation.JsonProperty("traceId") String traceId, @com.fasterxml.jackson.annotation.JsonProperty("taskId") String taskId, @com.fasterxml.jackson.annotation.JsonProperty("phase") Phase phase, @com.fasterxml.jackson.annotation.JsonProperty("category") Category category, @com.fasterxml.jackson.annotation.JsonProperty("causes") List<Cause> causes, @com.fasterxml.jackson.annotation.JsonProperty("chainTruncated") boolean chainTruncated, @com.fasterxml.jackson.annotation.JsonProperty("userMessage") String userMessage) { causes=cn.bidcheck.compat.Java8Api.listCopyOf(causes);
            this.traceId = traceId; this.taskId = taskId; this.phase = phase; this.category = category; this.causes = causes; this.chainTruncated = chainTruncated; this.userMessage = userMessage;
        }
        public Map<String,Object> logRecord() {
            // User-facing business descriptions deliberately stay out of the technical log.
            return cn.bidcheck.compat.Java8Api.mapOf("traceId", traceId, "taskId", taskId, "phase", phase.name(), "category", category.name(), "causes", causes, "chainTruncated", chainTruncated);
        }
    }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Business {
        private final String code;
        private final Category category;
        private final String text;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Business(@com.fasterxml.jackson.annotation.JsonProperty("code") String code, @com.fasterxml.jackson.annotation.JsonProperty("category") Category category, @com.fasterxml.jackson.annotation.JsonProperty("text") String text) { this.code = code; this.category = category; this.text = text; }
        public String code() { return code; }
        public Category category() { return category; }
        public String text() { return text; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Business that = (Business) other; return java.util.Objects.equals(this.code, that.code) && java.util.Objects.equals(this.category, that.category) && java.util.Objects.equals(this.text, that.text); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.code); hash = 31 * hash + java.util.Objects.hashCode(this.category); hash = 31 * hash + java.util.Objects.hashCode(this.text); return hash; }
        @Override public String toString() { return "Business[" + "code=" + code + ", category=" + category + ", text=" + text + "]"; }
 }
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    private static final class Node {
        private final Throwable error;
        private final String relation;
        @com.fasterxml.jackson.annotation.JsonCreator
        private Node(@com.fasterxml.jackson.annotation.JsonProperty("error") Throwable error, @com.fasterxml.jackson.annotation.JsonProperty("relation") String relation) { this.error = error; this.relation = relation; }
        public Throwable error() { return error; }
        public String relation() { return relation; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (other == null || getClass() != other.getClass()) return false; Node that = (Node) other; return java.util.Objects.equals(this.error, that.error) && java.util.Objects.equals(this.relation, that.relation); }
        @Override public int hashCode() { int hash = 0; hash = 31 * hash + java.util.Objects.hashCode(this.error); hash = 31 * hash + java.util.Objects.hashCode(this.relation); return hash; }
        @Override public String toString() { return "Node[" + "error=" + error + ", relation=" + relation + "]"; }
 }
    private static final int MAX_CAUSES=8,MAX_FRAMES=4;
    private static final Set<String> CODES=cn.bidcheck.compat.Java8Api.setOf("FACT_ENTITY_MISSING", "FACT_ENTITY_SCOPE_MISMATCH", "FACT_SOURCE_SCOPE_MISMATCH", "FACT_EVIDENCE_MISSING", "FACT_RELATION_MISSING", "FACT_ALREADY_EXISTS", "ENTITY_IMMUTABILITY_VIOLATION", "EVIDENCE_IMMUTABILITY_VIOLATION", "FACT_JSON_INVALID", "FACT_JSON_UNREADABLE", "FACT_BATCH_LIMIT", "FACT_SOURCE_LIMIT", "FACT_COUNT_LIMIT", "FACT_OPERATION_INVALID", "FACT_SCOPE_ID_INVALID", "FACT_PREPARATION_OWNER_INVALID", "FACT_RUN_SNAPSHOT_FROZEN", "FACT_EXISTING_SCOPE_INVALID", "FACT_SNAPSHOT_PREPARATION_REQUIRES_NO_TRANSACTION", "SOURCE_OUTSIDE_MANIFEST", "SOURCE_ID_DUPLICATE", "FACT_SOURCE_OR_TYPE_INVALID", "FACT_RAW_VALUE_UNGROUNDED", "FACT_ATTRIBUTES_LIMIT", "FACT_PUBLICATION_INCOMPLETE", "FACT_PUBLICATION_CHANGED", "FACT_PUBLICATION_REJECTED", "FACT_SNAPSHOT_PREPARATION_OWNER_INVALID", "CONSISTENCY_PUBLICATION_REQUIRES_NO_TRANSACTION", "CONSISTENCY_PUBLICATION_RUN_CHANGED", "CONSISTENCY_PUBLICATION_INVALID", "CONSISTENCY_BATCH_SCOPE_MISMATCH", "CONSISTENCY_BATCH_SET_CHANGED", "CONSISTENCY_BATCH_CHANGED", "CONSISTENCY_BATCH_PLAN_MISMATCH", "CONSISTENCY_BATCH_POLICY_INVALID", "SOURCE_HASH_MISMATCH", "WORD_STAGING_PATH_INVALID", "WORD_PACKAGE_LIMIT", "WORD_XML_LIMIT", "WORD_MAIN_PART_MISSING");
    private static final Set<String> OCR_CODES=cn.bidcheck.compat.Java8Api.setOf("TIMEOUT", "QUEUE_TIMEOUT", "QUEUE_FULL", "CIRCUIT_OPEN", "BATCH_BUDGET_EXHAUSTED", "SHARED_REQUEST_CANCELLED", "INVALID_REQUEST", "INVALID_RESPONSE", "RESPONSE_LIMIT", "INVALID_BATCH", "INVALID_IMAGE", "IMAGE_DIMENSION_MISMATCH", "BATCH_DIMENSION_MISMATCH", "BATCH_EMPTY_TEXT", "BATCH_HASH_MISMATCH", "BATCH_INPUT_LIMIT", "BATCH_INVALID_IDS", "BATCH_INVALID_ITEM", "BATCH_INVALID_RESPONSE", "BATCH_TEXT_INVALID", "BATCH_TEXT_SCHEMA", "BATCH_TRUNCATED");
    private static final Map<String,Business> BUSINESS=businessMessages();

    public static Diagnostic inspect(String taskId,Phase phase,Throwable failure) {
        Objects.requireNonNull(failure);Objects.requireNonNull(phase);
        List<Throwable> errors=new ArrayList<>();List<Cause> causes=new ArrayList<>();
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Node> pending=new ArrayDeque<>();pending.add(new Node(failure,"ROOT"));seen.add(failure);
        while(!pending.isEmpty()&&causes.size()<MAX_CAUSES) {
            Node node=pending.removeFirst();Throwable error=node.error();errors.add(error);
            String state="";Integer vendor=null;
            if(error instanceof SQLException){state=sqlState(((SQLException) (error)));vendor=((SQLException) (error)).getErrorCode();}
            causes.add(new Cause(safeType(error.getClass().getName()),node.relation(),internalCode(error),state,vendor,frames(error)));
            Throwable cause=error.getCause();if(cause!=null&&seen.add(cause))pending.addLast(new Node(cause,"CAUSE"));
            if(error instanceof SQLException){SQLException next=((SQLException) (error)).getNextException();if(next!=null&&seen.add(next))pending.addLast(new Node(next,"SQL_NEXT"));}
        }
        Category category=category(errors);Business business=null;
        if(!cn.bidcheck.compat.Java8Api.setOf(Category.DATABASE_TIMEOUT, Category.DATABASE_UNAVAILABLE, Category.DATABASE_ERROR, Category.TIMEOUT, Category.INTERRUPTED).contains(category)) {
            for(Throwable error:errors){business=business(error);if(business!=null){category=business.category();break;}}
        }
        String trace="taskdiag-"+UUID.randomUUID();
        String description=business==null?description(category):business.text();
        return new Diagnostic(trace,taskId!=null&&taskId.matches("[a-fA-F0-9-]{36}")?taskId:"UNAVAILABLE",phase,category,causes,!pending.isEmpty(),
                description+"（诊断编号："+trace+"）");
    }
    private static Category category(List<Throwable> errors) {
        boolean database=errors.stream().anyMatch(e->e instanceof SQLException||e instanceof DataAccessException||e instanceof TransactionTimedOutException);
        if(database) {
            if(errors.stream().anyMatch(e->e instanceof SQLTimeoutException||e instanceof QueryTimeoutException||e instanceof TransactionTimedOutException
                    ||e instanceof SQLException&&(((SQLException) (e)).getErrorCode()==1205||((SQLException) (e)).getErrorCode()==1213||sqlState(((SQLException) (e))).startsWith("40"))))return Category.DATABASE_TIMEOUT;
            if(errors.stream().anyMatch(e->e instanceof SQLTransientConnectionException||e instanceof SQLNonTransientConnectionException||e instanceof DataAccessResourceFailureException
                    ||e instanceof SQLException&&sqlState(((SQLException) (e))).startsWith("08")))return Category.DATABASE_UNAVAILABLE;
            return Category.DATABASE_ERROR;
        }
        if(errors.stream().anyMatch(e->e instanceof TimeoutException||e instanceof SocketTimeoutException||e instanceof HttpTimeoutException))return Category.TIMEOUT;
        if(errors.stream().anyMatch(e->e instanceof InterruptedException||e instanceof InterruptedIOException||e instanceof CancellationException))return Category.INTERRUPTED;
        if(errors.stream().anyMatch(e->e instanceof StorageUnavailableException))return Category.STORAGE_UNAVAILABLE;
        if(errors.stream().anyMatch(e->e instanceof OcrFailure))return Category.OCR_SERVICE;
        if(errors.stream().anyMatch(e->CODES.contains(exactMessage(e))))return Category.SOURCE_VALIDATION;
        if(errors.stream().anyMatch(e->e instanceof IllegalArgumentException||e instanceof ResponseStatusException&&((ResponseStatusException) (e)).getStatus().is4xxClientError()))return Category.INPUT_OR_CONFIGURATION;
        if(errors.stream().anyMatch(e->e instanceof IOException||e instanceof NoSuchFileException))return Category.FILE_OR_IO;
        return Category.INTERNAL;
    }
    private static String description(Category category) {
        switch (category) {case DATABASE_TIMEOUT:return "数据库操作超时或事务冲突，任务未完成，请稍后重试；持续失败时请提供诊断编号。";case DATABASE_UNAVAILABLE:return "数据库连接暂不可用，任务未完成，请恢复数据库服务后重试。";case DATABASE_ERROR:return "数据保存或读取失败，任务未完成，请联系维护人员核对诊断记录。";case TIMEOUT:return "处理请求超时，任务未完成，请稍后重试。";case INTERRUPTED:return "任务执行被中断，尚未完成，请核对任务恢复状态。";case STORAGE_UNAVAILABLE:return "文件存储服务暂不可用，请检查存储服务后重试。";case OCR_SERVICE:return "文字识别请求未完成，请核对识别服务状态后重试。";case SOURCE_VALIDATION:return "检查结果或来源校验未通过，请联系维护人员核对诊断记录。";case INPUT_OR_CONFIGURATION:return "任务输入或配置校验未通过，请核对任务配置并提供诊断编号。";case FILE_OR_IO:return "文件读取或服务通信未完成，请核对存储及服务状态。";case INTERNAL:return "任务处理出现内部错误，尚未完成，请联系维护人员并提供诊断编号。";default:throw new IncompatibleClassChangeError();}
    }
    private static String exactMessage(Throwable error) {
        String text=error.getMessage();return text!=null&&text.length()<=200?text:"";
    }
    private static Business business(Throwable error) {
        String value=error instanceof ResponseStatusException?((ResponseStatusException) (error)).getReason():exactMessage(error);
        return value==null||value.length()>200?null:BUSINESS.get(value);
    }
    private static String internalCode(Throwable error) {
        String value=exactMessage(error);if(CODES.contains(value))return value;
        Business business=business(error);if(business!=null)return business.code();
        if(error instanceof OcrFailure){String code=((OcrFailure) (error)).code();if(code!=null&&OCR_CODES.contains(code))return "OCR_"+code;
            Integer status=((OcrFailure) (error)).httpStatus();if(status!=null&&status>=400&&status<=599)return "OCR_HTTP_"+status;}
        return "";
    }
    private static String sqlState(SQLException error) {String value=error.getSQLState();return value!=null&&value.matches("[A-Z0-9]{5}")?value:"UNKNOWN";}
    private static String safeType(String type) {return type!=null&&type.length()<=200&&type.matches("[A-Za-z0-9_.$]+")?type:"UNAVAILABLE";}
    private static List<String> frames(Throwable error) {
        List<String> application=new ArrayList<>(),infrastructure=new ArrayList<>();
        int visited=0;
        for(StackTraceElement frame:error.getStackTrace()) {
            if(++visited>192)break;
            String type=frame.getClassName(),method=frame.getMethodName();
            if(type.length()>180||!type.matches("[A-Za-z0-9_.$]+")||!method.matches("[A-Za-z0-9_$]{1,80}|<init>|<clinit>"))continue;
            if(!cn.bidcheck.compat.Java8Api.listOf("cn.bidcheck.", "org.apache.ibatis.", "org.mybatis.", "org.springframework.", "java.sql.", "com.mysql.cj.", "org.h2.").stream().anyMatch(type::startsWith))continue;
            String value=type+"#"+method+":"+(frame.getLineNumber()>=0&&frame.getLineNumber()<1_000_000?frame.getLineNumber():"?");
            List<String> target=type.startsWith("cn.bidcheck.")?application:infrastructure;
            if(target.size()<MAX_FRAMES)target.add(value);
        }
        // JDBC/MyBatis wrappers can occupy dozens of frames. Reserve application call sites so
        // the safe bounded record still identifies the failed publication, without raw SQL/text.
        List<String> frames=new ArrayList<>(application.subList(0,Math.min(2,application.size())));
        for(String frame:infrastructure)if(frames.size()<MAX_FRAMES)frames.add(frame);
        for(int i=2;i<application.size()&&frames.size()<MAX_FRAMES;i++)frames.add(application.get(i));
        return frames;
    }
    private static Map<String,Business> businessMessages() {
        Map<String,Business> values=new LinkedHashMap<>();
        add(values,"ENGINE_VERSION_CHANGED",Category.INPUT_OR_CONFIGURATION,"检查引擎已升级，请重新发起检查，历史结果保持原样");
        add(values,"SEMANTIC_CONFIGURATION_CHANGED",Category.INPUT_OR_CONFIGURATION,"语义服务配置已变更，请新建检查任务，避免混用不同模型的结论");
        add(values,"PROCESSING_CONFIGURATION_CHANGED",Category.INPUT_OR_CONFIGURATION,"处理配置已变化，请重新发起继续处理");
        add(values,"DOCUMENT_PROJECT_MISMATCH",Category.INPUT_OR_CONFIGURATION,"资料不属于当前项目");
        add(values,"DOCUMENT_SCOPE_MISMATCH",Category.INPUT_OR_CONFIGURATION,"该文件不属于当前项目或类型不符");
        add(values,"DOCUMENT_NOT_FOUND",Category.INPUT_OR_CONFIGURATION,"文档不存在");
        add(values,"MIGRATION_SCOPE_LIMIT",Category.INPUT_OR_CONFIGURATION,"迁移资料超过单次范围，请按项目分批处理");
        add(values,"SOURCE_HASH_MISMATCH",Category.SOURCE_VALIDATION,"原始文件 SHA-256 与已登记证据不一致，已拒绝解析和下载，请核对存储数据。");
        add(values,"SOURCE_HASH_MISSING",Category.SOURCE_VALIDATION,"原始文件缺少有效的完整性校验值，无法读取。");
        add(values,"SOURCE_MISSING",Category.FILE_OR_IO,"原始文件不存在，请重新上传。");
        add(values,"SOURCE_UNREADABLE",Category.FILE_OR_IO,"原始文件不存在或不可读取。");
        add(values,"SOURCE_EMPTY_OR_LIMIT",Category.FILE_OR_IO,"原始文件不能为空，且不能超过 1024 MB。");
        add(values,"SOURCE_SIZE_LIMIT",Category.FILE_OR_IO,"原始文件超过 1024 MB 限制。");
        add(values,"SOURCE_NOT_PERSISTED",Category.FILE_OR_IO,"原始文件尚未持久化，不能开始解析。");
        add(values,"SOURCE_LENGTH_MISMATCH",Category.FILE_OR_IO,"上传文件长度不一致，请重新上传。");
        add(values,"STORAGE_CLOCK_SKEW",Category.STORAGE_UNAVAILABLE,"文件存储服务器与本机时间不同步，请校准虚拟机时间后重试上传。");
        add(values,"STORAGE_WRITE_FAILED",Category.STORAGE_UNAVAILABLE,"原始文件写入 MinIO 失败；未创建解析任务，请检查对象存储及虚拟机时间后重试。");
        add(values,"STORAGE_READ_FAILED",Category.STORAGE_UNAVAILABLE,"无法从 MinIO 读取原始文件，或文件为空、损坏、超过 1024 MB，请检查存储后重试。");
        add(values,"OCR_NOT_ENABLED",Category.OCR_SERVICE,"OCR 尚未启用，请人工核对图片原件");
        add(values,"PDF_DIMENSION_INVALID",Category.FILE_OR_IO,"PDF 页面尺寸无效。");
        add(values,"CONSISTENCY_PUBLICATION_REJECTED",Category.SOURCE_VALIDATION,"一致性事实未通过原文验证，不能发布报告");
        return cn.bidcheck.compat.Java8Api.mapCopyOf(values);
    }
    private static void add(Map<String,Business> values,String code,Category category,String text) {values.put(text,new Business(code,category,text));}
}
