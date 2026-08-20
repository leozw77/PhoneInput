#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

VERSION = "1.4.0"
DATE = "2026-08-09"
RUNTIME_NAME = f"PhoneInputEnhanced_{VERSION}_Windows-x64_{DATE}"
SOURCE_NAME = f"PhoneInputEnhanced_{VERSION}_Source_{DATE}"
ANDROID_SOURCE_NAME = f"PhoneInputEnhanced_{VERSION}_Android_Source_{DATE}"
CHECKSUM_NAME = f"PhoneInputEnhanced_{VERSION}_SHA256SUMS.txt"
ROOT = Path(__file__).resolve().parents[1]


def run(command: list[str], *, cwd: Path = ROOT, env: dict[str, str] | None = None) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=cwd, env=env, check=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_tree_checksums(root: Path, output: Path, *, excludes: set[str] | None = None) -> None:
    excludes = excludes or set()
    lines: list[str] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path == output:
            continue
        relative_path = path.relative_to(root)
        if any(part in excludes for part in relative_path.parts) or path.suffix == ".pyc":
            continue
        relative = relative_path.as_posix()
        lines.append(f"{sha256(path)}  {relative}")
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def zip_tree(source: Path, output: Path, prefix: str, *, excludes: set[str] | None = None) -> None:
    excludes = excludes or set()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(source.rglob("*")):
            if not path.is_file():
                continue
            relative = path.relative_to(source)
            if any(part in excludes for part in relative.parts) or path.suffix == ".pyc":
                continue
            archive.write(path, (Path(prefix) / relative).as_posix())


def check_javascript() -> None:
    html = (ROOT / "cmd" / "touchpadhost" / "touchpad.html").read_text(encoding="utf-8")
    script = html.split("<script>", 1)[1].split("</script>", 1)[0]
    with tempfile.NamedTemporaryFile("w", suffix=".js", encoding="utf-8", delete=False) as handle:
        handle.write(script)
        temp_path = Path(handle.name)
    try:
        run(["node", "--check", str(temp_path)])
        run(["node", "--check", str(ROOT / "cmd" / "touchpadhost" / "input_component.js")])
    finally:
        temp_path.unlink(missing_ok=True)



def verify_touchpad_first_core(path: Path) -> None:
    data = path.read_bytes()
    old = "$('#touchpad').onclick=()=>location.href='http://'+location.hostname+':51877/';".encode("utf-16le")
    redirect = "location.href='http://'+location.hostname+':51877/';/*default-touchpad-home*/  ".encode("utf-16le")
    if old in data:
        raise RuntimeError("patched core still contains the legacy click-only touchpad entry")
    if data.count(redirect) != 2:
        raise RuntimeError(f"patched core default touchpad redirect count={data.count(redirect)}, expected 2")
    if data.count("phone-input-v1.3.9".encode("utf-16le")) != 2:
        raise RuntimeError("patched core cache revision is not v1.3.9")

