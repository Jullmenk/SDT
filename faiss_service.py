from fastapi import FastAPI
from pydantic import BaseModel
import faiss
import numpy as np
import os
import uvicorn

DIM = int(os.environ.get("EMBEDDINGS_DIM", "128"))

class IndexItem(BaseModel):
    cid: str
    embedding: list[float]

class SearchRequest(BaseModel):
    embedding: list[float]
    k: int = 5

app = FastAPI()
index = faiss.IndexFlatIP(DIM)
cids: list[str] = []

@app.get("/health")
def health():
    return {"estado": "OK", "dim": DIM, "ntotal": int(index.ntotal)}

@app.post("/index")
def add_item(item: IndexItem):
    if len(item.embedding) != DIM:
        return {"erro": f"embedding dimension {len(item.embedding)} != {DIM}"}
    if item.cid in cids:
        return {"ntotal": int(index.ntotal), "info": "cid já indexado, ignorado"}
    v = np.array(item.embedding, dtype="float32").reshape(1, DIM)
    index.add(v)
    cids.append(item.cid)
    return {"ntotal": int(index.ntotal)}

@app.post("/search")
def search(req: SearchRequest):
    if len(req.embedding) != DIM:
        return {"erro": f"embedding dimension {len(req.embedding)} != {DIM}"}
    if index.ntotal == 0:
        return {"results": []}
    v = np.array(req.embedding, dtype="float32").reshape(1, DIM)
    candidatos = min(max(req.k * 4, req.k), int(index.ntotal))
    D, I = index.search(v, candidatos)
    results = []
    vistos = set()
    for score, idx in zip(D[0], I[0]):
        if idx < 0 or idx >= len(cids):
            continue
        cid = cids[idx]
        if cid in vistos:
            continue
        vistos.add(cid)
        results.append({"cid": cid, "score": float(score)})
        if len(results) >= req.k:
            break
    return {"results": results}

if __name__ == "__main__":
    porta = int(os.environ.get("FAISS_PORT", "9000"))
    uvicorn.run(app, host="0.0.0.0", port=porta)
