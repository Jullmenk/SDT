# Documentação por Sprint — o que foi feito, onde está no código, e como defender

Este documento mapeia cada sprint do enunciado ao código atual (já corrigido e
reestruturado — ver `README.md` para instruções de execução), com referências exatas de
ficheiro/linha, e inclui uma secção "Como defender" por sprint com as perguntas mais
prováveis do professor e como respondê-las com segurança.

Todas as referências de linha correspondem ao código deste pacote (pasta `src/main/java`),
não à versão original que entregaram antes.

---

## Estrutura do código (mapa geral)

| Pacote | Ficheiro | Responsabilidade |
|---|---|---|
| `common` | `Config.java` | lê `application.properties` (antes não era lido em lado nenhum) |
| `common` | `TipoMensagem.java` | constantes para o campo `"tipo"` das mensagens PubSub |
| `common` | `JsonUtil.java` | extração de mensagens PubSub (`extrairMensagem`) + instância Gson partilhada |
| `common` | `HashUtil.java` | SHA-256 do vetor de CIDs |
| `common` | `EmbeddingUtil.java` | geração de embeddings — **única** implementação, usada por líder e peer |
| `common` | `PeerSelector.java` | algoritmo distribuído de seleção de peers (pinning e distribuição de carga) |
| `common` | `EstadoPersistente.java` | leitura/escrita de `estado_lider.json` (usado pelo líder e pelo peer que faz failover) |
| `lider` | `Lider.java` | ponto de entrada do líder: rotas REST + subscrição PubSub (fino, delega tudo) |
| `lider` | `LiderEstado.java` | estado do líder (vetor confirmado, versões pendentes, quóruns) — thread-safe |
| `lider` | `LiderConsenso.java` | RF1: PREPARE, receção de confirmações, COMMIT, cálculo de quórum, pinning |
| `lider` | `LiderPesquisa.java` | RF2: gerar query, escolher peer responsável, guardar resultado |
| `peer` | `Peer.java` | ponto de entrada do peer: subscrição PubSub + router de mensagens (fino) |
| `peer` | `PeerEstado.java` | estado do peer — thread-safe |
| `peer` | `PeerConsenso.java` | RF1: receção de PREPARE/COMMIT, confirmação, indexação FAISS, pinning |
| `peer` | `PeerEleicao.java` | RNF3/RNF4: heartbeats, deteção de falha, eleição, arranque de novo líder |
| `peer` | `PeerPesquisa.java` | RF2: processa a query (se for o peer responsável) |
| `peer` | `FaissClient.java` | cliente HTTP para o `faiss_service.py`, com timeouts |

---

## Sprint 1 — Instalação IPFS / API de upload / Routing entre peers

**O que está feito:**

- Upload de ficheiro pelo cliente e armazenamento no IPFS: `lider/Lider.java`, rota
  `post("/upload", ...)` a partir da linha 94; o ficheiro é escrito para o IPFS em
  `ipfs.add(wrapper)` (linha 120).
- Routing/mensagens entre líder e peers: PubSub do IPFS, tópico único configurável
  (`pubsub.topic` em `application.properties`, lido em `Config.get(...)` — ver
  `lider/Lider.java:44` e `peer/Peer.java:39`).
- Qualquer peer conseguir obter qualquer ficheiro da rede: usado em `peer/PeerPesquisa.java:84`
  (`ipfs.cat(...)`, corrigido nesta versão — ver Sprint 7 abaixo).

**Como defender:**

- *"Porque escolheram PubSub do IPFS em vez de RMI/multicast/broker externo?"* — O
  enunciado do próprio Sprint 1 nota que "o libp2p já faz parte do IPFS, pelo que a rede
  utilizada para o sistema de ficheiros pode ser utilizada para o envio de mensagens".
  Evita montar infraestrutura extra (broker MQTT, sockets multicast) só para mensagens,
  quando o IPFS já dá isso de fábrica.
- *"E se quiséssemos várias mensagens paralelas, um único tópico não é um gargalo?"* —
  Sim, é uma limitação reconhecida (ver `README.md`, "Limitações conhecidas"): todos os
  peers recebem todo o tráfego (heartbeats, PREPARE, queries, etc.), mesmo o que não lhes
  interessa. Para um sistema maior, dividiríamos em vários tópicos (ex.: um por tipo de
  mensagem, ou um por "shard" de documentos).

---

