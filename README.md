# Ghost in the Lens Artifacts

This anonymous repository contains the artifacts required to reproduce the core contributions, prototype architecture, and state-eradication measurements presented in our USENIX Security '27 submission. 

In compliance with the double-blind review process, all identifying information (e.g., author names, institutional affiliations, commit histories, and personal identifiers) has been stripped from this repository. 

## 📁 Repository Structure

    .
    ├── prototype/
    │   ├── android/ (Android Studio Project)
    │   └── edge/ 
    │       └── main.py
    ├── evaluation/
    │   ├── evaluate_live_server.py
    │   └── prototype_evaluation_results.csv
    └── dataset/
        ├── ground_truth_nested.json
        └── local_images

---

## 🛠️ Components & Usage Instructions

### 1. Verifiable Privacy Companion App (Android)
**Location:** `/prototype/android/`

This folder contains the modified Android Studio source code extending Meta's public `CameraAccess` sample. It implements the wearable capture, local session caching, selective disclosure UI, and the Synchronized Revocation kill switch.

**To run the Android Prototype:**
1. Import the `android` folder into Android Studio.
2. Ensure you have a physical pair of Ray-Ban Meta Smart Glasses. Please refer to the [official Meta Wearables DAT SDK documentation](https://github.com/facebook/meta-wearables-dat-android) for hardware instructions on putting the glasses into pairing mode and establishing a connection with the host device (Android 14+ recommended).
3. In `CameraViewModel.kt`, update the `edgeNodeIp` variable to match the local IP address of the machine hosting the Edge Server.
4. Build and deploy the `.apk`.

### 2. Edge Processing & Session Context Server (FastAPI)
**Location:** `/prototype/edge/`

This folder contains the `main.py` FastAPI service that implements the remote half of the architecture: visual extraction (via LLaVA), numeric redaction (Regex), ephemeral vector storage (ChromaDB), and server-side context deletion.

* **Prerequisites:** Python 3.9+, and a running instance of `ollama` serving the `llava` model.
    ```bash
    pip install fastapi uvicorn chromadb requests
    ollama run llava
    ```
* **To run the Edge Server:**
    ```bash
    uvicorn main:app --host 0.0.0.0 --port 8000
    ```

### 3. Evaluation Scripts & Results
**Location:** `/evaluation/`

These scripts replicate our automated pipeline for transmitting the test corpus to the edge server, verifying pre-deletion memory, triggering the kill switch, and verifying post-revocation state eradication.

* **To run the evaluation:**
    Ensure the Edge Server is running locally, then execute:
    ```bash
    python evaluate_live_server.py
    ```
* `prototype_evaluation_results.csv`: Contains the finalized output of our 55-scenario evaluation, including raw size, vector size, reduction percentage, guardrail triggers, latency, and verified state eradication.

### 4. Evaluation Dataset
**Location:** `/dataset/`

Contains the synthetic identity and financial documents used to evaluate the numeric redaction guardrails, alongside the `ground_truth_nested.json` file containing the semantic extractions.

---

## 📝 Note to Reviewers: Data Confidentiality & Open Science
In the submitted manuscript's Open Science section, we stated that the `dataset/` folder contains our evaluation images. To strictly comply with USENIX ethical guidelines and protect personal privacy, the authentic personal photographs used in the "natural scenes" category have been **omitted** from this public artifact. 

To ensure full reproducibility of the paper's core claims without exposing private imagery, we have provided all sample financial and identification documents, the complete `ground_truth_nested.json` file, and the full `prototype_evaluation_results.csv` generated during our internal 55-scenario run.

These artifacts are provided exclusively for the purpose of evaluating this paper during the USENIX Security '27 review cycle.
