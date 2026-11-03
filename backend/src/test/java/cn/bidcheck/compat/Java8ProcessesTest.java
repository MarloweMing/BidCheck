package cn.bidcheck.compat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Java8ProcessesTest {
    @Test void cleanupKillsOwnedTreePreservesInterruptAndLeavesOtherProcessAlive() throws Exception {
        Process other = worker("child");
        Process parent = worker("parent");
        ExecutorService reader = Executors.newSingleThreadExecutor();
        long descendant = -1;
        try {
            Future<String> line = reader.submit(() -> new BufferedReader(new InputStreamReader(parent.getInputStream(), StandardCharsets.UTF_8)).readLine());
            descendant = Long.parseLong(line.get(5, TimeUnit.SECONDS));
            assertTrue(Java8Processes.pid(parent) > 0);
            assertTrue(Java8Processes.isAlive(descendant));
            assertTrue(other.isAlive());
            Thread.currentThread().interrupt();
            Java8Processes.destroyTree(parent);
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();
            assertTrue(parent.waitFor(3, TimeUnit.SECONDS));
            for (int retry = 0; retry < 30 && Java8Processes.isAlive(descendant); retry++) Thread.sleep(25);
            assertFalse(Java8Processes.isAlive(descendant));
            assertTrue(other.isAlive(), "cleanup must leave an unrelated child process alive");
            assertTrue(Java8Processes.isAlive(Long.parseLong(ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0])));
        } finally {
            Thread.interrupted();
            Java8Processes.destroyTree(parent);
            Java8Processes.destroyTree(other);
            reader.shutdownNow();
        }
    }

    private static Process worker(String mode) throws Exception {
        String executable = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(executable, "-cp", classpath, Worker.class.getName(), mode).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows"); }

    public static final class Worker {
        public static void main(String[] arguments) throws Exception {
            if (arguments[0].equals("parent")) {
                Process child = worker("child");
                System.out.println(Java8Processes.pid(child));
                System.out.flush();
            }
            Thread.sleep(60000);
        }
    }
}
