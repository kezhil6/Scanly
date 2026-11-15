# 📄 Scanly

**A powerful, privacy-focused document scanner for Android.**

Scan, organize, edit, and export documents directly from your Android device. Scanly brings document scanning, OCR, PDF management, and document export together in one simple application.

No account required. No advertisements. Your documents stay on your device.

---

## ✨ Features

### 📷 Smart Document Scanning
- Automatic document edge detection and capture.
- Perspective correction and image enhancement.
- Crop, rotate, and apply filters.
- Scan documents, books, ID cards, business cards, and whiteboards.
- Import existing PDFs and images.
- Support for book scanning with up to 100 pages.

### 🗂️ Document Management
- Organize documents into folders.
- Add tags and favorites.
- Rename and move documents.
- Search by document name, folder, tag, or recognized text.

### 🔍 OCR – Text Recognition
- Extract text from scanned documents.
- Edit, copy, and share recognized text.
- Automatically index recognized text for searching.
- Extract business card information and save contacts.

### 📑 PDF Tools
- Merge multiple documents.
- Reorder, rotate, add, and delete pages.
- Split PDFs and extract individual pages.
- Compress PDF files.
- Password-protect PDFs and remove passwords.
- Annotate documents using:
  - Pen
  - Highlighter
  - Text
  - Saved signatures

### 📤 Export & Share
Export your documents in multiple formats:

| Format | Description |
|---|---|
| PDF | Scanned documents |
| DOCX | Word documents |
| XLSX | Excel spreadsheets |
| TXT | Plain text |
| JPG | Individual page images |

Share documents through your favorite applications, including WhatsApp, Google Drive, and email.

---

## 🔒 Privacy First

Scanly is designed with privacy in mind.

- No account registration.
- No advertisements.
- On-device document management.
- No account-based cloud synchronization.

Document scanning is powered by Google's ML Kit Document Scanner and requires Google Play services.

---

## 🛠️ Tech Stack

- **Platform:** Android
- **Language:** Kotlin
- **Build System:** Gradle
- **Document Scanning:** Google ML Kit Document Scanner
- **IDE:** Android Studio

---

## 🚀 Getting Started

### Prerequisites

- Android Studio (Koala or newer).
- Android SDK.
- An Android device or emulator with Google Play services.

### Build and Run

1. Clone the repository:

   ```bash
   git clone https://github.com/kezhil6/Scanly.git
   ```

2. Open the project in Android Studio.

3. Allow Gradle to sync.

4. Connect an Android device with USB debugging enabled, or start an emulator with Google Play services.

5. Click **Run** to build and install the application.

### Build APK

You can also download the generated debug APK through GitHub Actions:

1. Open the repository's **Actions** tab.
2. Select the latest successful build.
3. Download the `app-debug.apk` artifact.

---

## 📁 Project Structure

```text
Scanly/
├── .github/
│   └── workflows/
├── app/
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

### Main Components

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Home screen, scanning, navigation |
| `ViewerScreen.kt` | Document viewer and page editing |
| `AnnotateScreen.kt` | Annotations and signatures |
| `TextScreen.kt` | OCR and text exports |
| `PdfTools.kt` | PDF operations and OCR |
| `Exporter.kt` | Document exports and business card parsing |
| `ScanRepository.kt` | Storage, folders, tags, and search |

---

## 🗺️ Roadmap

Potential future improvements:

- [ ] Dark mode improvements.
- [ ] Batch document scanning.
- [ ] Additional document export formats.
- [ ] More image enhancement options.
- [ ] Additional PDF customization tools.
- [ ] Backup and restore options.

---

## 🤝 Contributing

Contributions,
