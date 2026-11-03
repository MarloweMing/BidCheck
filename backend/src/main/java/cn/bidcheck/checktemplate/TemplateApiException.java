package cn.bidcheck.checktemplate;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** A typed API error; callers must not infer concurrency or validation failures from prose. */
public final class TemplateApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final boolean retryable;
    private final List<Map<String,Object>> fieldErrors;
    private final Integer currentRevision;

    public TemplateApiException(HttpStatus status,String code,String message,boolean retryable,
                                List<Map<String,Object>> fieldErrors,Integer currentRevision) {
        super(message);this.status=status;this.code=code;this.retryable=retryable;
        this.fieldErrors=fieldErrors==null?cn.bidcheck.compat.Java8Api.listOf():cn.bidcheck.compat.Java8Api.listCopyOf(fieldErrors);this.currentRevision=currentRevision;
    }
    public HttpStatus status(){return status;}
    public String code(){return code;}
    public boolean retryable(){return retryable;}
    public List<Map<String,Object>> fieldErrors(){return fieldErrors;}
    public Integer currentRevision(){return currentRevision;}

    public static TemplateApiException conflict(String code,String message,int currentRevision) {
        return new TemplateApiException(HttpStatus.CONFLICT,code,message,false,cn.bidcheck.compat.Java8Api.listOf(),currentRevision);
    }
    public static TemplateApiException invalid(String message,List<Map<String,Object>> fieldErrors) {
        return new TemplateApiException(HttpStatus.BAD_REQUEST,"SPEC_VALIDATION_FAILED",message,false,fieldErrors,null);
    }
}
