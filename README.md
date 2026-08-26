# Ghost in the Lens Artifacts

This anonymous repository contains the research artifacts accompanying
our USENIX Security '27 submission. The artifact provides the prototype
implementation, analysis code, per-scenario results, and redistributable
evaluation materials used to support the paper's core findings.

All identifying information, including author names, institutional
affiliations, repository history, and personal identifiers, has been
removed for double-blind review.

## Data Availability and Anonymization

The evaluation reported in the paper contains 55 controlled scenarios:
20 natural scenes, 10 driving-license samples, 10 student-ID samples,
10 bank-card samples, and 5 Social-Security-card samples.

The original natural-scene images are not included in the anonymized
review artifact because some contain recognizable individuals, locations,
or contextual information that could compromise author anonymity or
third-party privacy.

The artifact provides:

1. Redistributable identification and financial-document samples used in
   the controlled evaluation.
2. Sanitized ground-truth information for the evaluation corpus.
3. `prototype_evaluation_results.csv`, containing the recorded
   per-scenario measurements and revocation outcomes for all 55 trials.
4. Prototype, static-analysis, and dynamic-evaluation code.

The withheld natural-scene trials cannot be independently rerun without
their original source images. Their recorded measurements and outcomes
remain included in the evaluation results.

---

## Repository Structure

    .
    ├── prototype/
    │   ├── android/
    │   │   └── [modified CameraAccess components]
    │   └── edge/
    │       ├── main.py
    │       └── requirements.txt
    │
    ├── evaluation/
    │   ├── static_analysis/
    │   │   └── llm_screening.py
    │   └── dynamic_analysis/
    │       ├── evaluate_live_server.py
    │       └── prototype_evaluation_results.csv
    │
    └── dataset/
        ├── ground_truth_nested.json
        └── local_images/

---

## 1. Android Companion Prototype

**Location:** `prototype/android/`

The Android prototype extends Meta's public `CameraAccess` sample from
the Wearables Device Access Toolkit for Android. This artifact provides
the components modified or added for the Verifiable Privacy Architecture.

The released components include:

- `MainActivity.kt`: routes the prototype to the privacy-aware interface.
- `CameraViewModel.kt`: coordinates capture, session handling, selective
  disclosure, and revocation.
- `PrivacyChatScreen.kt`: provides capture visualization, redacted-text
  presentation, and disclosure controls.
- `SecurePhoneClient.kt`: implements session-scoped local caching,
  edge communication, application-level overwrite followed by file
  deletion, and server-side revocation requests.
- `CameraScreen.kt`: supporting camera-interface modifications.

### Building the Android Prototype

1. Clone Meta's upstream repository:

       https://github.com/facebook/meta-wearables-dat-android

2. Open `samples/CameraAccess` in Android Studio.

3. Copy the files under `prototype/android/` into the corresponding
   source locations of the upstream project.

4. Replace the upstream files where applicable and add the new
   prototype-specific files.

5. Configure the edge-service address to point to the machine running
   `prototype/edge/main.py`.

6. Follow Meta's upstream instructions for pairing supported Ray-Ban
   Meta smart glasses and building the application.

See `UPSTREAM.md` for provenance and the upstream revision used.

---

## 2. Edge Processing and Session-Context Service

**Location:** `prototype/edge/`

`main.py` implements the FastAPI edge service used by the prototype,
including:

- visual scene extraction using LLaVA;
- numeric redaction using a regular-expression rule;
- session-scoped storage of redacted textual context;
- follow-up inference through explicit session lookup; and
- deletion of session context during revocation.

The prototype uses an in-memory ChromaDB collection for session-context
management.

### Prerequisites

- Python 3.9+
- Ollama
- LLaVA

Install dependencies:

    pip install -r requirements.txt

Make LLaVA available:

    ollama pull llava

Run the edge service:

    uvicorn main:app --host 0.0.0.0 --port 8000

The Android device and edge host must be reachable within the configured
network environment.

---

## 3. Static Analysis

**Location:** `evaluation/static_analysis/`

`llm_screening.py` contains the LLM-assisted triage script used during
static analysis of the decompiled companion application.

The script uses a locally hosted Gemma4 model through Ollama to prioritize
candidate classes that may construct network payloads containing user
data.

LLM output was used only for candidate prioritization. Findings reported
in the paper were subsequently verified manually.

The proprietary decompiled application source is not redistributed.

---

## 4. Dynamic Analysis and Results

**Location:** `evaluation/dynamic_analysis/`

`evaluate_live_server.py` exercises the prototype workflow over the
available evaluation images. It submits captures, records representation
and redaction measurements, verifies pre-revocation session context,
invokes the deletion endpoint, and tests post-revocation context
availability.

Run the evaluation after starting the edge server:

    python evaluate_live_server.py

Because the anonymized artifact does not contain all 20 original
natural-scene images, rerunning the script operates only on the images
available in `dataset/local_images/`.

`prototype_evaluation_results.csv` contains the recorded results from
all 55 scenarios, including representation sizes, redaction outcomes,
latency, deletion outcomes, and post-revocation results.

---

## 5. Evaluation Dataset

**Location:** `dataset/`

The released dataset contains redistributable sample identification and
financial documents used in the controlled evaluation.

`ground_truth_nested.json` contains sanitized ground-truth information
used by the evaluation code.

Natural-scene images that could expose researcher identity, recognizable
locations, or third-party information are withheld from the anonymous
review artifact. Their corresponding measurements remain included in
`evaluation/dynamic_analysis/prototype_evaluation_results.csv`.

---

## Scope of the Artifact

The artifact evaluates application-managed state. The prototype
implements application-level overwrite followed by deletion of the
locally cached image and explicit deletion of server-side session
context.

The artifact does not claim physical NAND sanitization, sanitization of
transient process or accelerator memory, deletion from unobservable
infrastructure, or distributed production-scale deletion.

No proprietary Meta application binaries, account credentials, private
forensic acquisitions, or identifying researcher information are
included.

---

This artifact is provided for confidential evaluation during the
USENIX Security '27 review process.
