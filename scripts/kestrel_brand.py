"""Apply the reviewed Kestrel Android brand overlay; --check never writes.

The original nanoMuse rebrand implementation remains in rebrand.py for upstream
merges. This overlay deliberately leaves runtime identifiers, external service
names, legal attribution, iOS and the temporary upstream icons intact.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android/src/android/app"
MAIN = APP / "src/main"
JAVA = MAIN / "java"
UP = JAVA / "com/openminis/app"
NM = JAVA / "io/github/nanomuse"
REPO = "https://github.com/limingyi2/kestrel-app"
APP_ID = "io.github.limingyi2.kestrel"
# These denote other products/services or attribution, not this app's name.
PRODUCT = re.compile(r"\bnanoMuse\b(?! Cloud| Desktop)")
LITERAL = re.compile(r'"""[\s\S]*?"""|"(?:[^"\\\n]|\\.)*"')


def overlay() -> dict[Path, str]:
    edits: dict[Path, str] = {}

    def replace(path: Path, pairs: list[tuple[str, str]]) -> None:
        text = edits.get(path, path.read_text(encoding="utf-8"))
        for old, new in pairs:
            text = text.replace(old, new)
        edits[path] = text

    replace(
        APP / "build.gradle.kts",
        [
            (
                'applicationId = "io.github.nanomuse.app"',
                f'applicationId = "{APP_ID}" // nanoMuse: Kestrel install identity',
            ),
        ],
    )
    replace(
        MAIN / "res/xml/shortcuts.xml",
        [
            ('android:targetPackage="io.github.nanomuse.app"', f'android:targetPackage="{APP_ID}"'),
        ],
    )
    replace(
        MAIN / "AndroidManifest.xml",
        [
            (
                '<data android:scheme="nanomuse" />',
                '<data android:scheme="kestrel" /><!-- nanoMuse: Kestrel external scheme -->',
            ),
        ],
    )
    replace(
        NM / "deeplink/OwnScheme.kt",
        [
            ('const val SYSTEM = "nanomuse"', 'const val SYSTEM = "kestrel"'),
            ("`nanomuse`", "`kestrel`"),
            ("nanomuse://", "kestrel://"),
        ],
    )
    # Resource values only: never keys, URLs, attributes or copyright comments.
    for path in sorted((MAIN / "res").glob("values*/*strings.xml")):
        text = path.read_text(encoding="utf-8")

        def resource(m: re.Match[str]) -> str:
            key, value = m.group(1), m.group(2)
            if key.startswith("nm_about_upstream") or "https://" in value:
                return m.group(0)
            return f"{m.group(0)[: m.start(2) - m.start()]}{PRODUCT.sub('Kestrel', value)}</string>"

        text = re.sub(r'<string name="([^"]+)"[^>]*>(.*?)</string>', resource, text)
        ET.fromstring(text)
        edits[path] = text

    # Only reviewed display/default-name files. No paths, storage keys, MCP
    # identities, protocol markers, headers or external product names move.
    display_files = [
        UP / rel
        for rel in (
            "agent/SoulStore.kt",
            "ui/chat/ChatIndicators.kt",
            "ui/chat/ChatToolFormatting.kt",
            "ui/settings/SoulSettingsScreen.kt",
            "ui/components/QuickTestSheet.kt",
            "service/AgentForegroundService.kt",
            "service/ToolOverlayController.kt",
            "providers/MinisDocumentsProvider.kt",
            "offload/AlarmOffloadManager.kt",
            "offload/ScheduledNotificationReceiver.kt",
            "backup/BackupPackageReader.kt",
        )
    ] + [
        NM / rel
        for rel in (
            "avatar/AvatarShare.kt",
            "hands/HandsOperator.kt",
            "hands/HandsOffloadHandler.kt",
            "cloud/ProfileSync.kt",
        )
    ]
    for path in display_files:
        text = path.read_text(encoding="utf-8")

        def literal(m: re.Match[str]) -> str:
            value = m.group(0)
            if "https://" in value or "minis://" in value or "/var/" in value:
                return value
            return PRODUCT.sub("Kestrel", value)

        text = LITERAL.sub(literal, text)
        if text != path.read_text(encoding="utf-8") and path.is_relative_to(UP):
            text = (
                "// nanoMuse: Kestrel display-name overlay; runtime identifiers are preserved.\n"
                + text
            )
        edits[path] = text

    replace(
        UP / "data/UpdateChecker.kt",
        [
            (
                'private const val OWNER = "nano-muse"',
                'private const val OWNER = "limingyi2" // nanoMuse: Kestrel update source',
            ),
            ('private const val REPO = "nanoMuse"', 'private const val REPO = "kestrel-app"'),
            ("https://github.com/nano-muse/nanoMuse/releases", f"{REPO}/releases"),
        ],
    )
    replace(
        NM / "community/UpdateCheck.kt",
        [
            ("https://nanomuse.cn/dl/", f"{REPO}/releases"),
            (f"{REPO}/releasesindex.json", f"{REPO}/releases/download/update-index/index.json"),
            (
                "https://api.github.com/repos/nano-muse/nanoMuse/releases/latest",
                "https://api.github.com/repos/limingyi2/kestrel-app/releases/latest",
            ),
            (
                "get(INDEX_URL)?.let { parseIndex(it) }?.let { return it }",
                "// Kestrel: GitHub is the only update authority until our index is deployed.",
            ),
        ],
    )
    replace(NM / "community/StarPrompt.kt", [("https://github.com/nano-muse/nanoMuse", REPO)])
    replace(
        UP / "ui/settings/SettingsScreen.kt",
        [
            ("https://github.com/nano-muse/nanoMuse/issues/new", f"{REPO}/issues/new"),
            ("nanoMuse Version", "Kestrel Version"),
            ("nanoMuse Feedback", "Kestrel Feedback"),
        ],
    )
    about = UP / "ui/settings/AboutScreen.kt"
    # First link is this repository; the later upstream attribution stays nanoMuse.
    before, marker, after = about.read_text(encoding="utf-8").partition(
        "title = stringResource(R.string.nm_about_upstream)"
    )
    text = before.replace('"https://github.com/nano-muse/nanoMuse"', f'"{REPO}"')
    text = (text + marker + after).replace(
        "https://github.com/nano-muse/nanoMuse/blob/main/LICENSE", f"{REPO}/blob/main/LICENSE"
    )
    edits[about] = text
    for path in (NM / "avatar/AvatarShare.kt", NM / "ui/cloud/CloudAccountScreen.kt"):
        replace(path, [("https://github.com/nano-muse/nanoMuse", REPO)])

    test = APP / "src/test/java/io/github/nanomuse/deeplink/OwnSchemeTest.kt"
    replace(
        test,
        [
            ('"nanomuse"', '"kestrel"'),
            ('"NanoMuse"', '"Kestrel"'),
            ('"NANOMUSE"', '"KESTREL"'),
            ('"nanomuse-mcp"', '"kestrel-mcp"'),
            ('"nanomuse://', '"kestrel://'),
            ('"NanoMuse://', '"Kestrel://'),
        ],
    )
    return edits


def main() -> int:
    if sys.argv[1:] not in ([], ["--check"]):
        print("usage: python scripts/rebrand.py [--check]", file=sys.stderr)
        return 2
    check = sys.argv[1:] == ["--check"]
    changed = 0
    for path, text in overlay().items():
        if path.read_text(encoding="utf-8") != text:
            changed += 1
            print(f"{'needs rebrand' if check else 'edited'}: {path.relative_to(ROOT)}")
            if not check:
                path.write_text(text, encoding="utf-8", newline="\n")
    # Identity drift must fail even if a future upstream uses a different brand.
    gradle = (APP / "build.gradle.kts").read_text(encoding="utf-8")
    valid = f'applicationId = "{APP_ID}"' in gradle
    for path in (MAIN / "res").glob("values*/strings.xml"):
        valid = valid and ET.parse(path).findtext("string[@name='app_name']") == "Kestrel"
    print(
        f"{changed} files {'pending' if check else 'updated'}; identity {'ok' if valid else 'invalid'}"
    )
    return int(not valid or (check and changed > 0))


if __name__ == "__main__":
    raise SystemExit(main())
