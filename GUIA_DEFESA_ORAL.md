# Guia de Defesa Oral — Perguntas e Respostas por Sprint

Este guia foi feito para a defesa oral, não para ler código. As respostas estão escritas
em linguagem simples, a explicar o *porquê* e o *como funciona*, para conseguires
responder por palavras tuas sem decorar. Se o professor pedir para mostrar no código,
usa o `DOCUMENTACAO_SPRINTS.md` (tem as linhas exatas); este ficheiro é só para a
conversa.

**Regra de ouro para a defesa:** quando não souberes uma resposta perfeita, di o que o
sistema faz hoje e admite a limitação — é muito mais credível do que inventar. Este guia
já inclui, para os pontos mais fracos, a resposta honesta "isto não está feito, e faria-se
assim".

---

## 0. As 5 ideias que tens mesmo de saber explicar de cor

Se só tiveres tempo para preparar uma coisa, prepara isto — é a base de tudo o resto:

1. **CID** = uma "impressão digital" (hash) do conteúdo de um ficheiro. Se o conteúdo
   for igual, o CID é igual. É assim que o IPFS identifica ficheiros sem precisar de
   nomes nem de um servidor central.
2. **PubSub do IPFS** = um "grupo de WhatsApp" entre os nós: quem está inscrito no
   tópico recebe tudo o que for publicado nele. Usamos um único tópico
   (`atualizacoes`) para toda a comunicação líder↔peers.
3. **Quórum / maioria** = o líder só confirma definitivamente ("faz commit") uma
   atualização depois de mais de metade dos peers dizerem "recebi e concordo". Isto
   protege contra um peer isolado ou com dados corrompidos estragar o sistema todo.
4. **Líder eleito, não fixo** = o papel de líder não está preso a uma máquina. Se o
   processo líder cair, os peers dão-se conta (deixam de receber heartbeats) e elegem
   um substituto.
5. **Cada peer é "auto-suficiente" para pesquisa**: guarda o seu próprio índice FAISS em
   memória (via o serviço Python local) e só precisa de ir ao IPFS buscar o conteúdo
   dos documentos quando alguém lhe pede uma pesquisa.

---

## 1. Perguntas gerais de arquitetura (prováveis logo no início)

**P: Descreve-me o sistema em 2 minutos.**
R: É um sistema de armazenamento de documentos distribuído por vários computadores
(peers), coordenado por um líder. Um cliente envia um ficheiro ao líder; o líder guarda-o
no IPFS (que dá um identificador único, o CID), gera um vetor de características
(embedding) do conteúdo, e propaga essa informação a todos os peers através do PubSub do
IPFS. Os peers confirmam que receberam, e quando o líder tem confirmação da maioria,
manda um "commit" para todos aplicarem definitivamente essa atualização. Cada peer
guarda os embeddings num índice FAISS local, para depois poder responder a pesquisas:
o cliente manda uma pergunta (prompt) ao líder, o líder escolhe um peer para tratar
disso, esse peer usa o FAISS para encontrar os documentos mais relevantes, vai buscá-los
ao IPFS, e devolve a resposta.

**P: Porque é que precisam de um líder? Não podia ser tudo distribuído sem coordenação?**
R: Podia, mas seria muito mais complexo garantir consistência (todos os peers com a
mesma versão do vetor de documentos) sem alguém a coordenar a ordem das operações. Ter
um líder simplifica: é ele que decide a ordem das atualizações e recolhe as confirmações.
A parte "distribuída de verdade" está em o líder não ser um ponto fixo — se cair, é
substituído automaticamente.

**P: Isso não é um ponto único de falha (single point of failure)?**
R: É, durante o curto período entre o líder cair e um novo ser eleito (alguns segundos,
configurável). Fora isso, não — porque qualquer peer pode tornar-se líder, e o sistema
recupera sozinho.

**P: Porque usaram IPFS PubSub em vez de, por exemplo, sockets multicast ou RMI?**
R: Porque já estávamos a usar o IPFS para guardar os ficheiros, e o IPFS já vem com uma
rede P2P (baseada em libp2p) com um mecanismo de publish/subscribe embutido. Não fazia
sentido montar uma segunda rede de comunicação (multicast ou RMI) só para mensagens,
quando já tínhamos uma a funcionar.

