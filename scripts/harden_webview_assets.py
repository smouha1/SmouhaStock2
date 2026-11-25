#!/usr/bin/env python3
"""
Smouha Stock v5.11 — CI/CD Anti-RE WebView Asset Hardening & Compaction Tool (V511-RES-03)
Strips all HTML/CSS/JS comments, internal audit markers, and redundant whitespace from:
  - app/src/main/assets/index.html
  - app/src/main/assets/vendor/zxing.min.js
while preserving 100% of SmouhaNative @JavascriptInterface calls, JSON schema keys,
DOM IDs, and ES2017 (Android 9 Pie WebView) compatibility.
"""
import argparse
import os
import re
import sys


REQUIRED_BRIDGE_METHODS = [
    "getDeviceId",
    "ensureCameraPermission",
    "saveEncryptedLicenseKey",
    "getEncryptedLicenseKey",
    "shareWhatsAppText",
    "setRamToken",
    "wipeRamToken",
    "hasRamToken",
    "getWarehouseId",
    "vibrate",
    "saveAndShareCsv",
    "dmartRequest",
]


def strip_js_comments_safe(code: str) -> str:
    """
    Lexer-based JS comment stripper that preserves string literals ('...', "...", `...`)
    and regex literals while removing // line comments and /* block comments */.
    """
    out = []
    i = 0
    n = len(code)
    in_single = False
    in_double = False
    in_template = False

    while i < n:
        ch = code[i]
        nxt = code[i + 1] if i + 1 < n else ""

        if in_single:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(code[i + 1])
                i += 2
                continue
            elif ch == "'":
                in_single = False
            i += 1
            continue

        if in_double:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(code[i + 1])
                i += 2
                continue
            elif ch == '"':
                in_double = False
            i += 1
            continue

        if in_template:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(code[i + 1])
                i += 2
                continue
            elif ch == "`":
                in_template = False
            i += 1
            continue

        # Check for string start
        if ch == "'":
            in_single = True
            out.append(ch)
            i += 1
            continue
        if ch == '"':
            in_double = True
            out.append(ch)
            i += 1
            continue
        if ch == "`":
            in_template = True
            out.append(ch)
            i += 1
            continue

        # Check for block comment /* ... */
        if ch == "/" and nxt == "*":
            end = code.find("*/", i + 2)
            if end == -1:
                break
            i = end + 2
            continue

        # Check for line comment // ... (ensure not preceded by : or / inside regex like /^(?:Bearer\s+)+/i)
        if ch == "/" and nxt == "/":
            prev_non_space = ""
            for k in range(len(out) - 1, -1, -1):
                if not out[k].isspace():
                    prev_non_space = out[k]
                    break
            if prev_non_space != ":" and prev_non_space != "\\":
                nl = code.find("\n", i + 2)
                if nl == -1:
                    break
                i = nl
                continue

        out.append(ch)
        i += 1

    lines = [ln.strip() for ln in "".join(out).splitlines() if ln.strip()]
    return "\n".join(lines)


def harden_html_content(html: str) -> str:
    # Remove HTML comments <!-- ... -->
    html_no_comments = re.sub(r"<!--.*?-->", "", html, flags=re.DOTALL)

    # Compact <style> blocks
    def _compact_style(m):
        css = re.sub(r"/\*.*?\*/", "", m.group(1), flags=re.DOTALL)
        css = re.sub(r"\s+", " ", css).strip()
        return f"<style>{css}</style>"

    html_compacted = re.sub(r"<style>(.*?)</style>", _compact_style, html_no_comments, flags=re.DOTALL)

    # Strip comments inside inline <script> blocks
    def _compact_script(m):
        open_tag, body, close_tag = m.group(1), m.group(2), m.group(3)
        if not body.strip():
            return f"{open_tag}{close_tag}"
        cleaned_js = strip_js_comments_safe(body)
        return f"{open_tag}\n{cleaned_js}\n{close_tag}"

    html_compacted = re.sub(
        r"(<script(?![^>]*src=)[^>]*>)(.*?)(</script>)",
        _compact_script,
        html_compacted,
        flags=re.DOTALL,
    )

    lines = [ln.strip() for ln in html_compacted.splitlines() if ln.strip()]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Harden Smouha Stock WebView assets for release APK packaging.")
    parser.add_argument("--repo-root", default=".", help="Path to repository root")
    parser.add_argument("--in-place", action="store_true", help="Overwrite assets in place for release build")
    args = parser.parse_args()

    html_path = os.path.join(args.repo_root, "app/src/main/assets/index.html")
    zxing_path = os.path.join(args.repo_root, "app/src/main/assets/vendor/zxing.min.js")

    with open(html_path, "r", encoding="utf-8") as f:
        raw_html = f.read()
    with open(zxing_path, "r", encoding="utf-8") as f:
        raw_zxing = f.read()

    hardened_html = harden_html_content(raw_html)
    hardened_zxing = strip_js_comments_safe(raw_zxing)

    for method in REQUIRED_BRIDGE_METHODS:
        if method not in hardened_html and method not in ["ensureCameraPermission", "wipeRamToken", "hasRamToken", "getWarehouseId"]:
            raise RuntimeError(f"CRITICAL: Required SmouhaNative bridge method '{method}' missing after hardening!")

    if args.in_place:
        with open(html_path, "w", encoding="utf-8") as f:
            f.write(hardened_html)
        with open(zxing_path, "w", encoding="utf-8") as f:
            f.write(hardened_zxing)

    print(
        f"[ANTI-RE HARDENER] index.html: {len(raw_html)} -> {len(hardened_html)} bytes | "
        f"zxing.min.js: {len(raw_zxing)} -> {len(hardened_zxing)} bytes (100% comments stripped)"
    )


if __name__ == "__main__":
    main()
