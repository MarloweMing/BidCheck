package cn.bidcheck.verification.agent;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Offline JSON Schema validation for server-owned tool contracts. No URI is ever fetched. */
final class JavaToolSchema {
    static final class ValidationFailure {
        final String keyword,path;
        ValidationFailure(String keyword,String path){this.keyword=keyword;this.path=path.isEmpty()?"/":path;}
    }
    private static final Set<String> TYPES=set("object","array","string","number","integer","boolean","null");
    private static final Set<String> KEYWORDS=set("$schema","$id","$anchor","$comment","$ref","$defs","definitions","title","description","default","examples","readOnly","writeOnly","deprecated","format","contentEncoding","contentMediaType","contentSchema","type","enum","const","allOf","anyOf","oneOf","not","if","then","else","properties","patternProperties","additionalProperties","required","propertyNames","minProperties","maxProperties","dependentRequired","dependentSchemas","dependencies","items","prefixItems","contains","minContains","maxContains","uniqueItems","minItems","maxItems","minLength","maxLength","pattern","minimum","maximum","exclusiveMinimum","exclusiveMaximum","multipleOf");
    private JavaToolSchema(){}
    static void validateSchema(Map<String,Object> schema){
        if(schema==null||!"object".equals(schema.get("type")))throw invalid();
        check(schema,schema,0,new IdentityHashMap<Object,Boolean>());
    }
    static ValidationFailure validate(Map<String,Object> schema,Map<String,Object> arguments){
        validateSchema(schema);
        return test(schema,arguments,"",schema,0);
    }
    private static void check(Object raw,Map<String,Object> root,int depth,IdentityHashMap<Object,Boolean> visiting){
        if(raw instanceof Boolean)return;
        if(!(raw instanceof Map<?,?>)||depth>96||visiting.put(raw,Boolean.TRUE)!=null)throw invalid();
        try{
            Map<?,?> s=(Map<?,?>)raw;
            if(s.containsKey("$dynamicRef")){Object ref=s.get("$dynamicRef");if(!(ref instanceof String)||!((String)ref).startsWith("#"))throw new IllegalArgumentException("REMOTE_SCHEMA_REFERENCE_NOT_ALLOWED");throw invalid();}
            for(Object key:s.keySet())if(!(key instanceof String)||!KEYWORDS.contains(key))throw invalid();
            if(s.containsKey("$ref")){Object ref=s.get("$ref");if(!(ref instanceof String)||!((String)ref).startsWith("#"))throw new IllegalArgumentException("REMOTE_SCHEMA_REFERENCE_NOT_ALLOWED");Object target=resolve(root,(String)ref);if(!visiting.containsKey(target))check(target,root,depth+1,visiting);}
            for(String key:Arrays.asList("$schema","$id","$anchor","$comment","title","description","format","contentEncoding","contentMediaType"))if(s.containsKey(key)&&!(s.get(key) instanceof String))throw invalid();
            // Nested resource IDs change reference scope; this offline tool dialect
            // accepts one root resource instead of guessing those semantics.
            if(raw!=root&&s.containsKey("$id"))throw invalid();
            if(s.containsKey("$anchor")&&!((String)s.get("$anchor")).matches("[A-Za-z_][-A-Za-z0-9._]*"))throw invalid();
            for(String key:Arrays.asList("readOnly","writeOnly","deprecated","uniqueItems"))if(s.containsKey(key)&&!(s.get(key) instanceof Boolean))throw invalid();
            if(s.containsKey("type")){Object type=s.get("type");if(type instanceof String){if(!TYPES.contains(type))throw invalid();}else{List<?> types=list(type);if(types.isEmpty()||new HashSet<Object>(types).size()!=types.size()||!TYPES.containsAll(types))throw invalid();}}
            if(s.containsKey("enum")){List<?> values=list(s.get("enum"));if(values.isEmpty())throw invalid();for(int i=0;i<values.size();i++)for(int j=0;j<i;j++)if(equal(values.get(i),values.get(j)))throw invalid();}
            for(String key:Arrays.asList("required","dependentRequired")){
                if(!s.containsKey(key))continue;
                if(key.equals("required"))strings(s.get(key));
                else for(Object value:map(s.get(key)).values())strings(value);
            }
            for(String key:Arrays.asList("minLength","maxLength","minItems","maxItems","minProperties","maxProperties","minContains","maxContains"))if(s.containsKey(key)){BigDecimal n=number(s.get(key));if(n.signum()<0||n.stripTrailingZeros().scale()>0)throw invalid();}
            for(String key:Arrays.asList("minimum","maximum","exclusiveMinimum","exclusiveMaximum","multipleOf"))if(s.containsKey(key)){BigDecimal n=number(s.get(key));if(key.equals("multipleOf")&&n.signum()<=0)throw invalid();}
            if(s.containsKey("pattern")){if(!(s.get("pattern") instanceof String))throw invalid();Pattern.compile((String)s.get("pattern"));}
            for(String key:Arrays.asList("$defs","definitions","properties","patternProperties","dependentSchemas"))if(s.containsKey(key))for(Map.Entry<?,?> entry:map(s.get(key)).entrySet()){if(!(entry.getKey() instanceof String))throw invalid();if(key.equals("patternProperties"))Pattern.compile((String)entry.getKey());check(entry.getValue(),root,depth+1,visiting);}
            if(s.containsKey("dependencies"))for(Object dep:map(s.get("dependencies")).values()){if(dep instanceof Collection<?>)strings(dep);else check(dep,root,depth+1,visiting);}
            for(String key:Arrays.asList("additionalProperties","propertyNames","items","contains","not","if","then","else","contentSchema"))if(s.containsKey(key))check(s.get(key),root,depth+1,visiting);
            for(String key:Arrays.asList("allOf","anyOf","oneOf","prefixItems"))if(s.containsKey(key)){List<?> values=list(s.get(key));if(!key.equals("prefixItems")&&values.isEmpty())throw invalid();for(Object item:values)check(item,root,depth+1,visiting);}
            if(s.containsKey("examples"))list(s.get("examples"));
        }finally{visiting.remove(raw);}
    }
    private static ValidationFailure test(Object raw,Object value,String path,Map<String,Object> root,int depth){
        if(depth>128)return fail("depth",path);
        if(raw instanceof Boolean)return Boolean.TRUE.equals(raw)?null:fail("falseSchema",path);
        Map<?,?> s=(Map<?,?>)raw;ValidationFailure found;
        if(s.containsKey("$ref")){found=test(resolve(root,(String)s.get("$ref")),value,path,root,depth+1);if(found!=null)return found;}
        if(s.containsKey("type")){Object t=s.get("type");boolean matches=t instanceof String?type((String)t,value):list(t).stream().anyMatch(x->type((String)x,value));if(!matches)return fail("type",path);}
        if(s.containsKey("enum")&&!list(s.get("enum")).stream().anyMatch(x->equal(x,value)))return fail("enum",path);
        if(s.containsKey("const")&&!equal(s.get("const"),value))return fail("const",path);
        for(String key:Arrays.asList("allOf","anyOf","oneOf"))if(s.containsKey(key)){
            List<?> alternatives=list(s.get(key));int matches=0;
            for(Object alternative:alternatives)if(test(alternative,value,path,root,depth+1)==null)matches++;
            if(key.equals("allOf")&&matches!=alternatives.size()||key.equals("anyOf")&&matches==0||key.equals("oneOf")&&matches!=1)return fail(key,path);
        }
        if(s.containsKey("not")&&test(s.get("not"),value,path,root,depth+1)==null)return fail("not",path);
        if(s.containsKey("if")){String branch=test(s.get("if"),value,path,root,depth+1)==null?"then":"else";if(s.containsKey(branch)){found=test(s.get(branch),value,path,root,depth+1);if(found!=null)return found;}}
        if(value instanceof Map<?,?>){
            Map<?,?> object=(Map<?,?>)value;
            if(bound(s,"minProperties",object.size(),false)||bound(s,"maxProperties",object.size(),true))return fail(bound(s,"minProperties",object.size(),false)?"minProperties":"maxProperties",path);
            if(s.containsKey("required"))for(Object key:list(s.get("required")))if(!object.containsKey(key))return fail("required",child(path,key));
            Map<?,?> properties=s.containsKey("properties")?map(s.get("properties")):Collections.emptyMap();
            Map<?,?> patterns=s.containsKey("patternProperties")?map(s.get("patternProperties")):Collections.emptyMap();
            for(Map.Entry<?,?> entry:object.entrySet()){
                if(!(entry.getKey() instanceof String))return fail("type",path);
                String field=(String)entry.getKey(),next=child(path,field);boolean covered=false;
                if(s.containsKey("propertyNames")){found=test(s.get("propertyNames"),field,next,root,depth+1);if(found!=null)return found;}
                if(properties.containsKey(field)){covered=true;found=test(properties.get(field),entry.getValue(),next,root,depth+1);if(found!=null)return found;}
                for(Map.Entry<?,?> pattern:patterns.entrySet())if(Pattern.compile((String)pattern.getKey()).matcher(field).find()){covered=true;found=test(pattern.getValue(),entry.getValue(),next,root,depth+1);if(found!=null)return found;}
                if(!covered&&s.containsKey("additionalProperties")){if(Boolean.FALSE.equals(s.get("additionalProperties")))return fail("additionalProperties",next);found=test(s.get("additionalProperties"),entry.getValue(),next,root,depth+1);if(found!=null)return found;}
            }
            for(String key:Arrays.asList("dependentRequired","dependencies"))if(s.containsKey(key))for(Map.Entry<?,?> dep:map(s.get(key)).entrySet())if(object.containsKey(dep.getKey())){
                if(dep.getValue() instanceof Collection<?>){for(Object required:list(dep.getValue()))if(!object.containsKey(required))return fail(key,child(path,required));}
                else {found=test(dep.getValue(),value,path,root,depth+1);if(found!=null)return found;}
            }
            if(s.containsKey("dependentSchemas"))for(Map.Entry<?,?> dep:map(s.get("dependentSchemas")).entrySet())if(object.containsKey(dep.getKey())){found=test(dep.getValue(),value,path,root,depth+1);if(found!=null)return found;}
        }
        if(value instanceof Collection<?>){
            List<?> items=list(value);
            if(bound(s,"minItems",items.size(),false))return fail("minItems",path);if(bound(s,"maxItems",items.size(),true))return fail("maxItems",path);
            if(Boolean.TRUE.equals(s.get("uniqueItems")))for(int i=0;i<items.size();i++)for(int j=0;j<i;j++)if(equal(items.get(i),items.get(j)))return fail("uniqueItems",child(path,i));
            List<?> prefix=s.containsKey("prefixItems")?list(s.get("prefixItems")):Collections.emptyList();
            for(int i=0;i<items.size();i++){Object itemSchema=i<prefix.size()?prefix.get(i):s.get("items");if(itemSchema!=null){found=test(itemSchema,items.get(i),child(path,i),root,depth+1);if(found!=null)return found;}}
            if(s.containsKey("contains")){int matches=0;for(Object item:items)if(test(s.get("contains"),item,path,root,depth+1)==null)matches++;BigDecimal count=BigDecimal.valueOf(matches);if(count.compareTo(s.containsKey("minContains")?number(s.get("minContains")):BigDecimal.ONE)<0||s.containsKey("maxContains")&&count.compareTo(number(s.get("maxContains")))>0)return fail("contains",path);}
        }
        if(value instanceof String){String text=(String)value;int length=text.codePointCount(0,text.length());if(bound(s,"minLength",length,false))return fail("minLength",path);if(bound(s,"maxLength",length,true))return fail("maxLength",path);if(s.containsKey("pattern")&&!Pattern.compile((String)s.get("pattern")).matcher(text).find())return fail("pattern",path);}
        if(value instanceof Number){
            BigDecimal n;try{n=number(value);}catch(IllegalArgumentException bad){return fail("type",path);}
            for(String key:Arrays.asList("minimum","maximum","exclusiveMinimum","exclusiveMaximum"))if(s.containsKey(key)){int cmp=n.compareTo(number(s.get(key)));if(key.equals("minimum")&&cmp<0||key.equals("maximum")&&cmp>0||key.equals("exclusiveMinimum")&&cmp<=0||key.equals("exclusiveMaximum")&&cmp>=0)return fail(key,path);}
            if(s.containsKey("multipleOf")&&n.remainder(number(s.get("multipleOf"))).compareTo(BigDecimal.ZERO)!=0)return fail("multipleOf",path);
        }
        return null;
    }
    private static Object resolve(Map<String,Object> root,String ref){
        if(ref.equals("#"))return root;
        Object node=root;
        if(ref.startsWith("#/")){
            for(String token:ref.substring(2).split("/",-1)){
                // URI escapes are intentionally rejected; there is no URL decoding or resolver.
                if(token.contains("%")||token.matches(".*~(?![01]).*"))throw invalid();
                String field=token.replace("~1","/").replace("~0","~");
                if(node instanceof Map<?,?>)node=((Map<?,?>)node).get(field);
                else if(node instanceof List<?>){try{node=((List<?>)node).get(Integer.parseInt(field));}catch(RuntimeException e){throw invalid();}}
                else throw invalid();
            }
        }else if(ref.matches("#[A-Za-z_][-A-Za-z0-9._]*")){List<Object> matches=new ArrayList<>();anchors(root,ref.substring(1),matches,0);if(matches.size()!=1)throw invalid();node=matches.get(0);}
        else throw invalid();
        if(!(node instanceof Map<?,?>)&&!(node instanceof Boolean))throw invalid();return node;
    }
    private static void anchors(Object node,String name,List<Object> matches,int depth){if(depth>96)throw invalid();if(node instanceof Map<?,?>){Map<?,?> object=(Map<?,?>)node;if(name.equals(object.get("$anchor")))matches.add(node);for(Object item:object.values())anchors(item,name,matches,depth+1);}else if(node instanceof Collection<?>)for(Object item:(Collection<?>)node)anchors(item,name,matches,depth+1);}
    private static boolean type(String type,Object value){switch(type){case "null":return value==null;case "boolean":return value instanceof Boolean;case "string":return value instanceof String;case "object":return value instanceof Map<?,?>;case "array":return value instanceof Collection<?>;case "number":return finite(value);case "integer":return finite(value)&&number(value).stripTrailingZeros().scale()<=0;default:return false;}}
    private static boolean finite(Object n){if(!(n instanceof Number))return false;try{number(n);return true;}catch(IllegalArgumentException bad){return false;}}
    private static BigDecimal number(Object n){if(!(n instanceof Number))throw invalid();try{return new BigDecimal(n.toString());}catch(NumberFormatException bad){throw invalid();}}
    private static boolean bound(Map<?,?> s,String key,int size,boolean upper){if(!s.containsKey(key))return false;int compare=BigDecimal.valueOf(size).compareTo(number(s.get(key)));return upper?compare>0:compare<0;}
    private static boolean equal(Object a,Object b){if(a instanceof Number&&b instanceof Number)return finite(a)&&finite(b)&&number(a).compareTo(number(b))==0;if(a instanceof Map<?,?>&&b instanceof Map<?,?>){Map<?,?> x=(Map<?,?>)a,y=(Map<?,?>)b;if(!x.keySet().equals(y.keySet()))return false;for(Object key:x.keySet())if(!equal(x.get(key),y.get(key)))return false;return true;}if(a instanceof Collection<?> && b instanceof Collection<?>){List<?> x=list(a),y=list(b);if(x.size()!=y.size())return false;for(int i=0;i<x.size();i++)if(!equal(x.get(i),y.get(i)))return false;return true;}return Objects.equals(a,b);}
    private static Map<?,?> map(Object value){if(!(value instanceof Map<?,?>))throw invalid();return (Map<?,?>)value;}
    private static List<?> list(Object value){if(!(value instanceof Collection<?>))throw invalid();return value instanceof List<?>?(List<?>)value:new ArrayList<>((Collection<?>)value);}
    private static void strings(Object value){List<?> items=list(value);if(new HashSet<Object>(items).size()!=items.size()||items.stream().anyMatch(v->!(v instanceof String)))throw invalid();}
    private static String child(String path,Object key){return path+"/"+key.toString().replace("~","~0").replace("/","~1");}
    private static ValidationFailure fail(String keyword,String path){return new ValidationFailure(keyword,path);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_TOOL_SCHEMA");}
    private static Set<String> set(String... values){return new HashSet<>(Arrays.asList(values));}
}
