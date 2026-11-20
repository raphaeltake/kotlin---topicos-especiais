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
| `teams/{teamId}` | `name`, `description`, `code`, `createdBy`, `memberIds`, `adminIds`, `memberNames`, `invitedEmails`, `createdAt`, `updatedAt` |
| `teamCodes/{code}` | `teamId`, `createdAt` |
| `teams/{teamId}/tasks/{taskId}` | `title`, `description`, `responsibles`, `dueDate` (aaaa-mm-dd), `status` (`pending`, `in_progress`, `finished`), `createdBy`, `createdAt`, `updatedAt` |
| `teams/{teamId}/tasks/{taskId}/comments/{commentId}` | `text`, `history` (versões anteriores), `authorId`, `authorName`, `createdAt`, `updatedAt` |

Cada equipe é um único documento. `memberIds` lista os uids dos membros (o
criador sempre incluído), `adminIds` os administradores (o criador fica fora
dessa lista, mas tem todos os poderes), `memberNames` guarda o nome exibido de
cada membro e `invitedEmails` os convites pendentes. O código de convite é
reservado em `teamCodes/{code}` na mesma transação que cria a equipe, e as
regras impedem sobrescrever um código existente.

As operações ficam em `FirebaseTeamRepository` (equipes) e
`FirebaseTaskRepository` (tarefas e comentários), e cada uma é validada por
`firestore.rules`:

| Operação | Quem pode |
| --- | --- |
| Criar equipe | Qualquer usuário autenticado |
| Ver equipe e membros | Membros e convidados |
| Editar nome e descrição, convidar por e-mail | Criador e administradores |
| Promover/rebaixar e remover membros | Criador e administradores (o criador nunca é afetado) |
| Entrar por código ou aceitar convite | Qualquer usuário, sempre como membro comum |
| Recusar convite | O próprio convidado |
| Sair da equipe | Qualquer membro, exceto o criador |
| Apagar equipe (com tarefas e comentários) | Só o criador |
| Ver tarefas e comentários | Membros |
| Criar, editar e apagar tarefas | Criador e administradores (editar só enquanto não finalizada) |
| Iniciar e finalizar tarefas | Qualquer membro, só para a frente (pendente → em andamento → finalizada) |
| Comentar | Qualquer membro, enquanto a tarefa não for finalizada |
| Editar e apagar comentário | O autor ou um administrador (cada edição guarda a versão anterior em `history`) |

O plugin `com.google.gms.google-services` e aplicado automaticamente quando o
arquivo de configuracao esta presente. Sem o arquivo, o projeto ainda compila,
mas nenhum servico Firebase e inicializado em tempo de execucao.

O `google-services.json` contem identificadores do aplicativo, nao uma chave de
servidor. Ainda assim, nunca adicione ao app credenciais administrativas ou
arquivos de conta de servico.
