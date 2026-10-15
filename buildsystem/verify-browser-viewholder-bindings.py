#!/usr/bin/env python3
"""Source-level regression guard for RecyclerView's media/separator ViewHolders.

This verifies the production dispatch paths; it is NOT an Android runtime test.
"""
from pathlib import Path
import re

source = Path("application/vlc-android/src/org/videolan/vlc/gui/browser/BaseBrowserAdapter.kt").read_text()
assert "if (viewType == TYPE_MEDIA || viewType == TYPE_STORAGE)" in source, "Do not change storage holder creation"
assert "when (holder) {" in source, "Full binding must dispatch on actual holder type"
assert "is MediaViewHolder -> {" in source and "onBindMediaViewHolder(holder, position)" in source
assert "is SeparatorViewHolder -> holder.binding.title = dataset[position].title" in source
assert re.search(r"if \(payloads\.isEmpty\(\)\) \{\s*onBindViewHolder\(holder, position\)\s*return\s*\}", source), "Full bind must return before payload handling"
assert re.search(r"if \(holder !is MediaViewHolder\) \{[^}]*onBindViewHolder\(holder, position\)\s*return\s*\}", source, re.S), "Separator payloads must use separator binding"
assert "(holder as MediaViewHolder)" not in source, "Unchecked MediaViewHolder cast reintroduced"
assert "itemFocusChanged(position, false, vh.bindingContainer)" in source, "Keep normal media focus"
assert "itemFocusChanged(position, false, holder.bindingContainer)" in source, "Keep media payload focus"
print("PASS: media/separator binding and payload holder safety")
