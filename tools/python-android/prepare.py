"""Prepare the embedded CPython runtime for the OPhone AI APK.

Downloads the official python.org Android release for each supported ABI,
verifies its SHA-256, compiles the small executable launcher with the NDK and
writes generated jniLibs/ and assets/ trees that Gradle adds to the app.

Usage: prepare.py --sdk <android sdk> --cache <dir> --out <dir>
Requires Python 3.7 or newer on the build machine. Output is reused when the
inputs are unchanged.
"""
import argparse
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

VERSION = "3.14.8"
SERIES = "3.14"
RELEASES = {
    # Android ABI: (GNU triplet, SHA-256 of python.org release tarball)
    "arm64-v8a": ("aarch64-linux-android", "11324313957da3736f44e90257304d288864321a4dd376ed0cc35a54eacf9a33"),
    "x86_64": ("x86_64-linux-android", "58eb3b2d76ef57e076985a0a6b093cf08906d65ea4ab78cccc6d894d4250c972"),
}
# 32-bit ABIs are not supported by CPython on Android. A stub keeps the APK
# installable there; the runtime then reports Python as unavailable.
STUB_ABIS = {"armeabi-v7a": "armv7a-linux-androideabi", "x86": "i686-linux-android"}
API_LEVEL = 24
NATIVE_LIBS = ["libpython%s.so" % SERIES, "libcrypto_python.so", "libssl_python.so", "libsqlite3_python.so"]
# Large or desktop-only parts of the standard library that are not useful to
# the on-phone agent.
EXCLUDED_DIRS = {"test", "idlelib", "tkinter", "turtledemo", "__pycache__", "site-packages"}
EXCLUDED_FILES = {"turtle.py"}
EXCLUDED_MODULE_PREFIXES = ("_test", "_ctypes_test", "_xxtestfuzz", "xxlimited", "xxsubtype")
SCRIPT_REVISION = "1"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def download(abi, triplet, expected, cache):
    name = "python-%s-%s.tar.gz" % (VERSION, triplet)
    path = os.path.join(cache, name)
    if not os.path.exists(path) or sha256(path) != expected:
        url = "https://www.python.org/ftp/python/%s/%s" % (VERSION, name)
        print("Downloading " + url, flush=True)
        os.makedirs(cache, exist_ok=True)
        partial = path + ".partial"
        with urllib.request.urlopen(url, timeout=120) as response, open(partial, "wb") as out:
            shutil.copyfileobj(response, out)
        actual = sha256(partial)
        if actual != expected:
            os.remove(partial)
            raise SystemExit("SHA-256 mismatch for %s: %s" % (name, actual))
        os.replace(partial, path)
    return path


def ndk_toolchain(sdk):
    ndk_root = os.path.join(sdk, "ndk")
    versions = sorted(os.listdir(ndk_root)) if os.path.isdir(ndk_root) else []
    if not versions:
        raise SystemExit("Android NDK not found under " + ndk_root)
    prebuilt = os.path.join(ndk_root, versions[-1], "toolchains", "llvm", "prebuilt")
    return os.path.join(prebuilt, os.listdir(prebuilt)[0], "bin")


def compiler(bin_dir, clang_triplet):
    base = os.path.join(bin_dir, "%s%d-clang" % (clang_triplet, API_LEVEL))
    return base + ".cmd" if os.name == "nt" else base


def run(cmd):
    subprocess.check_call(cmd, shell=False)


def stdlib_member(rel):
    parts = rel.split("/")
    if any(p in EXCLUDED_DIRS for p in parts[:-1]) or parts[-1] in EXCLUDED_FILES:
        return False
    if parts[0] == "lib-dynload" and parts[-1].startswith(EXCLUDED_MODULE_PREFIXES):
        return False
    return not parts[0].startswith("config-")


def abi_specific(rel, triplet):
    return rel.startswith("lib-dynload/") or triplet in rel or rel == "build-details.json"


