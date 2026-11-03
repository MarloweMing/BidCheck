package cn.bidcheck.document.word;

import cn.bidcheck.compat.Java8Api;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class WordWorkerWatchdogTest {
    @TempDir Path temp;

    @Test void realJavaWorkerPreservesArgumentsTemporaryDirectoryAndPrivateInput() throws Exception {
        Path output = Files.createDirectories(temp.resolve("worker with spaces"));
        Path report = output.resolve("probe.txt");
        String argument = "quoted \"value\" with trailing slash\\";
        List<String> command = new ArrayList<>(WordRenderingService.ProcessWorker.command());
        command.set(command.size() - 1, LaunchProbe.class.getName());
        command.add(argument);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("report", report.toAbsolutePath().toString());
        request.put("privateInput", "synthetic-stdin-marker");

        assertEquals("COMPLETE", WordRenderingService.ProcessWorker.launch(command, request, output, 20));
        String result = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertEquals(argument + "\n" + output.resolve("temp").toAbsolutePath(), result);
        assertFalse(result.contains("synthetic-stdin-marker"));
        assertFalse(Files.exists(output.resolve("worker.args")));
    }

    @Test void packagedWorkerRecognizesSpringBoot26AndNewerLauncherLocations() throws Exception {
        String[] launchers = {"org.springframework.boot.loader.PropertiesLauncher", "org.springframework.boot.loader.launch.PropertiesLauncher"};
        for (int index = 0; index < launchers.length; index++) {
            Path jar = temp.resolve("application " + index + ".jar");
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
                output.putNextEntry(new ZipEntry(launchers[index].replace('.', '/') + ".class"));
                output.write(0);
                output.closeEntry();
            }
            assertEquals(launchers[index], WordRenderingService.ProcessWorker.bootLauncher(jar));
        }
    }

    public static final class LaunchProbe {
        public static void main(String[] arguments) throws Exception {
            for (String key : System.getenv().keySet()) {
                if (key.startsWith("BIDCHECK_") || key.equals("JAVA_TOOL_OPTIONS") || key.equals("JDK_JAVA_OPTIONS") || key.equals("_JAVA_OPTIONS")) {
                    throw new IllegalStateException("WORKER_ENVIRONMENT_NOT_ISOLATED");
                }
            }
            JsonNode request = new ObjectMapper().readTree(Java8Api.readAllBytes(System.in));
            if (arguments.length != 1 || !"synthetic-stdin-marker".equals(request.path("privateInput").asText())) {
                throw new IllegalStateException("WORKER_INPUT_INVALID");
            }
            Path report = Paths.get(request.path("report").asText());
            Files.write(report, (arguments[0] + "\n" + System.getProperty("java.io.tmpdir")).getBytes(StandardCharsets.UTF_8));
            System.out.println("synthetic-stdin-marker");
            System.err.println("synthetic-stdin-marker");
        }
    }
}
