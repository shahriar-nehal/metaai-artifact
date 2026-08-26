import os
import json
import requests
import time
import csv
import uuid

# --- CONFIGURATION ---
API_BASE_URL = "http://localhost:8000"
IMAGE_DIR = "local_images"
GROUND_TRUTH_FILE = "ground_truth_nested.json"
RESULTS_CSV = "usenix_live_server_results.csv"

def evaluate_live_server():
    print("🚀 Starting End-to-End Evaluation against Live FastAPI Edge Server...\n")
    
    with open(GROUND_TRUTH_FILE, 'r') as f:
        ground_truth = json.load(f)

    valid_extensions = ('.jpg', '.jpeg', '.png')
    images = [f for f in os.listdir(IMAGE_DIR) if f.lower().endswith(valid_extensions)]
    results = []

    for idx, img_filename in enumerate(images):
        if img_filename not in ground_truth:
            continue
            
        img_path = os.path.join(IMAGE_DIR, img_filename)
        raw_size_bytes = os.path.getsize(img_path)
        category = ground_truth[img_filename].get("category", "unknown")
        session_id = str(uuid.uuid4())

        print(f"[{idx+1}/{len(images)}] Processing: {img_filename} (Session: {session_id[:8]}...)")

        # ---------------------------------------------------------
        # STEP 1: UPLOAD TO /capture (Edge Ingestion & Redaction)
        # ---------------------------------------------------------
        start_capture = time.time()
        with open(img_path, 'rb') as img_file:
            files = {'file': (img_filename, img_file, 'image/jpeg')}
            data = {'session_id': session_id}
            capture_res = requests.post(f"{API_BASE_URL}/capture", files=files, data=data)
            
        capture_latency = round(time.time() - start_capture, 2)
        capture_json = capture_res.json()
        
        safe_text = capture_json.get("safe_text", "")
        has_pii = capture_json.get("has_pii", False)
        vector_size_bytes = len(safe_text.encode('utf-8'))
        reduction_pct = (1 - (vector_size_bytes / raw_size_bytes)) * 100

        # ---------------------------------------------------------
        # STEP 2: PRE-DELETION QUERY TO /chat
        # ---------------------------------------------------------
        test_prompt = "What is the primary subject in this scene?"
        chat_pre = requests.post(
            f"{API_BASE_URL}/chat", 
            data={"session_id": session_id, "prompt": test_prompt}
        ).json().get("response", "")

        # ---------------------------------------------------------
        # STEP 3: REAL KILL-SWITCH CALL TO /delete
        # ---------------------------------------------------------
        delete_res = requests.post(
            f"{API_BASE_URL}/delete", 
            json={"session_id": session_id}
        )
        delete_success = 1 if delete_res.status_code == 200 else 0

        # ---------------------------------------------------------
        # STEP 4: POST-DELETION VERIFICATION CALL TO /chat
        # ---------------------------------------------------------
        chat_post = requests.post(
            f"{API_BASE_URL}/chat", 
            data={"session_id": session_id, "prompt": test_prompt}
        ).json().get("response", "")

        # Amnesia is 100% verified if the server has no memory
        amnesia_verified = 1 if "did you delete it" in chat_post.lower() or "no visual memory" in chat_post.lower() else 0

        print(f"  -> Reduction: {reduction_pct:.2f}% | PII Intercepted: {has_pii}")
        print(f"  -> Vector Dropped: {bool(delete_success)} | Amnesia Verified: {bool(amnesia_verified)}")

        results.append({
            "image_id": img_filename,
            "category": category,
            "session_id": session_id,
            "raw_size_kb": round(raw_size_bytes / 1024, 2),
            "vector_size_kb": round(vector_size_bytes / 1024, 2),
            "storage_reduction_pct": round(reduction_pct, 4),
            "guardrail_triggered": 1 if has_pii else 0,
            "capture_latency_sec": capture_latency,
            "pre_delete_response": chat_pre.replace('\n', ' '),
            "delete_endpoint_success": delete_success,
            "post_delete_response": chat_post.replace('\n', ' '),
            "amnesia_verified": amnesia_verified
        })

    # Save final results
    if results:
        with open(RESULTS_CSV, 'w', newline='', encoding='utf-8') as f:
            writer = csv.DictWriter(f, fieldnames=results[0].keys())
            writer.writeheader()
            writer.writerows(results)
        print(f"\n✅ Finished! Live server evaluation saved to {RESULTS_CSV}")

if __name__ == "__main__":
    evaluate_live_server()