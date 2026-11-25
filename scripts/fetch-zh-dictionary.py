#!/usr/bin/env python3
"""Builds the Chinese dictionary the word breakdown uses, exactly as Android's
`downloadChineseDictionary` Gradle task does, and writes it to
`VoicePad/Resources/zh_dict.dat` (gzip, header included).

Sources, both downloaded at run time:
  - CC-CEDICT (CC BY-SA 4.0, https://cc-cedict.org): words, readings, meanings.
  - jieba's word list (MIT, https://github.com/fxsjy/jieba): word frequencies,
    used to split sentences into words and to rank meanings.

Each CC-CEDICT line is prefixed with its word's jieba frequency and a tab:
    <frequency>\t<traditional> <simplified> [pin1 yin1] /meaning/meaning/
Being derived from CC-CEDICT, the file is CC BY-SA 4.0 too; its header says so.

Usage: scripts/fetch-zh-dictionary.py [--force]
"""
import gzip
import io
import os
import sys
import urllib.request

CEDICT_URLS = [
    "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz",
    "https://raw.githubusercontent.com/cschiller/zhongwen/master/data/cedict_ts.u8",
]
JIEBA_URL = "https://raw.githubusercontent.com/fxsjy/jieba/v0.42.1/jieba/dict.txt"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "VoicePad", "Resources", "zh_dict.dat")


def fetch(url: str) -> bytes:
    print(f"Downloading {url}")
    request = urllib.request.Request(url, headers={"User-Agent": "VoicePad-iOS-dictionary/1"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def cedict_text() -> str:
    for url in CEDICT_URLS:
        try:
            data = fetch(url)
            if url.endswith(".gz"):
                data = gzip.decompress(data)
            text = data.decode("utf-8")
            if sum(1 for line in text.splitlines() if not line.startswith("#")) > 100_000:
                return text
            print(f"  {url} looks incomplete; trying the next copy.")
        except Exception as error:  # network or format trouble: try the next source
            print(f"  {url} failed ({error}); trying the next copy.")
    raise SystemExit("Could not download CC-CEDICT from any source.")


def frequencies() -> dict:
    text = fetch(JIEBA_URL).decode("utf-8")
    found = {}
    for line in text.splitlines():
        parts = line.split(" ")
        if len(parts) >= 2 and parts[1].isdigit():
            found[parts[0]] = int(parts[1])
    if len(found) < 100_000:
        raise SystemExit("jieba's word list looks incomplete.")
    return found


def main() -> None:
    if os.path.exists(TARGET) and "--force" not in sys.argv:
        print(f"{TARGET} already exists; pass --force to rebuild.")
        return

    cedict = cedict_text()
    freq = frequencies()

    out = io.StringIO()
    out.write("# VoicePad Chinese dictionary: CC-CEDICT with jieba word frequencies.\n")
    out.write("# CC-CEDICT: https://cc-cedict.org, licensed CC BY-SA 4.0; so is this file.\n")
    out.write("# jieba word frequencies: https://github.com/fxsjy/jieba (MIT).\n")
    written = 0
    for line in cedict.splitlines():
        if line.startswith("#!"):
            out.write(line + "\n")  # version and date
        if not line.strip() or line.startswith("#"):
            continue
        head = line.split(" [")[0].split(" ")
        if len(head) < 2:
            continue
        simplified = head[1]
        out.write(f"{freq.get(simplified, 0)}\t{line}\n")
        written += 1
    if written < 100_000:
        raise SystemExit(f"Only {written} entries built; the dictionary looks incomplete.")

    os.makedirs(os.path.dirname(TARGET), exist_ok=True)
    with gzip.open(TARGET + ".part", "wb", compresslevel=9) as handle:
        handle.write(out.getvalue().encode("utf-8"))
    os.replace(TARGET + ".part", TARGET)
    print(f"Wrote {TARGET} ({os.path.getsize(TARGET) / 1e6:.1f} MB gz, {written} entries).")


if __name__ == "__main__":
    main()
