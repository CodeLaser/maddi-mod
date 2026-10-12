#!/usr/bin/env python3
"""
IntelliJ's Java -> Kotlin converter on a whole corpus module, headless (CodeLaser/maddi-mod#36).

IntelliJ 263.4825+ exposes the converter as the MCP tool `convert_java_to_kotlin` of its built-in MCP server; we start
the IDE headless (`idea.sh mcpServer`) with its own config, system and plugin paths, never a developer's IDE, open a
COPY of the module, and convert every main source file in one call, as "Convert Java File to Kotlin File" on the
source root does: J2K rewrites the Java callers too, so the module's Java tests then run against the Kotlin classes.

Usage: j2k.py --module <dir> --out <dir> [--idea-build 263.5153.40] [--work <dir>] [--batch N]
  --module   the module directory inside a corpus checkout (e.g. ~/git/test-oss/langchain4j/langchain4j-core); the
             whole checkout (its git root) is copied, so that a parent pom or settings.gradle resolves
  --out      receives src/ (the converted .kt files, by package path), test/ (the test sources as J2K left them),
             j2k.log and provenance.txt
Linux only (the far machines of `lab run`). Python 3.9+, standard library only.
"""
import argparse
import json
import re
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

PORT = 64399
TOOLS = ["convert_java_to_kotlin", "get_project_modules"]


def log(msg):
    print(f"[j2k {time.strftime('%H:%M:%S')}] {msg}", flush=True)


def ensure_idea(build: str) -> Path:
    cache = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "j2k-baseline"
    home = cache / f"idea-{build}"
    if (home / "bin" / "idea.sh").exists():
        return home
    suffix = "-aarch64" if platform.machine() in ("aarch64", "arm64") else ""
    url = f"https://download.jetbrains.com/idea/idea-{build}{suffix}.tar.gz"
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / f"idea-{build}{suffix}.tar.gz"
    log(f"downloading {url}")
    urllib.request.urlretrieve(url, archive)
    tmp = cache / f"idea-{build}.partial"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir()
    with tarfile.open(archive) as tf:
        top = tf.getmembers()[0].name.split("/")[0]
        tf.extractall(tmp)
    (tmp / top).rename(home)
    shutil.rmtree(tmp)
    archive.unlink()
    return home


def configure_kotlin_maven(pom: Path, version: str):
    """What IntelliJ's "Configure Kotlin in Project" does to a Maven module: kotlin-stdlib, and kotlin-maven-plugin
    compiling src/main/java. Without it the converted files do not resolve, and J2K's post-processing (redundant
    modifiers, primary constructors, properties) does not run."""
    text = pom.read_text()
    # skip <dependencies> and <plugins> inside dependencyManagement / pluginManagement
    def top_level(tag):
        masked = re.sub(r"<(dependencyManagement|pluginManagement)>.*?</\1>",
                        lambda m: " " * len(m.group(0)), text, flags=re.S)
        i = masked.find(f"</{tag}>")
        return i
    dep = (f"<dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId>"
           f"<version>{version}</version></dependency>\n")
    plugin = (f"<plugin><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId>"
              f"<version>{version}</version><executions>"
              f"<execution><id>compile</id><goals><goal>compile</goal></goals><configuration><sourceDirs>"
              f"<sourceDir>${{project.basedir}}/src/main/java</sourceDir></sourceDirs></configuration></execution>"
              f"<execution><id>test-compile</id><goals><goal>test-compile</goal></goals></execution>"
              f"</executions></plugin>\n")
    i = top_level("plugins")
    if i < 0:
        j = text.rfind("</project>")
        text = text[:j] + f"<build><plugins>{plugin}</plugins></build>\n" + text[j:]
    else:
        text = text[:i] + plugin + text[i:]
    i = top_level("dependencies")
    if i < 0:
        j = text.rfind("</project>")
        text = text[:j] + f"<dependencies>{dep}</dependencies>\n" + text[j:]
    else:
        text = text[:i] + dep + text[i:]
    pom.write_text(text)


def git(repo: Path, *args) -> str:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True).stdout.strip()


