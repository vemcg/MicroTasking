/**
 * Copyright (c) 2026 Vern McGeorge. All rights reserved.
 *
 * MicroTasking - One-Click Google Apps Script to Populate your Google Sheet
 *
 * INSTRUCTIONS:
 * 1. Open YOUR copy of the Sheet (File -> Make a copy of the template first).
 * 2. In that copy: Extensions -> Apps Script. This must be the Sheet-bound editor, NOT a
 *    standalone script.google.com project - the script acts on the Sheet it is bound to.
 * 3. Delete any code in the editor, paste this entire file, and Save (Ctrl+S).
 * 4. In the toolbar function picker choose "setupMicroTaskingSheet", then click "Run".
 * 5. First run only: an "Authorization required" dialog appears. Review permissions -> pick your
 *    account -> "Advanced" -> "Go to <project> (unsafe)" -> Allow. If nothing pops up, the
 *    browser blocked the popup - allow popups for script.google.com and Run again.
 * 6. It finishes in a few seconds and shows a toast in the Sheet. No add-ons or libraries are
 *    needed, it works on a blank spreadsheet, and the Sheet needs no particular name.
 *
 * Running setupMicroTaskingSheet also installs an onChange trigger (so new tabs get their header
 * row automatically) - the authorization prompt will mention managing triggers because of it.
 *
 * If "Running..." never ends: open Executions (clock icon, left sidebar) to see whether the run
 * actually finished or errored. onEdit / onGridChange_ / addRowCheckbox_ below are triggers -
 * don't run them by hand.
 *
 * MAINTAINER NOTE: this file is the source of truth and is pushed to the bound Apps Script
 * project of the shared template Sheet with `npm run push:sheet` (clasp). See .clasp.json.
 * `clasp push` uploads the code only - still open the editor and Run "setupMicroTaskingSheet"
 * once to (re)build the tabs. End users who copy the template still paste this file by hand.
 */

// Origin version of this template. Keep in sync with buildVersionBase in app/build.gradle.kts.
var TEMPLATE_VERSION = "0.1.7";

