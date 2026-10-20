package cn.bidcheck.document.word;

import com.aspose.words.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WordsCompatibilityTest {
    @TempDir Path temp;
    public static class OldStyles { boolean value=true;public void setAutomaticallyUpdateSyles(boolean v){value=v;} }
    public static class NewStyles extends OldStyles {boolean modernCalled;public void setAutomaticallyUpdateStyles(boolean v){value=v;modernCalled=true;} }
    public static class BrokenSetting {public void setFeature(boolean value){throw new IllegalStateException("private-sdk-details");}}
    public interface Progress {void notify(Object args);}
    public static class CallbackSetting {Progress progress;public void setProgressCallback(Progress progress){this.progress=progress;}}
    @Test void explicitOldTypoAndModernNameBothDisableStyleRefresh()throws Exception{
        OldStyles old=new OldStyles();WordsCompatibility.disableAutomaticStyles(old);assertFalse(old.value);
        NewStyles newer=new NewStyles();WordsCompatibility.disableAutomaticStyles(newer);assertFalse(newer.value);assertTrue(newer.modernCalled);
        assertEquals("WORD_SDK_UNSUPPORTED",assertThrows(IOException.class,()->WordsCompatibility.disableAutomaticStyles(new Object())).getMessage());
    }
    @Test void existingSetterFailureCannotBeTreatedAsAbsent()throws Exception{
        assertFalse(WordsCompatibility.optional(new Object(),"setFeature",boolean.class,false));
        var e=assertThrows(IOException.class,()->WordsCompatibility.optional(new BrokenSetting(),"setFeature",boolean.class,false));
        assertEquals("WORD_SDK_CONFIGURATION_ERROR",e.getMessage());assertNull(e.getCause());
    }
    @Test void callbackBridgePreservesDeadlineAndDoesNotRunOnObjectMethods()throws Exception{
        var setting=new CallbackSetting();var calls=new AtomicInteger();assertTrue(WordsCompatibility.callback(setting,"setProgressCallback",calls::incrementAndGet));
        assertEquals("BoundedWordProgress",setting.progress.toString());setting.progress.hashCode();assertTrue(setting.progress.equals(setting.progress));assertEquals(0,calls.get());
        setting.progress.notify(null);assertEquals(1,calls.get());assertFalse(WordsCompatibility.callback(new Object(),"setProgressCallback",()->fail()));
        assertTrue(WordsCompatibility.callback(setting,"setProgressCallback",()->{throw new IllegalStateException("TIMEOUT");}));assertThrows(IllegalStateException.class,()->setting.progress.notify(null));
    }
    @Test void actualSdkUsesItsAvailableApisAndPreservesIndependentDeadline()throws Exception{
        assertTrue(WordsCompatibility.compatible());var config=WordsCompatibility.configuration();assertEquals(BuildVersionInfo.getVersion(),config.get("runtimeVersion"));
        assertEquals("ISOLATED_PROCESS_DEADLINE",config.get("timeoutEnforcement"));assertEquals("EXPLICIT_LICENSE_REQUIRED",config.get("licensePolicy"));
        var options=new LoadOptions();WordsCompatibility.configureLoad(options,temp,()->{});
        var pdf=new PdfSaveOptions();WordsCompatibility.configureSave(pdf,temp,()->{});
        var doc=new Document();WordsCompatibility.configureDocument(doc,()->{});
        String name=(String)config.get("automaticStylesApi");Method getter=Document.class.getMethod(name.replaceFirst("^set","get"));assertEquals(false,getter.invoke(doc));
        if(Boolean.TRUE.equals(config.get("saveTempFolder")))assertEquals(temp.toString(),PdfSaveOptions.class.getMethod("getTempFolder").invoke(pdf));
        assertEquals(!Boolean.TRUE.equals(config.get("ignoreOleData"))||!Boolean.TRUE.equals(config.get("disableOleImageUpdate"))||!Boolean.TRUE.equals(config.get("disableFormScripts")),WordsCompatibility.legacyPreflightRequired());
    }
    @Test void identityIsBasedOnLoadedCodeAndCannotChangeWithCallerMapMutation(){
        String identity=WordsCompatibility.identity();assertTrue(identity.matches("[a-f0-9]{64}"));var config=WordsCompatibility.configuration();config.put("runtimeVersion","made-up");
        assertEquals(identity,WordsCompatibility.identity());assertEquals(BuildVersionInfo.getVersion(),WordsCompatibility.configuration().get("runtimeVersion"));
    }
}
