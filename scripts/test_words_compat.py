#!/usr/bin/env python3
"""Offline Aspose.Words API/Java 8 compatibility probe; no license is supplied.

Example (PowerShell, append --compile-production after changing the adapter):
  python scripts/test_words_compat.py --java-home C:/path/to/jdk-8 `
    --jar legacy=C:/path/to/aspose-words-19.7-jdk17.jar `
    --jar current=.local/aspose-word/aspose-words-26.9-jdk17.jar

--compile-production compiles only the current document/word package, using
non-Words dependencies from --application-jar (default: the existing Boot JAR).
It also invokes the freshly compiled worker with a deliberately missing license.
Every invocation uses a new private output directory. User libraries and source
documents are never written. The two-page synthetic fixture is loaded only for
in-memory EVALUATION_ONLY layout; no PDF/image/business evidence is produced.
An SDK reporting isLicensed=true is recorded, never accepted as authorization.
No Maven, network download, model invocation, or license modification is used.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[1]
WORD_PREFIX = "cn/bidcheck/document/word/"
API_CLASSES = ("Document", "LoadOptions", "SaveOptions", "PdfSaveOptions",
               "FixedPageSaveOptions", "LayoutOptions")

PROBE_JAVA = r'''
import com.aspose.words.*;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public final class WordsCompatibilityProbe {
    static void emit(String key, Object value) {
        System.out.println("PROBE\t" + key + "\t" + value);
    }
    static boolean has(Class<?> type, String name) {
        return Arrays.stream(type.getMethods()).anyMatch(m -> m.getName().equals(name));
    }
    static Object adapter(String name, Class<?>[] parameters, Object... values) throws Exception {
        Method method=Class.forName("cn.bidcheck.document.word.WordsCompatibility")
            .getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(null, values);
    }
    public static void main(String[] args) throws Exception {
        boolean useAdapter=args.length>1 && args[1].equals("adapter");
        Runnable deadline=()->{if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("INTERRUPTED");};
        Path temp=Paths.get(System.getProperty("java.io.tmpdir"));
        emit("mode", "EVALUATION_ONLY");
        emit("licenseProvided", false);
        emit("usableForEvidence", false);
        emit("runtimeVersion", BuildVersionInfo.getVersion());
        // A boolean from a borrowed binary is diagnostic, not proof of entitlement.
        emit("sdkReportsLicensed", has(License.class,"isLicensed")
            ? License.class.getMethod("isLicensed").invoke(new License()) : "API_NOT_EXPOSED");
        Class<?>[] types = {LoadOptions.class, LoadOptions.class, LoadOptions.class,
            Document.class, LayoutOptions.class, PdfSaveOptions.class,
            PdfSaveOptions.class, PdfSaveOptions.class};
        String[] names = {"setIgnoreOleData", "setProgressCallback", "setTempFolder",
            "setAutomaticallyUpdateStyles", "setCallback", "setUpdateOleControlImages",
            "setGenerateFormFieldScripts", "setProgressCallback"};
        for (int i=0; i<types.length; i++)
            emit("api."+types[i].getSimpleName()+"."+names[i], has(types[i], names[i]));
        emit("alternative.Document.setAutomaticallyUpdateSyles",
            has(Document.class, "setAutomaticallyUpdateSyles"));
        emit("alternative.SaveOptions.setTempFolder", has(SaveOptions.class, "setTempFolder"));
        emit("alternative.FixedPageSaveOptions.setPageSavingCallback",
            has(FixedPageSaveOptions.class, "setPageSavingCallback"));

        List<Integer> warnings = new ArrayList<>();
        int[] resourceRequests = {0};
        IResourceLoadingCallback deny = a -> {resourceRequests[0]++; return ResourceLoadingAction.SKIP;};
        LoadOptions options = new LoadOptions();
        options.setLoadFormat(LoadFormat.DOCX);
        options.setUpdateDirtyFields(false);
        options.setResourceLoadingCallback(deny);
        options.setWarningCallback(w -> warnings.add(w.getWarningType()));
        if(useAdapter) adapter("configureLoad",new Class<?>[]{LoadOptions.class,Path.class,Runnable.class},options,temp,deadline);
        Document document;
        try (InputStream in=Files.newInputStream(Paths.get(args[0]))) {
            document = new Document(in, options);
        }
        document.setResourceLoadingCallback(deny);
        document.setWarningCallback(w -> warnings.add(w.getWarningType()));
        document.setAttachedTemplate("");
        String styleMethod=has(Document.class,"setAutomaticallyUpdateStyles")
            ? "setAutomaticallyUpdateStyles" : "setAutomaticallyUpdateSyles";
        if(useAdapter) {
            adapter("configureDocument",new Class<?>[]{Document.class,Runnable.class},document,deadline);
            if(!Boolean.FALSE.equals(Document.class.getMethod("get"+styleMethod.substring(3)).invoke(document)))
                throw new IOException("ADAPTER_AUTOMATIC_STYLES_NOT_DISABLED");
            PdfSaveOptions save=new PdfSaveOptions();
            adapter("configureSave",new Class<?>[]{PdfSaveOptions.class,Path.class,Runnable.class},save,temp,deadline);
            if(!temp.toString().equals(save.getTempFolder())) throw new IOException("ADAPTER_SAVE_TEMP_NOT_SET");
            for(String getter:new String[]{"getUpdateOleControlImages","getGenerateFormFieldScripts"})
                if(has(PdfSaveOptions.class,getter) && !Boolean.FALSE.equals(PdfSaveOptions.class.getMethod(getter).invoke(save)))
                    throw new IOException("ADAPTER_SAVE_CONTROL_NOT_DISABLED");
            emit("productionAdapterIdentity",adapter("identity",new Class<?>[]{}));
            emit("productionAdapterApplied",true);
        } else Document.class.getMethod(styleMethod, boolean.class).invoke(document, false);
        emit("styleSetterUsed", styleMethod);
        document.removeMacros();
        document.removeExternalSchemaReferences();
        for (Field field: document.getRange().getFields()) field.isLocked(true);
        LayoutCollector collector = new LayoutCollector(document);
        document.updatePageLayout();
        int count=document.getPageCount();
        emit("pageCount", count);
        if (count < 1 || count > 5) throw new IOException("SYNTHETIC_PAGE_BOUND");
        LayoutEnumerator enumerator = new LayoutEnumerator(document);
        int[] seen = {0, 0};
        for (Object object : document.getChildNodes(NodeType.PARAGRAPH, true)) {
            Node node=(Node)object;
            String text=node.getText();
            for (int i=0; i<2; i++) if(text.contains("SYNTHETIC_EVALUATION_PAGE_"+(i+1))) {
                seen[i]++;
                int start=collector.getStartPageIndex(node), end=collector.getEndPageIndex(node);
                emit("marker."+(i+1)+".startRenderedPage", start);
                emit("marker."+(i+1)+".endRenderedPage", end);
                Object entity=collector.getEntity(node);
                if(entity==null) throw new IOException("MISSING_LAYOUT_ANCHOR");
                enumerator.setCurrent(entity);
                Rectangle2D.Float box=enumerator.getRectangle();
                boolean finite=box!=null && Float.isFinite(box.x) && Float.isFinite(box.y)
                    && Float.isFinite(box.width) && Float.isFinite(box.height)
                    && box.x>=0 && box.y>=0 && box.width>=0 && box.height>=0;
                emit("marker."+(i+1)+".anchorPage", enumerator.getPageIndex());
                emit("marker."+(i+1)+".anchorFinite", finite);
                if(start!=i+1 || end!=i+1 || enumerator.getPageIndex()!=i+1 || !finite)
                    throw new IOException("SYNTHETIC_MAPPING_MISMATCH");
            }
        }
        if(seen[0]!=1 || seen[1]!=1) throw new IOException("SYNTHETIC_MARKER_COUNT");
        emit("resourceRequestsBlocked", resourceRequests[0]);
        emit("warningTypes", warnings.stream().distinct().sorted().collect(java.util.stream.Collectors.toList()));
        emit("mappingPassed", true);
        emit("businessEvidenceProduced", false);
        collector.setDocument(null);
    }
}
'''


def sha256(path: Path) -> str:
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def child_environment() -> dict[str, str]:
    # Do not pass application/model credentials or implicit Java agent switches.
    return {key: value for key, value in os.environ.items()
            if not key.startswith("BIDCHECK_") and key not in
            {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"}}


def execute(command: list[str], directory: Path, timeout: int,
            stdin: str | None = None) -> dict:
    started = time.monotonic()
    kwargs = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
    try:
        completed = subprocess.run(command, cwd=directory, env=child_environment(),
                                   input=stdin, capture_output=True, text=True,
                                   encoding="utf-8", errors="replace", timeout=timeout,
                                   **kwargs)
        return {"exitCode": completed.returncode,
                "stdout": completed.stdout[:65536], "stderr": completed.stderr[:65536],
                "seconds": round(time.monotonic()-started, 3), "timedOut": False}
    except subprocess.TimeoutExpired:
        # subprocess.run kills/waits for this isolated Java process on timeout.
        return {"exitCode": None, "stdout": "", "stderr": "TIMEOUT",
                "seconds": round(time.monotonic()-started, 3), "timedOut": True}


def java_args(path: Path, args: list[str]) -> str:
    # Java argfile quoting, not shell quoting; command is always passed as a list.
    path.write_text("\n".join('"'+a.replace("\\", "/").replace('"', '\\"')+'"'
                              for a in args)+"\n", encoding="utf-8")
    return "@"+str(path)


def java_run_args(path: Path, args: list[str]) -> list[str]:
    # Java 8 supports javac argfiles, but the java launcher accepts direct arguments only.
    java_args(path, args)
    return args


def fixture(path: Path) -> None:
    parts = {
        "[Content_Types].xml": '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
        "_rels/.rels": '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
        "word/document.xml": '<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>SYNTHETIC_EVALUATION_PAGE_1</w:t></w:r></w:p><w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>SYNTHETIC_EVALUATION_PAGE_2</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>',
    }
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, text in parts.items():
            entry=zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type=zipfile.ZIP_DEFLATED
            archive.writestr(entry, text)


def clean_result(result: dict) -> dict:
    return {key: value for key, value in result.items() if key not in {"stdout", "stderr"}}


def properties(result: dict) -> dict:
    props = {}
    for line in result["stdout"].splitlines():
        if line.startswith("PROBE\t"):
            _, key, value = line.split("\t", 2)
            props[key] = {"true": True, "false": False}.get(value, value)
    return props


def boot_dependencies(artifact: Path, directory: Path) -> list[Path]:
    classes = directory / "application-classes"
    libs = directory / "lib"
    classes.mkdir()
    libs.mkdir()
    result = [classes]
    with zipfile.ZipFile(artifact) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or entry.file_size > 160*1024*1024:
                continue
            if entry.filename.startswith("BOOT-INF/classes/"):
                relative = entry.filename[len("BOOT-INF/classes/"):]
                if relative.startswith(WORD_PREFIX) or not relative.endswith(".class"):
                    continue
                output = classes / relative
                if not output.resolve().is_relative_to(classes.resolve()):
                    raise ValueError("Unsafe application artifact entry")
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(archive.read(entry))
            elif entry.filename.startswith("BOOT-INF/lib/") and entry.filename.endswith(".jar"):
                # Do not rely on a vendor filename to exclude a second Words SDK.
                import io
                data = archive.read(entry)
                with zipfile.ZipFile(io.BytesIO(data)) as nested:
                    if "com/aspose/words/Document.class" in nested.namelist():
                        continue
                output = libs / Path(entry.filename).name
                output.write_bytes(data)
                result.append(output)
    if len(result) < 2:
        raise ValueError("Application JAR has no Boot dependencies")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--java-home", type=Path,
                        default=Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    parser.add_argument("--jar", action="append", required=True, metavar="LABEL=PATH")
    parser.add_argument("--output", type=Path, default=ROOT/".local/words-compat")
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument("--compile-production", action="store_true")
    parser.add_argument("--application-jar", type=Path, default=ROOT/"backend/target/bidcheck-0.1.0.jar")
    args = parser.parse_args()
    if args.java_home is None:
        parser.error("set JAVA_HOME or provide --java-home")
    if not 5 <= args.timeout <= 120:
        parser.error("--timeout must be within 5..120 seconds")
    java = (args.java_home/"bin"/("java.exe" if os.name == "nt" else "java")).resolve()
    javac = java.with_name("javac.exe" if os.name == "nt" else "javac")
    javap = java.with_name("javap.exe" if os.name == "nt" else "javap")
    if not all(path.is_file() for path in (java, javac, javap)):
        parser.error("Java 8 JDK executables not found")
    jars = []
    for argument in args.jar:
        label, separator, raw_path = argument.partition("=")
        path = Path(raw_path).resolve()
        if not separator or not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", label) or not path.is_file():
            parser.error("each --jar must be a unique simple LABEL=existing-file.jar")
        if any(previous[0] == label for previous in jars):
            parser.error("duplicate jar label")
        jars.append((label, path))
    output = args.output.resolve()/str(uuid.uuid4())
    output.mkdir(parents=True)
    original = output/"synthetic-evaluation-only.docx"
    fixture(original)
    source = output/"WordsCompatibilityProbe.java"
    source.write_text(PROBE_JAVA, encoding="utf-8")
    artifact = args.application_jar.resolve()
    artifact_hash = sha256(artifact) if args.compile_production else None
    dependencies = boot_dependencies(artifact, output) if args.compile_production else []
    sources = sorted((ROOT/"backend/src/main/java/cn/bidcheck/document/word").rglob("*.java"))
    report = {"mode": "EVALUATION_ONLY", "licenseProvided": False, "usableForEvidence": False,
              "businessEvidenceProduced": False, "modelsInvoked": False,
              "syntheticFixtureSha256": sha256(original), "jars": [],
              "productionCompileRequested": args.compile_production,
              "dependencyArtifactSha256": artifact_hash,
              "productionSourceHashes": {p.relative_to(ROOT).as_posix(): sha256(p) for p in sources}
              if args.compile_production else {},
              "limitations": ["SDK isLicensed boolean does not establish authorization.",
                              "Rendered page/anchor checks apply only to a tiny synthetic fixture.",
                              "No PDF/image is saved and no production license gate is bypassed.",
                              "Missing progress callbacks require process-level time and memory limits."]}
    for label, jar in jars:
        area = output/label
        area.mkdir()
        classes = area/"probe-classes"
        classes.mkdir()
        temporary = area/"tmp"
        temporary.mkdir()
        before = sha256(jar)
        row = {"label": label, "jarName": jar.name, "sha256": before}
        api = execute([str(javap), "-classpath", str(jar), *["com.aspose.words."+name for name in API_CLASSES]], area, args.timeout)
        (area/"public-api.txt").write_text(api["stdout"], encoding="utf-8")
        row["apiInspection"] = clean_result(api)
        compile_args = java_args(area/"probe-compile.args", ["-proc:none", "-source", "8", "-target", "8", "-encoding", "UTF-8", "-cp", str(jar), "-d", str(classes), str(source)])
        compiled = execute([str(javac), "-J-Xmx384m", compile_args], area, args.timeout)
        (area/"probe-compile.log").write_text(compiled["stderr"], encoding="utf-8")
        row["probeCompile"] = clean_result(compiled)
        if compiled["exitCode"] == 0:
            run_args = java_run_args(area/"probe-run.args", ["-Xmx256m", "-XX:MaxMetaspaceSize=192m", "-Djava.awt.headless=true", "-Djava.io.tmpdir="+str(temporary), "-cp", os.pathsep.join(map(str, [classes, jar])), "WordsCompatibilityProbe", str(original)])
            run = execute([str(java), *run_args], area, args.timeout)
            row["runtime"] = {**clean_result(run), "properties": properties(run)}
            (area/"probe-runtime.log").write_text(run["stderr"], encoding="utf-8")
        if args.compile_production:
            production = area/"production-classes"
            production.mkdir()
            classpath = os.pathsep.join(map(str, [jar, *dependencies]))
            compile_args = java_args(area/"production-compile.args", ["-proc:none", "-source", "8", "-target", "8", "-encoding", "UTF-8", "-cp", classpath, "-d", str(production), *map(str, sources)])
            built = execute([str(javac), "-J-Xmx384m", compile_args], area, args.timeout)
            row["productionCompile"] = clean_result(built)
            (area/"production-compile.log").write_text(built["stderr"], encoding="utf-8")
            if built["exitCode"] == 0:
                adapter_args = java_run_args(area/"production-adapter.args", ["-Xmx256m", "-XX:MaxMetaspaceSize=192m", "-Djava.awt.headless=true", "-Djava.io.tmpdir="+str(temporary), "-cp", os.pathsep.join(map(str, [production, classes, jar, *dependencies])), "WordsCompatibilityProbe", str(original), "adapter"])
                adapted = execute([str(java), *adapter_args], area, args.timeout)
                row["productionAdapterRuntime"] = {**clean_result(adapted), "properties": properties(adapted)}
                (area/"production-adapter.log").write_text(adapted["stderr"], encoding="utf-8")
                gate = area/"missing-license-gate"
                gate.mkdir()
                worker_args = java_run_args(area/"worker-gate.args", ["-Xmx256m", "-Djava.awt.headless=true", "-Djava.io.tmpdir="+str(temporary), "-cp", os.pathsep.join(map(str, [production, jar, *dependencies])), "cn.bidcheck.document.word.WordRenderWorker"])
                request = {"outputDirectory": str(gate), "licensePath": str(gate/"not-provided.lic")}
                worker = execute([str(java), *worker_args], area, args.timeout, json.dumps(request))
                result_path = gate/"result.json"
                response = json.loads(result_path.read_text(encoding="utf-8")) if result_path.is_file() and result_path.stat().st_size<100000 else {}
                row["productionLicenseGate"] = {**clean_result(worker), "status": response.get("status"), "errorCode": response.get("errorCode"), "usableForEvidence": response.get("usableForEvidence"), "derivedPdfProduced": (gate/"rendered.pdf").exists(), "passed": worker["exitCode"]==0 and response.get("errorCode", response.get("status")) in {"LICENSE_INVALID", "LICENSE_REQUIRED"} and response.get("usableForEvidence") is False and not (gate/"rendered.pdf").exists()}
        row["jarUnchanged"] = sha256(jar) == before
        report["jars"].append(row)
    report["syntheticFixtureUnchanged"] = sha256(original) == report["syntheticFixtureSha256"]
    after_sources = {p.relative_to(ROOT).as_posix(): sha256(p) for p in (ROOT/"backend/src/main/java/cn/bidcheck/document/word").rglob("*.java")} if args.compile_production else {}
    report["productionSourcesUnchanged"] = after_sources == report["productionSourceHashes"]
    report["dependencyArtifactUnchanged"] = not args.compile_production or sha256(artifact)==artifact_hash
    report["passed"] = report["syntheticFixtureUnchanged"] and report["productionSourcesUnchanged"] and report["dependencyArtifactUnchanged"] and all(row["jarUnchanged"] and row.get("runtime", {}).get("exitCode") == 0 and row.get("runtime", {}).get("properties", {}).get("mappingPassed") is True and (not args.compile_production or (row.get("productionLicenseGate", {}).get("passed") is True and row.get("productionAdapterRuntime", {}).get("exitCode") == 0 and row.get("productionAdapterRuntime", {}).get("properties", {}).get("productionAdapterApplied") is True)) for row in report["jars"])
    result_path = output/"summary.json"
    result_path.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(json.dumps({"summary": str(result_path), "passed": report["passed"], "mode": report["mode"], "usableForEvidence": False}, ensure_ascii=False))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
