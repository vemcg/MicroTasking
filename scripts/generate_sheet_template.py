#!/usr/bin/env python3
"""Builds the Google Sheets import template (one tab per category) from content/tasks.json.

Upload the resulting .xlsx to Google Drive; it opens as a native Sheet with tabs already
matching the external-task-source schema (description, durationMinutes, link).
"""
import argparse
import json
import pathlib

from openpyxl import Workbook

HEADER = ["description", "durationMinutes", "link"]
INVALID_SHEET_TITLE_CHARS = str.maketrans({c: "-" for c in "/\\?*[]:"})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tasks-json", default="content/tasks.json")
    parser.add_argument("--out", default="content/microtasking-sheet-template.xlsx")
    args = parser.parse_args()

    data = json.loads(pathlib.Path(args.tasks_json).read_text(encoding="utf-8"))

    workbook = Workbook()
    workbook.remove(workbook.active)

    for category in data["categories"]:
        title = category["name"].translate(INVALID_SHEET_TITLE_CHARS)[:31]
        sheet = workbook.create_sheet(title=title)
        sheet.append(HEADER)
        for task in category["tasks"]:
            sheet.append([task["description"], task["durationMinutes"], ""])

    pathlib.Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    workbook.save(args.out)
    print(f"Wrote {args.out}")


if __name__ == "__main__":
    main()
