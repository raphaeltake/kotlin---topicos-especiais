// Testes do servidor de push nos emuladores do Firestore e do Authentication.
// O FCM não tem emulador, então o envio é simulado por fakeMessaging().
// Rode com `npm test` (veja README.md).
import { after, afterEach, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { deleteApp, initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { FieldValue, Timestamp, getFirestore } from 'firebase-admin/firestore';
import { startPushServer } from './push-server.js';

const projectId = process.env.GCLOUD_PROJECT ?? 'demo-syncro-push';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';

const app = initializeApp({ projectId });
const db = getFirestore(app);
const auth = getAuth(app);
const quiet = { info() {}, warn() {}, error: console.error };

/** Registra cada envio; tokens que começam com "stale" são recusados pelo "FCM". */
function fakeMessaging() {
  const sent = [];
  return {
    sent,
    async sendEachForMulticast(message) {
      sent.push(message);
      const responses = message.tokens.map((token) => (token.startsWith('stale')
        ? { success: false, error: { code: 'messaging/registration-token-not-registered', message: 'vencido' } }
        : { success: true }));
      const successCount = responses.filter((r) => r.success).length;
      return { responses, successCount, failureCount: responses.length - successCount };
    },
  };
}

let servers = [];
async function start(messaging, options = {}) {
  const server = await startPushServer({ db, auth, messaging, log: quiet, retryDelayMs: 200, ...options });
  servers.push(server);
  await server.ready;
  return server;
}

async function waitFor(condition, what, timeoutMs = 10_000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    if (await condition()) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.fail(`tempo esgotado esperando: ${what}`);
}
/** Espera um pouco para confirmar que nada mais acontece. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 1_500));

beforeEach(async () => {
  const firestoreHost = process.env.FIRESTORE_EMULATOR_HOST;
  const authHost = process.env.FIREBASE_AUTH_EMULATOR_HOST;
  await fetch(`http://${firestoreHost}/emulator/v1/projects/${projectId}/databases/(default)/documents`, { method: 'DELETE' });
  await fetch(`http://${authHost}/emulator/v1/projects/${projectId}/accounts`, { method: 'DELETE' });
});
afterEach(async () => {
  await Promise.all(servers.map((server) => server.stop()));
  servers = [];
});
after(() => deleteApp(app));

const notifications = (uid) => db.collection(`users/${uid}/notifications`);
const addToken = (uid, token) => db.doc(`users/${uid}/fcmTokens/${token}`).set({ updatedAt: FieldValue.serverTimestamp() });
const taskNotice = (o = {}) => ({
  type: 'task_comment', content: 'Alice comentou na tarefa "Planejar sprint".', seen: false,
  createdAt: FieldValue.serverTimestamp(), teamId: 'team1', teamName: 'Equipe', taskId: 'task1', actorId: 'alice', ...o,
});
const inviteNotice = (o = {}) => taskNotice({
  type: 'team_invite', content: 'Você foi convidado(a) para entrar na equipe Equipe.', taskId: '', ...o,
});
const team = db.doc('teams/team1');
const createTeam = (invitedEmails = []) => team.set({
  name: 'Equipe', description: '', code: 'TEAM2345', createdBy: 'alice', memberIds: ['alice'], adminIds: [],
  memberNames: { alice: 'Alice' }, memberEmails: { alice: 'alice@example.com' }, invitedEmails,
  createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp(),
});

test('envia os avisos de tarefa criados depois do início, e só eles', async () => {
  await addToken('dave', 'token-dave');
  await notifications('dave').add(taskNotice({ content: 'Aviso antigo' }));
  const fcm = fakeMessaging();
  await start(fcm);

  const fresh = await notifications('dave').add(taskNotice());
  await waitFor(() => fcm.sent.length === 1, 'envio do aviso novo');
  await settle();
  assert.equal(fcm.sent.length, 1);
  assert.deepEqual(fcm.sent[0].tokens, ['token-dave']);
  assert.deepEqual(fcm.sent[0].data, {
    uid: 'dave', notificationId: fresh.id, type: 'task_comment', title: 'Equipe',
    body: 'Alice comentou na tarefa "Planejar sprint".', teamId: 'team1', taskId: 'task1',
  });
  assert.equal(fcm.sent[0].android.priority, 'high');
});

test('ignora avisos de convite gravados pelo app e contas sem aparelho', async () => {
  await addToken('dave', 'token-dave');
  const fcm = fakeMessaging();
  await start(fcm);

  await notifications('dave').doc('invite_team1').set(inviteNotice({ actorId: 'dave' }));
  await notifications('carol').add(taskNotice());
  const last = await notifications('dave').add(taskNotice({ type: 'task_status' }));
  await waitFor(() => fcm.sent.length > 0, 'envio do último aviso');
  await settle();
  assert.deepEqual(fcm.sent.map((m) => m.data.notificationId), [last.id]);
});

test('renovar a escuta não repete envios', async () => {
  await addToken('dave', 'token-dave');
  const fcm = fakeMessaging();
  await start(fcm, { refreshMs: 150 });

  await notifications('dave').add(taskNotice());
  await notifications('dave').add(taskNotice({ type: 'task_status' }));
  await waitFor(() => fcm.sent.length === 2, 'envio dos dois avisos');
  await settle();
  assert.equal(fcm.sent.length, 2);
});

test('apaga os tokens que o FCM recusa e mantém os válidos', async () => {
  await addToken('dave', 'token-ok');
  await addToken('dave', 'stale-token');
  const fcm = fakeMessaging();
  await start(fcm);

  await notifications('dave').add(taskNotice({ type: 'task_assigned' }));
  await waitFor(async () => !(await db.doc('users/dave/fcmTokens/stale-token').get()).exists, 'remoção do token vencido');
  assert.equal(fcm.sent[0].tokens.length, 2);
  assert.ok((await db.doc('users/dave/fcmTokens/token-ok').get()).exists);
});

test('convite: registra o aviso para a conta do e-mail e envia o push; e-mail sem conta é ignorado', async () => {
  const bob = await auth.createUser({ email: 'bob@example.com' });
  await addToken(bob.uid, 'token-bob');
  await createTeam();
  const fcm = fakeMessaging();
  await start(fcm);

  await team.update({ invitedEmails: ['bob@example.com', 'ghost@example.com'] });
  await waitFor(() => fcm.sent.length === 1, 'push do convite');
  const notice = (await notifications(bob.uid).doc('invite_team1').get()).data();
  assert.ok(notice.createdAt instanceof Timestamp);
  delete notice.createdAt;
  assert.deepEqual(notice, {
    type: 'team_invite', content: 'Você foi convidado(a) para entrar na equipe Equipe.', seen: false,
    teamId: 'team1', teamName: 'Equipe', taskId: '', actorId: '',
  });
  assert.equal(fcm.sent[0].data.uid, bob.uid);
  assert.equal(fcm.sent[0].data.notificationId, 'invite_team1');

  await settle();
  assert.equal(fcm.sent.length, 1);
  const users = await db.collection('users').listDocuments();
  assert.deepEqual(users.map((ref) => ref.id), [bob.uid]);
});

test('convite já registrado pelo app do convidado não gera outro push', async () => {
  const bob = await auth.createUser({ email: 'bob@example.com' });
  await addToken(bob.uid, 'token-bob');
  await notifications(bob.uid).doc('invite_team1').set(inviteNotice({ actorId: bob.uid }));
  await createTeam();
  const fcm = fakeMessaging();
  await start(fcm);

  await team.update({ invitedEmails: ['bob@example.com'] });
  await settle();
  assert.equal(fcm.sent.length, 0);
});

test('novo convite reabre o aviso já respondido, com o nome atual da equipe', async () => {
  const bob = await auth.createUser({ email: 'bob@example.com' });
  await addToken(bob.uid, 'token-bob');
  await notifications(bob.uid).doc('invite_team1')
    .set(inviteNotice({ type: 'team_invite_declined', seen: true, teamName: 'Nome antigo', content: 'antigo' }));
  await createTeam();
  const fcm = fakeMessaging();
  await start(fcm);

  await team.update({ name: 'Equipe Nova', invitedEmails: ['bob@example.com'] });
  await waitFor(() => fcm.sent.length === 1, 'push do novo convite');
  const notice = (await notifications(bob.uid).doc('invite_team1').get()).data();
  assert.equal(notice.type, 'team_invite');
  assert.equal(notice.seen, false);
  assert.equal(notice.teamName, 'Equipe Nova');
  assert.equal(notice.content, 'Você foi convidado(a) para entrar na equipe Equipe Nova.');
});

test('ao reiniciar, envia o que chegou com o serviço parado sem repetir o resto', async () => {
  const bob = await auth.createUser({ email: 'bob@example.com' });
  await addToken(bob.uid, 'token-bob');
  await addToken('dave', 'token-dave');
  await createTeam();

  const first = fakeMessaging();
  const server = await start(first);
  const before = await notifications('dave').add(taskNotice());
  await waitFor(() => first.sent.length === 1, 'envio antes de parar');
  await server.stop();

  const whileStopped = await notifications('dave').add(taskNotice({ type: 'task_status' }));
  await team.update({ invitedEmails: ['bob@example.com'] });

  const second = fakeMessaging();
  await start(second);
  await waitFor(() => second.sent.length === 2, 'envios do que chegou com o serviço parado');
  await settle();
  const sentIds = second.sent.map((m) => m.data.notificationId).sort();
  assert.deepEqual(sentIds, [whileStopped.id, 'invite_team1'].sort());
  assert.ok(!sentIds.includes(before.id));
});
