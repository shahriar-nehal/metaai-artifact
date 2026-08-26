# Ghost in the Lens: Verifiable Privacy Architecture Artifact

This anonymous repository contains the research artifacts accompanying
our USENIX Security '27 submission. The artifact provides the prototype
implementation, evaluation code, per-scenario results, and redistributable
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

The artifact therefore provides:

1. Redistributable sample identification and financial documents used in
   the controlled evaluation.
2. Sanitized ground-truth information for the evaluation corpus.
3. `prototype_evaluation_results.csv`, containing the recorded
   per-scenario measurements and revocation outcomes for all 55 trials.
4. The prototype and evaluation code used to generate and test the
   application-managed session state.

The withheld natural-scene trials cannot be independently rerun from this
anonymized artifact without their original source images. Their recorded
measurements and outcomes remain included in the evaluation results.

---

## Repository Structure

    .
    ├── prototype/
    │   ├── android/
    │   │   └── [modified CameraAccess components]
    │   └── edge/
    │       ├── main.py
    │
    ├── evaluation/
    │   ├── evaluate_live_server.py
    │   └── prototype_evaluation_results.csv
    │
    └── dataset/
        ├── ground_truth_nested.json
        └── local_images/

---

## 1. Android Companion Prototype

**Location:** `prototype/android/`

The Android prototype extends Meta's public `CameraAccess` sample from
the Wearables Device Access Toolkit for Android. Rather than duplicating
the complete upstream project, this artifact provides the components
modified or added for the Verifiable Privacy Architecture.

The released components include:

- `MainActivity.kt`: routes the prototype into the privacy-aware
  conversational interface.
- `CameraViewModel.kt`: coordinates wearable capture, session handling,
  selective disclosure, and revocation actions.
- `PrivacyChatScreen.kt`: provides capture visualization, redacted-text
  presentation, and disclosure controls.
- `SecurePhoneClient.kt`: implements session-scoped local caching,
  communication with the edge service, application-level overwrite
  followed by file deletion, and server-side revocation requests.
- `CameraScreen.kt`: supporting modifications to the camera interface.

### Building the Android Prototype

1. Clone Meta's upstream repository:

       https://github.com/facebook/meta-wearables-dat-android

2. Open the `samples/CameraAccess` project in Android Studio.

3. Copy the files under `prototype/android/` into the corresponding
   source locations of the upstream `CameraAccess` project.

4. Replace the upstream versions where applicable and add the new
   prototype-specific files.

5. Configure the edge-service address in the Android client to point to
   the machine running `prototype/edge/main.py`.

6. Follow Meta's upstream instructions for pairing a supported pair of
   Ray-Ban Meta smart glasses and building the `CameraAccess` sample.

See the repository-level `UPSTREAM.md` for provenance and the upstream
revision used by this artifact.

---

## 2. Edge Processing and Session-Context Service

**Location:** `prototype/edge/`

`main.py` implements the FastAPI service used by the prototype. Its
application-level data path consists of:

- visual scene extraction using LLaVA;
- numeric redaction using a regular-expression rule;
- session-scoped storage of redacted textual context;
- follow-up inference through explicit session lookup; and
- deletion of the corresponding session context during revocation.

The session-context collection is maintained in memory by ChromaDB for
the evaluated prototype.

### Prerequisites

- Python 3.9+
- Ollama
- LLaVA available through Ollama

Install the Python dependencies using:

    pip install -r requirements.txt

Ensure LLaVA is available:

    ollama run llava

Run the edge service:

    uvicorn main:app --host 0.0.0.0 --port 8000

The Android device and edge host must be reachable from the same
configured network environment.

---

## 3. Evaluation Code and Results

**Location:** `evaluation/`

`evaluate_live_server.py` exercises the edge-service workflow over the
available evaluation images. It submits captures, records representation
and redaction measurements, verifies that session context is available
before revocation, invokes the deletion endpoint, and tests whether the
same session context remains available afterward.

To run the evaluation over the released images, first start the edge
service and then execute:

    python evaluate_live_server.py

The anonymized artifact does not contain all 20 original natural-scene
images; therefore, rerunning the script operates only on the evaluation
images available in `dataset/local_images/`.

### Evaluation Results

`prototype_evaluation_results.csv` contains the recorded results from
all 55 scenarios evaluated for the paper, including:

- input representation size;
- retained-context representation size;
- representation-size reduction;
- numeric-redaction outcome;
- capture-processing latency;
- deletion-endpoint outcome; and
- post-revocation context/query outcome.

These are the per-scenario measurements underlying the aggregate results
reported in the paper.

---

## 4. Evaluation Dataset

**Location:** `dataset/`

The released dataset contains redistributable sample documents used in
the controlled identification and financial-document scenarios.

Natural-scene source images that could expose researcher identity,
recognizable locations, or third-party information are withheld from the
anonymous review artifact. See `dataset/README.md` for details.

`ground_truth_nested.json` contains the sanitized ground-truth information
used by the evaluation code. Identifying contextual descriptions from
withheld natural-scene images have been removed from the anonymous
version.

---

## Scope of the Artifact

The artifact evaluates application-managed state. In particular, the
prototype implements application-level overwrite followed by deletion of
the locally cached image and explicit deletion of server-side session
context.

The artifact does not claim or evaluate physical NAND sanitization,
sanitization of transient process or accelerator memory, deletion from
unobservable infrastructure, or distributed production-scale deletion.

No proprietary Meta application binaries, account credentials, private
forensic acquisitions, or identifying researcher information are
included in this repository.

---

This artifact is provided for confidential evaluation during the
USENIX Security '27 review process.
