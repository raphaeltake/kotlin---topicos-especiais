# Testes das regras do Firestore

Conferem, no emulador do Firestore, o que `../firestore.rules` permite e o que
recusa. As operações dos testes gravam os mesmos campos que os repositórios do
app (`FirebaseTeamRepository`, `FirebaseTaskRepository` e
`FirebaseNotificationRepository`).

| Arquivo | Conteúdo |
| --- | --- |
| `teams.test.mjs` | Criação e edição de equipes, código de convite, convites, ingresso, papéis, remoção, saída e exclusão |
| `tasks.test.mjs` | Tarefas, responsáveis, situação, comentários e histórico |
| `notifications.test.mjs` | Avisos de convite e de tarefas, leitura e marcação como lida |

## Como rodar

Requer Node.js 20 ou mais recente, a [Firebase CLI](https://firebase.google.com/docs/cli)
e Java 21 ou mais recente (exigido pelo emulador).

```bash
npm install
npm test
```

`npm test` inicia o emulador, executa `run.mjs` e encerra o emulador. Com o
emulador já em execução (`firebase emulators:start --only firestore`), basta
`node run.mjs`.
