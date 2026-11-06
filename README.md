# Fakturka

Fakturka is a small native Linux desktop app for issuing invoices and preparing the quarterly VAT filings of a Czech VAT payer. It is written in Kotlin with Compose Multiplatform Desktop (Material 3). The UI is in Czech.

## What it does

- Numbers invoices by a pattern you choose, e.g. `{RR}{NNNNN}` → `2600023` or `FV-{RRRR}-{NNN}` → `FV-2026-023` (the sequence restarts every year; the variable symbol is the digits of the number).
- Issues PDF invoices: pick a named template, enter the quantity (hours, man-days), save. The PDF has a QR payment code (QR Platba). Invoices for EU customers are bilingual Czech/English, without VAT, marked as reverse charge.
- Fills in customer and seller details from the ARES business register by IČ.
- Tracks payments: an invoice can be marked paid, and an unpaid invoice past its due date is highlighted.
- Adds your stamp and signature to the PDF from one image (PNG or JPG) in the data folder.
- Shows statistics: invoiced amounts without VAT by month, quarter and year.
- Imports issued invoices from a ProFact XML export.
- Records received invoices (numbers and amounts only).
- Once a quarter, creates the VAT control statement (kontrolní hlášení, KH), the VAT return (přiznání k DPH, DP) and, when needed, the EC Sales List (souhrnné hlášení, SH) as XML files for the EPO portal. Each file is checked against the official XSD schema before it is written.
- Keeps all data as readable XML files in one folder that you can back up or put in git.

## Who it is for

A Czech VAT payer who is a natural person (`typ_ds="F"`), a regular payer (`typ_platce="P"`) and files quarterly. Customers are domestic or from the EU, invoices are in CZK. Other taxpayer profiles are not supported yet.

## Install

Download the latest release from the [Releases page](https://github.com/chapcz/fakturka/releases):

- **Debian, Ubuntu, Mint:** `fakturka_<version>_amd64.deb`, then `sudo apt install ./fakturka_<version>_amd64.deb`. The app appears in the "Office" menu group.
- **Any other Linux:** `Fakturka-<version>-x86_64.AppImage`, then `chmod +x Fakturka-*.AppImage` and run it.

Both bundle their own Java runtime. `SHA256SUMS` in the release lists the checksums.

At start (at most once a day) the app asks GitHub whether a newer release exists and offers it in a strip at the top. The AppImage replaces itself and the new version starts with the next launch. The `.deb` is downloaded to your download folder and opened in the system software installer, which asks for the administrator password. The check can be turned off in Settings → Application.

## Data folder

On first run the app asks for a data folder (default `~/Documents/invoicing`) and remembers it in `~/.config/fakturka/settings.xml`.

```
<data folder>/
  config.xml        seller details, bank account, tax office, numbering, defaults
  contacts.xml      customers and suppliers
  templates.xml     named invoice templates
  signature.png     optional stamp and signature image (or signature.jpg)
  2026/
    issued.xml      issued invoices of this year
    received.xml    received invoices of this year
    pdf/            generated invoice PDFs, e.g. 2600023.pdf
    received/       empty folder where you can drop supplier PDFs by hand
    export/
      Q4/           KH, DP and SH XML files for that quarter
```

Files are UTF-8, pretty-printed, with a `version` attribute on the root element. Money is stored as decimal strings (`30580.50`). Every write is atomic, and the previous version is kept as `<file>.bak`. A data file that cannot be read is reported and never overwritten.

Your data lives outside this repository. Do not commit it.

## Build and run

Requirements: JDK 21 (Gradle toolchain) and, for a GUI, a desktop session.

```
./gradlew test       # run the tests
./gradlew run        # start the app
```

To use a different settings folder (for example for testing), start the app with `JAVA_TOOL_OPTIONS=-Dfakturka.configDir=<dir>`.

## Debian package and AppImage

```
./gradlew packageDeb createDistributable -Pfakturka.version=1.2.0
sudo apt install ./build/compose/binaries/main/deb/fakturka_1.2.0_amd64.deb
packaging/build-appimage.sh 1.2.0     # downloads appimagetool, writes build/appimage/Fakturka-1.2.0-x86_64.AppImage
```

Without `-Pfakturka.version` the version is `1.0.0`.

The package bundles its own Java runtime and adds a launcher to the "Office" menu group.

`packageDeb` needs a full JDK that includes `jmods` (a headless distribution package often lacks them). If the JDK Gradle finds does not work, point the build at one:

```
FAKTURKA_PACKAGING_JDK=/path/to/full/jdk ./gradlew packageDeb
# or
./gradlew packageDeb -Pfakturka.packagingJdk=/path/to/full/jdk
```

## Releasing

Push a version tag; the `Release` GitHub Actions workflow runs the tests, builds the `.deb` and the AppImage and publishes them as a release with generated notes:

```
git tag v1.2.0
git push origin v1.2.0
```

The version shown in the app and compared by the update check comes from the tag.

## Current limits (version 1)

- No stock, accounting, double-entry bookkeeping or income tax return.
- No sending invoices by e-mail, no payment reminders.
- No credit notes or corrective tax documents.
- Only CZK; only domestic and EU customers.
- Import only from ProFact; numbering continues from a configured start number. The sequence restarts only yearly. Invoices issued elsewhere can also be entered by hand.
- Received invoices under reverse charge (purchases from foreign suppliers) are not supported.
- Only the profile above: natural person, regular payer, quarterly filing.
- PDFs of received invoices are not stored by the app.

## License

[MIT](LICENSE)
