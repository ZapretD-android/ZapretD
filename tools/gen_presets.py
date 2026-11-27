import glob
import json
import os
import re
import shutil
import sys

if len(sys.argv) != 2:
    sys.exit("Использование: python3 tools/gen_presets.py <папка zapret-discord-youtube>")

SRC = sys.argv[1]
OUT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def read_args(path):
    text = open(path, encoding="utf-8", errors="ignore").read().replace("\r", "")
    joined = re.sub(r"\^\n", " ", text)
    start = joined.index("winws.exe")
    rest = joined[start + len("winws.exe"):].lstrip('"').strip()
    rest = rest.replace("^!", "\x00BANG\x00")
    rest = rest.replace("%BIN%", "{BIN}/").replace("%LISTS%", "{LISTS}/")
    rest = rest.replace('"', "")
    return rest.split()

def split_segments(tokens):
    segments, current = [], []
    for tok in tokens:
        if tok == "--new":
            segments.append(current)
            current = []
        else:
            current.append(tok.replace("\x00BANG\x00", "!"))
    segments.append(current)
    return segments

def classify(segment):
    joined = " ".join(segment)
    if "GameFilter" in joined or "ipset-all" in joined:
        return None
    if "filter-l7=discord" in joined or "hostlist-domains=discord.media" in joined:
        return "discord"
    if "list-google" in joined:
        return "youtube"
    if "list-general" in joined:
        return "general"
    return None

def desync_of(segment):
    for tok in segment:
        if tok.startswith("--dpi-desync="):
            return tok.split("=", 1)[1]
    return ""

def byedpi_for(segments):
    tcp_general = ""
    for seg in segments:
        joined = " ".join(seg["args"])
        if "--filter-tcp=80,443" in joined and "list-general" in joined:
            tcp_general = desync_of(seg["args"])
    if "multidisorder" in tcp_general:
        return "--disorder 1+s --fake -1 --ttl 8 --tlsrec 1+s"
    if "fakedsplit" in tcp_general or "fakeddisorder" in tcp_general:
        return "--split 1+s --fake -1 --ttl 8"
    if "multisplit" in tcp_general:
        return "--split 1+s --tlsrec 1+s"
    if tcp_general.startswith("fake"):
        return "--disorder 2 --fake -1 --ttl 8"
    return "--split 1+s --tlsrec 1+s"

presets = []
for path in sorted(glob.glob(SRC + "/general*.bat")):
    name = os.path.basename(path)[:-4]
    segments = []
    for seg in split_segments(read_args(path)):
        seg = [t for t in seg if not t.startswith("--wf-")]
        kind = classify(seg)
        if kind:
            segments.append({"kind": kind, "args": seg})
    presets.append({"name": name, "segments": segments, "byedpi": byedpi_for(segments)})

presets.sort(key=lambda p: (p["name"] != "general", p["name"]))
os.makedirs(OUT + "/assets/lists", exist_ok=True)
os.makedirs(OUT + "/assets/bin", exist_ok=True)
json.dump(presets, open(OUT + "/assets/presets.json", "w", encoding="utf-8"), ensure_ascii=False)

for f in ["list-general.txt", "list-google.txt", "list-exclude.txt", "ipset-exclude.txt"]:
    shutil.copy(SRC + "/lists/" + f, OUT + "/assets/lists/" + f)
for f in glob.glob(SRC + "/bin/*.bin"):
    shutil.copy(f, OUT + "/assets/bin/")

print("Пресетов:", len(presets))
