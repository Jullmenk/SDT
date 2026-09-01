# Serviço FAISS local, usado pelo processo Peer (peer.Peer) para indexar/pesquisar
# embeddings. IMPORTANTE (ver README.md "Como correr"): cada PEER deve correr a sua
# própria instância deste serviço em localhost:9000 - não é um serviço central partilhado
# por todos os peers. Isto corresponde ao requisito RF1 "atualiza a indexação FAISS
# (armazenada em memória)": cada peer mantém o seu próprio índice, espelhando todos os
# documentos confirmados (não só os que faz pinning).
#
# DIM tem de ser igual ao valor de "embeddings.dim" em application.properties
# (common.EmbeddingUtil, do lado Java). Por omissão, ambos usam 128.
#
# CORREÇÃO (RF2, testes ao vivo): re-fazer upload do MESMO ficheiro (mesmo conteúdo) dá
# sempre o mesmo CID (content-addressing do IPFS) e, por isso, sempre o mesmo embedding
# (função determinística - ver common.EmbeddingUtil do lado Java). Como o RF1 não
# deduplica o vetor (cada upload continua a somar uma entrada nova, propositadamente -
# ver DOCUMENTACAO_SPRINTS.md), o COMMIT chamava sempre /index outra vez para o mesmo cid,
# criando ENTRADAS DUPLICADAS no índice - resultados de /search apareciam repetidos (mesmo
# cid, mesmo score, mais que uma vez). Corrigido em dois sítios: /index já não indexa um
# cid que já lá esteja; /search filtra duplicados mesmo que já existam no índice (índices
# criados antes desta correção podem já ter duplicados guardados em memória).
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
        # Reupload do mesmo conteúdo (mesmo CID -> mesmo embedding, determinístico) -
        # não indexa outra vez, para não aparecer duplicado nos resultados de /search.
        return {"ntotal": int(index.ntotal), "info": "cid já indexado, ignorado"}
    v = np.array(item.embedding, dtype="float32").reshape(1, DIM)
    index.add(v)
    cids.append(item.cid)
    # ntotal é atributo, não função
    return {"ntotal": int(index.ntotal)}

@app.post("/search")
def search(req: SearchRequest):
    if len(req.embedding) != DIM:
        return {"erro": f"embedding dimension {len(req.embedding)} != {DIM}"}
    if index.ntotal == 0:
        return {"results": []}
    v = np.array(req.embedding, dtype="float32").reshape(1, DIM)
    # Pede mais candidatos do que o pedido (req.k) para poder filtrar CIDs repetidos e
    # ainda assim devolver até req.k resultados DISTINTOS - protege também índices criados
    # antes da correção acima, que podem já ter entradas duplicadas guardadas em memória.
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
    # Porta configuravel via variavel de ambiente (util para correr varias instancias
    # na mesma maquina, uma por peer - ver README.md "Correr varios peers na mesma
    # maquina"). Tem de bater certo com "faiss.port" desse peer no application.properties
    # (ou com o "-Dfaiss.port=..." passado na linha de comandos ao arrancar o Peer).
    porta = int(os.environ.get("FAISS_PORT", "9000"))
    uvicorn.run(app, host="0.0.0.0", port=porta)
