#!/usr/bin/env python3
"""Remove obsolete strings from all translated strings.xml files.

Deletes:
  <string name="enable_simpmusic_lyrics">...</string>
  <string name="enable_bini_lyrics">...</string>
  <string name="paxsenix_stats">...</string>
  <string name="paxsenix_stats_failed">...</string>
  <string name="paxsenix_status_operational">...</string>
  <string name="paxsenix_status_degraded">...</string>
  <string name="paxsenix_status_down">...</string>
  <string name="paxsenix_endpoint">...</string>
  <string name="paxsenix_api_key">...</string>
  <string name="paxsenix_check_endpoints">...</string>

Replaces with a comment marker to keep the diff readable.
"""
import re
import sys
from pathlib import Path

RES_DIR = Path("/home/z/my-project/ArchiveTune/app/src/main/res")

OBSOLETE_NAMES = {
    "enable_simpmusic_lyrics",
    "enable_bini_lyrics",
    "paxsenix_stats",
    "paxsenix_stats_failed",
    "paxsenix_status_operational",
    "paxsenix_status_degraded",
    "paxsenix_status_down",
    "paxsenix_endpoint",
    "paxsenix_api_key",
    "paxsenix_check_endpoints",
}

# Pattern: <string name="NAME">CONTENT</string> on a single line, or
# multi-line with newline between > and </string>.
pattern = re.compile(
    r'    <string name="(' + "|".join(re.escape(n) for n in OBSOLETE_NAMES) + r')">'
    r'(?:[^<]|<[^/])*</string>\n?',
    re.DOTALL,
)

total_removals = 0
for xml_path in sorted(RES_DIR.rglob("strings.xml")):
    src = xml_path.read_text()
    new_src, count = pattern.subn("", src)
    if count > 0:
        xml_path.write_text(new_src)
        print(f"  {xml_path.relative_to(RES_DIR.parent.parent)}: removed {count} string(s)")
        total_removals += count
print(f"Total strings removed: {total_removals}")
