#!/usr/bin/env python3
# Copyright (c) 2026 Vern McGeorge. All rights reserved.
"""Builds the Google Sheets import template (one README tab + one tab per category) from
content/tasks.json.

Upload the resulting .xlsx to Google Drive; it opens as a native Sheet with tabs already
matching the external-task-source schema: a checkbox in column A (enabled toggle), the
description in column B, an optional link in column C. Duration is app-maintained and adaptive,
so it is never authored in the sheet.

This is only a static starting point. The live behavior - the A1 master toggle, and adding /
removing a row's checkbox as its description is typed or cleared - lives in the bundled Apps
Script (scripts/populate_google_sheet.js), which the user runs against their own copy.
"""
import argparse
import datetime
import json
import pathlib

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font
from openpyxl.worksheet.hyperlink import Hyperlink

INVALID_SHEET_TITLE_CHARS = str.maketrans({c: "-" for c in "/\\?*[]:"})

# Origin version of this template. Keep in sync with buildVersionBase in app/build.gradle.kts.
TEMPLATE_VERSION = "0.1.7"

README_CONTENT = [
    ["MICROTASKING TASK POOL TEMPLATE"],
    [f"Template version: {TEMPLATE_VERSION}  (generated {datetime.date.today().isoformat()})"],
    [""],
    ["Welcome to your MicroTasking Task Pool spreadsheet!"],
    [""],
    ["HOW TO USE THIS SPREADSHEET:"],
    ["1. CATEGORIES (TABS): Each tab at the bottom represents a category (e.g. Decluttering, Cleaning, Paperwork, Finances, Health, Errands)."],
    ["   - You can add new tabs, rename existing tabs, or delete tabs you don't need."],
    [""],
    ["2. COLUMNS IN TASK TABS:"],
    ["   - Column A (Enabled): each task row has a checkbox. Checked = the app may suggest it; unchecked = still imported, but never suggested. Typing a description in column B adds the checkbox automatically; clearing a row's description deletes the whole row. Cell A1 is the master toggle for the whole tab."],
    ["   - Column B (Description): The text description of the micro-task."],
    ["   - Column C (Link): Optional URL (e.g. video tutorial, document, or web tool)."],
    [""],
    ["3. SYNCING WITH THE APP:"],
    ["   - Set Share permissions to 'Anyone with the link can view'."],
    ["   - Paste your Sheet URL into the onboarding page to generate your custom QR code."],
    ["   - In the MicroTasking app, tap Settings -> Import External Task Pool -> Scan QR Code."],
]

HEADER_FONT = Font(bold=True)
HEADER_ALIGNMENT = Alignment(horizontal="center")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tasks-json", default="content/tasks.json")
    parser.add_argument("--out", default="content/microtasking-sheet-template.xlsx")
    args = parser.parse_args()

    data = json.loads(pathlib.Path(args.tasks_json).read_text(encoding="utf-8"))

    workbook = Workbook()
    workbook.remove(workbook.active)

    readme_sheet = workbook.create_sheet(title="README")
    for index, row in enumerate(README_CONTENT):
        readme_sheet.append(row)
        # Bold the title, the version stamp, and every section heading (rows ending with a colon).
        if index < 2 or row[0].rstrip().endswith(":"):
            readme_sheet.cell(row=index + 1, column=1).font = HEADER_FONT
    readme_sheet.column_dimensions["A"].width = 110

    for category in data["categories"]:
        raw_name = category["name"]
        # Exception requested by user: rename "Admin / Paperwork" to "Paperwork".
        if "Admin" in raw_name or "Paperwork" in raw_name:
            raw_name = "Paperwork"

        title = raw_name.translate(INVALID_SHEET_TITLE_CHARS)[:31]
        sheet = workbook.create_sheet(title=title)

        # Row 1: A1 = master toggle, B1 = Description, C1 = Link (bold, centered).
        sheet.cell(row=1, column=1, value=True)
        sheet.cell(row=1, column=2, value="Description")
        sheet.cell(row=1, column=3, value="Link")
        for column in (1, 2, 3):
            header_cell = sheet.cell(row=1, column=column)
            header_cell.font = HEADER_FONT
            header_cell.alignment = HEADER_ALIGNMENT

        for row_index, task in enumerate(category["tasks"], start=2):
            # Each task row carries its own checkbox value; A1 is the master toggle.
            sheet.cell(row=row_index, column=1, value=True)
            sheet.cell(row=row_index, column=2, value=task["description"])
            link = task.get("link", "")
            link_cell = sheet.cell(row=row_index, column=3, value=link or None)
            if link:
                link_cell.hyperlink = Hyperlink(ref=link_cell.coordinate, target=link)

        sheet.column_dimensions["A"].width = 12
        sheet.column_dimensions["B"].width = 75
        sheet.column_dimensions["C"].width = 35

    pathlib.Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    workbook.save(args.out)
    print(f"Wrote {args.out}")


if __name__ == "__main__":
    main()