## Sprint 2 — Atualização do vetor de documentos (líder)

**O que está feito:**

- Nova versão do vetor + embeddings + propagação: `lider/LiderConsenso.java`, método
  `publicarPrepare(...)` (linha 48). A criação da versão em si está em
  `LiderEstado.iniciarNovaVersao(...)` (linha 63) — devolve um `record VersaoPreparada`
  (linha 59) em vez de mutar campos soltos, para deixar claro que é uma operação atómica.
- Geração de embeddings: `common/EmbeddingUtil.java`, método `gerarEmbedding(byte[])`
  (linha 33).

**Correção feita nesta versão (importante saber explicar):**

Antes existiam duas implementações de embeddings diferentes (uma no líder para
documentos, outra no peer para prompts) que não eram comparáveis entre si — ver Sprint 7.
Agora há uma única função, `common.EmbeddingUtil.gerarEmbedding(byte[])`, chamada tanto
pelo líder (sobre os bytes do ficheiro, em `LiderConsenso.java:56`) como pelo peer (sobre
os bytes da prompt, em `PeerPesquisa.java:61`).

**Como defender:**

- *"Porque não usam um modelo de embeddings real (SentenceTransformer, como sugerido no
  enunciado)?"* — Resposta honesta: por limitação de tempo, optámos por uma função de
  hashing determinística que garante que documentos parecidos (mesmo conteúdo/tamanho)
  ficam próximos no espaço vetorial, o suficiente para demonstrar o pipeline distribuído
  de ponta a ponta (propagação, consenso, indexação, pesquisa). Sabemos que não captura
  semântica real — é um ponto de evolução documentado no `README.md`. Se pedirem para
  argumentar como evoluir: adicionar um endpoint `/embed` ao `faiss_service.py` com
  `sentence-transformers`, chamado tanto pelo líder como pelo peer (mantendo a garantia
  de que ambos usam exatamente o mesmo modelo/versão).
- *"Como garantem que o líder e o peer usam o mesmo algoritmo de embeddings?"* — É a
  mesma função Java, chamada dos dois lados (`common.EmbeddingUtil`), não há reimplementação.

---

## Sprint 3 — Atualização do vetor de documentos e confirmação (peer)

**O que está feito:**

- Peer recebe PREPARE, verifica versão, guarda pendente, confirma:
  `peer/PeerConsenso.java`, método `tratarAtualizacao(JsonObject)` (linha 40).
  - Verificação de conflito de versão: linhas 50-58 (compara com
    `estado.getVersaoConfirmada()`).
  - Guarda a versão pendente sem apagar outras ainda não confirmadas:
    `estado.registarVersaoPendente(...)` (linha 80), implementado em
    `peer/PeerEstado.java:40` — usa um `Map<Integer, ...>` por versão, por isso várias
    versões pendentes coexistem sem se substituírem.
  - Devolve a hash do vetor ao líder: `enviarConfirmacao(...)` (`PeerConsenso.java:144`).

**Correção feita nesta versão:**

A versão passou a ser **global ao vetor** (`versaoVetor`), não por CID. Antes, duas
atualizações concorrentes a ficheiros diferentes podiam fazer com que o commit mais
tardio *substituísse* o vetor confirmado em vez de o fundir, perdendo o CID da primeira
atualização. Agora, cada nova versão pendente é sempre construída em cima do último
vetor conhecido (confirmado + pendentes anteriores) — ver `LiderEstado.iniciarNovaVersao`
(`lider/LiderEstado.java:63`) e o comentário na classe (linhas 12-27) que explica o
raciocínio em detalhe.

**Como defender:**

- *"O que acontece se dois peers derem hashes diferentes para a mesma versão?"* — Hoje
  isso significaria que um peer calculou mal o hash local a partir do `novoVetor` que
  recebeu (o `novoVetor` em si vem sempre igual do líder, replicado por broadcast) — na
  prática seria sinal de uma mensagem corrompida/perdida, não de um conflito real de
  dados. O líder só avança para COMMIT quando atinge quórum de confirmações (não exige
  que *todas* tenham o mesmo hash) — é um ponto que pode ser reforçado no futuro
  (rejeitar confirmações cujo hash não bata certo com o hash calculado pelo próprio líder).
