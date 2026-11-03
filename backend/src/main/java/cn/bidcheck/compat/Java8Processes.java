package cn.bidcheck.compat;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Bounded cleanup of external worker processes on JDK 8. */
public final class Java8Processes {
    private static final int MAX_PROCESS_OUTPUT = 1024 * 1024;
    private static final int MAX_DESCENDANTS = 4096;
    private static final ExecutorService OUTPUT_READERS = Executors.newCachedThreadPool(work -> {
        Thread reader = new Thread(work, "bidcheck-process-cleanup");
        reader.setDaemon(true);
        return reader;
    });

    private Java8Processes() { }

    public static ProcessBuilder.Redirect discardOutput() {
        return ProcessBuilder.Redirect.to(new java.io.File(Platform.isWindows() ? "NUL" : "/dev/null"));
    }

    public static long pid(Process process) {
        try { return ((Number) Process.class.getMethod("pid").invoke(process)).longValue(); }
        catch (ReflectiveOperationException ignored) { /* JDK 8 has no Process.pid(). */ }
        for (Class<?> type = process.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field pid = type.getDeclaredField(Platform.isWindows() ? "handle" : "pid");
                pid.setAccessible(true);
                long nativeValue = ((Number) pid.get(process)).longValue();
                return Platform.isWindows() ? Integer.toUnsignedLong(Windows.KERNEL.GetProcessId(new Pointer(nativeValue))) : nativeValue;
            } catch (NoSuchFieldException ignored) { /* Try the superclass. */ }
            catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return -1; }
        }
        return -1;
    }

    public static void destroyTree(Process process) {
        if (process == null) return;
        boolean interrupted = Thread.interrupted();
        try {
            long root = pid(process);
            if (root > 1 && root != currentPid() && process.isAlive()) {
                if (Platform.isWindows()) {
                    // Toolhelp works even when Windows management services are unavailable.
                    // Resolve the descendant set before terminating its owned parent.
                    if (!destroyWindowsDescendants(root)) {
                        String systemRoot = System.getenv("SystemRoot");
                        String taskkill = systemRoot == null ? "taskkill.exe" : Paths.get(systemRoot, "System32", "taskkill.exe").toString();
                        run(new String[]{taskkill, "/PID", Long.toString(root), "/T", "/F"});
                    }
                } else {
                    List<Long> descendants = descendants(root);
                    if (!descendants.isEmpty()) {
                        List<String> arguments = new ArrayList<>();
                        arguments.add("kill"); arguments.add("-KILL");
                        for (Long child : descendants) arguments.add(Long.toString(child));
                        run(arguments.toArray(new String[0]));
                    }
                }
            }
        } catch (InterruptedException stopped) { interrupted = true; }
        catch (IOException | RuntimeException | LinkageError unavailable) { /* Always terminate the owned parent below. */ }
        finally {
            try { process.destroyForcibly(); } catch (RuntimeException ignored) { }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public static boolean isAlive(long pid) {
        if (pid <= 0) return false;
        boolean interrupted = Thread.interrupted();
        try {
            if (Platform.isWindows()) {
                if (pid > 0xffffffffL) return false;
                Pointer handle = Windows.KERNEL.OpenProcess(0x1000, false, (int) pid);
                if (handle == null || Pointer.nativeValue(handle) == 0) return false;
                try {
                    IntByReference status = new IntByReference();
                    return Windows.KERNEL.GetExitCodeProcess(handle, status) && status.getValue() == 259;
                } finally { Windows.KERNEL.CloseHandle(handle); }
            }
            return run(new String[]{"kill", "-0", Long.toString(pid)}).exit == 0;
        } catch (InterruptedException stopped) { interrupted = true; return false; }
        catch (IOException | RuntimeException | LinkageError unavailable) { return false; }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static List<Long> descendants(long root) throws IOException, InterruptedException {
        Result snapshot = run(new String[]{"ps", "-e", "-o", "pid=,ppid="});
        if (snapshot.exit != 0) return Collections.emptyList();
        List<long[]> relationships = new ArrayList<>();
        for (String line : snapshot.output.split("\\r?\\n")) {
            String[] pair = line.trim().split("\\s+");
            if (pair.length != 2) continue;
            try { relationships.add(new long[]{Long.parseLong(pair[0]), Long.parseLong(pair[1])}); }
            catch (NumberFormatException ignored) { }
        }
        return descendants(root, relationships);
    }

    private static boolean destroyWindowsDescendants(long root) {
        Pointer snapshot = Windows.KERNEL.CreateToolhelp32Snapshot(2, 0);
        if (snapshot == null || Pointer.nativeValue(snapshot) == -1) return false;
        List<long[]> relationships = new ArrayList<>();
        try {
            ProcessEntry32 entry = new ProcessEntry32();
            if (!Windows.KERNEL.Process32FirstW(snapshot, entry)) return false;
            do {
                relationships.add(new long[]{Integer.toUnsignedLong(entry.th32ProcessID), Integer.toUnsignedLong(entry.th32ParentProcessID)});
            } while (Windows.KERNEL.Process32NextW(snapshot, entry));
        } finally { Windows.KERNEL.CloseHandle(snapshot); }
        for (Long child : descendants(root, relationships)) {
            Pointer handle = Windows.KERNEL.OpenProcess(1, false, child.intValue());
            if (handle == null || Pointer.nativeValue(handle) == 0) continue;
            try { Windows.KERNEL.TerminateProcess(handle, 1); }
            finally { Windows.KERNEL.CloseHandle(handle); }
        }
        return true;
    }

    private static List<Long> descendants(long root, List<long[]> relationships) {
        List<Long> found = new ArrayList<>();
        Set<Long> parents = new HashSet<>();
        parents.add(root);
        long current = currentPid();
        boolean changed = true;
        while (changed && found.size() < MAX_DESCENDANTS) {
            changed = false;
            for (long[] relation : relationships) {
                long child = relation[0];
                if (child > 1 && child != current && parents.contains(relation[1]) && parents.add(child)) {
                    found.add(child); changed = true;
                    if (found.size() == MAX_DESCENDANTS) break;
                }
            }
        }
        Collections.reverse(found);
        return found;
    }

    private static long currentPid() {
        try { return Long.parseLong(ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0]); }
        catch (RuntimeException unavailable) { return -1; }
    }

    private static Result run(String[] arguments) throws IOException, InterruptedException {
        Process command = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        Future<String> output = OUTPUT_READERS.submit(() -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            try (InputStream input = command.getInputStream()) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (bytes.size() > MAX_PROCESS_OUTPUT - count) throw new IOException("Process listing exceeds limit");
                    bytes.write(buffer, 0, count);
                }
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        });
        try {
            if (!command.waitFor(2, TimeUnit.SECONDS)) throw new IOException("Process cleanup timed out");
            try { return new Result(command.exitValue(), output.get(1, TimeUnit.SECONDS)); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) { throw new IOException("Process cleanup failed"); }
        } finally {
            output.cancel(true);
            if (command.isAlive()) command.destroyForcibly();
            try { command.getInputStream().close(); } catch (IOException ignored) { }
        }
    }

    private static final class Result {
        final int exit;
        final String output;
        Result(int exit, String output) { this.exit = exit; this.output = output; }
    }

    public interface Kernel32 extends StdCallLibrary {
        int GetProcessId(Pointer process);
        Pointer OpenProcess(int access, boolean inheritHandle, int pid);
        boolean GetExitCodeProcess(Pointer process, IntByReference exitCode);
        boolean CloseHandle(Pointer handle);
        Pointer CreateToolhelp32Snapshot(int flags, int pid);
        boolean Process32FirstW(Pointer snapshot, ProcessEntry32 entry);
        boolean Process32NextW(Pointer snapshot, ProcessEntry32 entry);
        boolean TerminateProcess(Pointer process, int exitCode);
    }

    public static final class ProcessEntry32 extends Structure {
        public int dwSize;
        public int cntUsage;
        public int th32ProcessID;
        public Pointer th32DefaultHeapID;
        public int th32ModuleID;
        public int cntThreads;
        public int th32ParentProcessID;
        public int pcPriClassBase;
        public int dwFlags;
        public char[] szExeFile = new char[260];
        public ProcessEntry32() { dwSize = size(); }
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("dwSize", "cntUsage", "th32ProcessID", "th32DefaultHeapID", "th32ModuleID", "cntThreads", "th32ParentProcessID", "pcPriClassBase", "dwFlags", "szExeFile");
        }
    }

    private static final class Windows {
        static final Kernel32 KERNEL = Native.load("kernel32", Kernel32.class);
    }
}