**P: O que é que acontece fisicamente quando fazem `curl .../upload`?**
R: O pedido HTTP chega à API REST do líder (feita com a biblioteca Spark). O líder lê o
ficheiro, manda-o para o IPFS local dele (`ipfs.add`), recebe o CID de volta, calcula um
embedding a partir dos bytes do ficheiro, monta uma mensagem com [nova versão do vetor,
CID, embeddings] e publica essa mensagem no tópico PubSub. Devolve o CID ao cliente
imediatamente — não espera pelas confirmações dos peers antes de responder ao HTTP (o
`/upload` só garante que o ficheiro está guardado no IPFS; a propagação/consenso
acontece em paralelo).

---

## 2. Sprint 1 — IPFS, API de upload, routing

**P: O que é um CID, tecnicamente?**
R: É um hash (uma função criptográfica de resumo) do conteúdo do ficheiro, codificado em
Base58. Dois ficheiros com o mesmo conteúdo byte-a-byte têm sempre o mesmo CID, mesmo
que tenham nomes diferentes ou sejam adicionados por peers diferentes.

**P: Se eu enviar o mesmo ficheiro duas vezes, o que acontece?**
R: O IPFS devolve o mesmo CID (o conteúdo é igual), mas do lado do nosso sistema conta
como uma nova entrada no vetor — sobem dois CIDs iguais na lista. Não deduplicamos hoje
a esse nível.

**P: O que acontece a um ficheiro que ninguém "pina" (pin)?**
R: Fica no IPFS como cache temporária e pode ser removido pelo garbage collector do IPFS
passado algum tempo. Por isso é que a regra do enunciado exige que pelo menos 2 peers
façam pinning explícito de cada ficheiro — para garantir que fica lá de forma persistente.

**P: Como é que garantem que qualquer peer consegue ir buscar qualquer ficheiro à rede,
mesmo que não o tenha guardado localmente?**
R: É uma propriedade do próprio IPFS: quando um nó pede um bloco de dados por CID e não o
tem localmente, o IPFS vai perguntar à rede (aos outros nós ligados) quem tem esse bloco,
descarrega-o e guarda uma cópia em cache local. Nós não tivemos de implementar isso — é
a funcionalidade base do protocolo.

---

## 3. Sprint 2 — Atualização do vetor de documentos (líder)

**P: O que é o "vetor de CIDs de documentos"?**
R: É a lista, mantida pelo líder e replicada nos peers, de todos os CIDs dos documentos
que já foram confirmados no sistema. É o "índice mestre" de que documentos existem.

**P: Porque é que criam uma "nova versão" em vez de simplesmente adicionar o CID à lista
existente?**
R: Porque a atualização ainda não está confirmada pelos peers — se fizéssemos a
alteração diretamente na lista "oficial", e depois a confirmação falhasse (por exemplo,
não se atingir quórum), teríamos alterado o estado sem consenso. Por isso mantemos a
lista confirmada intocada, e construímos uma versão "candidata" à parte, que só substitui
a oficial depois do commit.

**P: O que é um embedding, e para que serve aqui?**
R: É um vetor de números (no nosso caso, 128 números) que representa as características
do conteúdo de um documento, de forma que documentos parecidos tenham vetores
"próximos" matematicamente. Serve para a pesquisa por similaridade no FAISS: em vez de
procurar palavras exatas, comparamos vetores.

**P: O vosso embedding é gerado por um modelo de inteligência artificial?**
R: Não — usámos uma função determinística baseada em hashing dos bytes do ficheiro (não
um modelo de linguagem como o SentenceTransformer sugerido no enunciado). Sabemos
perfeitamente que isto não captura significado semântico real; foi uma opção consciente
para termos o pipeline todo (líder gera → propaga → peer indexa → peer pesquisa) a
funcionar de ponta a ponta, com uma função simples, previsível e fácil de explicar. Se
quiséssemos evoluir isto, o próximo passo seria pôr um modelo real (ex.:
SentenceTransformer) a correr no serviço Python que já temos (`faiss_service.py`), e
tanto o líder como o peer chamariam esse serviço em vez de calcular o embedding
localmente.

**P: Porque é que geram o embedding no líder e não em cada peer?**
R: Porque o líder já tem o ficheiro em mãos no momento do upload (acabou de o receber do
cliente) — faz sentido calcular ali e mandar o resultado já pronto, em vez de mandar o
ficheiro inteiro outra vez para cada peer calcular por conta própria (poupa trabalho
repetido e tráfego).

---

## 4. Sprint 3 — Confirmação nos peers