function setupMicroTaskingSheet() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) {
    throw new Error(
      "No active spreadsheet. Open this from Extensions -> Apps Script inside your Sheet, " +
      "not as a standalone script.google.com project."
    );
  }

  // Keep the spreadsheet's own name stable ("MicroTasking Task Pool Template"); the version
  // lives in README row 2, not the title.
  ss.rename("MicroTasking Task Pool Template");

  var today = Utilities.formatDate(new Date(), Session.getScriptTimeZone(), "yyyy-MM-dd");

  // 1. Create or select README tab (ALL CAPS)
  var readmeSheet = ss.getSheetByName("README") || ss.insertSheet("README", 0);
  readmeSheet.clear();

  // Keep this text in sync with README_CONTENT in scripts/generate_sheet_template.py.
  var readmeData = [
    ["MICROTASKING TASK POOL TEMPLATE"],
    ["Template version: " + TEMPLATE_VERSION + "  (set up " + today + ")"],
    [""],
    ["Welcome to your MicroTasking Task Pool spreadsheet!"],
    [""],
    ["HOW TO USE THIS SPREADSHEET:"],
    [""],
    ["1. CATEGORIES (TABS):"],
    ["   - Each tab at the bottom is a category (e.g. Decluttering, Cleaning, Paperwork, Finances, Health, Errands)."],
    ["   - You can add, rename, delete, and rearrange tabs."],
    ["   - Tab order sets the odds: tasks in your leftmost enabled category are about twice as likely to be assigned as tasks in your rightmost enabled category, sliding linearly in between. Enable or disable categories in the app's settings."],
    [""],
    ["2. COLUMNS IN TASK TABS:"],
    ["   - Column A (Enabled): each task row has a checkbox. Checked = the app may suggest it; unchecked = still imported, but never suggested. Typing a description in column B adds the checkbox automatically; clearing a row's description deletes the whole row. Cell A1 is the master toggle for the whole tab."],
    ["   - Column B (Description): The text description of the micro-task."],
    ["   - Column C (Link): Optional URL (e.g. video tutorial, document, or web tool)."],
    ["   - The order of task rows within a tab does not affect how often a task is assigned."],
    [""],
    ["3. SYNCING WITH THE APP:"],
    ["   - Set Share permissions to 'Anyone with the link can view'."],
    ["   - Paste your Sheet URL into the onboarding page to generate your custom QR code."],
    ["   - In the MicroTasking app, tap Settings -> Import External Task Pool -> Scan QR Code."]
  ];

  readmeSheet.getRange(1, 1, readmeData.length, 1).setValues(readmeData);
  // Bold the title, the version stamp, and every section heading (the rows that end with a colon)
  // - matched by content so inserting a line above doesn't silently shift the wrong rows bold.
  for (var r = 0; r < readmeData.length; r++) {
    if (r < 2 || /:\s*$/.test(readmeData[r][0])) {
      readmeSheet.getRange(r + 1, 1).setFontWeight("bold");
    }
  }
  readmeSheet.getRange("A1").setFontSize(14);
  // Fixed, on-screen-friendly width + wrap so every line shows in full instead of autoResize
  // blowing the column out to the length of the longest sentence.
  readmeSheet.setColumnWidth(1, 700);
  readmeSheet.getRange(1, 1, readmeData.length, 1).setWrap(true);

  // Categories data
  var categories = [
    {
      name: "Decluttering",
      tasks: [
        ["Find 3 things you don't need and throw them away.", ""],
        ["Clear off one flat surface completely (desk, counter, nightstand).", ""],
        ["Empty one trash/recycling bin that isn't empty yet.", ""],
        ["Sort the mail pile into keep / recycle / shred.", ""],
        ["Put away 5 items that are out of place.", ""],
        ["Empty your bag or pockets and toss any trash/receipts inside.", ""],
        ["Clear expired or unused items off one shelf.", ""],
        ["Delete 10 apps or photos you don't need from your phone.", ""],
        ["Toss expired food from the fridge door or one shelf.", ""],
        ["Gather stray cords/cables into a pile and toss the broken ones.", ""],
        ["Clear out one kitchen drawer.", ""],
        ["Sort through the stack of papers on your desk.", ""],
        ["Go through your closet and pull 5 items to donate.", ""],
        ["Wipe down and organize one bathroom cabinet or drawer.", ""],
        ["Consolidate duplicate pantry items (spices, condiments, etc.).", ""],
        ["Sort your sock/underwear drawer; pair or toss the unmatched ones.", ""],
        ["Clear off and reorganize one bookshelf.", ""],
        ["Go through your car's glovebox and center console.", ""],
        ["Sort through \"the junk drawer.\"", ""],
        ["Organize shoes by the front door; remove pairs you don't wear.", ""],
        ["Clean out one section of your closet and bag up donations.", ""],
        ["Go through a box you haven't opened in months.", ""],
        ["Fully declutter your desktop workspace — cables, papers, supplies.", ""],
        ["Sort through your bathroom counter/cabinet and toss old products.", ""],
        ["Go through your bookshelf and box up books to donate.", ""],
        ["Clear out the top of the fridge or a high cabinet you avoid.", ""],
        ["Sort the linen closet — towels/sheets you don't use.", ""],
        ["Go through the garage or entryway and remove items that don't belong there.", ""]
      ]
    },
    {
      name: "Cleaning",
      tasks: [
        ["Wipe down the kitchen counters.", ""],
        ["Wipe down the bathroom sink and faucet.", ""],
        ["Spot-clean one mirror or window.", ""],
        ["Wipe down the stovetop.", ""],
        ["Quick sweep of one room's floor.", ""],
        ["Wipe down light switches and door handles.", ""],
        ["Rinse the dishes sitting in the sink.", ""],
        ["Wipe down the microwave inside and out.", ""],
        ["Vacuum one room.", ""],
        ["Clean the toilet.", ""],
        ["Wipe down kitchen appliance exteriors (fridge, toaster, etc.).", ""],
        ["Sweep and mop one small floor area (kitchen/bathroom).", ""],
        ["Dust one room's surfaces (shelves, tables).", ""],
        ["Clean the shower or tub.", ""],
        ["Wipe down baseboards in one room.", ""],
        ["Deep-wipe the inside of the microwave, including the turntable.", ""],
        ["Vacuum and mop an entire room.", ""],
        ["Deep clean the bathroom (toilet, sink, tub, mirror).", ""],
        ["Clean the inside of the refrigerator.", ""],
        ["Wash a full sink of dishes.", ""],
        ["Clean the windows in one room, inside and out.", ""],
        ["Dust and wipe down all furniture in one room.", ""],
        ["Clean out and wipe down the oven interior (surface level).", ""]
      ]
    },
    {
      name: "Paperwork",
      tasks: [
        ["Open and sort today's mail.", ""],
        ["Shred one pile of old documents.", ""],
        ["File one document that's been sitting out.", ""],
        ["Update one contact's info in your phone.", ""],
        ["Set a reminder for one upcoming due date or bill.", ""],
        ["Unsubscribe from 3 unwanted emails or newsletters.", ""],
        ["Scan or photograph one important document for your records.", ""],
        ["Pay one outstanding bill.", ""],
        ["Sort through a stack of paperwork into keep/file/shred.", ""],
        ["Renew or schedule renewal of one subscription, license, or registration.", ""],
        ["Fill out one pending form.", ""],
        ["Update your calendar with upcoming appointments or deadlines.", ""],
        ["Organize digital files on your desktop into folders.", ""],
        ["Review and respond to one important email you've been avoiding.", ""],
        ["Set up autopay or reminders for a recurring bill.", ""],
        ["Organize a full folder or drawer of physical documents.", ""],
        ["Review your subscriptions and cancel the ones you don't use.", ""],
        ["Complete one section of a longer form (taxes, insurance, etc.).", ""],
        ["Back up your important files or photos.", ""],
        ["Draft and send one email you've been putting off.", ""]
      ]
    },
    {
      name: "Finances",
      tasks: [
        ["Check your bank account balance and recent transactions.", ""],
        ["Log one recent expense in your budget or app.", ""],
        ["Move a small amount into savings.", ""],
        ["Check for any unexpected charges on your statement.", ""],
        ["Review one upcoming bill's due date.", ""],
        ["Round up loose cash or coins and put them into savings.", ""],
        ["Categorize last week's transactions in your budgeting app.", ""],
        ["Compare prices on one recurring expense (insurance, phone plan, etc.).", ""],
        ["Review your credit card statement for errors.", ""],
        ["Set or adjust a budget for one spending category.", ""],
        ["Check your credit score.", ""],
        ["Cancel one unused subscription or service.", ""],
        ["Reconcile your budget for the week or month.", ""],
        ["Research and compare rates for one bill (insurance, utilities).", ""],
        ["Review your retirement or investment account balances.", ""],
        ["Set up or adjust automatic transfers to savings or investments.", ""],
        ["Create or update a simple monthly budget.", ""],
        ["Review your net worth (assets minus debts).", ""]
      ]
    },
    {
      name: "Health",
      tasks: [
        ["Take today's medication or vitamins.", ""],
        ["Schedule a doctor or dentist appointment you've been putting off.", ""],
        ["Refill one prescription.", ""],
        ["Do a quick stretch routine.", ""],
        ["Drink a full glass of water and refill your water bottle.", ""],
        ["Log today's meals or water in a health app.", ""],
        ["Do 20 pushups/squats or a short burst of exercise.", ""],
        ["Go for a short walk.", ""],
        ["Do a full stretching routine.", ""],
        ["Prep a healthy snack or meal for tomorrow.", ""],
        ["Call to confirm or reschedule an upcoming appointment.", ""],
        ["Do a quick home workout video.", ""],
        ["Organize your medicine cabinet and check expiration dates.", ""],
        ["Go for a brisk walk or short jog.", ""],
        ["Do a full home workout session.", ""],
        ["Meal-prep one healthy dish for the week.", ""],
        ["Research and book a needed medical or dental appointment.", ""],
        ["Organize your health records or insurance documents.", ""]
      ]
    },
    {
      name: "Errands",
      tasks: [
        ["Add missing items to your grocery list.", ""],
        ["Take out the trash/recycling.", ""],
        ["Start the errand of putting gas in the car if it's low.", ""],
        ["Gather items that need to be returned to a store.", ""],
        ["Water your plants.", ""],
        ["Bring in the mail or packages.", ""],
        ["Set aside a bag of items to donate or drop off.", ""],
        ["Pack a return package and prepare the shipping label.", ""],
        ["Drop off or pick up dry cleaning.", ""],
        ["Wash the car (quick exterior rinse).", ""],
        ["Take pets for a quick walk, feed them, and refill supplies.", ""],
        ["Organize your car — remove trash, wipe down surfaces.", ""],
        ["Prepare a grocery list and meal plan for the week.", ""],
        ["Do a full grocery run for a few essential items.", ""],
        ["Take a bag of donations to a donation center.", ""],
        ["Wash and detail the interior of your car.", ""],
        ["Run multiple small errands in one trip (post office, pharmacy, store).", ""],
        ["Take pets to a grooming or vet appointment.", ""]
      ]
    }
  ];

  for (var c = 0; c < categories.length; c++) {
    var cat = categories[c];
    var sheet = ss.getSheetByName(cat.name) || ss.insertSheet(cat.name);
    sheet.clear();

    // A1 is the master toggle; rows 2+ are independent, but can all be synced together.
    applyCategoryTabHeader_(sheet);

    var numTasks = cat.tasks.length;
    if (numTasks > 0) {
      // Set Column B (description) and Column C (link)
      sheet.getRange(2, 2, numTasks, 2).setValues(cat.tasks);
      
      // Each task row gets its own independent checkbox, while A1 remains the master toggle.
      var rowValues = [];
      for (var i = 0; i < numTasks; i++) {
        rowValues.push([true]);
      }
      var checkboxRange = sheet.getRange(2, 1, numTasks, 1);
      checkboxRange.setValues(rowValues);
      checkboxRange.insertCheckboxes();

      // Keep the master checkbox in A1 synchronized with the whole tab.
      var masterToggle = sheet.getRange("A1");
      if (!masterToggle.getValue()) {
        checkboxRange.setValues(Array(numTasks).fill([false]));
      }
    }
  }

  // Delete default Sheet1 if present
  var sheet1 = ss.getSheetByName("Sheet1");
  if (sheet1 && ss.getSheets().length > 1) {
    ss.deleteSheet(sheet1);
  }

  // onEdit can't see a tab being added, so an installable onChange trigger headers new tabs.
  ensureTriggers_();

  // toast(), not getUi().alert(): a toast is non-blocking and needs no UI context. alert() blocks
  // waiting for a click in the *spreadsheet* tab, which looks exactly like the script "hanging"
  // if you're still looking at the Apps Script editor.
  ss.toast("README and all " + categories.length + " category tabs are ready.", "MicroTasking setup complete", 5);
}

