package cn.bidcheck.document.word;

import com.aspose.words.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import static cn.bidcheck.document.word.WordRenderSupport.*;

/** Public-API adapter for 19.7 and current Words. No binary patching or license-state overrides. */
final class WordsCompatibility {
    static final String VERSION="words-public-api-compat-1";
    private WordsCompatibility(){}
    private static final class State {
        static final Map<String,Object> CAPABILITIES=inspect();
        static final String FINGERPRINT=fingerprint();
    }
    static boolean legacyPreflightRequired(){return !Boolean.TRUE.equals(State.CAPABILITIES.get("ignoreOleData"))
            ||!Boolean.TRUE.equals(State.CAPABILITIES.get("disableOleImageUpdate"))
            ||!Boolean.TRUE.equals(State.CAPABILITIES.get("disableFormScripts"));}
    static boolean compatible(){return Boolean.TRUE.equals(State.CAPABILITIES.get("compatible"));}
    static String identity(){return State.FINGERPRINT;}
    static Map<String,Object> configuration(){var result=new LinkedHashMap<>(State.CAPABILITIES);result.put("adapterVersion",VERSION);result.put("identity",identity());return result;}

    static void configureLoad(LoadOptions options,Path temp,Runnable deadline)throws IOException {
        requireCompatible();optional(options,"setIgnoreOleData",boolean.class,true);
        optional(options,"setTempFolder",String.class,temp.toString());callback(options,"setProgressCallback",deadline);
    }
    static void configureDocument(Document document,Runnable deadline)throws IOException {
        requireCompatible();disableAutomaticStyles(document);
        callback(document.getLayoutOptions(),"setCallback",deadline);
    }
    static void configureSave(PdfSaveOptions options,Path temp,Runnable deadline)throws IOException {
        requireCompatible();optional(options,"setUpdateOleControlImages",boolean.class,false);
        optional(options,"setGenerateFormFieldScripts",boolean.class,false);
        // 19.7 exposes temporary storage on SaveOptions rather than LoadOptions.
        optional(options,"setTempFolder",String.class,temp.toString());callback(options,"setProgressCallback",deadline);
    }
    static void requireCompatible()throws IOException {if(!compatible())throw new IOException("WORD_SDK_UNSUPPORTED");}
    static void disableAutomaticStyles(Object document)throws IOException {
        if(!optional(document,"setAutomaticallyUpdateStyles",boolean.class,false)
                &&!optional(document,"setAutomaticallyUpdateSyles",boolean.class,false))throw new IOException("WORD_SDK_UNSUPPORTED");
    }
    static boolean optional(Object target,String name,Class<?> parameter,Object value)throws IOException {
        Method method=method(target.getClass(),name,parameter);if(method==null)return false;
        invoke(method,target,value);return true;
    }
    static boolean callback(Object target,String name,Runnable check)throws IOException {
        Method setter=callbackMethod(target.getClass(),name);if(setter==null)return false;
        Class<?> callback=setter.getParameterTypes()[0];
        Object handler=Proxy.newProxyInstance(callback.getClassLoader(),new Class<?>[]{callback},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return switch(method.getName()){
                case "toString"->"BoundedWordProgress";case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->null;};
            if(method.getReturnType()!=void.class)throw new IllegalStateException("WORD_SDK_CALLBACK_INVALID");
            check.run();return null;
        });
        invoke(setter,target,handler);return true;
    }
    private static void invoke(Method method,Object target,Object value)throws IOException {
        try{method.invoke(target,value);}catch(IllegalAccessException|InvocationTargetException|IllegalArgumentException failure){throw new IOException("WORD_SDK_CONFIGURATION_ERROR");}
    }
    private static Method method(Class<?> type,String name,Class<?> parameter){try{return type.getMethod(name,parameter);}catch(NoSuchMethodException missing){return null;}}
    private static Method callbackMethod(Class<?> type,String name){for(Method m:type.getMethods())if(m.getName().equals(name)&&m.getParameterCount()==1&&m.getParameterTypes()[0].isInterface()&&m.getReturnType()==void.class)return m;return null;}
    private static Map<String,Object> inspect(){
        boolean modernStyles=method(Document.class,"setAutomaticallyUpdateStyles",boolean.class)!=null;
        boolean oldStyles=method(Document.class,"setAutomaticallyUpdateSyles",boolean.class)!=null;
        return Collections.unmodifiableMap(map("runtimeVersion",BuildVersionInfo.getVersion(),"compatible",modernStyles||oldStyles,
            "automaticStylesApi",modernStyles?"setAutomaticallyUpdateStyles":oldStyles?"setAutomaticallyUpdateSyles":"UNSUPPORTED",
            "ignoreOleData",method(LoadOptions.class,"setIgnoreOleData",boolean.class)!=null,
            "loadProgressCallback",callbackMethod(LoadOptions.class,"setProgressCallback")!=null,
            "loadTempFolder",method(LoadOptions.class,"setTempFolder",String.class)!=null,
            "saveTempFolder",method(PdfSaveOptions.class,"setTempFolder",String.class)!=null,
            "layoutProgressCallback",callbackMethod(LayoutOptions.class,"setCallback")!=null,
            "saveProgressCallback",callbackMethod(PdfSaveOptions.class,"setProgressCallback")!=null,
            "disableOleImageUpdate",method(PdfSaveOptions.class,"setUpdateOleControlImages",boolean.class)!=null,
            "disableFormScripts",method(PdfSaveOptions.class,"setGenerateFormFieldScripts",boolean.class)!=null,
            "timeoutEnforcement","ISOLATED_PROCESS_DEADLINE","licensePolicy","EXPLICIT_LICENSE_REQUIRED"));
    }
    private static String fingerprint(){
        // Loaded code, not the user-supplied filename, determines the cache identity.
        var parts=new TreeMap<String,String>();parts.put("policy",VERSION);parts.put("capabilities",State.CAPABILITIES.toString());
        for(Class<?> type:List.of(BuildVersionInfo.class,Document.class,LoadOptions.class,PdfSaveOptions.class,LayoutOptions.class,License.class)){
            try(InputStream in=type.getResourceAsStream(type.getSimpleName()+".class")){
                if(in==null)throw new IOException();byte[] bytes=in.readNBytes(8*1024*1024+1);if(bytes.length>8*1024*1024)throw new IOException();parts.put(type.getName(),hash(bytes));
            }catch(IOException failure){throw new IllegalStateException("WORD_SDK_FINGERPRINT_UNAVAILABLE");}
        }
        return hash(parts.toString());
    }
}