def write_zip(path, entries):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel in sorted(entries):
            info = zipfile.ZipInfo(rel, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, entries[rel])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", required=True)
    parser.add_argument("--cache", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    launcher = os.path.join(here, "launcher.c")
    with open(launcher, "rb") as f:
        launcher_hash = hashlib.sha256(f.read()).hexdigest()
    fingerprint = hashlib.sha256(json.dumps([VERSION, RELEASES, SCRIPT_REVISION, launcher_hash, sorted(STUB_ABIS)]).encode()).hexdigest()
    stamp = os.path.join(args.out, "stamp.txt")
    if os.path.exists(stamp) and open(stamp).read() == fingerprint:
        print("Embedded Python is up to date")
        return

    shutil.rmtree(args.out, ignore_errors=True)
    jni = os.path.join(args.out, "jniLibs")
    assets = os.path.join(args.out, "assets", "python")
    os.makedirs(assets)
    tools = ndk_toolchain(args.sdk)
    work = os.path.join(args.out, "work")

    common = None
    manifest = {"version": VERSION, "series": SERIES, "abis": {}}
    for abi, (triplet, expected) in sorted(RELEASES.items()):
        archive = download(abi, triplet, expected, args.cache)
        files = {}
        with tarfile.open(archive, "r:gz") as tar:
            for member in tar.getmembers():
                if not member.isfile():
                    continue
                name = member.name[2:] if member.name.startswith("./") else member.name
                files[name] = tar.extractfile(member).read()

        out_dir = os.path.join(jni, abi)
        os.makedirs(out_dir)
        for lib in NATIVE_LIBS:
            with open(os.path.join(out_dir, lib), "wb") as f:
                f.write(files["prefix/lib/" + lib])

        include = os.path.join(work, abi, "include")
        libdir = os.path.join(work, abi, "lib")
        os.makedirs(libdir)
        for name, data in files.items():
            if name.startswith("prefix/include/python" + SERIES + "/"):
                target = os.path.join(include, name[len("prefix/include/"):])
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "wb") as f:
                    f.write(data)
        shutil.copy(os.path.join(out_dir, "libpython%s.so" % SERIES), libdir)
        run([compiler(tools, triplet), "-O2", "-fPIE", "-pie",
             "-I", os.path.join(include, "python" + SERIES), launcher,
             "-L", libdir, "-lpython" + SERIES, "-lm", "-llog", "-o", os.path.join(out_dir, "libophone_python.so")])

        prefix = "prefix/lib/python%s/" % SERIES
        stdlib = {n[len(prefix):]: d for n, d in files.items() if n.startswith(prefix) and stdlib_member(n[len(prefix):])}
        specific = {n: d for n, d in stdlib.items() if abi_specific(n, triplet)}
        shared = {n: d for n, d in stdlib.items() if n not in specific}
        if common is None:
            common = shared
        elif set(common) != set(shared) or any(common[n] != shared[n] for n in shared):
            raise SystemExit("ABI-independent standard library differs between ABIs")
        write_zip(os.path.join(assets, abi + ".zip"), specific)
        manifest["abis"][abi] = {"zip": abi + ".zip", "sha256": sha256(os.path.join(assets, abi + ".zip"))}

    write_zip(os.path.join(assets, "stdlib.zip"), common)
    manifest["stdlib"] = {"zip": "stdlib.zip", "sha256": sha256(os.path.join(assets, "stdlib.zip"))}
    manifest["id"] = VERSION + "-" + hashlib.sha256(json.dumps(manifest, sort_keys=True).encode()).hexdigest()[:16]
    with open(os.path.join(assets, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)

    stub = os.path.join(work, "stub.c")
    with open(stub, "w") as f:
        f.write("int ophone_python_unavailable;\n")
    for abi, clang_triplet in sorted(STUB_ABIS.items()):
        os.makedirs(os.path.join(jni, abi))
        run([compiler(tools, clang_triplet), "-shared", "-fPIC", stub, "-o", os.path.join(jni, abi, "libophone_python_stub.so")])

    shutil.rmtree(work)
    with open(stamp, "w") as f:
        f.write(fingerprint)
    print("Prepared embedded Python " + manifest["id"])


if __name__ == "__main__":
    main()