- *"Isto resolve conflitos verdadeiros (duas escritas concorrentes)?"* — Não
  completamente — ver a nota em `PeerConsenso.java:53-56`. Resolve o caso "esta
  atualização já está desatualizada" (rejeitando-a), mas não tem um processo de
  reconciliação para duas propostas para a mesma versão com conteúdo diferente. É um
  cenário que, neste desenho (líder único a gerar as versões), não deveria acontecer em
  condições normais — só apareceria com múltiplos líderes em simultâneo (split-brain),
  que é uma limitação já assumida (ver Sprint 6).

---

## Sprint 4 — "Sprint de Recuperação"

O enunciado fornecido não tem uma descrição própria para este sprint (aparece só o
título). O trabalho de recuperação de dados está distribuído pelos Sprints 5 e 6 (ver
abaixo) — commit em todos os peers e recuperação de estado no failover do líder.

---

## Sprint 5 — Atualização do vetor em todos os peers + deteção de falha do líder (RNF3 parte 1)

**O que está feito:**

- Commit aplicado no líder: `lider/LiderConsenso.java`, método `tratarConfirmacao(...)`
  (linha 78) — quando o quórum é atingido (`estado.registarConfirmacao(...)`, linha 85),
  chama `estado.confirmarVersao(versao)` (linha 90) e publica o COMMIT (linhas 98-106).
- Commit aplicado no peer: `peer/PeerConsenso.java`, método `tratarCommit(JsonObject)`
  (linha 91) — aplica via `estado.aplicarCommit(versao)` (linha 98), depois atualiza o
  índice FAISS (`indexarNoFaiss`, linha 120) e faz pinning se for responsável
  (`fazerPinningSeResponsavel`, linha 131).
- Heartbeats periódicos do líder: `lider/Lider.java`, método `iniciarHeartbeats()`
  (linha 191).
- Deteção de falha no peer: `peer/PeerEleicao.java`, método `iniciarDetetor()` (linha 55)
  — thread que compara `System.currentTimeMillis() - lastHeartbeatTime` contra
  `heartbeat.timeout.ms`.

**Correção crítica feita nesta versão — cálculo do quórum:**

O código original tinha este bug (`Lider.java` original, linhas 149-156):
```java
List<Object> peers = Collections.singletonList(ipfs.pubsub.peers(TOPICO_PUBSUB));
numPeers = Math.max(1, peers.size());
```
`Collections.singletonList(...)` cria uma lista com **um único elemento** — a lista de
peers inteira lá dentro — por isso `peers.size()` dava sempre `1`, e o quórum
(`numPeers / 2 + 1`) era sempre `1`. Na prática, o líder fazia COMMIT assim que **um só**
peer confirmava, independentemente de quantos peers existissem — a "maioria" pedida pelo
RF1 nunca chegava a ser testada.

Corrigido em `lider/LiderConsenso.java`, método `calcularNumPeers()` (linha 110): usa
diretamente `ipfs.pubsub.peers(topico).size()`, sem o embrulhar outra vez.

**Como defender:**

- *"Como é que o líder sabe quantos peers existem, se pode haver desacoplamento
  espacial (peers momentaneamente incontactáveis)?"* — Esta é literalmente a pergunta
  colocada como "Desafio" no enunciado do Sprint 5. Respondemos com duas fontes
  combinadas: (1) `ipfs.pubsub.peers(topico)`, que dá o número de peers *atualmente*
  subscritos ao tópico segundo o próprio daemon IPFS local — é a fonte principal; (2) se
  essa chamada falhar, um *fallback* para o número de peers de que já tivemos notícia via
  `peer_hello` (`estado.getPeersConhecidos().size()`, ver `LiderConsenso.java:116`). Nenhuma
  das duas é perfeita (a vista de peers é sempre uma aproximação num sistema distribuído
  sem coordenação central de membros), mas dá uma estimativa razoável sem depender de um
  registo centralizado à parte.
- *"Porque é que o quórum é `numPeers/2 + 1` e não outra fórmula?"* — É a definição
  clássica de maioria simples (mais de metade dos peers conhecidos), que é o que o RF1
  pede literalmente ("depois de receber a maioria das respostas dos peers").

---

## Sprint 6 — Recuperação da falha do líder (RNF3 parte 2 + RNF4)

**O que está feito:**

