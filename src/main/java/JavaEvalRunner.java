import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import java.util.stream.Collectors;

public class JavaEvalRunner {
    public static void main(String[] args) throws Exception {
        Path jsonlPath = Paths.get("data.jsonl");
        if (!Files.exists(jsonlPath)) {
            System.err.println("data.jsonl not found.");
            return;
        }
        List<JsonObject> tasks = new ArrayList<>();
        Gson gson = new Gson();
        try (BufferedReader br = Files.newBufferedReader(jsonlPath)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                tasks.add(gson.fromJson(line, JsonObject.class));
            }
        }
        int idx = 1;
        for (JsonObject task : tasks) {
            String taskId = task.has("task_id") ? task.get("task_id").getAsString() : ("task_" + idx);
            String prompt = task.has("prompt") ? task.get("prompt").getAsString() : "";
            String solution = task.has("canonical_solution") ? task.get("canonical_solution").getAsString() : "";
            String testCode = task.has("test") ? task.get("test").getAsString() : "";
            System.out.println("=== " + taskId + " ===");
            Path tempDir = Files.createTempDirectory("javaeval_" + taskId + "_");
            Path solutionFile = tempDir.resolve("Solution.java");
            Path testFile = tempDir.resolve("SolutionTest.java");
            Files.write(solutionFile, solution.getBytes());
            Files.write(testFile, testCode.getBytes());
            boolean compiled = compileJava(tempDir, Arrays.asList(solutionFile, testFile));
            if (!compiled) {
                System.out.println("Compilation failed for " + taskId);
                printFile(solutionFile);
                printFile(testFile);
                continue;
            }
            Result result = runJUnit(tempDir, "SolutionTest");
            if (result == null) {
                System.out.println("Test execution failed for " + taskId);
            } else {
                System.out.println("Tests run: " + result.getRunCount() + ", Failures: " + result.getFailureCount());
                result.getFailures().forEach(f -> System.out.println("Failure: " + f.toString()));
            }
            idx++;
        }
    }

    private static boolean compileJava(Path outDir, List<Path> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("No system Java compiler found. Use a JDK, not a JRE.");
            return false;
        }
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(
                sources.stream().map(Path::toFile).collect(Collectors.toList())
        );
        List<String> options = Arrays.asList("-d", outDir.toString(), "-classpath", System.getProperty("java.class.path"));
        StringWriter sw = new StringWriter();
        boolean ok = compiler.getTask(sw, fileManager, null, options, null, units).call();
        fileManager.close();
        if (!ok) {
            System.err.println("Compilation errors:\n" + sw);
        }
        return ok;
    }

    private static Result runJUnit(Path classesDir, String testClassName) {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classesDir.toUri().toURL()}, JavaEvalRunner.class.getClassLoader())) {
            Class<?> testClass = Class.forName(testClassName, true, loader);
            JUnitCore core = new JUnitCore();
            return core.run(testClass);
        } catch (Exception e) {
            System.err.println("JUnit error: " + e.getMessage());
            return null;
        }
    }

    private static void printFile(Path p) {
        try {
            System.out.println("----- " + p.getFileName() + " -----");
            Files.lines(p).forEach(System.out::println);
            System.out.println("----- end -----");
        } catch (IOException ignored) {}
    }
}