/**
 * Writes the standard category-tab header and column widths: A1 = master checkbox (checked),
 * B1 = "Description", C1 = "Link", header row bold + centered, columns sized 40 / 500 / 250.
 * Idempotent - safe to re-run on a tab that already has it.
 */
function applyCategoryTabHeader_(sheet) {
  sheet.getRange("A1").insertCheckboxes();
  sheet.getRange("A1").setValue(true);
  sheet.getRange("B1").setValue("Description");
  sheet.getRange("C1").setValue("Link");
  sheet.getRange("A1:C1").setFontWeight("bold").setHorizontalAlignment("center");
  sheet.setColumnWidth(1, 40);
  sheet.setColumnWidth(2, 500);
  sheet.setColumnWidth(3, 250);
}

/**
 * Installable onChange handler (installed by setupMicroTaskingSheet via ensureTriggers_). onEdit
 * never fires for a sheet being inserted, so this fills in the header row + column widths on any
 * freshly added tab. Runs on every structural change; cheap and idempotent, so it just re-headers
 * whichever non-README tab is still missing its "Description" header.
 */
function onGridChange_(e) {
  if (!e || e.changeType !== "INSERT_GRID") return;
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  ss.getSheets().forEach(function (sheet) {
    if (sheet.getName() === "README") return;
    if (String(sheet.getRange("B1").getValue()).trim() === "Description") return;
    applyCategoryTabHeader_(sheet);
  });
}