- Eleição (bully simplificado, maior id vence): `peer/PeerEleicao.java`.
  - Início de eleição: `iniciarEleicao()` (linha 97).
  - Registo de candidaturas de outros peers: `tratarElection(JsonObject)` (linha 87).
  - Decisão do vencedor: `finalizarEleicaoSeForGanhador()` (linha 116).
  - Arranque do novo processo líder: `arrancarNovoLider()` (linha 141).

**Correção crítica feita nesta versão — perda de estado no failover:**

Antes, quando um peer ganhava a eleição, `arrancarNovoLider()` limitava-se a lançar o
script `start-leader.sh`/`.bat`, que arranca um **processo novo** a correr `main()` do
zero. Esse processo só carregava estado de `estado_lider.json` **local a essa máquina**
— e, a menos que essa máquina já tivesse sido líder antes (e tivesse esse ficheiro
atualizado), arrancava com o vetor **vazio**, perdendo todo o histórico confirmado que o
peer já tinha em memória.

Corrigido em `peer/PeerEleicao.java` (`arrancarNovoLider()`): antes de lançar o processo,
o peer escreve o **seu próprio** estado em memória (`estado.getVetorConfirmado()`,
`estado.getVersaoConfirmada()`) para `estado_lider.json`, usando
`common.EstadoPersistente.escrever(...)` — a mesma classe que o líder usa para persistir
a cada commit (`lider/LiderEstado.java`, `persistirEstado()`). O novo processo `Lider` lê
esse ficheiro no arranque (`LiderEstado`, construtor) e começa já com os dados.

**Correção adicional — recuperação também das estruturas TEMPORÁRIAS, não só das
permanentes:**

A Descrição do requisito (RNF3 parte 2) pede explicitamente: *"A recuperação deve
envolver todas as estruturas de dados **permanentes e temporárias** armazenadas pelo
líder."* A correção acima só recuperava o vetor **confirmado** (permanente) — se o líder
morresse a **meio** de uma ronda de consenso (já tinha feito PREPARE, ainda sem quórum
para COMMIT), essa versão pendente perdia-se por completo, mesmo que um ou mais peers já
a tivessem recebido.

Corrigido:

- `common/EstadoPersistente.java` — o `Snapshot` passou a incluir um campo opcional
  `VersaoPendente` (versão, cid, vetor, peersPin, embeddings), gravado no mesmo
  `estado_lider.json`.
- `peer/PeerEstado.java` — novos getters (`getVersaoPendenteMaisRecente()`,
  `getVetorPendente(versao)`, `getCidPendente(versao)`) para consultar, sem apagar, a
  versão pendente mais recente que o peer já tinha recebido via PREPARE.
- `peer/PeerEleicao.java` — `construirHandoffPendente()` inclui essa versão pendente no
  handoff, se existir.
- `lider/LiderEstado.java` — o construtor recupera essa versão pendente para os seus
  próprios mapas internos (`vetorPendentePorVersao`, `cidPorVersao`, etc.) em vez de
  arrancar sempre "limpo".
- `lider/LiderConsenso.java` — novo método `republicarPendente(versao)`: trata a versão
  recuperada como uma ronda de consenso NOVA — recalcula o quórum com os peers atuais e
  volta a publicar o mesmo PREPARE (mesmo conteúdo, para o hash bater certo). Os peers que
  já a tinham como pendente simplesmente voltam a confirmar.
- `lider/Lider.java` — no arranque, se `LiderEstado.getVersaoPendenteRecuperada()` não for
  nula, chama `republicarPendente(...)` numa thread à parte, alguns segundos depois de a
  subscrição PubSub estar ativa.

**Limitação assumida nesta correção**: só é recuperada a versão pendente mais **recente**
que o peer vencedor conhece, não uma cadeia de várias versões pendentes em simultâneo
(vários uploads concorrentes, nenhum ainda confirmado, ao mesmo tempo) — cenário raro e
não coberto.

**Como defender:**

- *"Isto é Raft?"* — Não, é mais simples: eleição por maior id (bully), sem números de
  termo/época, sem log replicado. Testámos e demonstrámos que resolve o caso principal
  (líder cai de forma limpa — fail-stop — e um novo assume com o estado correto). O que
  não cobre é split-brain numa partição de rede (dizemos isto abertamente — ver
  `PeerEleicao.java`, comentário javadoc antes de `arrancarNovoLider()`, linhas 132-140).
  Explorar Raft "a sério" (com termos e log replicado) fica identificado como evolução
  futura.
