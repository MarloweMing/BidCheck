package cn.bidcheck.verification.engine;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Ordered grades apply only within the already source-bound certificate category. */
final class CredentialGrades {
    private CredentialGrades() {}
    static Boolean meets(String category,String value,String expected,String operator,String source) {
        String actual=normalize(value),required=normalize(expected),op=Objects.toString(operator,"");
        if(cn.bidcheck.compat.Java8Api.isBlank(op)||op.equals("="))return actual.equals(required);
        if(!op.equals(">="))return null;
        String quote=Objects.toString(source,"").replaceAll("\\s+","");
        String grade=Pattern.quote(Objects.toString(expected,"").replaceAll("\\s+",""));
        if(!quote.matches("(?s).*(?:"+grade+"(?:[（(]含[）)])?(?:以上|及以上)|(?:不低于|至少|最低为)"+grade+").*"))return null;
        String kind=Objects.toString(category,"");
        List<String> ordered;
        if(kind.matches(".*(?:工程设计|建筑行业|建筑工程专业|设计专项|专项设计|装饰设计).*"))ordered=cn.bidcheck.compat.Java8Api.listOf("丙级", "乙级", "甲级");
        else if(kind.matches(".*(?:施工总承包|专业承包).*"))ordered=cn.bidcheck.compat.Java8Api.listOf("三级", "二级", "一级", "特级");
        else return null;
        int have=ordered.indexOf(actual),need=ordered.indexOf(required);
        return have<0||need<0?null:have>=need;
    }
    static String normalize(String value) {
        return Objects.toString(value,"").replaceAll("\\s+","").replace("壹级","一级").replace("贰级","二级").replace("叁级","三级");
    }
}
