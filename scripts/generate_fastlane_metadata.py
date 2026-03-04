#!/usr/bin/env python3
import os
import re
import sys
import json
from pathlib import Path
import xml.etree.ElementTree as ET

def read_file(path):
    return Path(path).read_text(encoding="utf-8", errors="ignore")

def write_file(path, content):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")

def extract_title(readme):
    for line in readme.splitlines():
        line = line.strip()
        if line.startswith("# "):
            return line.lstrip("#").strip()
    return ""

def extract_short_description(readme):
    lines = readme.splitlines()
    for line in lines:
        s = line.strip()
        if not s:
            continue
        if s.startswith("#"):
            continue
        if s.startswith("[") and "](" in s:
            continue
        if s.startswith("!["):
            continue
        if s.startswith("<") and s.endswith(">"):
            continue
        return s
    return ""

def section_lines(readme, header_text):
    lines = readme.splitlines()
    start = None
    for i, line in enumerate(lines):
        if line.strip().lower().startswith("##") and header_text.lower() in line.lower():
            start = i + 1
            break
    if start is None:
        return []
    collected = []
    for line in lines[start:]:
        if line.strip().startswith("##"):
            break
        collected.append(line.rstrip())
    return collected

def bullets_to_html(lines):
    items = []
    for line in lines:
        s = line.strip()
        if s.startswith("- "):
            items.append(s[2:].strip())
    if not items:
        return ""
    inner = "".join(f"<li>{i}</li>" for i in items)
    return f"<ul>{inner}</ul>"

def paragraphs_to_html(lines):
    result = []
    buf = []
    for line in lines:
        if not line.strip():
            if buf:
                result.append("<p>" + " ".join(buf).strip() + "</p>")
                buf = []
        else:
            buf.append(line.strip())
    if buf:
        result.append("<p>" + " ".join(buf).strip() + "</p>")
    return "".join(result)

def extract_full_description(readme):
    features = section_lines(readme, "Features")
    warnings = section_lines(readme, "Warning")
    html = ""
    if features:
        html += "<h3>Features</h3>"
        html += bullets_to_html(features)
    if warnings:
        html += "<h3>Warning</h3>"
        html += paragraphs_to_html(warnings)
    return html or ""

def extract_version_name_as_code(build_gradle_path):
    text = read_file(build_gradle_path)
    # Match versionName "x.y.z"
    m = re.search(r'versionName\s+"(\d+\.\d+\.\d+)"', text)
    if m:
        version_str = m.group(1)
        # Split 0.2.2 -> [0, 2, 2]
        parts = version_str.split('.')
        # Format each part to 4 digits: 0000 0002 0002
        formatted = "".join(f"{int(p):04d}" for p in parts)
        return formatted
    return None

