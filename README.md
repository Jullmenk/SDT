# Sistema Distribuído — IPFS + FAISS

Sistema distribuído para armazenamento e pesquisa de documentos, com um líder eleito
dinamicamente a coordenar um protocolo de consenso (PREPARE/CONFIRMAÇÃO/COMMIT) sobre
IPFS PubSub, e pesquisa semântica via FAISS.

Este README substitui o `readme.md` original e explica como correr o sistema completo
do zero. Para perceber **o que mudou** em relação à versão anterior do código e **como
defender cada sprint**, ver `DOCUMENTACAO_SPRINTS.md`.

> **Nota sobre este documento:** foi escrito depois de uma refatoração de código feita
> sem acesso a um ambiente com o Maven Central/IPFS/Python disponíveis para testar
> end-to-end (sandbox sem rede para essas dependências). O código foi revisto
> manualmente com cuidado, mas o primeiro passo depois de o receberem deve ser
> `mvn compile` na vossa máquina, com internet, para confirmar que compila e corrigir
> quaisquer pequenas diferenças de assinatura da biblioteca `java-ipfs-http-client`
> (ver secção "Pontos a confirmar ao compilar", no fim deste ficheiro).

---

## 1. Arquitetura, num relance

```
Cliente (curl / Postman)
     │  HTTP (upload, prompt)
     ▼
  Líder (Lider.java) ──── IPFS PubSub (tópico "atualizacoes") ────► Peers (Peer.java)
     │                                                                    │
     ▼                                                                    ▼
  IPFS local (líder)                                              IPFS local (cada peer)
                                                                          │
                                                                          ▼
                                                             faiss_service.py (local, por peer)
```

- Cada máquina (líder ou peer) corre o seu **próprio** daemon IPFS local.
- Toda a comunicação líder ↔ peers passa pelo PubSub do IPFS (tópico único
  `atualizacoes`, configurável em `application.properties`).
- Cada **peer** corre a sua própria instância local do `faiss_service.py` (porta 9000)
  — não é um serviço central partilhado.
- O papel de "líder" não está fixo num PC: começa por ser o processo que arrancarem
  primeiro com `lider.Lider`, mas se esse processo cair, um dos peers assume
  automaticamente (ver `peer.PeerEleicao`).

Estrutura do código (ver `DOCUMENTACAO_SPRINTS.md` para o detalhe):

```
src/main/java/
  common/   -> utilitários e configuração partilhados por Lider e Peer
  lider/    -> tudo o que só o processo Líder usa
  peer/     -> tudo o que só o processo Peer usa
faiss_service.py -> serviço FAISS local (correr um por peer)
```

---

## 2. Pré-requisitos

| Ferramenta | Versão mínima | Verificar com |
|---|---|---|
| Java (JDK) | 17 | `java -version` |
| Maven | 3.6+ | `mvn -v` |
| IPFS (Kubo) | qualquer versão recente | `ipfs version` |
| Python | 3.9+ | `python3 --version` |
| pip packages | `fastapi uvicorn faiss-cpu numpy pydantic` | `pip install fastapi uvicorn faiss-cpu numpy pydantic` |

Instalação do IPFS (Kubo): https://docs.ipfs.tech/install/command-line/ — depois de
instalado, inicializar o repositório local uma vez por máquina:

```bash
ipfs init
```

---

## 3. Configuração (`application.properties`)

Ficheiro em `src/main/resources/application.properties`. Todos os valores são
efetivamente lidos pelo código (ver `common.Config`). Os mais relevantes para adaptar à
vossa rede:

> Qualquer propriedade desta tabela pode também ser definida como **variável de
> ambiente** (a chave em maiúsculas, "." trocado por "_" — ex.: `faiss.port` vira
> `FAISS_PORT`), sem editar o ficheiro nem recompilar: `FAISS_PORT=9001 mvn exec:java
> -Dexec.mainClass=peer.Peer`. É a forma recomendada — testámos e o `-Dchave=valor` do
> Maven nem sempre chega ao processo do Peer/Líder (depende de o `exec:java` correr
> dentro do próprio processo do Maven ou num processo à parte, o que varia por versão do
> plugin); a variável de ambiente funciona sempre, porque é herdada por qualquer
> processo filho. Usa-se sobretudo para correr vários peers na mesma máquina (ver secção 4.6).

| Propriedade | Default | Para quê |
|---|---|---|
| `ipfs.host` / `ipfs.port` | `127.0.0.1` / `5001` | onde está o IPFS local desta máquina |
| `lider.api.port` | `8080` | porta da API REST do líder |
| `peer.faiss.proxy.port` | `8090` | porta da API de debug do peer |
| `faiss.host` / `faiss.port` | `localhost` / `9000` | onde está o `faiss_service.py` local |
| `pinning.replicas` | `2` | nº de peers que fazem pinning de cada ficheiro (regra do RF1) |
| `heartbeat.interval.ms` / `heartbeat.timeout.ms` | `5000` / `10000` | deteção de falha do líder |
| `eleicao.duracao.ms` | `3000` | janela de espera da eleição |
| `embeddings.dim` | `128` | tem de ser igual ao `EMBEDDINGS_DIM` do `faiss_service.py` |

---

## 4. Passo a passo para correr tudo localmente (1 máquina, demo rápida)

Útil para testar antes de distribuir por várias máquinas.

### 4.1 Compilar

```bash
cd SDTProject-SPRINT7
mvn -q compile
```

### 4.2 Iniciar o IPFS

```bash
ipfs daemon --enable-pubsub-experiment
```
(em versões recentes do Kubo o PubSub já vem ligado por omissão, mas a flag não faz mal).

Deixar este terminal aberto.

### 4.3 Iniciar o serviço FAISS local

```bash
python3 faiss_service.py
```
Confirmar com `curl http://localhost:9000/health`.

### 4.4 Iniciar o Líder

```bash
mvn exec:java -Dexec.mainClass=lider.Lider
```
ou, depois de `mvn package`/`mvn compile`, usando o script com o classpath já montado:
```bash
./start-leader.sh      # Linux/Mac
start-leader.bat       # Windows
```

### 4.5 Iniciar um ou mais Peers

Em **cada** peer é preciso: o seu próprio `ipfs daemon` a correr, o seu próprio
`faiss_service.py` a correr, e depois:

```bash
mvn exec:java -Dexec.mainClass=peer.Peer
```
ou `./start-peer.sh` / `start-peer.bat`.

> Para testar tudo numa só máquina (demo), basta abrir vários terminais e correr vários
> `Peer` — cada um gera automaticamente um ID aleatório (`estado.meuId`). Atenção: se
> todos os peers tentarem usar a mesma porta 8090 (API de debug) e o mesmo
> `faiss_service.py` (porta 9000), há conflito de porta no segundo peer e o índice FAISS
> fica partilhado (o mesmo documento fica indexado a dobrar, um por peer que faça
> commit) — ver a secção seguinte para correr vários peers em segurança.

### 4.6 Correr vários peers na mesma máquina (sem conflitos de porta)

Cada peer precisa da sua própria porta de debug (`peer.faiss.proxy.port`) e do seu
próprio `faiss_service.py` (porta `faiss.port`). Todos podem continuar a apontar para o
**mesmo** `ipfs daemon` local (isso não é problema).

**Peer 1** (portas por omissão — não precisa de nada especial):
```bash
# terminal A - FAISS do peer 1
python3 faiss_service.py

# terminal B - o peer 1
mvn exec:java -Dexec.mainClass=peer.Peer
```

