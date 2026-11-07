package cn.bidcheck.verification.source;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** GB 11643-1999 identity checks shared by source and table readers. */
public final class CitizenId {
    private static final int[] WEIGHTS={7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};
    private CitizenId(){}
    private static final Pattern PRINTED=Pattern.compile("(?<![0-9])[0-9]{17}[0-9Xx](?![0-9])");
    private static final Pattern ID_FIELD=Pattern.compile("(?i)(?:身份证(?:件号码或证书编号|号码|件号码|号)?|公民身份号码|社会保障号码|CN_ID|identityNumber|idNumber)(?:[\\s:：\"'\\\\|,=\\[\\]{}*]|value|rawValue|text){0,35}$");
    private static Set<String> protectedValues(String text){
        Set<String> result=new HashSet<>();Matcher numbers=PRINTED.matcher(text);
        while(numbers.find()){
            String n=numbers.group().toUpperCase(Locale.ROOT);
            if(valid(n)||ID_FIELD.matcher(text.substring(Math.max(0,numbers.start()-180),numbers.start()).replaceAll("<[^>]{1,100}>"," ").replace("&nbsp;"," ").replace("\\n"," ").replace("\\r"," ")).find())result.add(n);
        }
        return result;
    }
    private static String mask(String value,Set<String> protectedIds){
        if(protectedIds.isEmpty())return value;
        Matcher matcher=PRINTED.matcher(value);StringBuffer result=new StringBuffer();
        while(matcher.find())if(protectedIds.contains(matcher.group().toUpperCase(Locale.ROOT)))matcher.appendReplacement(result,"[证件号码已隐藏]");
        return matcher.appendTail(result).toString();
    }
    public static String mask(String value){return mask(value,protectedValues(value));}
    public static String maskJson(String json){
        Set<String> protectedIds=protectedValues(json);
        if(protectedIds.isEmpty())return json;
        com.fasterxml.jackson.core.JsonFactory factory=new com.fasterxml.jackson.core.JsonFactory();
        java.io.StringWriter result=new java.io.StringWriter();
        try(com.fasterxml.jackson.core.JsonParser reader=factory.createParser(json);
            com.fasterxml.jackson.core.JsonGenerator writer=factory.createGenerator(result)){
            for(com.fasterxml.jackson.core.JsonToken t=reader.nextToken();t!=null;t=reader.nextToken()){
                if(t==com.fasterxml.jackson.core.JsonToken.VALUE_STRING)writer.writeString(mask(reader.getText(),protectedIds));
                else if(t==com.fasterxml.jackson.core.JsonToken.FIELD_NAME)writer.writeFieldName(mask(reader.getText(),protectedIds));
                else if(t==com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT&&protectedIds.contains(reader.getText()))writer.writeString("[证件号码已隐藏]");
                else writer.copyCurrentEvent(reader);
            }
        }catch(java.io.IOException invalid){throw new IllegalStateException("报告内容无法导出",invalid);}
        return result.toString();
    }
    public static boolean valid(String raw){
        if(raw==null||!raw.matches("[0-9]{17}[0-9Xx]"))return false;
        String id=raw.toUpperCase(Locale.ROOT);
        try {LocalDate.of(Integer.parseInt(id.substring(6,10)),Integer.parseInt(id.substring(10,12)),Integer.parseInt(id.substring(12,14)));}
        catch(RuntimeException badDate){return false;}
        int sum=0;for(int i=0;i<17;i++)sum+=(id.charAt(i)-'0')*WEIGHTS[i];
        return id.charAt(17)=="10X98765432".charAt(sum%11);
    }
}