- *"Como é que sabem que o novo líder recuperou os dados certos?"* — É testável e está
  documentado no `README.md`, secção 5.3: matar o líder, esperar a eleição, e confirmar
  via `curl http://localhost:8080/vetor` que a versão não voltou a zero.
- *"E se dois peers ganharem a eleição ao mesmo tempo (empate)?"* — Não há empate
  possível com IDs UUID (probabilidade de colisão desprezável), e o critério de desempate
  (`compareTo`, maior string vence) é determinístico — todos os peers que virem as
  mesmas mensagens de eleição chegam à mesma conclusão sobre quem venceu.
- *"E se o líder morrer a meio de um upload, antes do quórum?"* — Essa versão pendente
  (temporária) é recuperada do handoff do peer vencedor e republicada pelo novo líder como
  um PREPARE novo, com o quórum recalculado — os peers que já a tinham pendente
  simplesmente voltam a confirmar. Nada se perde silenciosamente. Sabemos que isto só
  cobre a versão pendente mais recente, não uma cadeia de várias em simultâneo — é uma
  simplificação assumida e documentada.

---

## Sprint 7 — RF2: Pesquisa de Informação

**O que está feito:**

- Líder recebe a prompt, gera id/token, escolhe o peer responsável, devolve o id:
  `lider/LiderPesquisa.java`, método `publicarQuery(String)` (linha 42).
- Peer que aceita processa a query com FAISS e IPFS, guarda localmente e avisa o líder:
  `peer/PeerPesquisa.java`, método `tratarQuery(JsonObject)` (linha 46).
- Líder guarda o resultado devolvido por um peer:
  `LiderPesquisa.registarResultado(JsonObject)` (linha 69).
- 2º pedido do cliente (obter resposta pelo id): `lider/Lider.java`,
  rota `get("/prompt/:id", ...)` (linha 178) → `LiderPesquisa.obterResultado(String)`
  (`LiderPesquisa.java:77`).

**Duas correções críticas feitas nesta versão:**