**Peer 2** (portas diferentes, via **variáveis de ambiente** — não precisa de editar o
`application.properties` nem recompilar, e funciona sempre, ao contrário do `-D`):
```bash
# terminal C - FAISS do peer 2, noutra porta
FAISS_PORT=9001 python3 faiss_service.py

# terminal D - o peer 2, a apontar para a porta de FAISS dele e com porta de debug própria
FAISS_PORT=9001 PEER_FAISS_PROXY_PORT=8091 mvn exec:java -Dexec.mainClass=peer.Peer
```

**Peer 3** (se quiseres um terceiro, mesma lógica):
```bash
FAISS_PORT=9002 python3 faiss_service.py
FAISS_PORT=9002 PEER_FAISS_PROXY_PORT=8092 mvn exec:java -Dexec.mainClass=peer.Peer
```

Nota: a variável `FAISS_PORT` é usada aqui dos dois lados de propósito — no
`faiss_service.py` decide em que porta o serviço Python fica à escuta, e no
`mvn exec:java` (lida pelo `common.Config`) diz ao peer em Java em que porta ir bater a
essa mesma instância. Têm de ser sempre o mesmo número dos dois lados.

Para confirmar que cada peer está mesmo isolado: `curl http://localhost:8090/estado`,
`curl http://localhost:8091/estado`, `curl http://localhost:8092/estado` devem devolver
`peerId` diferentes.

> Se já tinhas peers a correr com as portas por omissão em conflito (dois em 8090/9000):
> pára-os todos (Ctrl+C em cada terminal), e volta a arrancá-los seguindo esta secção —
> o primeiro fica com as portas por omissão, os seguintes com as variáveis de ambiente a
> apontar para portas livres.
>
> **Importante:** cada comando desta secção fica a correr indefinidamente (bloqueia o
> terminal). Um comando por terminal, sempre — nunca colar dois comandos destes
> seguidos no mesmo terminal, ou o segundo fica só "escrito" sem chegar a executar.

---

## 5. Testar (RF1 e RF2)

### 5.1 Upload de um ficheiro (RF1)

```bash
echo "Sprint funcionando!" > teste.txt
curl -X POST -F "file=@teste.txt" http://localhost:8080/upload
```
Devolve o CID do ficheiro. Nos terminais dos peers deve aparecer o PREPARE recebido,
a confirmação enviada, e por fim o COMMIT (e, se o peer for responsável, uma linha de
"Pinning feito").

Verificar o estado confirmado do líder:
```bash
curl http://localhost:8080/vetor
```

Verificar o estado de um peer (inclui a lista de peers conhecidos, usada pelo
`PeerSelector` para pinning/distribuição de carga):
```bash
curl http://localhost:8090/estado
```

### 5.2 Pesquisa (RF2)

```bash
curl -X POST http://localhost:8080/prompt \
  -H "Content-Type: application/json" \
  -d '{"prompt":"conteudo de teste"}'
```
Devolve `{"id": "..."}`. Passado um instante:
```bash
curl http://localhost:8080/prompt/<id>
```

Para confirmar QUAL peer processou a query (só um, por desenho — ver
`DOCUMENTACAO_SPRINTS.md`, Sprint 7):
```bash
curl http://localhost:8090/pesquisas/<id>   # noutros peers deve dar 404
```

### 5.3 Testar a recuperação de falha do líder (RNF3/RNF4)

1. Com o líder e pelo menos 1-2 peers a correr, e já com algum estado confirmado
   (`/vetor` a mostrar `versao > 0`), matar o processo do Líder (Ctrl+C).
2. Ao fim de `heartbeat.timeout.ms` (10s por omissão), os peers devem imprimir o
   "ALERTA: possível falha do líder" e arrancar uma eleição.
3. O peer vencedor imprime "Vou arrancar o Líder", escreve o estado atual em
   `estado_lider.json` e lança um novo processo `lider.Lider` via `start-leader.sh`/`.bat`.
4. Confirmar que o novo líder arrancou já com o estado antigo:
   ```bash
   curl http://localhost:8080/vetor
   ```
   A versão deve corresponder ao que estava confirmado antes da falha (não deve voltar a
   zero — essa era precisamente a falha corrigida nesta versão, ver Sprint 6 na
   documentação).

