#!/usr/bin/env python3
"""Generates the GitHub Pages install page + QR code pointing at a release APK."""
import argparse
import pathlib

import qrcode


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True, help="Direct APK download URL")
    parser.add_argument("--version", required=True, help="Release tag/version label")
    parser.add_argument("--out", default="docs", help="Output directory")
    args = parser.parse_args()

    out_dir = pathlib.Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    qrcode.make(args.url).save(out_dir / "qr.png")

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MicroTasking install</title>
<style>
  body {{ font-family: sans-serif; text-align: center; margin-top: 3rem; }}
  img {{ width: 260px; height: 260px; }}
  a {{ display: inline-block; margin-top: 1rem; font-size: 1.1rem; }}
</style>
</head>
<body>
  <h1>MicroTasking</h1>
  <p>Scan to install/update &mdash; version {args.version}</p>
  <img src="qr.png" alt="QR code linking to the MicroTasking APK">
  <p><a href="{args.url}">Direct download link</a></p>
</body>
</html>
"""
    (out_dir / "index.html").write_text(html, encoding="utf-8")


if __name__ == "__main__":
    main()
