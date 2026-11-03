package cn.bidcheck.verification.agent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Legacy adversarial protocol fixtures live only on the test classpath. */
final class TestAgentProtocol implements SdkAgentInvestigator.ProtocolFactory {
    private final Path interpreter,script;
    TestAgentProtocol(Path interpreter,Path script){this.interpreter=interpreter;this.script=script;}
    @Override public SdkAgentInvestigator.ProtocolConnection open(int timeout,String endpoint,String key,String model,String audit)throws Exception{
        ProcessBuilder builder=new ProcessBuilder(interpreter.toString(),"-u",script.toString());builder.redirectError(cn.bidcheck.compat.Java8Processes.discardOutput());
        Map<String,String> environment=builder.environment();Set<String> runtime=new HashSet<>(Arrays.asList("SYSTEMROOT","WINDIR","SYSTEMDRIVE","COMSPEC","PATH","PATHEXT","TEMP","TMP","TMPDIR","HOME","USERPROFILE","APPDATA","LOCALAPPDATA","LANG","LC_ALL","LD_LIBRARY_PATH"));
        environment.keySet().removeIf(name->!runtime.contains(name.toUpperCase(Locale.ROOT)));
        environment.put("PYTHONIOENCODING","utf-8");environment.put("PYTHONUTF8","1");environment.put("BIDCHECK_AGENT_API_KEY",key);environment.put("BIDCHECK_AGENT_BASE_URL",endpoint);environment.put("BIDCHECK_AGENT_MODEL",model);
        environment.put("BIDCHECK_AGENT_RUN_TIMEOUT_SECONDS",Integer.toString(timeout));environment.put("BIDCHECK_AGENT_TOOL_TIMEOUT_SECONDS",Integer.toString(timeout));
        if(audit!=null&&!audit.trim().isEmpty())environment.put("BIDCHECK_AGENT_AUDIT_DIRECTORY",Paths.get(audit).toAbsolutePath().normalize().toString());
        environment.put("OPENAI_AGENTS_DISABLE_TRACING","1");environment.put("OPENAI_AGENTS_DONT_LOG_MODEL_DATA","1");environment.put("OPENAI_AGENTS_DONT_LOG_TOOL_DATA","1");
        Process process=builder.start();return new SdkAgentInvestigator.ProtocolConnection(){
            public InputStream input(){return process.getInputStream();}public OutputStream output(){return process.getOutputStream();}
            public void close(){cn.bidcheck.compat.Java8Processes.destroyTree(process);try{process.getOutputStream().close();}catch(IOException ignored){}try{process.getInputStream().close();}catch(IOException ignored){}}
        };
    }
    @Override public String identity(){try{MessageDigest digest=MessageDigest.getInstance("SHA-256");for(Path path:Arrays.asList(script,script.resolveSibling("requirements.lock"),script.resolveSibling("model_audit.py")))if(Files.isRegularFile(path)){digest.update(path.getFileName().toString().getBytes(StandardCharsets.UTF_8));digest.update(Files.readAllBytes(path));}return cn.bidcheck.compat.HexFormat.of().formatHex(digest.digest());}catch(Exception error){return "UNAVAILABLE";}}
}
