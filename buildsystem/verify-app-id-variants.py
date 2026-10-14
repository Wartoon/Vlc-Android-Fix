#!/usr/bin/env python3
from pathlib import Path
import re


EXPECTED_SUFFIXES = {
    "internalStorage": ".internalstorage",
    "smb": ".smb",
    "tvFix": ".tvfix",
}


def block_for(text: str, name: str) -> str | None:
    match = re.search(rf"(?m)^\s*{re.escape(name)}\s*\{{", text)
    if not match:
        return None
    opening = text.index("{", match.start())
    depth = 0
    for index in range(opening, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[opening + 1:index]
    raise SystemExit(f"Unclosed Gradle block: {name}")


app_text = Path("application/app/build.gradle").read_text()
module_texts = [
    Path("application/vlc-android/build.gradle").read_text(),
    Path("application/resources/build.gradle").read_text(),
]
validated = 0
for variant, suffix in EXPECTED_SUFFIXES.items():
    app_block = block_for(app_text, variant)
    if app_block is None:
        continue
    if f'applicationIdSuffix "{suffix}"' not in app_block:
        raise SystemExit(f"{variant}: app package suffix does not match {suffix}")
    expected = f'buildConfigField "String", "APP_ID", "\\"${{rootProject.ext.appId}}{suffix}\\""'
    for path, text in zip(("vlc-android", "resources"), module_texts):
        module_block = block_for(text, variant)
        if module_block is None or expected not in module_block:
            raise SystemExit(f"{variant}: {path} APP_ID does not match {suffix}")
    validated += 1

if validated == 0:
    raise SystemExit("No installable applicationIdSuffix variants were verified")
print(f"Verified BuildConfig.APP_ID for {validated} installable app variants")
