# Servidor de push do Syncro

Envia pelo Firebase Cloud Messaging (FCM) as notificações que o app grava no
Firestore, para que cheguem ao celular mesmo com o app fechado. Funciona no
plano gratuito (Spark) do Firebase, sem Cloud Functions.

O serviço acompanha o Firestore em tempo real e:

- envia o push de cada aviso de tarefa (atribuição, mudança de situação e
  comentário) criado em `users/{uid}/notifications`;
- quando um e-mail entra em `invitedEmails` de uma equipe, procura a conta com
  esse e-mail, registra (ou reabre) o aviso `invite_{teamId}` e envia o push;
- apaga os tokens que o FCM informa como inválidos.

Os tokens dos aparelhos ficam em `users/{uid}/fcmTokens/{token}`, gravados pelo
app (`PushNotifications.kt`). O ponto até onde os avisos já foram enviados fica
em `pushServer/state`: se o serviço ficar parado, ao voltar ele envia o que
chegou nesse intervalo, sem repetir o que já tinha enviado.

## Requisitos

- Node.js 20 ou mais recente.
- Regras e índice do Firestore publicados (a partir de `../syncro-kotlin`):

  ```bash
  firebase deploy --only firestore
  ```

## Chave da conta de serviço

O serviço precisa da chave de administrador do projeto. Ela dá acesso total ao
Firebase, então **nunca a coloque no repositório nem no app**.

1. No Console do Firebase, abra **Configurações do projeto > Contas de
   serviço** e clique em **Gerar nova chave privada**.
2. Guarde o arquivo fora do repositório e restrinja o acesso:

   ```bash
   mkdir -p ~/.config/syncro
   mv ~/Downloads/syncro-91de4-firebase-adminsdk-*.json ~/.config/syncro/service-account.json
   chmod 600 ~/.config/syncro/service-account.json
   ```

Se a chave vazar, apague-a na mesma tela do Console e gere outra.

## Executar

```bash
npm install
GOOGLE_APPLICATION_CREDENTIALS=~/.config/syncro/service-account.json npm start
```

O terminal mostra `Servidor de push do Syncro em execução.` e uma linha a cada
notificação enviada. `Ctrl+C` encerra.

Rode **uma única instância** por projeto, senão os avisos chegam em dobro.

## Manter rodando (Linux com systemd)

Crie `~/.config/systemd/user/syncro-push.service`, trocando os caminhos (o do
Node aparece em `which node`):

```ini
[Unit]
Description=Servidor de push do Syncro
After=network-online.target

[Service]
WorkingDirectory=/caminho/para/kotlin---topicos-especiais/syncro-push
Environment=GOOGLE_APPLICATION_CREDENTIALS=%h/.config/syncro/service-account.json
ExecStart=/caminho/para/node index.js
Restart=always
RestartSec=10

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload
systemctl --user enable --now syncro-push
journalctl --user -u syncro-push -f   # acompanhar os registros
sudo loginctl enable-linger "$USER"   # continuar rodando sem sessão aberta
```

## Cotas do plano Spark

Cada aviso custa cerca de 2 leituras e 1 gravação no Firestore, e cada início
do serviço lê todas as equipes. Isso fica bem abaixo das cotas diárias gratuitas
(50 mil leituras e 20 mil gravações). O envio pelo FCM não é cobrado.

## Testes

Rodam nos emuladores do Firestore e do Authentication, com o FCM simulado.
Requerem a [Firebase CLI](https://firebase.google.com/docs/cli) e Java 21 ou
mais recente (exigido pelo emulador do Firestore).

```bash
npm install
npm test
```
