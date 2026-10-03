#!/usr/bin/env python3
"""
Assign LCSC footprints to KiCad schematic symbols that have an empty Footprint field.

For each placed symbol with an empty "Footprint" property, the LCSC code is read
from the "Supplier Part" property (e.g. C41349510) and matched against
libs/lcsc/footprints.pretty/C41349510_*.kicad_mod. The Footprint field is then set to
    <lib nickname>:<file name without .kicad_mod>
e.g. lcsc_footprints:C41349510_WIFIM-SMD_ESP32-C3-MINI-1

Usage (run from the project folder, with KiCad CLOSED):
    python assign_missing_footprints.py --dry-run     # preview
    python assign_missing_footprints.py               # apply to every .kicad_sch found
    python assign_missing_footprints.py my.kicad_sch  # apply to specific file(s)

Options:
    --lib-nickname NAME   override the footprint library nickname
    --overwrite           also replace footprints that are already assigned
"""
import argparse
import re
import shutil
import sys
from pathlib import Path

FP_DIR_REL = Path("libs/lcsc/footprints.pretty")
DEFAULT_NICKNAME = "lcsc_footprints"
CODE_PROPS = ("Supplier Part", "LCSC Part", "LCSC", "LCSC#")


def detect_nickname(project_dir: Path) -> str:
    """Look in fp-lib-table for the entry pointing at footprints.pretty."""
    table = project_dir / "fp-lib-table"
    if table.exists():
        text = table.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r'\(lib\s+\(name\s+"?([^")\s]+)"?\).*?\(uri\s+"?([^")]+)"?\)', text, re.S):
            if "lcsc" in m.group(2).lower() and "footprints.pretty" in m.group(2):
                return m.group(1)
    return DEFAULT_NICKNAME


def find_symbol_blocks(text: str):
    """Return (start, end) spans of placed symbols (direct children of the root).
    Symbols inside (lib_symbols ...) are deeper, so they are skipped automatically."""
    spans, stack = [], []
    depth, i, n = 0, 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':  # skip string
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
        elif c == "(":
            depth += 1
            if depth == 2 and re.match(r"\(symbol[\s]", text[i:i + 9]):
                stack.append(i)
            else:
                stack.append(None)
        elif c == ")":
            start = stack.pop()
            if start is not None:
                spans.append((start, i + 1))
            depth -= 1
        i += 1
    return spans


def get_prop(block: str, name: str):
    m = re.search(r'\(property\s+"%s"\s+"((?:[^"\\]|\\.)*)"' % re.escape(name), block)
    return m.group(1) if m else None


def lookup_footprint(fp_dir: Path, code: str):
    code = code.strip().upper()
    return sorted(p.stem for p in fp_dir.glob("*.kicad_mod")
                  if p.stem.upper().startswith(code + "_"))


def process_file(path: Path, fp_dir: Path, nickname: str, dry: bool, overwrite: bool):
    text = path.read_text(encoding="utf-8")
    changes = []  # (start, end, new_block)
    for start, end in find_symbol_blocks(text):
        block = text[start:end]
        ref = get_prop(block, "Reference") or "?"
        if ref.startswith("#"):  # power symbols / flags
            continue
        current = get_prop(block, "Footprint")
        if current and not overwrite:
            continue

        code = next((get_prop(block, p) for p in CODE_PROPS if get_prop(block, p)), None)
        if not code:
            print(f"  [skip] {ref}: no LCSC code in fields")
            continue

        matches = lookup_footprint(fp_dir, code)
        if not matches:
            print(f"  [warn] {ref}: no footprint file found for {code}")
            continue
        if len(matches) > 1:
            print(f"  [warn] {ref}: several matches for {code}, using {matches[0]}: {matches}")

        new_val = f"{nickname}:{matches[0]}"
        if current == new_val:
            continue

        if current is not None:
            new_block = re.sub(r'(\(property\s+"Footprint"\s+)"(?:[^"\\]|\\.)*"',
                               lambda m: m.group(1) + f'"{new_val}"', block, count=1)
        else:  # property missing entirely: add it after the symbol's position
            at = re.search(r"\(at\s+([-\d.]+)\s+([-\d.]+)", block)
            x, y = (at.group(1), at.group(2)) if at else ("0", "0")
            prop = (f'\n\t\t(property "Footprint" "{new_val}"\n\t\t\t(at {x} {y} 0)\n'
                    f'\t\t\t(effects\n\t\t\t\t(font\n\t\t\t\t\t(size 1.27 1.27)\n\t\t\t\t)\n'
                    f'\t\t\t\t(hide yes)\n\t\t\t)\n\t\t)')
            first_prop_end = re.search(r'\(property\s+"Value".*?\n\t\t\)', block, re.S)
            pos = first_prop_end.end() if first_prop_end else block.index("\n")
            new_block = block[:pos] + prop + block[pos:]

        print(f"  [set]  {ref}: {code} -> {new_val}")
        changes.append((start, end, new_block))

    if changes and not dry:
        shutil.copy2(path, path.with_suffix(path.suffix + ".pre_fp_backup"))
        for start, end, new_block in sorted(changes, reverse=True):
            text = text[:start] + new_block + text[end:]
        path.write_text(text, encoding="utf-8")
    return len(changes)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="*", help=".kicad_sch files (default: all in this folder)")
    ap.add_argument("--project-dir", default=".", help="KiCad project folder (default: current)")
    ap.add_argument("--lib-nickname", help="footprint library nickname")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--overwrite", action="store_true")
    args = ap.parse_args()

    project = Path(args.project_dir).resolve()
    fp_dir = project / FP_DIR_REL
    if not fp_dir.is_dir():
        sys.exit(f"Footprint folder not found: {fp_dir}")
    if list(project.glob("~*.lck")):
        print("WARNING: KiCad lock file found - close KiCad first or it may overwrite these changes.\n")

    nickname = args.lib_nickname or detect_nickname(project)
    print(f"Library nickname: {nickname}\nFootprint folder: {fp_dir}\n")

    files = [Path(f) for f in args.files] or sorted(project.glob("*.kicad_sch"))
    total = 0
    for f in files:
        print(f"{f.name}:")
        total += process_file(f, fp_dir, nickname, args.dry_run, args.overwrite)
    print(f"\n{'Would update' if args.dry_run else 'Updated'} {total} symbol(s).")


if __name__ == "__main__":
    main()
