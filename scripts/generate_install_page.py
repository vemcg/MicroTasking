#!/usr/bin/env python3
"""Generates the GitHub Pages install & onboarding page + QR code pointing at a release APK."""
import argparse
import pathlib

import qrcode
from qrcode.constants import ERROR_CORRECT_H
from PIL import Image, ImageDraw, ImageFont

# App icon colors, matching app/src/main/res/drawable/ic_launcher_foreground.xml.
_LOGO_OUTER = "#81C784"
_LOGO_INNER = "#43A047"
_LOGO_CHECK = "#2E7D32"


def _load_font(size: int) -> ImageFont.FreeTypeFont:
    for candidate in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "C:/Windows/Fonts/segoeuib.ttf",
    ):
        try:
            return ImageFont.truetype(candidate, size)
        except OSError:
            continue
    return ImageFont.load_default()


def make_qr_with_logo(url: str, version_label: str, out_path: pathlib.Path) -> None:
    """Renders a QR code with a center carve-out holding the app mark + short version.

    Uses error-correction level H (tolerates ~30% damage) and keeps the carve-out to a
    small fraction of the total area so the code stays reliably scannable.
    """
    qr = qrcode.QRCode(error_correction=ERROR_CORRECT_H, box_size=10, border=4)
    qr.add_data(url)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
    draw = ImageDraw.Draw(img)

    width, height = img.size
    label = version_label if version_label.startswith("v") else f"v{version_label}"

    # Landscape pill in the center: small nested-square mark on the left, version text on
    # the right. Kept well under the ~30% damage budget of error-correction level H.
    box_w, box_h = int(width * 0.46), int(height * 0.16)
    left, top = (width - box_w) // 2, (height - box_h) // 2
    right, bottom = left + box_w, top + box_h
    draw.rounded_rectangle([left, top, right, bottom], radius=box_h // 4, fill="white", outline="#cbd5e1", width=2)

    mark_pad = int(box_h * 0.14)
    mark_size = box_h - 2 * mark_pad
    mark_left, mark_top = left + mark_pad, top + mark_pad
    mark_right, mark_bottom = mark_left + mark_size, mark_top + mark_size
    draw.rectangle([mark_left, mark_top, mark_right, mark_bottom], outline=_LOGO_OUTER, width=max(1, mark_size // 16))
    inset = max(2, mark_size // 6)
    draw.rectangle(
        [mark_left + inset, mark_top + inset, mark_right - inset, mark_bottom - inset],
        outline=_LOGO_INNER,
        width=max(1, mark_size // 14),
    )
    check_w = max(2, mark_size // 10)
    cx0, cy0 = mark_left + mark_size * 0.28, mark_top + mark_size * 0.52
    cx1, cy1 = mark_left + mark_size * 0.44, mark_top + mark_size * 0.70
    cx2, cy2 = mark_left + mark_size * 0.74, mark_top + mark_size * 0.32
    draw.line([cx0, cy0, cx1, cy1, cx2, cy2], fill=_LOGO_CHECK, width=check_w, joint="curve")

    font = _load_font(max(10, int(box_h * 0.42)))
    text_area_left = mark_right + mark_pad
    text_bbox = draw.textbbox((0, 0), label, font=font)
    text_w, text_h = text_bbox[2] - text_bbox[0], text_bbox[3] - text_bbox[1]
    text_x = text_area_left + max(0, ((right - mark_pad) - text_area_left - text_w) // 2)
    text_y = top + (box_h - text_h) // 2 - text_bbox[1]
    draw.text((text_x, text_y), label, fill=_LOGO_CHECK, font=font)

    img.save(out_path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True, help="Direct APK download URL")
    parser.add_argument("--version", required=True, help="Release tag/version label")
    parser.add_argument(
        "--template-url",
        default="https://docs.google.com/spreadsheets/d/1Ss15J7afOl3HON6h2dI8f8hGi8JYjH0hRywuV0nCYOg/edit?usp=sharing",
        help="Google Sheet template URL",
    )
    parser.add_argument("--out", default="docs", help="Output directory")
    args = parser.parse_args()

    out_dir = pathlib.Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Normalize to a single leading "v" regardless of whether --version already has one.
    display_version = args.version if args.version.startswith("v") else f"v{args.version}"

    make_qr_with_logo(args.url, display_version, out_dir / "qr.png")


    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MicroTasking &mdash; Quick Start & Onboarding</title>
<style>
  :root {{
    --bg: #0f172a;
    --card-bg: #1e293b;
    --text: #f8fafc;
    --text-muted: #94a3b8;
    --accent: #38bdf8;
    --accent-hover: #0284c7;
    --border: #334155;
    --badge-bg: #0369a1;
  }}
  * {{ box-sizing: border-box; margin: 0; padding: 0; }}
  body {{
    font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background-color: var(--bg);
    color: var(--text);
    line-height: 1.6;
    padding: 2rem 1rem;
    max-width: 800px;
    margin: 0 auto;
  }}
  header {{
    text-align: center;
    margin-bottom: 2.5rem;
    padding-bottom: 1.5rem;
    border-bottom: 1px solid var(--border);
  }}
  h1 {{
    font-size: 2.25rem;
    color: var(--accent);
    margin-bottom: 0.5rem;
  }}
  .tagline {{
    font-size: 1.1rem;
    font-weight: 600;
    color: var(--text-muted);
    margin-bottom: 1rem;
  }}
  .intro {{
    font-size: 1.05rem;
    color: #cbd5e1;
    text-align: left;
    background: var(--card-bg);
    padding: 1.25rem;
    border-radius: 8px;
    border: 1px solid var(--border);
  }}
  .step {{
    background: var(--card-bg);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 1.5rem;
    margin-bottom: 2rem;
  }}
  .step-header {{
    display: flex;
    align-items: center;
    gap: 0.75rem;
    margin-bottom: 1rem;
  }}
  .step-num {{
    background: var(--badge-bg);
    color: #fff;
    font-weight: bold;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }}
  .step-title {{
    font-size: 1.3rem;
    font-weight: 600;
    color: var(--text);
  }}
  ul, ol {{
    margin-left: 1.5rem;
    margin-bottom: 1rem;
  }}
  li {{
    margin-bottom: 0.5rem;
  }}
  a {{
    color: var(--accent);
    text-decoration: none;
  }}
  a:hover {{
    text-decoration: underline;
  }}
  .btn {{
    display: inline-block;
    background: var(--accent);
    color: #0f172a;
    font-weight: bold;
    padding: 0.6rem 1.2rem;
    border-radius: 6px;
    margin-top: 0.5rem;
  }}
  .btn:hover {{
    background: var(--accent-hover);
    color: #fff;
    text-decoration: none;
  }}
  .qr-container {{
    text-align: center;
    margin: 1.25rem 0;
  }}
  .qr-container img, .qr-container canvas {{
    background: #fff;
    padding: 10px;
    border-radius: 8px;
    max-width: 220px;
    height: auto;
  }}
  .input-group {{
    margin: 1rem 0;
  }}
  input[type="url"] {{
    width: 100%;
    padding: 0.75rem;
    border-radius: 6px;
    border: 1px solid var(--border);
    background: #090d16;
    color: #fff;
    font-size: 1rem;
  }}
  input[type="url"]:focus {{
    outline: none;
    border-color: var(--accent);
  }}
  .badge-android {{
    display: inline-block;
    background: #15803d;
    color: #fff;
    font-size: 0.8rem;
    padding: 0.2rem 0.5rem;
    border-radius: 4px;
    vertical-align: middle;
    margin-left: 0.5rem;
  }}
</style>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
</head>
<body>
  <header>
    <h1>MicroTasking</h1>
    <div class="tagline">Break Procrastination. Build Momentum.</div>
    <div class="intro">
      <strong>MicroTasking</strong> helps you stop overthinking and start doing. Procrastination usually stems from friction, decision fatigue, and feeling overwhelmed by large tasks. MicroTasking overcomes this by prompting you at random times with brief, bite-sized tasks (5&ndash;15 minutes) from your own customizable pool. When a prompt appears, there is no deliberation &mdash; just start, build momentum, and get on with your day.
    </div>
  </header>

  <main>
    <!-- STEP 1 -->
    <section class="step">
      <div class="step-header">
        <div class="step-num">1</div>
        <div class="step-title">Create & Customize Your Task Pool</div>
      </div>
      <p>Your task pool is managed directly in a Google Sheet:</p>
      <ul>
        <li><strong>Open the Template:</strong> Click <a href="{args.template_url}" target="_blank" rel="noopener">MicroTasking Google Sheet Template</a> to open it in your browser.</li>
        <li><strong>Make Your Copy:</strong> In Google Sheets, tap <strong>File &rarr; Make a copy</strong> to save an editable copy to your Google Drive.</li>
        <li><strong>Categories = Tabs:</strong> Each tab at the bottom represents a category (e.g. <em>Cleaning</em>, <em>Decluttering</em>, <em>Admin</em>). Add, rename, or remove tabs as you wish.</li>
        <li><strong>Tasks & Checkboxes:</strong> Add your tasks into the spreadsheet. Use the checkbox in <strong>Column A</strong> to enable or disable individual tasks. <em>(Category-level toggles can also be managed directly in the app!)</em></li>
      </ul>
    </section>

    <!-- STEP 2 -->
    <section class="step">
      <div class="step-header">
        <div class="step-num">2</div>
        <div class="step-title">Install & Launch the App <span class="badge-android">Android Only</span></div>
      </div>
      <ol>
        <li>
          <strong>Scan or Download:</strong> Point your phone's camera at the QR code below, or click the direct download link if viewing this page on your phone:
          <div class="qr-container">
            <img src="qr.png" alt="MicroTasking APK QR Code">
            <br>
            <a href="{args.url}" class="btn" target="_blank" rel="noopener">Download MicroTasking APK ({display_version})</a>
            <br>
            <a href="{args.url}" target="_blank" rel="noopener">Open the download again</a>
          </div>
        </li>
        <li>
          <strong>Allow Sideloading (First Time Only):</strong>
          <ul>
            <li>After tapping <strong>Download anyway</strong>, open your browser's <strong>Downloads</strong> list or the Android <strong>Files</strong> app and tap the downloaded <code>.apk</code> file. The browser may not open the installer automatically.</li>
            <li>If Android displays <em>"For your security, your phone is not allowed to install unknown apps from this source"</em>, tap <strong>Settings</strong>, toggle <strong>Allow from this source</strong> to ON, then go back and tap <strong>Install</strong>.</li>
            <li>If Google Play Protect displays a warning, tap <strong>More details &rarr; Install anyway</strong>.</li>
          </ul>
        </li>
        <li>
          <strong>Launch & Grant Permissions:</strong> Open MicroTasking from your home screen. When prompted, grant <strong>Notifications</strong> and <strong>Exact Alarms / Display over other apps</strong> permissions so full-screen prompt takeovers can wake your screen.
        </li>
      </ol>
    </section>

    <!-- STEP 3 -->
    <section class="step">
      <div class="step-header">
        <div class="step-num">3</div>
        <div class="step-title">Generate Your Sheet's QR Code</div>
      </div>
      <p>Instead of typing a long URL on your phone keyboard, generate a QR code right here:</p>
      <ol>
        <li>In your copied Google Sheet, make sure sharing is set to <strong>"Anyone with the link can view"</strong> (tap <strong>Share &rarr; Anyone with the link</strong>).</li>
        <li>Copy your Google Sheet URL from your browser bar.</li>
        <li>Paste your URL into the box below:</li>
      </ol>
      <div class="input-group">
        <input type="url" id="sheetUrl" placeholder="https://docs.google.com/spreadsheets/d/your-sheet-id/edit..." oninput="generateSheetQr()">
      </div>
      <div class="qr-container" id="sheetQrContainer" style="display:none;">
        <p style="margin-bottom: 0.5rem; font-weight: 600; color: var(--accent);">Your Sheet QR Code:</p>
        <div id="sheetQr"></div>
      </div>
    </section>

    <!-- STEP 4 -->
    <section class="step">
      <div class="step-header">
        <div class="step-num">4</div>
        <div class="step-title">Scan & Sync in MicroTasking</div>
      </div>
      <ol>
        <li>Open the <strong>MicroTasking</strong> app on your Android phone.</li>
        <li>Tap the <strong>Settings (gear icon)</strong> in the top corner.</li>
        <li>Select <strong>Import External Task Pool</strong>.</li>
        <li>Tap <strong>Scan QR Code</strong> and scan the QR code generated in Step 3 above.</li>
      </ol>
      <p style="color: var(--accent); font-weight: 600; margin-top: 0.5rem;">You're all set! MicroTasking will now automatically sync your categories and tasks.</p>
    </section>
  </main>

  <script>
    function generateSheetQr() {{
      const input = document.getElementById('sheetUrl').value.trim();
      const container = document.getElementById('sheetQrContainer');
      const qrDiv = document.getElementById('sheetQr');
      
      qrDiv.innerHTML = '';
      if (input.length > 10) {{
        container.style.display = 'block';
        new QRCode(qrDiv, {{
          text: input,
          width: 200,
          height: 200,
          colorDark : "#000000",
          colorLight : "#ffffff",
          correctLevel : QRCode.CorrectLevel.M
        }});
      }} else {{
        container.style.display = 'none';
      }}
    }}
  </script>
</body>
</html>
"""
    (out_dir / "index.html").write_text(html, encoding="utf-8")


if __name__ == "__main__":
    main()

