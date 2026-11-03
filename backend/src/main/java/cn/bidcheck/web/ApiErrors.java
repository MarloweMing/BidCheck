package cn.bidcheck.web;

import cn.bidcheck.document.DocumentLimits;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException e) { return ResponseEntity.status(e.getStatus()).body(cn.bidcheck.compat.Java8Api.mapOf("message", e.getReason()==null?"请求无法完成":e.getReason())); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) public ResponseEntity<?> upload(MaxUploadSizeExceededException e) { return ResponseEntity.status(413).body(cn.bidcheck.compat.Java8Api.mapOf("message", "文件不能超过"+DocumentLimits.MAX_FILE_MB+"MB")); }
    @ExceptionHandler(cn.bidcheck.storage.StorageUnavailableException.class)
    public ResponseEntity<?> storage(cn.bidcheck.storage.StorageUnavailableException e) { return ResponseEntity.status(503).body(cn.bidcheck.compat.Java8Api.mapOf("message", e.getMessage())); }
    @ExceptionHandler({IllegalArgumentException.class,IOException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(cn.bidcheck.compat.Java8Api.mapOf("message", e instanceof IOException?"文件读取失败，请检查格式和页码":"请求内容无效，请检查输入")); }
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.web.multipart.support.MissingServletRequestPartException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> missing(Exception e) { return ResponseEntity.badRequest().body(cn.bidcheck.compat.Java8Api.mapOf("message", "缺少必要参数或参数格式无效")); }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> method(Exception e) { return ResponseEntity.status(405).body(cn.bidcheck.compat.Java8Api.mapOf("message", "请求方法不支持")); }
    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    public ResponseEntity<?> missingResource(Exception e) { return ResponseEntity.status(404).body(cn.bidcheck.compat.Java8Api.mapOf("message", "资源不存在")); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<?> unavailable(Exception e) { return ResponseEntity.status(503).body(cn.bidcheck.compat.Java8Api.mapOf("message", "数据服务暂不可用，请稍后重试")); }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> other(Exception e) {
        String incident=java.util.UUID.randomUUID().toString();
        log.error("Request failed incident={} type={}",incident,e.getClass().getSimpleName());
        return ResponseEntity.status(500).body(cn.bidcheck.compat.Java8Api.mapOf("message", "服务处理失败，请稍后重试", "incidentId", incident));
    }
}
