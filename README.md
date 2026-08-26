# 🕶️ Ghost in the Lens Artifact

This anonymous repository contains the research artifacts accompanying our USENIX Security '27 submission. The artifact provides the prototype implementation, analysis code, per-scenario results, and redistributable evaluation materials used to support the paper's core findings. 

In compliance with the double-blind review process, all identifying information (e.g., author names, institutional affiliations, repository history, and personal identifiers) has been strictly removed.

---

## ⚠️ Data Availability & Anonymization

The evaluation reported in the paper contains 55 controlled scenarios: 20 natural scenes, 10 driving-license samples, 10 student-ID samples, 10 bank-card samples, and 5 Social-Security-card samples.

**Note on Data Confidentiality:** The original 20 natural-scene images are **not included** in this anonymized review artifact because some contain recognizable individuals, locations, or contextual information that could compromise author anonymity or third-party privacy. 

To support reproducibility and artifact inspection, this artifact provides:
* Redistributable identification and financial-document samples used in the controlled evaluation.
* Sanitized `ground_truth_nested.json` information for the evaluation corpus.
* `prototype_evaluation_results.csv`, containing the recorded per-scenario measurements and revocation outcomes for **all 55 trials**.
* * The prototype components, static-analysis code, and dynamic-evaluation code.

*The withheld natural-scene trials cannot be independently rerun without their original source images. Their recorded measurements and outcomes remain fully documented in the evaluation results CSV.*

---

## 📁 Repository Structure

```text
.
├── prototype/
│   ├── android/ (Modified CameraAccess components)
│   └── edge/
│       ├── main.py
│       └── requirements.txt
├── evaluation/
│   ├── static_analysis/
│   │   └── llm_screening.py
│   └── dynamic_analysis/
│       ├── evaluate_live_server.py
│       └── prototype_evaluation_results.csv
└── dataset/
    ├── ground_truth_nested.json
    └── local_images

```

---

## 🛠️ Components & Setup Instructions

### 1. Android Companion Prototype (📱)

**Location:** `/prototype/android/`

The Android prototype extends Meta's public `CameraAccess` sample from the Wearables Device Access Toolkit for Android. This artifact provides the components modified or added for the Verifiable Privacy Architecture:

* `MainActivity.kt`: Routes the prototype to the privacy-aware interface.


* `CameraViewModel.kt`: Coordinates capture, session handling, selective disclosure, and revocation.


* `PrivacyChatScreen.kt`: Provides capture visualization, redacted-text presentation, and disclosure controls.


* `SecurePhoneClient.kt`: Implements session-scoped local caching, edge communication, application-level overwrite followed by file deletion, and server-side revocation requests.


* `CameraScreen.kt`: Supporting camera-interface modifications.



**How to Build & Run:**

1. Clone Meta's upstream repository: `https://github.com/facebook/meta-wearables-dat-android` (See `UPSTREAM.md` for provenance and exact revision).
2. Open `samples/CameraAccess` in Android Studio.
3. Copy the files under `prototype/android/` into the corresponding source locations of the upstream project, replacing upstream files where applicable.
4. Configure the edge-service address to point to the machine running `prototype/edge/main.py`.
5. Follow Meta's upstream instructions for pairing supported Ray-Ban Meta smart glasses and building the application.

### 2. Edge Processing & Session-Context Service (☁️)

**Location:** `/prototype/edge/`

`main.py` implements the FastAPI edge service used by the prototype. It handles LLaVA visual extraction, regex-based numeric redaction, in-memory ChromaDB session-context storage, follow-up context retrieval, and server-side context deletion during revocation.

**Prerequisites:** Python 3.9+, Ollama, and the LLaVA model.

```bash
# Install Python dependencies
pip install -r requirements.txt

# Pull the required Ollama model
ollama pull llava

# Run the Edge Service
uvicorn main:app --host 0.0.0.0 --port 8000

```

*Note: The Android device and edge host must be reachable within the configured network environment.*

### 3. Static Analysis Triage (🔍)

**Location:** `/evaluation/static_analysis/`

`llm_screening.py` contains the LLM-assisted triage script used during static analysis of the decompiled companion application. It uses a locally hosted Gemma4 model through Ollama to prioritize candidate classes that may construct network payloads containing user data.

* *Note: LLM output was used only for candidate prioritization. Findings reported in the paper were verified manually. The proprietary decompiled application source is not redistributed.*

### 4. Dynamic Analysis & Results (⚙️)

**Location:** `/evaluation/dynamic_analysis/`

`evaluate_live_server.py` exercises the prototype workflow over the available evaluation images. It submits captures, records representation and redaction measurements, verifies pre-revocation session context, invokes the deletion endpoint, and tests post-revocation context availability.

**Run the Evaluation:**
*(Ensure the Edge Server is running first)*

```bash
python evaluate_live_server.py

```

* `prototype_evaluation_results.csv`: Contains the finalized output of our full 55-scenario evaluation, including representation sizes, redaction outcomes, latency, deletion outcomes, and post-revocation results.

### 5. Evaluation Dataset (📂)

**Location:** `/dataset/`

Contains redistributable sample identification and financial documents used in the controlled evaluation, along with `ground_truth_nested.json` (sanitized ground-truth information used by the evaluation code).

---

## 🛡️ Scope of the Artifact

The artifact evaluates application-managed state. The prototype implements application-level overwrite followed by deletion of the locally cached image and explicit deletion of server-side session context.

**The artifact does NOT claim:**

* Physical NAND or flash-memory sanitization.
* Sanitization of transient process or accelerator memory.
* Deletion from unobservable infrastructure.
* Distributed production-scale deletion guarantees.

The artifact does not redistribute proprietary Meta application binaries, account credentials, or private forensic acquisitions.

---

*This artifact is provided exclusively for confidential evaluation during the USENIX Security '27 review cycle.*
