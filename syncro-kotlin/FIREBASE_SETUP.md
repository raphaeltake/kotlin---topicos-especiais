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
falhará ao tentar salvar o perfil no Firestore com `PERMISSION_DENIED`.

O plugin `com.google.gms.google-services` e aplicado automaticamente quando o
arquivo de configuracao esta presente. Sem o arquivo, o projeto ainda compila,
mas nenhum servico Firebase e inicializado em tempo de execucao.

O `google-services.json` contem identificadores do aplicativo, nao uma chave de
servidor. Ainda assim, nunca adicione ao app credenciais administrativas ou
arquivos de conta de servico.