---

## 6. Limitações conhecidas (para não serem apanhados de surpresa na defesa)

- **Split-brain durante partições de rede**: a eleição é "maior id vence" sem números de
  termo/época (ao estilo Raft). Numa partição em que o líder antigo continua vivo mas
  fica isolado dos peers, é possível haver dois líderes em simultâneo por breves
  instantes. Não implementado — ver `peer.PeerEleicao`, comentário sobre `arrancarNovoLider`.
- **Transparência da eleição para o cliente (RNF4)**: se o líder mudar de máquina, o
  cliente tem de descobrir manualmente o novo IP/porta — não há proxy/DNS automático.
- **Resolução de conflitos concorrentes (RF1/Sprint 3)**: o sistema deteta e ignora
  versões desatualizadas, mas não tem um processo de resolução para duas propostas
  concorrentes à mesma versão (cenário que o próprio enunciado do Sprint 3 admite ficar
  "a implementar no futuro").
- **Segurança (RNF6)**: mensagens PubSub não são assinadas nem cifradas; a identidade de
  peer (`meuId`) é um UUID local, sem verificação criptográfica.
- **Embeddings**: continuam a ser uma função de hashing determinística
  (`common.EmbeddingUtil`), não um modelo de linguagem real — suficiente para demonstrar
  o pipeline distribuído de ponta a ponta, mas não para relevância semântica real. Ver
  `DOCUMENTACAO_SPRINTS.md`, Sprint 2/7, para como apresentar isto na defesa.
- **Memória no líder**: os mapas por versão (`vetorPendentePorVersao`,
  `confirmacoesPorVersao`, etc., em `LiderEstado`) não são limpos após o commit — para
  uma demo/projeto académico não é problema, mas num sistema de longa duração
  precisariam de um "garbage collection" periódico das versões antigas.
- **Recuperação de estado no failover (RNF3)**: desde a última correção, o handoff da
  eleição (`peer.PeerEleicao#arrancarNovoLider`) recupera não só o vetor confirmado
  (permanente) mas também a versão pendente mais recente (temporária), republicando-a
  como um PREPARE novo (`lider.LiderConsenso#republicarPendente`) — ver
  `DOCUMENTACAO_SPRINTS.md`, Sprint 6. Só é recuperada a versão pendente mais recente, não
  uma cadeia de várias versões pendentes em simultâneo (vários uploads concorrentes, todos
  ainda por confirmar ao mesmo tempo) — cenário raro, não coberto.

---

## 7. Pontos a confirmar ao compilar (`mvn compile`)

Não foi possível compilar este código num ambiente com acesso ao Maven Central/JitPack
durante esta refatoração. O código foi revisto manualmente com cuidado, mas há 2-3
chamadas à biblioteca `java-ipfs-http-client` cuja assinatura exata pode variar
ligeiramente consoante a versão exata do `.jar` — se o `mvn compile` falhar, é
provavelmente aqui:

1. `ipfs.cat(Multihash.fromBase58(cid))` em `peer/PeerPesquisa.java` — lê o conteúdo de
   um ficheiro do IPFS. Se o método se chamar de outra forma nesta versão da biblioteca,
   procurar por um método equivalente a `cat`/`get` que devolva o ficheiro já
   descodificado (não o bloco raw — ver Sprint 7 na documentação para explicar porquê).
2. `ipfs.pin.add(Multihash.fromBase58(cid))` em `peer/PeerConsenso.java` — faz pinning.
3. `ipfs.pubsub.peers(topico)` em `lider/LiderConsenso.java` — devolve a lista de peers
   subscritos ao tópico (usada para calcular o quórum).

Todas as três chamadas estão isoladas em métodos pequenos e bem identificados, por isso
um eventual ajuste de assinatura é uma alteração de 1-2 linhas, não uma reescrita.
# SDT