**P: O que faz um peer quando recebe a mensagem do líder (PREPARE)?**
R: Primeiro verifica se a versão que está a receber é mais recente do que a que já tem
confirmada — se não for (é uma mensagem antiga ou duplicada), ignora. Se for válida,
guarda essa versão do vetor e os embeddings numa zona "temporária" (pendente), sem
mexer no vetor oficial ainda, calcula o hash dessa lista de CIDs, e manda esse hash de
volta ao líder como confirmação.

**P: Para que serve mandar o hash em vez de, por exemplo, mandar só "ok"?**
R: Para o líder poder verificar que o peer construiu exatamente a mesma lista de CIDs
que ele — não só que recebeu a mensagem, mas que a interpretou corretamente. Se os hashes
não batessem certo, seria sinal de inconsistência.

**P: O que é que fazem quando há um conflito de versões?**
R: Hoje, detetamos o caso em que a versão recebida já está ultrapassada (é igual ou
anterior à que já temos confirmada) e simplesmente ignoramos essa mensagem. Um verdadeiro
processo de *resolução* de conflitos — por exemplo, duas atualizações concorrentes para a
mesma versão, com conteúdos diferentes — não está implementado; o próprio enunciado do
sprint já assumia isso como trabalho futuro. Na prática, como só há um líder ativo de
cada vez a gerar versões, esse cenário só aconteceria se, por alguma falha, houvesse dois
líderes em simultâneo (temos essa limitação identificada, ver Sprint 6).

**P: Porque é que o peer guarda a atualização como "pendente" em vez de aplicar logo?**
R: Porque ainda não sabemos se a maioria dos peers vai confirmar. Se aplicássemos logo e
depois a atualização fosse rejeitada (por falta de quórum, por exemplo), teríamos
peers com dados diferentes do resto do sistema. Só se aplica de forma definitiva quando
chega o commit.

---

## 5. Sprint 5 — Commit em todos os peers + deteção de falha do líder

**P: Como decide o líder que já pode fazer commit?**
R: Conta quantos peers já confirmaram aquela versão específica e compara com o quórum
necessário (mais de metade do número de peers atualmente subscritos ao tópico). Assim
que atinge esse número, marca a versão como confirmada localmente e publica uma
mensagem de commit para todos.

**P: Como sabe o líder quantos peers existem, se os peers podem entrar e sair a
qualquer momento?**
R: Pergunta ao próprio IPFS quantos nós estão neste momento inscritos no tópico PubSub
(`ipfs.pubsub.peers`). É uma contagem em tempo real, não um número fixo configurado à
mão — por isso o quórum se adapta automaticamente se entrarem ou saírem peers.

**P: E se um peer confirmar duas vezes a mesma versão, ou confirmar depois do commit já
ter acontecido?**
R: Guardamos as confirmações num conjunto (não uma lista) identificado por peer, por isso
confirmar duas vezes não conta a dobrar. E se a confirmação chegar tarde, depois do
commit já ter sido feito para uma versão mais recente, o líder simplesmente ignora —
nunca deixamos o estado "andar para trás".

**P: O que são os heartbeats e para que servem?**
R: São mensagens curtas que o líder envia periodicamente (de 5 em 5 segundos, por
omissão) só para dizer "ainda estou vivo". Os peers vão medindo há quanto tempo não
recebem um heartbeat; se passar demasiado tempo (10 segundos, por omissão) sem nenhum,
assumem que o líder falhou e arrancam uma eleição.

**P: Porque 5 segundos e não, por exemplo, 1 segundo ou 1 minuto?**
R: É um compromisso: intervalos muito curtos geram mais tráfego de rede sem necessidade;
intervalos muito longos atrasam a deteção de falhas (o sistema fica "às escuras" durante
mais tempo). Cinco segundos, com um timeout de dez, dá margem para uma mensagem se
perder ocasionalmente sem disparar uma eleição por falso alarme.

---

## 6. Sprint 6 — Recuperação da falha do líder

