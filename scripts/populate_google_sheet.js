/**
 * Copyright (c) 2026 Vern McGeorge. All rights reserved.
 *
 * MicroTasking - One-Click Google Apps Script to Populate your Google Sheet
 *
 * INSTRUCTIONS:
 * 1. Open your Google Sheet: https://docs.google.com/spreadsheets/d/1Ss15J7afOl3HON6h2dI8f8hGi8JYjH0hRywuV0nCYOg/edit
 * 2. Click on "Extensions" -> "Apps Script" in the top menu.
 * 3. Delete any code in the editor, paste this entire script, and click the Save icon (Ctrl+S).
 * 4. Click the "Run" button at the top.
 * 5. Grant permissions if prompted. Your Google Sheet will be automatically populated with README and all task tabs!
 */

function setupMicroTaskingSheet() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  
  // 1. Create or select README tab (ALL CAPS)
  var readmeSheet = ss.getSheetByName("README") || ss.insertSheet("README", 0);
  readmeSheet.clear();
  
  var readmeData = [
    ["MICROTASKING TASK POOL TEMPLATE"],
    [""],
    ["Welcome to your MicroTasking Task Pool spreadsheet!"],
    [""],
    ["HOW TO USE THIS SPREADSHEET:"],
    ["1. CATEGORIES (TABS): Each tab at the bottom represents a category (e.g. Decluttering, Cleaning, Paperwork, Finances, Health, Errands)."],
    ["   - You can add new tabs, rename existing tabs, or delete tabs you don't need."],
    [""],
    ["2. COLUMNS IN TASK TABS:"],
    ["   - Column A (Enabled): each task row has a checkbox. Checked = the app may suggest it; unchecked = still imported, but never suggested. Typing a description in column B adds the checkbox automatically; clearing the description removes it. Cell A1 is the master toggle for the whole tab."],
    ["   - Column B (Description): The text description of the micro-task."],
    ["   - Column C (Link): Optional URL (e.g. video tutorial, document, or web tool)."],
    [""],
    ["3. SYNCING WITH THE APP:"],
    ["   - Set Share permissions to 'Anyone with the link can view'."],
    ["   - Paste your Sheet URL into the onboarding page to generate your custom QR code."],
    ["   - In the MicroTasking app, tap Settings -> Import External Task Pool -> Scan QR Code."]
  ];
  
  readmeSheet.getRange(1, 1, readmeData.length, 1).setValues(readmeData);
  readmeSheet.getRange("A1").setFontWeight("bold").setFontSize(14);
  readmeSheet.getRange("A5").setFontWeight("bold");
  readmeSheet.getRange("A10").setFontWeight("bold");
  readmeSheet.getRange("A16").setFontWeight("bold");
  readmeSheet.autoResizeColumn(1);

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
    
    // Set headers. A1 is the master toggle; rows 2+ are independent, but can all be synced together.
    sheet.getRange("A1").insertCheckboxes();
    sheet.getRange("A1").setValue(true);
    sheet.getRange("B1").setValue("Description");
    sheet.getRange("C1").setValue("Link");
    sheet.getRange("A1:C1").setFontWeight("bold").setHorizontalAlignment("center");
    
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
    
    sheet.setColumnWidth(1, 40);
    sheet.setColumnWidth(2, 500);
    sheet.setColumnWidth(3, 250);
  }

  // Delete default Sheet1 if present
  var sheet1 = ss.getSheetByName("Sheet1");
  if (sheet1 && ss.getSheets().length > 1) {
    ss.deleteSheet(sheet1);
  }
  
  SpreadsheetApp.getUi().alert("MicroTasking Sheet Setup Complete! README and all 6 categories have been created.");
}

/**
 * Live sheet behavior. This is a simple onEdit trigger: it runs only while the sheet is open in
 * a browser and edited by someone with edit access - never for the app's CSV read, and not
 * reliably on mobile. Two things:
 *   - A1 is the tab's master toggle: flipping it sets every row checkbox below to match.
 *   - Column B is the description: typing a description into a row with no checkbox adds one
 *     (checked); clearing the description (trimmed empty) removes that row's checkbox.
 * Script-driven cell writes don't re-fire onEdit, so the A1 fan-out below can't loop.
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

  // Description column (B), data rows only - also covers a multi-row paste.
  if (range.getColumn() <= 2 && range.getLastColumn() >= 2) {
    for (var row = Math.max(2, range.getRow()); row <= range.getLastRow(); row++) {
      syncRowCheckbox_(sheet, row);
    }
  }
}

/** Adds or removes row `row`'s column-A checkbox to match whether column B holds a description. */
function syncRowCheckbox_(sheet, row) {
  var toggleCell = sheet.getRange(row, 1);
  var hasDescription = String(sheet.getRange(row, 2).getValue()).trim().length > 0;
  var rule = toggleCell.getDataValidation();
  var isCheckbox = rule != null &&
    rule.getCriteriaType() === SpreadsheetApp.DataValidationCriteria.CHECKBOX;

  if (hasDescription && !isCheckbox) {
    toggleCell.insertCheckboxes();
    toggleCell.setValue(true);
  } else if (!hasDescription && isCheckbox) {
    toggleCell.removeCheckboxes();
    toggleCell.clearContent();
  }
}