def build_windows(runtime_root: Path) -> None:
    env = os.environ.copy()
    env.update({"CGO_ENABLED": "0", "GOOS": "windows", "GOARCH": "amd64"})
    ldflags = "-s -w -H=windowsgui"
    run(["go", "build", "-trimpath", "-ldflags", ldflags, "-o", str(runtime_root / "PhoneInputTouchpadHost.exe"), "./cmd/touchpadhost"], env=env)
    run(["go", "build", "-trimpath", "-ldflags", ldflags, "-o", str(runtime_root / "PhoneInputEnhanced.exe"), "./cmd/launcher"], env=env)
    run(["go", "build", "-trimpath", "-ldflags", ldflags, "-o", str(runtime_root / "PhoneInputSendTo.exe"), "./cmd/sendtophone"], env=env)
    run(["go", "build", "-trimpath", "-ldflags", ldflags, "-o", str(runtime_root / "PhoneInputImageTray.exe"), "./cmd/imagetray"], env=env)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=ROOT / "artifacts")
    parser.add_argument("--browser-test", action="store_true")
    parser.add_argument("--skip-tests", action="store_true")
    args = parser.parse_args()

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    build_dir = ROOT / ".build"
    shutil.rmtree(build_dir, ignore_errors=True)
    runtime_root = build_dir / RUNTIME_NAME
    (runtime_root / "Core").mkdir(parents=True)
    (runtime_root / "docs").mkdir(parents=True)

    if not args.skip_tests:
        run(["go", "test", "./..."])
        run(["go", "vet", "./..."])
        check_javascript()
        if args.browser_test:
            run([sys.executable, "tools/browser_smoke_test.py"])

    build_windows(runtime_root)
    run([
        sys.executable,
        "tools/patch_core.py",
        "baseline/Core/PhoneInputEnhanced_preview.2.exe",
        str(runtime_root / "Core" / "PhoneInputEnhanced.exe"),
    ])
    verify_touchpad_first_core(runtime_root / "Core" / "PhoneInputEnhanced.exe")
    shutil.copy2(ROOT / "baseline" / "Core" / "web.config", runtime_root / "Core" / "web.config")
    shutil.copy2(ROOT / "release" / "README.txt", runtime_root / "README.txt")
    shutil.copy2(ROOT / "release" / "VERSION.txt", runtime_root / "VERSION.txt")
    for name in ["CHANGELOG.md", "DEVELOPMENT_NOTES.md", "TEST_REPORT.md", "KNOWN_ISSUES.md", "ACCEPTANCE_CHECKLIST.md", "VERSION_NOTES.md", "PROTOCOL_V2.md", "NATIVE_PREVIEW1_NOTES.md", "NATIVE_PREVIEW2_NOTES.md", "NATIVE_PREVIEW2_ACCEPTANCE.md", "NATIVE_PREVIEW3_NOTES.md", "NATIVE_PREVIEW4_NOTES.md", "NATIVE_PREVIEW5_NOTES.md", "NATIVE_PREVIEW6_NOTES.md", "NATIVE_PREVIEW7_NOTES.md", "NATIVE_PREVIEW8_NOTES.md", "NATIVE_PREVIEW9_NOTES.md", "NATIVE_PREVIEW9_BUILDFIX1_NOTES.md", "NATIVE_PREVIEW10_NOTES.md", "PhoneInputEnhanced_1.4.0-native-preview.10_CURRENT_VERSION_SUMMARY_2026-08-09.md", "LIFECYCLE_STRESS_TEST.md", "BUILD_STATUS.md"]:
        shutil.copy2(ROOT / name, runtime_root / "docs" / name)
    write_tree_checksums(runtime_root, runtime_root / "SHA256SUMS.txt")

    runtime_zip = output_dir / f"{RUNTIME_NAME}.zip"
    source_zip = output_dir / f"{SOURCE_NAME}.zip"
    android_source_zip = output_dir / f"{ANDROID_SOURCE_NAME}.zip"
    runtime_zip.unlink(missing_ok=True); source_zip.unlink(missing_ok=True); android_source_zip.unlink(missing_ok=True)
    zip_tree(runtime_root, runtime_zip, RUNTIME_NAME)
    zip_tree(ROOT / "android-native", android_source_zip, ANDROID_SOURCE_NAME)

    # Source-tree checksums intentionally exclude the checksum file itself and build output.
    write_tree_checksums(ROOT, ROOT / "SHA256SUMS.txt", excludes={".build", "artifacts", "__pycache__", ".git"})
    zip_tree(ROOT, source_zip, SOURCE_NAME, excludes={".build", "artifacts", "__pycache__", ".git"})

    final_checksum = output_dir / CHECKSUM_NAME
    final_checksum.write_text(
        f"{sha256(runtime_zip)}  {runtime_zip.name}\n"
        f"{sha256(source_zip)}  {source_zip.name}\n"
        f"{sha256(android_source_zip)}  {android_source_zip.name}\n",
        encoding="utf-8",
    )
    print(runtime_zip)
    print(source_zip)
    print(android_source_zip)
    print(final_checksum)


if __name__ == "__main__":
    main()