/** Idempotently installs the spreadsheet onChange trigger that onGridChange_ needs. */
function ensureTriggers_() {
  var installed = ScriptApp.getProjectTriggers().some(function (t) {
    return t.getHandlerFunction() === "onGridChange_";
  });
  if (!installed) {
    ScriptApp.newTrigger("onGridChange_")
      .forSpreadsheet(SpreadsheetApp.getActiveSpreadsheet())
      .onChange()
      .create();
  }
}

/**
 * Live sheet behavior. This is a simple onEdit trigger: it runs only while the sheet is open in
 * a browser and edited by someone with edit access - never for the app's CSV read, and not
 * reliably on mobile. Two things:
 *   - A1 is the tab's master toggle: flipping it sets every row checkbox below to match.
 *   - Column B is the description: typing a description into a row with no checkbox adds one
 *     (checked); clearing a row's description (trimmed empty) deletes the whole row - checkbox,
 *     description, and link together - so the table stays gap-free.
 * Script-driven cell writes don't re-fire onEdit, so the A1 fan-out below can't loop. Adding a
 * whole new tab is handled separately by onGridChange_ (an installable onChange trigger).
 */
function onEdit(e) {
  if (!e || !e.range) return;
  var range = e.range;
  var sheet = range.getSheet();
  if (!sheet || sheet.getName() === "README") return;

  // Master toggle in A1.
  if (range.getColumn() === 1 && range.getRow() === 1) {
    var lastRow = sheet.getLastRow();
    if (lastRow < 2) return;
    var count = lastRow - 1;
    sheet.getRange(2, 1, count, 1).setValues(Array(count).fill([Boolean(range.getValue())]));
    return;
  }

  // Description column (B), data rows only - also covers a multi-row paste or block-clear.
  if (range.getColumn() <= 2 && range.getLastColumn() >= 2) {
    var clearedRows = [];
    for (var row = Math.max(2, range.getRow()); row <= range.getLastRow(); row++) {
      if (String(sheet.getRange(row, 2).getValue()).trim().length > 0) {
        addRowCheckbox_(sheet, row);
      } else {
        clearedRows.push(row);
      }
    }
    // Delete emptied rows bottom-up so earlier deletions don't shift the ones still to go.
    clearedRows.sort(function (a, b) { return b - a; });
    for (var i = 0; i < clearedRows.length; i++) {
      if (clearedRows[i] >= 2) sheet.deleteRow(clearedRows[i]);
    }
  }
}

/** Ensures row `row` has a checked column-A checkbox (used when a description is first typed). */
function addRowCheckbox_(sheet, row) {
  var toggleCell = sheet.getRange(row, 1);
  var rule = toggleCell.getDataValidation();
  var isCheckbox = rule != null &&
    rule.getCriteriaType() === SpreadsheetApp.DataValidationCriteria.CHECKBOX;
  if (!isCheckbox) {
    toggleCell.insertCheckboxes();
    toggleCell.setValue(true);
  }
}
