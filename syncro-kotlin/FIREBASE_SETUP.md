# Configuracao do Firebase

O projeto Android ja esta preparado com Firebase Authentication e Cloud Firestore.
Para vincula-lo a um projeto Firebase:

1. No [Console do Firebase](https://console.firebase.google.com/), crie ou abra o projeto do Syncro.
2. Registre um aplicativo Android com o pacote `br.com.fatec.syncro`.
3. Baixe o arquivo `google-services.json` sem renomea-lo.
4. Coloque o arquivo em `syncro-kotlin/app/google-services.json`.
5. Em **Authentication > Sign-in method**, ative **E-mail/senha**.
6. Em **Firestore Database**, crie o banco.
7. Publique as regras de `firestore.rules` com `firebase deploy --only firestore:rules`.
8. Sincronize o Gradle e execute `./gradlew assembleDebug`.

As regras incluídas no projeto permitem que cada usuário autenticado leia e
grave apenas o documento `users/{uid}` pertencente à própria conta. Se elas não
forem publicadas, o Authentication poderá criar o usuário, mas o cadastro
falhará ao tentar salvar o perfil no Firestore com `PERMISSION_DENIED`. O mesmo
vale para a criação de equipes.

## Estrutura do Firestore

| Caminho | Conteúdo |
| --- | --- |
| `users/{uid}` | `name`, `email`, `createdAt`, `updatedAt` |
| `users/{uid}/notifications/{id}` | `type`, `content`, `seen`, `createdAt`, `teamId`, `teamName`, `taskId`, `actorId` |
| `users/{uid}/fcmTokens/{token}` | `updatedAt` (o id do documento é o token FCM do aparelho) |
| `pushServer/state` | Até onde o servidor de push já enviou os avisos (só o servidor acessa) |
| `teams/{teamId}` | `name`, `description`, `code`, `createdBy`, `memberIds`, `adminIds`, `memberNames`, `memberEmails`, `invitedEmails`, `createdAt`, `updatedAt` |
| `teamCodes/{code}` | `teamId`, `name`, `description`, `createdAt` |
| `teams/{teamId}/tasks/{taskId}` | `title`, `description`, `responsibleIds`, `responsibleNames`, `dueDate` (aaaa-mm-dd), `status` (`pending`, `in_progress`, `finished`), `createdBy`, `createdAt`, `updatedAt` |
| `teams/{teamId}/tasks/{taskId}/comments/{commentId}` | `text`, `history` (versões anteriores: `text` e `editedAt`), `authorId`, `authorName`, `createdAt`, `updatedAt` |
| `teams/{teamId}/history/{eventId}` | `type`, `taskId`, `taskTitle`, `commentId`, `field`, `oldValue`, `newValue`, `userId`, `userName`, `createdAt` |

Cada equipe é um único documento. `memberIds` lista os uids dos membros (o
criador sempre incluído), `adminIds` os administradores (o criador fica fora
dessa lista, mas tem todos os poderes), `memberNames` e `memberEmails` guardam
o nome e o e-mail de cada membro e `invitedEmails` os convites pendentes. O
código de convite é reservado em `teamCodes/{code}` na mesma transação que cria
a equipe. Esse documento também guarda nome e descrição, para que quem digita o
código veja a equipe antes de confirmar o ingresso, e as regras impedem que ele
passe a apontar para outra equipe.

Cada alteração em tarefas e comentários grava, no mesmo lote ou transação, um
registro em `teams/{teamId}/history` com o tipo do evento, o campo alterado, os
valores anterior e novo, o autor e a data. As regras conferem que o registro
descreve uma alteração que realmente acontece no lote, e ninguém altera
registros depois de gravados.

As notificações ficam em `users/{uid}/notifications`. Avisos de tarefa
(atribuição, mudança de situação e novo comentário) são gravados por quem fez a
alteração na lista de cada interessado. Avisos de convite são registrados pela
servidor de push (`syncro-push`), que troca o e-mail convidado pela conta, ou
pelo próprio convidado quando o app encontra o convite (por exemplo, se a conta
foi criada depois do convite).

As operações ficam em `FirebaseTeamRepository` (equipes),
`FirebaseTaskRepository` (tarefas, comentários e histórico) e
`FirebaseNotificationRepository` (notificações). Cada uma é validada por
`firestore.rules`:

| Operação | Quem pode |
| --- | --- |
| Criar equipe (nome e descrição) | Qualquer usuário autenticado, em até 50 equipes |
| Ver equipe e membros | Membros e convidados |
| Editar nome e descrição | Só o criador |
| Convidar por e-mail | Criador e administradores (quem já é membro não é convidado) |
| Promover/rebaixar e remover membros | Criador e administradores (o criador nunca é afetado) |
| Entrar por código ou aceitar convite | Qualquer usuário, sempre como membro comum |
| Recusar convite | O próprio convidado |
| Sair da equipe | Qualquer membro, exceto o criador |
| Apagar equipe (com tarefas, comentários e histórico) | Só o criador |
| Ver tarefas, comentários e histórico | Membros |
| Criar, editar e apagar tarefas e escolher os responsáveis | Criador e administradores (responsáveis precisam ser membros; editar só enquanto não finalizada) |
| Iniciar e finalizar tarefas | Responsáveis pela tarefa e administradores, só para a frente (pendente → em andamento → finalizada) |
| Comentar | Qualquer membro, enquanto a tarefa não for finalizada |
| Editar e apagar comentário | O autor ou um administrador (cada edição guarda a versão anterior em `history`) |
| Ler, marcar como lida e apagar notificações | Só o dono |
| Enviar notificação de tarefa | Membro da equipe, para os responsáveis (e, em mudança de situação e comentário, para quem criou a tarefa) |
| Registrar e apagar o token FCM do aparelho | Só o dono (ninguém lê pelo app; o servidor de push lê com acesso de administrador) |

## Notificações push (Firebase Cloud Messaging)

Cada notificação gravada em `users/{uid}/notifications` também chega ao
celular do destinatário pela barra de notificações do Android, mesmo com o app
fechado. Tocar nela abre a tela de notificações do app.

Como funciona:

1. Ao entrar (ou ao abrir o app já conectado), o app grava o token FCM do
   aparelho em `users/{uid}/fcmTokens/{token}` (`PushNotifications.register`).
   O FCM pode trocar o token a qualquer momento; o novo é gravado por
   `SyncroMessagingService.onNewToken`.
2. O servidor de push (pasta `syncro-push`, na raiz do repositório) acompanha
   o Firestore em tempo real e envia as mensagens:
   - quando um aviso de tarefa (atribuição, mudança de situação ou comentário)
     é criado na lista de alguém;
   - quando um e-mail entra em `invitedEmails` de uma equipe: procura a conta
     com esse e-mail, registra (ou reabre) o aviso `invite_{teamId}` e envia o
     push. E-mails sem conta são ignorados.
   Tokens que o FCM informa como inválidos são apagados.
3. As mensagens levam só dados (`uid`, `notificationId`, `title`, `body`...).
   O app confere se a conta conectada é a destinatária antes de exibir e cria
   o canal "Convites e tarefas".
4. Ao sair da conta, o app apaga o token da lista e o invalida no FCM, para que
   outra conta usada no mesmo aparelho não receba os avisos da anterior.

No Android 13 ou mais recente, o app pede a autorização de notificações ao abrir
a lista de equipes. Sem ela, os avisos continuam aparecendo no sino do app.

Com o app aberto, as telas não dependem do push: `LiveUpdates` mantém escutas
em tempo real do Firestore (equipes, convites, notificações e tarefas; na tela
da tarefa, os comentários; no histórico, os registros), e qualquer alteração de
qualquer membro redesenha a tela na hora. As escutas param com o app em segundo
plano e funcionam sem o servidor de push.

O envio funciona no plano gratuito (Spark), mas o servidor de push precisa
estar rodando: enquanto estiver parado, os avisos ficam só no sino, e ao voltar
ele envia o que ficou pendente. Instruções em `../syncro-push/README.md`.

O servidor consulta as notificações de todos os usuários de uma vez, o que
exige o índice de `firestore.indexes.json`. Publique-o junto com as regras:

```bash
firebase deploy --only firestore
```

## Testes das regras

Os testes de `firestore.rules` ficam em `firestore-tests/` e rodam no emulador
do Firestore, que exige Java 21 ou mais recente:

```bash
cd firestore-tests
npm install
npm test
```

Depois de alterar `firestore.rules`, rode os testes e publique as regras de novo
com `firebase deploy --only firestore:rules`.

O plugin `com.google.gms.google-services` e aplicado automaticamente quando o
arquivo de configuracao esta presente. Sem o arquivo, o projeto ainda compila,
mas nenhum servico Firebase e inicializado em tempo de execucao.

O `google-services.json` contem identificadores do aplicativo, nao uma chave de
servidor. Ainda assim, nunca adicione ao app credenciais administrativas ou
arquivos de conta de servico.
