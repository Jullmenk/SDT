# Instruções para correr o projeto
## O que precisas de ter instalado

- Java 17 e Maven
- IPFS (kubo) instalado e já inicializado (`ipfs init`, só na primeira vez)
- Python 3 com fastapi, uvicorn, faiss-cpu, numpy e pydantic instalados (`pip install fastapi uvicorn faiss-cpu numpy pydantic`, usar `--break-system-packages` se o pip reclamar)

## Ordem para arrancar (importa muito, não saltar passos)

Cada um destes comandos fica preso no terminal (não devolve o prompt), por isso precisas de um terminal novo para cada um. Não colar dois comandos seguidos no mesmo terminal.

1. `ipfs daemon` — deixa a correr, é a base de tudo
2. `python3 faiss_service.py` — o serviço FAISS do líder (porta 9000 por omissão)
3. `mvn exec:java -Dexec.mainClass=lider.Lider` — o líder, fica à escuta em localhost:8080
4. Para cada peer que quiseres testar, dois terminais:
   - `python3 faiss_service.py` com a porta diferente, ex: `FAISS_PORT=9001 python3 faiss_service.py`
   - `PEER_FAISS_PROXY_PORT=8091 FAISS_PORT=9001 mvn exec:java -Dexec.mainClass=peer.Peer` (muda a porta a cada peer novo)

Com 2 peers dá para testar tudo bem. Não é preciso mais.

Se quiseres arrancar do zero (sem histórico de testes anteriores), apaga o ficheiro `estado_lider.json` antes de arrancar o líder, e garante que não deixaste nenhum peer ou faiss_service.py antigo a correr de um teste anterior.

## Testes a fazer

### 1. Upload de um ficheiro (RF1)

```
curl -F "file=@algum_ficheiro.txt" http://localhost:8080/upload
```

Vai aparecer nos terminais dos peers as linhas de PREPARE e depois COMMIT. Confirma que os dois peers chegam ao mesmo estado:

```
curl http://localhost:8080/vetor
curl http://localhost:8090/estado
curl http://localhost:8091/estado
```

A versão e o número de documentos têm de bater certo nos três.

### 2. Pesquisa (RF2)

```
curl -X POST http://localhost:8080/prompt -H "Content-Type: application/json" -d '{"prompt":"alguma coisa"}'
```

Isto devolve só um id. Depois:

```
curl http://localhost:8080/prompt/<id que recebeste>
```

Se der `"estado":"pendente"`, espera um segundo e tenta outra vez. Repara nos terminais dos peers qual deles é que respondeu à query (só um deve responder, não os dois).

### 3. Falha do líder e eleição

Com tudo a correr e pelo menos um upload já feito, mata o processo do líder (Ctrl+C no terminal dele). Espera uns 15 segundos. Um dos peers deve assumir como novo líder sozinho (vai aparecer nos logs "Iniciar eleição" e depois "Sou o vencedor da eleição"). Confirma outra vez com `curl http://localhost:8080/vetor` que o estado se manteve (não voltou a zero).

## Coisas a que prestar atenção

- Se os dois peers ficarem os dois com a mesma porta de debug (8090), é porque esqueceste de mudar o `PEER_FAISS_PROXY_PORT` ao arrancar o segundo. Corrige e arranca de novo.
- Se aparecer erro de ligação recusada (connection refused) num peer ao tentar indexar no FAISS, é porque o `faiss_service.py` desse peer morreu ou nunca chegou a arrancar. Confirma com `curl http://localhost:9000/health` (ou 9001) antes de fazeres upload.
- Reenviar o mesmo ficheiro duas vezes dá sempre o mesmo CID (é assim que o IPFS funciona), mas conta como uma entrada nova no vetor na mesma. Não é bug.
- A pesquisa não é uma pesquisa de texto normal, é por semelhança de vetores. Não esperar que devolva sempre o documento "certo", às vezes os resultados não fazem muito sentido, isso é uma limitação conhecida e está explicada no README principal.

## Postman

Há uma collection do Postman com todos os pedidos já organizados por sprint, importa esse ficheiro e usa as variáveis já configuradas (lider_url, peer1_url, peer2_url) em vez de escrever os pedidos à mão.
