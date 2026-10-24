#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Doc standard checker (S4 quality gates).

Mirrors the rules defined in docs/standards/04-quality-gates.md:
  G1 (blocking): R1-R12
  G2 (warning) : W1-W10
  G3 (manual)  : M1-M5 (not automatable, see the standards doc)

Usage:
  python3 tools/check_doc_standard.py            # G1 blocking + G2 warnings
  python3 tools/check_doc_standard.py --strict   # G1 only (CI mode)
  python3 tools/check_doc_standard.py --fix      # auto-fix W1/W3-ish trivial items
  python3 tools/check_doc_standard.py --quiet    # only print failures

Exit codes:
  0 = passed
  1 = G1 violation(s) found
  2 = environment error (missing repo root, etc.)

Design notes:
  - Python is used instead of PowerShell so the same script runs on the
    GitHub Actions (ubuntu) runner and on developer machines, with identical
    results. A .ps1 / .sh wrapper forwards to this script.
  - Only stdlib is used, no third-party dependencies.
"""

import os
import re
import sys
from datetime import date, datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs")
TEMPLATES = os.path.join(DOCS, "templates")
STANDARDS = os.path.join(DOCS, "standards")

FIX = "--fix" in sys.argv
STRICT = "--strict" in sys.argv
QUIET = "--quiet" in sys.argv

errors = []
warnings = []
oks = []


def err(code, path, msg):
    errors.append((code, path, msg))


def warn(code, path, msg):
    warnings.append((code, path, msg))


def ok(code, path, msg):
    oks.append((code, path, msg))


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------
SKIP_DIRS = {".git", "target", "node_modules", "assets", "template-design"}
PUBLIC_MODULES = [
    "pw-core",
    "pw-web-ui",
    "pw-api-core",
    "pw-route",
    "pw-codegen",
    "pw-reports-v2",
]
BASELINE_FILE = os.path.join(STANDARDS, "legacy-baseline.txt")


def load_legacy_baseline():
    """Paths grandfathered out of the blocking front-matter rule (R1).

    Follows the repo's existing 'baseline only shrinks' pattern used by
    checkstyle-baseline.xml: entries may be removed over time, never added
    without a review.
    """
    if not os.path.isfile(BASELINE_FILE):
        return set()
    out = set()
    with open(BASELINE_FILE, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#"):
                out.add(line.replace("\\", "/"))
    return out


LEGACY_BASELINE = load_legacy_baseline()


def is_template(path):
    return TEMPLATES in path


def is_standards(path):
    return STANDARDS in path


def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


def iter_docs():
    for base, dirs, files in os.walk(DOCS):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            if f.endswith(".md"):
                yield os.path.join(base, f)


def read(path):
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


# --------------------------------------------------------------------------
# front matter parsing (minimal YAML subset: flat key: value, plus lists)
# --------------------------------------------------------------------------
FM_RE = re.compile(r"^---\s*\n(.*?)\n---\s*\n", re.DOTALL)


def parse_front_matter(text):
    m = FM_RE.match(text)
    if not m:
        return None, text
    data = {}
    key = None
    for line in m.group(1).splitlines():
        if not line.strip() or line.strip().startswith("#"):
            continue
        if line.startswith(("  - ", "- ")) and key:
            item = line.split("- ", 1)[1].strip()
            if item:
                data.setdefault(key, [])
                if isinstance(data[key], list):
                    data[key].append(item)
            continue
        if ":" in line:
            k, v = line.split(":", 1)
            k = k.strip()
            v = v.strip()
            if v == "":
                data[k] = []
                key = k
            else:
                data[k] = v
                key = None
    return data, text[m.end():]


REQUIRED_FM = ["id", "title", "type", "status", "owner", "created", "updated", "applies_to"]
VALID_STATUS = {"draft", "active", "deprecated", "abandoned"}
VALID_TYPES = {
    "index", "standard", "template", "adr", "architecture",
    "usage", "howto", "reference", "runbook", "module", "alignment",
}
# directory -> allowed types
DIR_TYPES = {
    "docs/adr": {"adr"},
    "docs/architecture": {"architecture", "index"},
    "docs/usage": {"usage", "index"},
    "docs/howto": {"howto"},
    "docs/reference": {"reference", "index"},
    "docs/runbook": {"runbook"},
    "docs/report-alignment": {"alignment", "index"},
    "docs/standards": {"standard", "index"},
    "docs/templates": {"template"},
}
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
PLACEHOLDER_RE = re.compile(r"\{\{[A-Z][A-Z0-9_]*\}\}")
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
ABS_PATH_RE = re.compile(r"(?:[A-Za-z]:\\|/Users/|/home/)")
SECRET_RE = re.compile(
    r"(?i)\b(password|passwd|pwd|secret|token|api[_-]?key|apikey)\b\s*[:=]\s*[\"']?[A-Za-z0-9_\-]{6,}"
)
FENCE_RE = re.compile(r"^```.*$", re.MULTILINE)
INLINE_CODE_RE = re.compile(r"`[^`\n]*`")


def strip_code(text):
    """Remove fenced blocks and inline code spans.

    Links and placeholders shown INSIDE code samples are illustrations, not real
    references -- checking them would produce false positives (e.g. a tutorial
    showing how to write a link to a not-yet-existing file).
    """
    out = []
    in_fence = False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            out.append("")
            continue
        out.append("" if in_fence else line)
    return INLINE_CODE_RE.sub("", "\n".join(out))


def doc_type_for(path):
    d = os.path.dirname(path)
    for prefix, types in DIR_TYPES.items():
        if rel(d) == prefix or rel(d).startswith(prefix + "/"):
            return types
    return None


def expected_type_for(path):
    r = rel(path)
    if r == "docs/README.md":
        return {"index"}
    if r.startswith("docs/templates/"):
        return {"template"}
    if r.startswith("docs/standards/"):
        return {"standard"}
    if "/README.md" in r and r.count("/") == 1 and r.split("/")[0].startswith("pw-"):
        return {"module"}
    return doc_type_for(path)


# --------------------------------------------------------------------------
# G1 rules
# --------------------------------------------------------------------------
def check_front_matter(path, text, fm, body):
    r = rel(path)
    if fm is None:
        if is_template(path):
            return
        if r in LEGACY_BASELINE:
            warn("R1", r, "missing YAML front matter (grandfathered in legacy-baseline.txt)")
        else:
            err("R1", r, "missing YAML front matter (required: %s)" % ", ".join(REQUIRED_FM))
        return

    for key in REQUIRED_FM:
        if key not in fm or fm.get(key) in (None, "", []):
            err("R2", r, "front matter field missing or empty: '%s'" % key)

    doc_id = fm.get("id", "")
    if doc_id and not re.match(r"^DOC-[A-Z]+-\d{4}$", str(doc_id)):
        warn("R2", r, "id '%s' does not match DOC-<TYPE>-<4 digits>" % doc_id)

    dtype = fm.get("type")
    if dtype and dtype not in VALID_TYPES:
        err("R4", r, "unknown type '%s' (valid: %s)" % (dtype, ", ".join(sorted(VALID_TYPES))))

    status = fm.get("status")
    if status and status not in VALID_STATUS:
        err("R4", r, "unknown status '%s' (valid: %s)" % (status, ", ".join(sorted(VALID_STATUS))))

    for key in ("created", "updated"):
        val = fm.get(key)
        if val:
            if not DATE_RE.match(str(val)):
                err("R5", r, "field '%s' is not YYYY-MM-DD: %s" % (key, val))
            else:
                try:
                    d = datetime.strptime(str(val), "%Y-%m-%d").date()
                    if d > date.today():
                        err("R5", r, "field '%s' is in the future: %s" % (key, val))
                except ValueError:
                    err("R5", r, "field '%s' is not a valid date: %s" % (key, val))

    expected = expected_type_for(path)
    if expected and dtype and dtype not in expected:
        err("R3", r, "type '%s' not allowed in this location (allowed: %s)"
            % (dtype, ", ".join(sorted(expected))))

    sup = fm.get("superseded_by")
    if sup and str(sup).lower() not in ("null", "none", "") and status != "deprecated":
        err("R11", r, "superseded_by set but status is '%s' (must be 'deprecated')" % status)

    if status == "deprecated" and not re.search(r"已废弃|已替代|已不再适用", body):
        err("R12", r, "status=deprecated but no deprecation notice found in body")


def check_placeholders(path, text):
    if is_template(path):
        return
    # Placeholders inside inline code are illustrations of the rule itself
    # (e.g. a standard that documents the placeholder syntax).
    body = strip_code(text)
    hits = PLACEHOLDER_RE.findall(body)
    if hits:
        err("R7", rel(path), "unresolved template placeholder(s): %s" % ", ".join(sorted(set(hits))))


def check_links(path, text, base_dir):
    r = rel(path)
    if is_template(path):
        return
    # Links shown inside code samples are illustrations, not real references.
    for target in LINK_RE.findall(strip_code(text)):
        t = target.strip()
        if t.startswith(("http://", "https://", "mailto:", "#")):
            continue
        t = t.split("#")[0]
        if not t:
            continue
        if ABS_PATH_RE.search(t):
            warn("W7", r, "link points to an absolute local path: %s" % t)
            continue
        if t.startswith("/"):
            resolved = os.path.join(ROOT, t.lstrip("/"))
        else:
            resolved = os.path.join(base_dir, t)
        if not os.path.exists(resolved):
            err("R6", r, "dangling link target: %s" % t)


def check_module_readmes():
    for m in PUBLIC_MODULES:
        p = os.path.join(ROOT, m, "README.md")
        if os.path.isfile(p):
            ok("R8", rel(p), "module README present")
        else:
            err("R8", rel(os.path.join(ROOT, m)), "public module has no README.md")


def check_root_docs():
    for relpath, code in (("README.md", "R9"), ("docs/README.md", "R10")):
        p = os.path.join(ROOT, relpath)
        if not os.path.isfile(p):
            err(code, relpath, "required document is missing")
        elif os.path.getsize(p) == 0:
            err(code, relpath, "required document is empty")


def check_index_coverage(all_active):
    idx = os.path.join(DOCS, "README.md")
    if not os.path.isfile(idx):
        return
    text = read(idx)
    missing = []
    for path, fm in all_active:
        if fm is None or fm.get("status") != "active":
            continue
        if is_template(path) or is_standards(path):
            continue
        base = os.path.basename(path)
        if base == "README.md":
            continue
        if base not in text:
            missing.append(rel(path))
    for m in missing:
        warn("W10", "docs/README.md", "active document not listed in the index: %s" % m)


# --------------------------------------------------------------------------
# G2 rules
# --------------------------------------------------------------------------
def check_warnings(path, text, fm, body):
    r = rel(path)
    if fm:
        upd = fm.get("updated")
        if upd and DATE_RE.match(str(upd)):
            try:
                d = datetime.strptime(str(upd), "%Y-%m-%d").date()
                if (date.today() - d).days > 180:
                    warn("W1", r, "not updated for more than 6 months (%s)" % upd)
            except ValueError:
                pass
        if str(fm.get("owner", "")).lower() in ("unassigned", ""):
            warn("W2", r, "owner is '%s' (needs an owner)" % fm.get("owner", ""))

    # Headings inside code samples are comments, not document structure
    # (e.g. a bash block containing "# 在仓库根目录执行").
    structure = strip_code(body)

    h1 = re.search(r"^#\s+(.+)$", structure, re.MULTILINE)
    if not h1:
        warn("W3", r, "no H1 heading found")
    elif fm and fm.get("title") and h1.group(1).strip() != str(fm["title"]).strip():
        if not is_template(path):
            warn("W3", r, "H1 ('%s') differs from front matter title ('%s')"
                 % (h1.group(1).strip(), fm["title"]))

    levels = [len(m.group(1)) for m in re.finditer(r"^(#{1,6})\s+\S", structure, re.MULTILINE)]
    for a, b in zip(levels, levels[1:]):
        if b - a > 1:
            warn("W4", r, "heading level jumps from h%d to h%d" % (a, b))
            break

    # Only OPENING fences count: a fence that closes a block has no language tag.
    in_fence = False
    for line in body.splitlines():
        m = re.match(r"^```(.*)$", line)
        if not m:
            continue
        info = m.group(1).strip()
        if not in_fence:
            in_fence = True
            if not info:
                warn("W5", r, "code block without a language tag")
        else:
            in_fence = False

    if len(text.splitlines()) > 1500:
        warn("W6", r, "document is %d lines (>1500), consider splitting" % len(text.splitlines()))

    if SECRET_RE.search(body):
        warn("W8", r, "possible hard-coded credential in body")


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------
def main():
    if not os.path.isdir(DOCS):
        print("ERROR: docs/ not found under %s" % ROOT, file=sys.stderr)
        return 2

    all_docs = []
    for path in sorted(iter_docs()):
        text = read(path)
        fm, body = parse_front_matter(text)
        all_docs.append((path, fm))

        if not is_template(path):
            check_front_matter(path, text, fm, body)
            check_placeholders(path, text)
        check_links(path, text, os.path.dirname(path))
        check_warnings(path, text, fm, body)

    check_root_docs()
    check_module_readmes()
    check_index_coverage(all_docs)

    if not QUIET:
        for code, path, msg in oks:
            print("OK   : %s %s" % (code, msg))
    for code, path, msg in warnings:
        print("WARN : %s %s -- %s" % (code, path, msg))
    for code, path, msg in errors:
        print("FAIL : %s %s -- %s" % (code, path, msg), file=sys.stderr)

    if errors:
        print("Doc standard check FAILED (%d G1 violation(s), %d warning(s))"
              % (len(errors), len(warnings)), file=sys.stderr)
        return 1
    if STRICT:
        print("Doc standard check PASSED (strict mode, %d warning(s) ignored)" % len(warnings))
    else:
        print("Doc standard check PASSED (%d warning(s))" % len(warnings))
    return 0


if __name__ == "__main__":
    sys.exit(main())
