#!/usr/bin/env python3
"""Test VoicePad's "type as keyboard" mode from a Mac.

Usage:
    python3 scripts/mac-keyboard-test.py                 # check pairing, then capture keystrokes
    python3 scripts/mac-keyboard-test.py --sample        # print a test note to put on the phone
    python3 scripts/mac-keyboard-test.py --expect "..."  # also diff against the note's text
    python3 scripts/mac-keyboard-test.py --expect-file note.txt

Steps:
 1. On the phone, tap VoicePad's keyboard icon (it turns red).
 2. On the Mac: System Settings > Bluetooth, and connect "VoicePad".
 3. Run this script. Leave this Terminal window focused.
 4. On the phone, tap the keyboard icon (types the latest note) or long-press
    a note > "Type on paired device".
 5. Capture ends 3 s after the last key, or press Ctrl-C on the Mac.

Only the Python 3 standard library is used. macOS asks to install the
Command Line Tools the first time `python3` runs, if they aren't there yet.
"""

import argparse
import difflib
import os
import plistlib
import select
import subprocess
import sys
import termios
import time
import tty
import unicodedata

IDLE_SECONDS = 3.0
# Same as HidKeyMap.kt: characters with no ASCII decomposition that are
# typed as a substitute rather than dropped.
TYPOGRAPHY = {
    "‘": "'", "’": "'", "‚": "'", "′": "'",
    "“": '"', "”": '"', "„": '"', "″": '"',
    "«": '"', "»": '"',
    "‐": "-", "‑": "-", "‒": "-", "–": "-", "—": "-", "−": "-",
    "•": "*", "·": "*",
    " ": " ", " ": " ", " ": " ",
    "×": "x", "÷": "/", "⁄": "/",
    "ß": "ss", "æ": "ae", "Æ": "AE", "œ": "oe", "Œ": "OE",
    "ø": "o", "Ø": "O", "ł": "l", "Ł": "L", "đ": "d", "Đ": "D",
}
TYPABLE = set(
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    " \n\t-_=+[]{}\\|;:'\",<.>/?`~!@#$%^&*()"
)

SAMPLE = (
    "The quick brown fox jumps over the lazy dog.\n"
    "THE QUICK BROWN FOX: 0123456789\n"
    "!@#$%^&*() -_=+ [{]}\\| ;:'\" ,<.>/? `~\n"
    "Repeats: aa bb 1122 ... ---\n"
    "Folded: don’t “quote” — café ½ straße…"
)


def fold(text: str) -> str:
    """What VoicePad should type for [text] (mirrors HidKeyMap.strokes)."""
    out = []
    for ch in text:
        if ch == "\r":
            continue
        if ord(ch) < 128:
            folded = ch
        elif ch in TYPOGRAPHY:
            folded = TYPOGRAPHY[ch]
        else:
            folded = "".join(
                d if ord(d) < 128 else TYPOGRAPHY.get(d, "")
                for d in unicodedata.normalize("NFKD", ch)
            )
        out.append("".join(c for c in folded if c in TYPABLE))
    return "".join(out)


def run(cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.SubprocessError):
        return ""


def check_pairing():
    print("== Bluetooth ==")
    info = run(["system_profiler", "SPBluetoothDataType"])
    if not info:
        print("  Couldn't read Bluetooth info (system_profiler failed).")
    else:
        lines = info.splitlines()
        # Show the "Connected:" / "Not Connected:" heading the device sits under.
        section = None
        found = False
        for i, line in enumerate(lines):
            stripped = line.strip()
            if stripped in ("Connected:", "Not Connected:"):
                section = stripped.rstrip(":")
            if "voicepad" in stripped.lower():
                found = True
                print(f"  {stripped}  [{section or 'unknown'}]")
                for detail in lines[i + 1:i + 8]:
                    if ":" in detail and detail.strip().split(":")[0] in (
                        "Address", "Minor Type", "Services", "Vendor ID", "Product ID",
                    ):
                        print(f"    {detail.strip()}")
        if not found:
            print("  VoicePad isn't paired. Tap the keyboard icon on the phone, then connect it in")
            print("  System Settings > Bluetooth (it shows up as a keyboard named VoicePad).")

    hid = run(["hidutil", "list"])
    if hid:
        match = [l for l in hid.splitlines() if "voicepad" in l.lower()]
        print(f"  HID keyboard device: {'present' if match else 'not present (not connected right now)'}")

    print("\n== Keyboard layout ==")
    try:
        path = os.path.expanduser("~/Library/Preferences/com.apple.HIToolbox.plist")
        with open(path, "rb") as f:
            sources = plistlib.load(f).get("AppleSelectedInputSources", [])
        names = [s.get("KeyboardLayout Name") or s.get("Input Mode") or s.get("Bundle ID") for s in sources]
        names = [n for n in names if n]
        print(f"  Active input sources: {', '.join(names) or 'unknown'}")
        if names and not any(n in ("U.S.", "ABC", "US Extended", "U.S. International - PC") for n in names):
            print("  ! VoicePad sends US-layout key codes. With another layout, symbols (and on")
            print("    AZERTY/QWERTZ some letters) come out wrong. Switch to U.S. or ABC to test.")
    except (OSError, plistlib.InvalidFileException, AttributeError):
        print("  Couldn't read the input source; make sure it's U.S. or ABC.")
    print()