def convert_webp_to_png(src, dest):
    try:
        from PIL import Image
    except Exception:
        return False
    try:
        img = Image.open(src)
        w, h = img.size
        # Google Play requirement: max:min edge ratio should be at most 2.3
        # If it's 2.31, we need to crop it slightly.
        max_edge = max(w, h)
        min_edge = min(w, h)
        ratio = max_edge / min_edge
        if ratio > 2.3:
            # Need to increase min_edge or decrease max_edge.
            # We'll crop the max edge to satisfy max_edge = 2.3 * min_edge
            new_max = int(2.3 * min_edge)
            diff = max_edge - new_max
            if w > h:
                # Landscape: crop width
                img = img.crop((diff // 2, 0, w - (diff - diff // 2), h))
            else:
                # Portrait: crop height
                img = img.crop((0, diff // 2, w, h - (diff - diff // 2)))

        img.save(dest, format="PNG")
        return True
    except Exception as e:
        print(f"Error converting screenshot: {e}")
        return False

def find_icon_pngs(project_root):
    icons = []
    res_dir = Path(project_root) / "app" / "src" / "main" / "res"
    if res_dir.exists():
        for d in res_dir.glob("mipmap-*/"):
            for p in d.glob("ic_launcher*.png"):
                icons.append(p)
    return icons

def create_icon_from_screenshot(screenshot_path, dest_path, size=512):
    try:
        from PIL import Image
    except Exception:
        return False
    try:
        img = Image.open(screenshot_path).convert("RGBA")
        w, h = img.size
        side = min(w, h)
        left = (w - side) // 2
        top = (h - side) // 2
        img = img.crop((left, top, left + side, top + side))
        img = img.resize((size, size))
        img.save(dest_path, format="PNG")
        return True
    except Exception:
        return False

def parse_vector_background_color(path):
    try:
        tree = ET.parse(path)
        root = tree.getroot()
        # find path element with fillColor
        for child in root.iter():
            fill = child.attrib.get("{http://schemas.android.com/apk/res/android}fillColor")
            if fill:
                return fill
    except Exception:
        pass
    return "#000000"

def parse_vector_foreground(path):
    # returns dict with scaleX, scaleY, translateX, translateY, pathData, fillColor
    data = {"scaleX": 1.0, "scaleY": 1.0, "translateX": 0.0, "translateY": 0.0, "pathData": "", "fillColor": "#FFFFFF"}
    try:
        tree = ET.parse(path)
        root = tree.getroot()
        ns = "{http://schemas.android.com/apk/res/android}"
        # group attributes
        group = None
        for child in root:
            if child.tag.endswith("group"):
                group = child
                break
        if group is not None:
            data["scaleX"] = float(group.attrib.get(ns + "scaleX", "1"))
            data["scaleY"] = float(group.attrib.get(ns + "scaleY", "1"))
            data["translateX"] = float(group.attrib.get(ns + "translateX", "0"))
            data["translateY"] = float(group.attrib.get(ns + "translateY", "0"))
            # inside group find path
            for item in group:
                if item.tag.endswith("path"):
                    data["pathData"] = item.attrib.get(ns + "pathData", "")
                    data["fillColor"] = item.attrib.get(ns + "fillColor", "#FFFFFF")
                    break
        else:
            # fallback: direct path
            for item in root.iter():
                if item.tag.endswith("path"):
                    data["pathData"] = item.attrib.get(ns + "pathData", "")
                    data["fillColor"] = item.attrib.get(ns + "fillColor", "#FFFFFF")
                    break
    except Exception:
        pass
    return data

def render_icon_from_vectors(background_xml, foreground_xml, dest_path, size=512):
    try:
        bg_color = parse_vector_background_color(background_xml)
        fg = parse_vector_foreground(foreground_xml)
        # Compose SVG equivalent
        svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">
<rect x="0" y="0" width="108" height="108" fill="{bg_color}"/>
<g transform="translate({fg["translateX"]} {fg["translateY"]}) scale({fg["scaleX"]} {fg["scaleY"]})">
  <path d="{fg["pathData"]}" fill="{fg["fillColor"]}"/>
</g>
</svg>'''
        try:
            from cairosvg import svg2png
            Path(dest_path).parent.mkdir(parents=True, exist_ok=True)
            svg2png(bytestring=svg.encode("utf-8"), write_to=dest_path, output_width=size, output_height=size)
            return True
        except Exception as e:
            print(f"Error converting SVG to PNG: {e}")
            return False
    except Exception as e:
        print(f"Error parsing vectors: {e}")
        return False

def main():
    repo_root = Path(os.getenv("GITHUB_WORKSPACE", Path(__file__).resolve().parents[1]))
    readme_path = repo_root / "README.md"
    build_gradle_path = repo_root / "app" / "build.gradle"
    fallback_gradle = repo_root / "app" / "build.gradle.kts"
    if not build_gradle_path.exists() and fallback_gradle.exists():
        build_gradle_path = fallback_gradle
    out_dir = repo_root / "fastlane" / "metadata" / "android" / "en-US"

    readme = read_file(readme_path)
    title = extract_title(readme)
    short_desc = extract_short_description(readme)
    full_desc = extract_full_description(readme)

    write_file(out_dir / "title.txt", title)
    write_file(out_dir / "short_description.txt", short_desc[:80])
    write_file(out_dir / "full_description.txt", full_desc[:4000])

    # Extract versionName and format as 9-digit code (0.2.2 -> 000002002)
    version_code = extract_version_name_as_code(str(build_gradle_path)) or "000000001"
    release_notes = os.getenv("RELEASE_NOTES", "").strip()
    if not release_notes:
        release_notes = f"Release {os.getenv('RELEASE_TAG', '').strip()}"
    write_file(out_dir / "changelogs" / f"{version_code}.txt", release_notes.encode("ascii", "ignore").decode("ascii")[:500])

    images_dir = out_dir / "images"
    phone_dir = images_dir / "phoneScreenshots"
    phone_dir.mkdir(parents=True, exist_ok=True)
    screenshot_src = repo_root / "screenshot.webp"
    if screenshot_src.exists():
        png_dest = phone_dir / "1.png"
        ok = convert_webp_to_png(str(screenshot_src), str(png_dest))

    icons = find_icon_pngs(str(repo_root))
    if icons:
        largest = max(icons, key=lambda p: p.stat().st_size if p.exists() else 0)
        try:
            import shutil
            shutil.copyfile(str(largest), str(images_dir / "icon.png"))
        except Exception:
            pass
    else:
        # Try to generate icon from vector drawables
        background_xml = repo_root / "app" / "src" / "main" / "res" / "drawable" / "ic_launcher_background.xml"
        foreground_xml = repo_root / "app" / "src" / "main" / "res" / "drawable" / "ic_launcher_foreground.xml"
        made_icon = False
        if background_xml.exists() and foreground_xml.exists():
            made_icon = render_icon_from_vectors(str(background_xml), str(foreground_xml), str(images_dir / "icon.png"), size=512)
        if not made_icon and screenshot_src.exists():
            # Fallback: generate icon from screenshot if available
            create_icon_from_screenshot(str(screenshot_src), str(images_dir / "icon.png"))

    print(json.dumps({"output_dir": str(out_dir)}))

if __name__ == "__main__":
    sys.exit(main() or 0)
