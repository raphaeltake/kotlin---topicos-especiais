// Servidor de push do Syncro: envia pelo FCM as notificações registradas no
// Firestore. Precisa da chave de uma conta de serviço do projeto, indicada em
// GOOGLE_APPLICATION_CREDENTIALS (veja README.md).
import { initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore } from 'firebase-admin/firestore';
import { getMessaging } from 'firebase-admin/messaging';
import { startPushServer } from './push-server.js';

const stamp = () => new Date().toISOString();
const log = {
  info: (...args) => console.log(stamp(), ...args),
  warn: (...args) => console.warn(stamp(), ...args),
  error: (...args) => console.error(stamp(), ...args),
};

initializeApp();

let server;
try {
  server = await startPushServer({ db: getFirestore(), auth: getAuth(), messaging: getMessaging(), log });
  await server.ready;
} catch (error) {
  log.error('Não foi possível iniciar o servidor de push:', error.message);
  if (!process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    log.error('Defina GOOGLE_APPLICATION_CREDENTIALS com o caminho da chave da conta de serviço (veja README.md).');
  }
  process.exit(1);
}
log.info('Servidor de push do Syncro em execução. Ctrl+C para encerrar.');

let stopping = false;
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, async () => {
    if (stopping) return;
    stopping = true;
    log.info('Encerrando...');
    await server.stop();
    process.exit(0);
  });
}