**P: Como funciona a eleição de um novo líder?**
R: Quando um peer deteta que o líder desapareceu, publica uma mensagem "election" com o
seu próprio identificador. Todos os peers que veem mensagens de eleição vão guardando o
maior identificador que já viram. Passado um pequeno intervalo de tempo (a "janela de
eleição"), cada peer verifica: "o maior identificador que vi fui eu próprio?" Se for,
esse peer assume que ganhou e arranca um novo processo líder. Chama-se a isto um
algoritmo do tipo "bully" (o "valentão" ganha, neste caso o maior id).

**P: E se dois peers acharem que ganharam ao mesmo tempo?**
R: Não acontece, porque o critério é determinístico e todos os peers, ao verem as mesmas
mensagens, chegam à mesma conclusão sobre qual foi o maior identificador. Só há um
"vencedor" possível.

**P: O que acontece à máquina/processo que ganhou a eleição?**
R: O peer que ganha lança um processo novo, separado, que corre a classe do Líder (usando
um script auxiliar). O processo Peer continua a correr ao mesmo tempo — não se
"transforma" em líder, apenas arranca um líder novo ao lado.

**P: Este é um dos pontos que corrigimos: antes, esse novo processo líder arrancava com
o histórico de documentos completamente vazio. Porquê, e como resolveram?**
R: Porque o novo processo líder é uma JVM nova, sem memória de nada — só sabia carregar
estado de um ficheiro local (`estado_lider.json`), e esse ficheiro só existia
naquela máquina se ela já tivesse sido líder antes. O peer que estava a arrancar o novo
líder, por outro lado, já tinha em memória o vetor de documentos confirmado (recebido ao
longo do tempo via commits). A correção foi simples: antes de lançar o processo líder
novo, o peer escreve o seu próprio estado (o que já sabia) nesse mesmo ficheiro, para o
processo novo o carregar logo ao arrancar.

**P: Isto é Raft?**
R: Não. Implementámos só a parte de "eleger alguém com um critério determinístico e
recuperar estado", que é uma versão simplificada. O Raft "a sério" tem números de termo
(para saber qual eleição é mais recente), um log replicado com entradas numeradas, e
mecanismos explícitos para evitar dois líderes em simultâneo. Nós não temos números de
termo — é uma limitação que assumimos abertamente.

**P: Então pode haver dois líderes ao mesmo tempo?**
R: Em teoria sim, num cenário de partição de rede: se o líder antigo continuar vivo mas
ficar isolado (por exemplo, um problema de rede que o impede de mandar heartbeats a
alguns peers, mas continua a funcionar), esses peers podem eleger um líder novo enquanto
o antigo continua a pensar que está no comando. É um cenário conhecido como
"split-brain". Não o resolvemos — resolver isso a sério precisaria de números de termo e
de um mecanismo para o líder antigo "se aperceber" que já não é válido (fencing).

---

## 7. Sprint 7 — RF2, Pesquisa de Informação

**P: Explica o fluxo completo de uma pesquisa.**
R: O cliente manda uma prompt (uma pergunta em texto) ao líder via HTTP. O líder gera um
identificador único para esse pedido, escolhe qual peer deve tratar da pesquisa, publica
uma mensagem "query" no PubSub com esse identificador e o nome do peer escolhido, e
devolve logo o identificador ao cliente (a resposta ainda não está pronta). O peer
escolhido, ao ver a mensagem, calcula o embedding da prompt, manda-o ao seu FAISS local
para encontrar os documentos mais parecidos, vai buscar o conteúdo desses documentos ao
IPFS, monta a resposta, guarda-a localmente e avisa o líder (publica "query_result"). O
cliente, passado algum tempo, pergunta ao líder pelo resultado usando o identificador; o
líder devolve o que tiver recebido do peer (ou diz "ainda pendente", se ainda não
chegou).

**P: Porque é que a resposta não vem logo no primeiro pedido?**
R: Porque processar a pesquisa (calcular embedding, consultar FAISS, ir buscar
documentos ao IPFS) pode demorar, e não queremos que o cliente fique com o pedido HTTP
pendurado à espera. Por isso o desenho é assíncrono: o líder responde imediatamente com
um identificador, e o cliente volta a perguntar mais tarde pelo resultado — é o mesmo
padrão que o próprio enunciado descreve (1º pedido dá o id, 2º pedido dá a resposta).

**P: Como escolhe o líder qual peer deve processar cada pesquisa?**
R: Usamos um algoritmo de hashing determinístico: pegamos no identificador da pesquisa,
calculamos um número a partir dele, e usamos esse número para escolher um peer de entre
a lista (ordenada) de peers que o líder conhece. É determinístico — o mesmo
identificador escolhe sempre o mesmo peer — mas como cada pesquisa tem um identificador
diferente (gerado aleatoriamente), o trabalho tende a espalhar-se por todos os peers ao
longo do tempo, sem qualquer coordenação extra.

**P: Isto é o que o enunciado queria dizer com "distribuição de carga pelos peers"?**
R: Sim — é exatamente essa ideia: evitar que todas as pesquisas caiam sempre no mesmo
peer (ou, pior, que todos os peers façam o mesmo trabalho em duplicado). É a mesma lógica
que usamos para decidir quem faz pinning de cada ficheiro no RF1 — reaproveitámos o
mesmo mecanismo para os dois casos.

**P: Como é que o líder sabe que peers existem?**
R: Cada peer, periodicamente, publica uma mensagem de "presença" (peer_hello) com o seu
identificador. O líder e os outros peers vão registando quem já viram. Não é uma lista
perfeita e sempre atualizada ao segundo (é "eventualmente consistente"), mas é suficiente
para a escolha funcionar na prática.

**P: E se o líder ainda não conhecer nenhum peer quando chega um pedido de pesquisa?**
R: Manda a query sem indicar um peer responsável, e qualquer peer que a receba processa-a
em "melhor esforço" — para não bloquear pesquisas logo no arranque do sistema, antes de
haver tempo para os peers se anunciarem.

**P: Testaram que só um peer processa cada pesquisa?**
R: Sim — cada peer guarda localmente as pesquisas que processou e temos uma rota de
debug para perguntar a um peer específico "processaste esta pesquisa?" — só o peer
escolhido responde com o resultado, os outros dizem que não a processaram.

---

## 8. Requisitos Não Funcionais — perguntas transversais

**P (RNF2, Thread-Safety): O que significa o sistema ser "thread-safe" aqui, em concreto?**
R: O líder e cada peer recebem pedidos de várias origens ao mesmo tempo — pedidos HTTP
do cliente, mensagens PubSub a chegar em paralelo, threads de heartbeat, etc. Se duas
threads tentassem ler e escrever a mesma estrutura de dados (por exemplo, o mapa das
confirmações por versão) ao mesmo tempo sem cuidado, podíamos corromper esse mapa ou
perder atualizações. Usamos estruturas próprias para concorrência (que suportam leitura e
escrita simultânea em segurança) e, nos pontos em que é preciso "ler, decidir, e escrever"
como uma operação única (por exemplo, aplicar um commit), usamos blocos sincronizados
para garantir que só uma thread de cada vez faz essa operação composta.

**P: Deram-se conta de algum problema de concorrência durante o desenvolvimento?**
R: Sim — reparámos que, se o processamento de uma pesquisa (que pode demorar, por
envolver chamadas de rede ao FAISS e ao IPFS) partilhasse o mesmo bloqueio que a lógica
de deteção de falha do líder, uma pesquisa lenta podia atrasar a deteção de falhas nesse
peer. Corrigimos isso separando os dois: o processamento de pesquisas corre à parte
(num conjunto de threads dedicado), e a lógica de eleição/heartbeat tem o seu próprio
mecanismo de sincronização, independente.

**P (RNF3, Tolerância a falhas): Que tipo de falhas o sistema tolera?**
R: Falhas do tipo "fail-stop" — um processo (líder ou peer) simplesmente para de
responder, sem se comportar de forma imprevisível ou maliciosa. O líder tem eleição de
substituto; um peer que cai deixa de confirmar/responder e é, na prática, ignorado pelo
resto do sistema (o quórum recalcula-se com base em quantos peers ainda estão ativos).

**P: E a recuperação automática de ficheiros pinned quando um peer cai?**
R: Não está implementada. Hoje garantimos que cada ficheiro é pinned por pelo menos dois
peers no momento do commit, mas se um desses peers morrer permanentemente, não há hoje
um mecanismo automático que detete essa perda de redundância e mande outro peer fazer
pinning de substituição. Seria o próximo passo lógico de evolução.

**P (RNF4, Eleição): A eleição é transparente para o cliente?**
R: Não totalmente. O sistema recupera sozinho (elege um novo líder e continua a
funcionar), mas se o líder mudar de máquina, o cliente teria de saber o novo
endereço/porta para continuar a mandar pedidos — não temos hoje um mecanismo (proxy, DNS
dinâmico) que esconda essa mudança do cliente.

**P (RNF5, Dinamicidade de peers): Como entra um peer novo no sistema?**
R: Basta arrancar o processo — ele subscreve-se ao tópico PubSub, começa a anunciar-se
periodicamente (peer_hello), e a partir daí passa a poder receber PREPAREs, COMMITs e
pesquisas normalmente. Não precisa de nenhuma configuração central a saber da sua
existência antecipadamente.

**P: E a saída de um peer?**
R: Se sair de forma limpa, não avisa ninguém explicitamente — os outros só reparam
indiretamente (deixa de confirmar, deixa de responder a pesquisas atribuídas a ele). Não
implementámos uma mensagem de "saída" explícita.

**P (RNF6, Segurança): As mensagens entre peers são seguras?**
R: Não — é uma parte que não chegámos a implementar. As mensagens PubSub vão em JSON em
claro, sem assinatura nem cifra, e a identidade de um peer é só um identificador gerado
localmente (não está ligado a nenhuma chave criptográfica). Isto significa que, em teoria,
qualquer participante da rede podia forjar mensagens (por exemplo, fingir ser outro peer
a confirmar algo que não recebeu). Sabemos que é uma lacuna; se fôssemos implementar,
cada peer teria um par de chaves (por exemplo Ed25519), assinaria as suas mensagens, e o
identificador do peer seria derivado da chave pública em vez de ser um UUID aleatório.

---

## 9. Perguntas "armadilha" / de compreensão profunda

Estas são o tipo de pergunta que testa se percebem o sistema ou só decoraram o código.

**P: Se o líder cair mesmo no meio de um upload (depois do PREPARE, antes do commit), o
que acontece a essa atualização?**
R: Fica pendente nos peers que já a receberam (guardada como versão temporária). Quando
o novo líder assumir, ele vai basear-se no estado *confirmado* que herdou — não sabe
nada sobre atualizações que estavam a meio de ser confirmadas no líder antigo. Essa
atualização em concreto perde-se (o cliente teria de reenviar o ficheiro). É uma janela
de inconsistência conhecida — não fizemos recuperação de transações a meio.

**P: Porque é que o líder responde ao `/upload` antes de saber se a maioria dos peers
confirmou?**
R: Para não obrigar o cliente a esperar pelo tempo todo do protocolo de consenso (que
pode demorar, se algum peer estiver lento). O `/upload` garante que o ficheiro já está
guardado de forma persistente no IPFS (isso sim, acontece de forma síncrona); a
propagação e confirmação pelos peers continuam a acontecer em segundo plano. Isto é uma
opção de desenho — podíamos ter feito o `/upload` esperar pelo quórum antes de
responder, mas isso tornaria o cliente refém da velocidade do peer mais lento.

**P: O que é que o hash do vetor (SHA-256) está realmente a proteger?**
R: Está a confirmar que o peer construiu exatamente a mesma lista ordenada de CIDs que o
líder tinha em mente ao publicar a atualização — é uma forma barata de detetar
inconsistências (por exemplo, uma mensagem corrompida ou uma ordem diferente de
processamento) sem ter de comparar a lista inteira byte a byte.

**P: Se eu desligar a rede entre dois peers (mas ambos continuam ligados ao líder), o
que muda?**
R: Como toda a comunicação passa pelo líder via PubSub (não é comunicação direta
peer-a-peer neste desenho), dois peers não precisam de se falar diretamente — só
precisam de conseguir alcançar a rede IPFS/PubSub em geral. O quórum é calculado com
base em quem está subscrito ao tópico, por isso, se a rede permitir a ambos continuarem
ligados ao PubSub, o sistema nem dá por essa "desconexão" entre eles.

**P: Qual é, na vossa opinião, o ponto mais frágil do sistema?**
R: A ausência de números de termo/época na eleição do líder — é o que abre a porta ao
cenário de split-brain (dois líderes em simultâneo) numa partição de rede. É também o
tipo de problema que o Raft resolve "a sério", e que identificámos como a evolução mais
importante a fazer a seguir.

---

## 10. Se pedirem para correr uma demonstração ao vivo

Ordem sugerida (ver `README.md` para os comandos exatos):

1. Arrancar IPFS + FAISS local + líder + 2 peers.
2. Fazer upload de um ficheiro, mostrar o CID devolvido.
3. Mostrar nos terminais dos peers o PREPARE a chegar, a confirmação a ser enviada, e o
   COMMIT a ser aplicado (e o pinning, se esse peer for responsável).
4. Consultar `/vetor` no líder para mostrar o estado confirmado.
5. Fazer uma pesquisa (`/prompt`), mostrar que só um peer a processa, consultar o
   resultado.
6. (Se houver tempo e for pedido) Matar o processo do líder, esperar a eleição, mostrar
   que um peer assume e que o estado (`/vetor`) não volta a zero.

Se algo falhar ao vivo, o mais importante é conseguires explicar *o que devia acontecer*
e *porquê* — isso conta mais do que a demo correr perfeita.
