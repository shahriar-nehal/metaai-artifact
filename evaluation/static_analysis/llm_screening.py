import os
import requests
import json
from concurrent.futures import ThreadPoolExecutor, as_completed

LIST_FILE = "ai_server_files.txt"
OLLAMA_URL = "http://localhost:11434/api/generate"
MODEL_NAME = "gemma4"
OUTPUT_LOG = "cloud_payloads_found.txt"
MAX_WORKERS = 4

SYSTEM_PROMPT = """
You are an expert Mobile Privacy Researcher and Code Auditor. 
Analyze the provided Java class from a decompiled Meta smart glasses app.

Your goal is to identify exactly WHAT user data is being packaged and sent to remote servers (Meta's backend). 
Look for JSON builders, GraphQL mutations, HTTP POST body construction, or multipart uploaders.

Specifically, check if the payload includes:
1. Raw media (byte arrays of photos, camera frames, or voice audio).
2. Environmental context (GPS coordinates, Wi-Fi SSIDs, Bluetooth devices).
3. Telemetry (Battery level, device IDs, usage timestamps).
4. AI Prompts (The transcribed text of what the user asked).

Output strictly as a valid JSON object:
{
  "transmits_data": true,
  "target_class": "ClassName",
  "endpoint_or_method": "e.g., graph.facebook.com or GraphQL Mutation Name",
  "data_fields_sent": ["list", "of", "exact", "variables", "or", "keys", "sent"],
  "explanation": "Detailed explanation of how the payload is constructed and what specific user data is included in the network request."
}
If the file does not construct a network payload, return exactly:
{
  "transmits_data": false
}
"""

def audit_single_file(rel_path, index, total):
    if not os.path.exists(rel_path):
        return None
        
    try:
        with open(rel_path, "r", encoding="utf-8", errors="ignore") as code_f:
            code_content = code_f.read()
        
        payload = {
            "model": MODEL_NAME,
            "prompt": f"Analyze this code:\n\n{code_content}",
            "system": SYSTEM_PROMPT,
            "stream": False,
            "format": "json", 
            "options": {
                "temperature": 0.1
            }
        }
        
        response = requests.post(OLLAMA_URL, json=payload, timeout=120)
        raw_response = response.json().get("response", "").strip()
        
        parsed = json.loads(raw_response)
        if parsed.get("transmits_data") is True:
            alert_msg = f"\n[📡] DATA TRANSMISSION FOUND IN {rel_path} ({index}/{total})\n"
            alert_msg += json.dumps(parsed, indent=2) + "\n" + ("="*60) + "\n"
            return alert_msg
            
    except Exception as e:
        pass
    return None

def process_audit():
    if not os.path.exists(LIST_FILE):
        print(f"[-] Error: {LIST_FILE} not found. Run your grep command first.")
        return

    with open(LIST_FILE, "r") as f:
        file_paths = [line.strip() for line in f if line.strip()]

    total_files = len(file_paths)
    if not total_files:
        print(f"[-] The list {LIST_FILE} is empty.")
        return

    print(f"[*] Starting Cloud Payload Audit across {total_files} files...")
    completed_count = 0
    
    with open(OUTPUT_LOG, "a", encoding="utf-8") as log_file:
        with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
            futures = {
                executor.submit(audit_single_file, path, i, total_files): path 
                for i, path in enumerate(file_paths, 1)
            }
            
            for future in as_completed(futures):
                completed_count += 1
                result = future.result()
                print(f"Progress: [{completed_count}/{total_files}] files analyzed...", end="\r")
                
                if result:
                    print(result) 
                    log_file.write(result)
                    log_file.flush() 

    print(f"\n[*] Payload Audit complete. Results saved to: {OUTPUT_LOG}")

if __name__ == "__main__":
    process_audit()