class Mcp:
    def __init__(self, port: int, project: Path):
        self.url = f"http://127.0.0.1:{port}/stream"
        self.project = str(project)
        self.session = None
        self.id = 0

    def _post(self, body: dict, timeout: float):
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        req = urllib.request.Request(self.url, data=json.dumps(body).encode(), headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.read().decode(), res.headers.get("mcp-session-id")

    def _rpc(self, method: str, params: dict, timeout: float = 60):
        self.id += 1
        raw, _ = self._post({"jsonrpc": "2.0", "id": self.id, "method": method, "params": params}, timeout)
        if raw.startswith("event:") or "\ndata:" in raw:
            raw = next(l for l in raw.splitlines() if l.startswith("data:"))[5:].strip()
        return json.loads(raw)

    def connect(self):
        self.id += 1
        _, sid = self._post({"jsonrpc": "2.0", "id": self.id, "method": "initialize", "params": {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "maddi-j2k-baseline", "version": "1"}}}, 60)
        if not sid:
            raise SystemExit("no MCP session id")
        self.session = sid
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}}, 60)

    def tools(self):
        return [t["name"] for t in self._rpc("tools/list", {})["result"]["tools"]]

    def call(self, tool: str, args: dict, timeout: float):
        args = dict(args, projectPath=self.project)
        result = self._rpc("tools/call", {"name": tool, "arguments": args}, timeout)
        if "error" in result:
            raise SystemExit(f"{tool}: {result['error']}")
        r = result["result"]
        text = "\n".join(c.get("text", "") for c in r.get("content", []))
        if r.get("isError"):
            raise SystemExit(f"{tool} failed: {text[:2000]}")
        return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--module", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--idea-build", default="263.6800.24")
    ap.add_argument("--work", default=None)
    ap.add_argument("--batch", type=int, default=0, help="files per convert call; 0 = all in one call")
    ap.add_argument("--xmx", default="8g")
    ap.add_argument("--kotlin", default="2.1.21", help="Kotlin version configured in the copy (langchain4j's own)")
    ap.add_argument("--jdk", default=None, help="the project JDK; default $JAVA_HOME, else the java on PATH")
    a = ap.parse_args()

    module = Path(a.module).expanduser().resolve()
    checkout = Path(git(module, "rev-parse", "--show-toplevel"))
    rel = module.relative_to(checkout)
    out = Path(a.out).resolve()
    work = Path(a.work).resolve() if a.work else out.parent / (out.name + ".work")
    shutil.rmtree(out, ignore_errors=True)
    shutil.rmtree(work, ignore_errors=True)
    out.mkdir(parents=True)
    work.mkdir(parents=True)

    jdk = a.jdk or os.environ.get("JAVA_HOME") or str(Path(shutil.which("java")).resolve().parent.parent)
    if not (Path(jdk) / "bin" / "java").exists():
        raise SystemExit(f"no JDK at {jdk}")
    jdk_version = subprocess.run([str(Path(jdk) / "bin" / "java"), "-version"], capture_output=True,
                                 text=True).stderr.splitlines()[0]
    idea = ensure_idea(a.idea_build)
    build_txt = (idea / "build.txt").read_text().strip()
    log(f"IDE {build_txt}; corpus {checkout.name} @ {git(checkout, 'rev-parse', 'HEAD')}")

    # a copy of the checkout without .git and build output: J2K writes into it
    copy = work / checkout.name
    shutil.copytree(checkout, copy, ignore=shutil.ignore_patterns(".git", "target", "build", ".gradle", ".idea"))
    project = copy / rel
    if (project / "pom.xml").exists():
        configure_kotlin_maven(project / "pom.xml", a.kotlin)
        log(f"configured Kotlin {a.kotlin} in {rel}/pom.xml")
    else:
        raise SystemExit("only Maven modules can be configured for Kotlin so far")
    main_root = project / "src" / "main" / "java"
    test_root = project / "src" / "test" / "java"
    java_files = sorted(p.relative_to(project).as_posix() for p in main_root.rglob("*.java"))
    if not java_files:
        raise SystemExit(f"no main sources under {main_root}")
    log(f"{len(java_files)} main source files")

    vmoptions = work / "idea.vmoptions"
    vmoptions.write_text("\n".join([
        f"-Xmx{a.xmx}", "-Djava.awt.headless=true",
        f"-Didea.config.path={work}/ide/config", f"-Didea.system.path={work}/ide/system",
        f"-Didea.plugins.path={work}/ide/plugins", f"-Didea.log.path={work}/ide/log",
        # without a project JDK J2K resolves no java.* type: `Object?`, no MutableList (warmup's JdkWarmupProjectActivity)
        f"-Dproject.jdk={jdk}", "-Dproject.jdk.name=corpus-jdk",
    ]) + "\n")
    env = dict(os.environ, IDEA_VM_OPTIONS=str(vmoptions))
    idea_sh = str(idea / "bin" / "idea.sh")
    j2k_log = out / "j2k.log"

    t0 = time.time()
    log("warmup (project import and indexing)")
    with open(j2k_log, "w") as lf:
        rc = subprocess.run([idea_sh, "warmup", f"--project-dir={project}"], env=env, stdout=lf,
                            stderr=subprocess.STDOUT).returncode
    log(f"warmup rc={rc} after {time.time() - t0:.0f}s")
    if rc != 0:
        raise SystemExit(f"warmup failed; see {j2k_log}")

    srv_log = out / "mcp-server.log"
    srv = subprocess.Popen([idea_sh, "mcpServer", f"--project={project}", f"--port={PORT}",
                            "--invocation-mode=direct", f"--allowed-tools={','.join(TOOLS)}"],
                           env=env, stdout=open(srv_log, "w"), stderr=subprocess.STDOUT)
    try:
        for i in range(600):
            if "MCP Server started" in srv_log.read_text(errors="replace"):
                break
            if srv.poll() is not None:
                raise SystemExit(f"MCP server died; see {srv_log}")
            time.sleep(1)
        else:
            raise SystemExit("MCP server never came up in 600s")
        mcp = Mcp(PORT, project)
        mcp.connect()
        tools = mcp.tools()
        if "convert_java_to_kotlin" not in tools:
            raise SystemExit(f"convert_java_to_kotlin absent (IDE {build_txt}); tools: {tools}")
        with open(j2k_log, "a") as lf:
            lf.write("\n=== get_project_modules\n" + mcp.call("get_project_modules", {}, 300) + "\n")
        batches = [java_files] if a.batch <= 0 else [java_files[i:i + a.batch]
                                                      for i in range(0, len(java_files), a.batch)]
        t1 = time.time()
        for n, batch in enumerate(batches, 1):
            log(f"convert batch {n}/{len(batches)}: {len(batch)} files")
            text = mcp.call("convert_java_to_kotlin", {"file_paths": batch}, 6 * 3600)
            with open(j2k_log, "a") as lf:
                lf.write(f"\n=== convert batch {n}\n{text}\n")
        log(f"converted in {time.time() - t1:.0f}s")
    finally:
        srv.terminate()
        try:
            srv.wait(60)
        except subprocess.TimeoutExpired:
            srv.kill()

    left = sorted(p.relative_to(main_root).as_posix() for p in main_root.rglob("*.java"))
    kt = sorted(main_root.rglob("*.kt"))
    idea_log = work / "ide" / "log" / "idea.log"
    if idea_log.exists():
        shutil.copy(idea_log, out / "idea.log")
    severe = idea_log.read_text(errors="replace") if idea_log.exists() else ""
    post_errors = len(re.findall(r"SEVERE - #o\.j\.k\.j\.PostProcessor - (?!Current Command|Last Action|Plugin to blame|OS:|JDK:|IntelliJ)",
                                 severe))
    # a JDK J2K could not resolve shows as java.lang.Object kept as a type
    public = sum(len(re.findall(r"^\s*public ", p.read_text(errors="replace"), re.M)) for p in kt)
    unresolved = sum(len(re.findall(r"\bObject\b\??[>,)\s]", p.read_text(errors="replace"))) for p in kt)
    shutil.copytree(main_root, out / "src", ignore=shutil.ignore_patterns("*.java"))
    if test_root.is_dir():
        shutil.copytree(test_root, out / "test")
    (out / "provenance.txt").write_text("\n".join([
        f"ide: {build_txt}",
        f"jdk: {jdk} ({jdk_version})",
        f"kotlin configured: {a.kotlin}",
        f"sanity: 'Object' kept as a Kotlin type {unresolved} times (should be ~0: Any)",
        f"sanity: J2K post-processor exceptions {post_errors} (one aborts the remaining post-processing; see idea.log)",
        f"sanity: 'public' modifiers {public} (J2K's post-processing removes them; thousands = it did not run)",
        f"corpus: {checkout.name} {git(checkout, 'rev-parse', 'HEAD')} {rel}",
        f"main java files: {len(java_files)}",
        f"kotlin files produced: {len(kt)}",
        f"java files left unconverted: {len(left)}",
        f"batches: {len(batches)}",
        f"seconds: {time.time() - t0:.0f}",
    ] + [f"  left: {f}" for f in left]) + "\n")
    log(f"done: {len(kt)} .kt, {len(left)} .java left; {out}")


if __name__ == "__main__":
    sys.exit(main())