1. **Distribuição de carga** (pedida explicitamente no enunciado do Sprint 7: "utilizar
   uma abordagem distribuída que permita a distribuição da carga pelos peers"). Antes,
   TODOS os peers processavam TODAS as queries (trabalho duplicado, e o líder ficava só
   com o resultado que chegasse por último, sem critério). Agora, o líder escolhe — com o
   mesmo algoritmo de hashing determinístico usado para o pinning do RF1
   (`common/PeerSelector.java`, método `escolherPeerUnico`, linha 47) — qual peer deve
   responder, e inclui esse id no campo `"peerResponsavel"` da mensagem `query`
   (`LiderPesquisa.java:46`). Só esse peer processa (`PeerPesquisa.java:50-54`); os
   restantes ignoram a mensagem.
2. **Leitura de conteúdo do IPFS**: o código original lia os documentos com
   `ipfs.block.get(...)`, que devolve o **bloco raw** (com o *framing* protobuf do
   UnixFS) em vez do conteúdo do ficheiro — foi exatamente esse lixo binário que apareceu
   no vosso teste do sprint (`readme.md` antigo: `"conteudo":"\nw\bq\"Sprint 1 e 2
   funcionando!\"..."`). Corrigido para `ipfs.cat(Multihash.fromBase58(cid))`
   (`peer/PeerPesquisa.java:84`), que devolve o conteúdo já descodificado.

**Como defender:**

- *"Como é que o vosso 'token' distribui a carga?"* — Não é literalmente um token no
  sentido de autenticação; é o líder a calcular, de forma determinística e sem
  coordenação extra, qual peer deve responder a cada `queryId`
  (`common.PeerSelector.escolherPeerUnico`, hashing sobre a lista ordenada de peers
  conhecidos). Diferentes queries (ids diferentes) tendem a espalhar-se por peers
  diferentes, sem qualquer peer ter de "pedir licença" a ninguém.
- *"O líder sabe sempre quais são os peers vivos?"* — Não perfeitamente — é uma vista
  eventualmente consistente, construída a partir de mensagens `peer_hello` que cada peer
  emite periodicamente (`peer/Peer.java`, método `iniciarAnuncioPeriodico()`, linha 124)
  e de quem já confirmou/respondeu antes. Se o líder ainda não conhecer nenhum peer
  (arranque a frio), a query vai sem `peerResponsavel` e qualquer peer a processa em
  "melhor esforço" (`PeerPesquisa.java:56-57` e `LiderPesquisa.java:64`).
- *"Os resultados da pesquisa fazem sentido semântico?"* — Ver a resposta preparada para
  o Sprint 2 sobre embeddings — a mesma explicação aplica-se aqui (unificação da função
  de embeddings entre líder e peer, com a ressalva de que não é um modelo de linguagem
  real).
- *"Como testam que só um peer processou a query?"* — `README.md`, secção 5.2: chamar
  `GET /pesquisas/:id` em cada peer — só o responsável devolve a resposta, os outros dão
  404 (rota de debug em `peer/Peer.java:109`, apoiada em
  `PeerPesquisa.getRespostaLocal(String)`, linha 106).

---

## Requisitos Não Funcionais — estado atual e como defender

| RNF | Estado | Onde no código | Nota para a defesa |
|---|---|---|---|
| **RNF1 Escalabilidade** | Parcial | tópico único (`Config.get("pubsub.topic",...)`) | Reconhecer a limitação (todo o tráfego passa por todos os peers) e explicar a evolução natural (vários tópicos). |
| **RNF2 Thread-safety** | Corrigido nesta versão | `LiderEstado`/`PeerEstado` usam `ConcurrentHashMap` + métodos `synchronized` para operações compostas (ex.: `LiderEstado.iniciarNovaVersao`, linha 63; `PeerEstado.aplicarCommit`, linha 53) | Saber explicar a diferença entre "estrutura concorrente" (evita corromper o mapa) e "operação atómica" (`synchronized`, evita duas threads decidirem coisas incompatíveis ao mesmo tempo, ex.: dois commits a aplicar a mesma versão). Também saber explicar o isolamento do lock de eleição em `PeerEleicao` (não bloqueia com pesquisas lentas — ver `PeerEleicao.java`, comentário javadoc linhas 15-22). |
| **RNF3 Tolerância a falhas** | Parcial (fail-stop coberto; split-brain não) | `PeerEleicao.java` | Ver Sprint 6. |
| **RNF4 Eleição do líder** | Parcial | `PeerEleicao.java` | Eleição existe e funciona para o caso principal; transparência total para o cliente não está feita (ver `README.md`, limitações). |
| **RNF5 Dinamicidade de peers** | Melhorado nesta versão | `peer_hello` (`Peer.java:124`, `TipoMensagem.PEER_HELLO`) | Novo: antes não havia nenhum mecanismo de descoberta de peers; agora há um anúncio periódico que alimenta o `PeerSelector`. Saída "suja" de um peer (sem avisar) continua só a ser detetada indiretamente (deixa de responder/confirmar). |
| **RNF6 Segurança** | Não implementado | — | Ser honesto: não há assinatura/cifra nas mensagens PubSub. Se perguntarem "como fariam": assinar cada mensagem com a chave privada do peer (ex.: Ed25519), incluir o `peerId` derivado da chave pública (não um UUID aleatório), e os recetores verificam a assinatura antes de processar. |

---

## Perguntas gerais de defesa (transversais)

- **"O que é que corrigiram em relação à versão que entregaram antes?"** — Resposta
  curta, por ordem de impacto: (1) o cálculo do quórum estava sempre a dar 1, corrigido;
  (2) faltava a regra de pinning do RF1, implementada com um algoritmo de hashing
  determinístico; (3) o RF2 não distribuía carga entre peers, corrigido com o mesmo
  algoritmo; (4) a leitura de ficheiros do IPFS na pesquisa devolvia dados corrompidos,
  corrigido; (5) o estado partilhado não era thread-safe, corrigido; (6) o failover do
  líder perdia dados, corrigido.
- **"Porque dividiram o código em tantas classes?"** — Cada classe tem uma única
  responsabilidade (estado / protocolo de consenso / pesquisa / eleição), o que torna
  mais fácil de testar isoladamente e de explicar cada requisito apontando para um
  ficheiro específico — é exactamente o que este documento faz.
- **"Se eu pedir para desligar um peer no meio de um upload, o que acontece?"** — Se o
  peer que caiu não fazia parte do quórum necessário, o commit avança normalmente assim
  que os restantes confirmarem. Se fizer o número de peers cair abaixo do necessário
  para o quórum ser atingível, a atualização fica pendente indefinidamente — não há hoje
  um mecanismo de timeout/reconfiguração de quórum (ponto de evolução a assumir se
  perguntado).