def capture():
    if not sys.stdin.isatty():
        sys.exit("Run this in Terminal: it needs to read keystrokes directly.")
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    print("Waiting for VoicePad to type (keep this window focused; Ctrl-C to stop)...")
    received = []
    times = []
    try:
        tty.setraw(fd)
        while True:
            timeout = None if not times else IDLE_SECONDS
            ready, _, _ = select.select([fd], [], [], timeout)
            if not ready:
                break
            data = os.read(fd, 1024)
            now = time.monotonic()
            if b"\x03" in data:  # Ctrl-C
                data = data.split(b"\x03")[0]
                if data:
                    received.append(data)
                    times.append(now)
                break
            received.append(data)
            times.append(now)
            # Echo so the user can watch it arrive.
            os.write(sys.stdout.fileno(), data.replace(b"\r", b"\r\n"))
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)
    text = b"".join(received).decode("utf-8", errors="replace").replace("\r", "\n")
    return text, times


def longest_run(text):
    best, best_ch, run_len, prev = 0, "", 0, None
    for ch in text:
        run_len = run_len + 1 if ch == prev else 1
        prev = ch
        if run_len > best:
            best, best_ch = run_len, ch
    return best, best_ch


def report(text, times, expected):
    print("\n\n== Result ==")
    if not text:
        print("  Nothing was typed. Is the phone connected (icon in the theme colour, not red)?")
        return 1
    duration = times[-1] - times[0] if len(times) > 1 else 0.0
    print(f"  Characters received: {len(text)}")
    if duration > 0:
        print(f"  Duration: {duration:.2f} s  ({len(text) / duration:.0f} chars/s)")
    run_len, run_ch = longest_run(text)
    if run_len >= 8 and expected is None:
        print(f"  ! {run_len}x {run_ch!r} in a row: possibly a stuck key (auto-repeat).")
    non_ascii = sorted({c for c in text if ord(c) >= 128})
    if non_ascii:
        print(f"  ! Unexpected non-ASCII characters: {''.join(non_ascii)!r} (keyboard layout isn't U.S.?)")

    if expected is None:
        print("  (Pass --expect or --expect-file with the note's text to check every character.)")
        return 0

    want = fold(expected)
    if text == want:
        print(f"  PASS: all {len(want)} characters arrived exactly as expected.")
        return 0

    matcher = difflib.SequenceMatcher(a=want, b=text, autojunk=False)
    dropped = extra = wrong = 0
    print(f"  FAIL: {matcher.ratio() * 100:.1f}% match. Differences (expected -> received):")
    shown = 0
    for op, a0, a1, b0, b1 in matcher.get_opcodes():
        if op == "equal":
            continue
        exp, got = want[a0:a1], text[b0:b1]
        if op == "delete":
            dropped += len(exp)
        elif op == "insert":
            extra += len(got)
        else:
            wrong += max(len(exp), len(got))
        if shown < 20:
            context = want[max(0, a0 - 12):a0].replace("\n", "\\n")
            print(f"    after ...{context!r}: {exp!r} -> {got!r}")
            shown += 1
    if shown == 20:
        print("    (more differences not shown)")
    print(f"  Dropped: {dropped}  Extra: {extra}  Changed: {wrong}")
    if dropped and not extra:
        print("  Hint: only drops usually means keys are sent too fast for this host.")
    if any(e.lower() == g.lower() and e != g for e, g in zip(want, text)):
        print("  Hint: case mismatches point at the Shift modifier.")
    return 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sample", action="store_true", help="print a test note covering every key, then exit")
    parser.add_argument("--expect", help="the note's text, to diff against what arrives")
    parser.add_argument("--expect-file", help="file containing the note's text")
    parser.add_argument("--no-check", action="store_true", help="skip the Bluetooth/layout check")
    args = parser.parse_args()

    if args.sample:
        print(SAMPLE)
        print("\n# Put the text above in a VoicePad note, then run:")
        print("#   python3 scripts/mac-keyboard-test.py --expect-file <file with that text>")
        return 0

    expected = args.expect
    if args.expect_file:
        with open(args.expect_file, encoding="utf-8") as f:
            expected = f.read().rstrip("\n")

    if sys.platform != "darwin":
        print("Note: this is meant for macOS; the Bluetooth check is skipped.\n")
    elif not args.no_check:
        check_pairing()

    text, times = capture()
    return report(text, times, expected)


if __name__ == "__main__":
    sys.exit(main())
