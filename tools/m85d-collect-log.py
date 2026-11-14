import subprocess

ADB = r"C:\Android\platform-tools\adb.exe"
DEV = "192.0.2.10:45111"
OUT = r"D:\dsh\native\docs\m85d-logcat.txt"

raw = subprocess.run([ADB, "-s", DEV, "logcat", "-d"], capture_output=True).stdout.decode("utf-8", "replace")
keep = [
    line
    for line in raw.splitlines()
    if any(tag in line for tag in ("DshAgent", "DshTurn", "DshPage", "DshShell", "DshPlugin: debug import"))
]
with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(keep[-500:]))
print("lines", len(keep))
for line in [x for x in keep if "DshAgent" in x][-25:]:
    print(line)

bad = [line for line in raw.splitlines() if ("FATAL EXCEPTION" in line or "ANR in xin.dshaa" in line)]
print("fatal/anr lines:", len(bad))
for line in bad[-5:]:
    print(line)
