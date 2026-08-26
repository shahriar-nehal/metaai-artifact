import os
import uuid
import base64
import requests
import re
from fastapi import FastAPI, HTTPException, UploadFile, File, Form
from fastapi.responses import HTMLResponse, JSONResponse
import chromadb
from pydantic import BaseModel

app = FastAPI(title="Wearable Edge Node - Real Device Server")

# ==========================================
# 1. CLOUD/EDGE MEMORY ARCHITECTURE
# ==========================================
chroma_client = chromadb.Client() # Strictly In-Memory (RAM only)
collection = chroma_client.get_or_create_collection(name="user_session_memory")

class DeleteRequest(BaseModel):
    session_id: str

# ==========================================
# 2. LIVE DASHBOARD (Edge Node Monitor)
# ==========================================
@app.get("/stats")
def get_system_stats():
    """Returns the live count of active vectors in the edge database."""
    vector_count = collection.count() 
    return {"active_vectors": vector_count}

@app.get("/", response_class=HTMLResponse)
def serve_dashboard():
    """A clean UI to watch the vectors get created and deleted live."""
    html_content = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Edge Node Forensic Monitor</title>
        <script src="https://cdn.tailwindcss.com"></script>
        <style>
            body { background-color: #0b1120; color: #f8fafc; font-family: 'Inter', sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
            .monitor-card { background-color: #1e293b; padding: 40px; border-radius: 12px; text-align: center; border: 1px solid #334155; width: 400px; box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5); }
            .number { font-size: 6rem; font-weight: bold; margin: 20px 0; transition: color 0.3s ease; }
            .secure { color: #10b981; text-shadow: 0 0 20px rgba(16, 185, 129, 0.4); }
            .empty { color: #64748b; }
        </style>
    </head>
    <body>
        <div class="monitor-card">
            <span class="bg-purple-600 text-white px-4 py-1 rounded-full text-sm font-bold uppercase tracking-wider">Edge Node Server</span>
            <h3 class="mt-6 text-xl text-slate-300 font-bold">ChromaDB Vectors</h3>
            <div id="cloud-count" class="number empty">0</div>
            <p class="text-sm text-slate-400">Stateless AI Memory Retained</p>
            <p class="text-xs text-slate-500 mt-4 italic">Waiting for connection from Android App...</p>
        </div>

        <script>
            async function fetchStats() {
                try {
                    const response = await fetch('/stats');
                    const data = await response.json();
                    const cloudElem = document.getElementById('cloud-count');
                    cloudElem.innerText = data.active_vectors;
                    cloudElem.className = data.active_vectors > 0 ? 'number secure' : 'number empty';
                } catch (error) { console.error(error); }
            }
            setInterval(fetchStats, 1000); 
            window.onload = fetchStats;
        </script>
    </body>
    </html>
    """
    return html_content

# ==========================================
# 3. CORE API: Receiving Real Images
# ==========================================
@app.post("/capture")
async def process_real_capture(
    file: UploadFile = File(...), 
    session_id: str = Form(...)
):
    print(f"\n[NETWORK] Received image from Android. Session: {session_id}")
    
    image_bytes = await file.read()
    img_b64 = base64.b64encode(image_bytes).decode("utf-8")
    
    print("[AI] Analyzing image with LLaVA...")
    payload = {
        "model": "llava",
        "prompt": "Provide an exhaustive visual description of this image. Extract and read any text or numbers visible.",
        "images": [img_b64],
        "stream": False
    }
    
    try:
        ai_response = requests.post("http://localhost:11434/api/generate", json=payload, timeout=30)
        ai_response.raise_for_status()
        vision_caption = ai_response.json().get("response", "Failed to analyze.")
    except Exception as e:
        print(f"[ERROR] Ollama failed: {e}")
        vision_caption = "Simulated Fallback: I see an environment captured by the Meta glasses."

    # EDGE GUARDRAIL: Detect and redact sequences of 4 or more digits
    has_pii = bool(re.search(r'\b\d{4,}\b', vision_caption))
    safe_caption = re.sub(r'\b\d{4,}\b', '[REDACTED_NUMERIC]', vision_caption)

    print(f"[MEMORY] Saving REDACTED Vector to ChromaDB: '{safe_caption}'")
    collection.add(
        documents=[safe_caption],
        metadatas=[{"source": "android_hardware"}],
        ids=[session_id]
    )
    
    return JSONResponse(content={
        "safe_text": safe_caption,
        "has_pii": has_pii,
        "raw_text": vision_caption
    })

@app.post("/chat")
def chat_with_memory(session_id: str = Form(...), prompt: str = Form(...)):
    print(f"\n[CHAT] Follow-up question received: '{prompt}' for Session: {session_id}")
    
    results = collection.get(ids=[session_id])
    context = ""
    if results["documents"] and len(results["documents"]) > 0:
        context = results["documents"][0]
        print(f"[MEMORY] Recalled Context: {context}")
    else:
        return {"response": "I don't have any visual memory to reference. Did you delete it?"}

    system_prompt = f"You are a helpful AI assistant connected to smart glasses. The user is asking a question about a scene they previously looked at. Here is the REDACTED visual description of that scene: '{context}'. Answer their question based ONLY on this description."
    
    payload = {
        "model": "llava", 
        "prompt": f"{system_prompt}\n\nUser Question: {prompt}",
        "stream": False
    }
    
    try:
        ai_response = requests.post("http://localhost:11434/api/generate", json=payload, timeout=15)
        ai_response.raise_for_status()
        answer = ai_response.json().get("response", "I couldn't process that.")
    except Exception as e:
        print(f"[ERROR] Ollama failed: {e}")
        answer = "I'm having trouble connecting to my text brain."
        
    return {"response": answer}

@app.post("/delete")
def synchronized_kill_switch(req: DeleteRequest):
    print(f"\n[DELETE] Kill switch activated for session: {req.session_id}")
    results = collection.get(ids=[req.session_id])
    if not results["ids"]:
        raise HTTPException(status_code=404, detail="Session not found in DB")
        
    collection.delete(ids=[req.session_id])
    print("[DELETE] Vector successfully dropped from ChromaDB.")
    
    return {"status": "Vector Dropped Successfully"}

if __name__ == "__main__":
    # To run this script:
    # uvicorn main:app --host 0.0.0.0 --port 8000
    pass