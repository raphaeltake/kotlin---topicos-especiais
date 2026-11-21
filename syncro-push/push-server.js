// Envia pelo Firebase Cloud Messaging (FCM) as notificações do Syncro.
//
// O app grava os avisos em users/{uid}/notifications e o token de cada
// aparelho conectado em users/{uid}/fcmTokens/{token}. Este serviço acompanha
// o Firestore em tempo real e:
//   - envia o push de cada aviso de tarefa (atribuição, situação, comentário);
//   - para cada e-mail novo em teams/{teamId}.invitedEmails, procura a conta,
//     registra (ou reabre) o aviso invite_{teamId} e envia o push;
//   - apaga os tokens que o FCM informa como inválidos.
//
// Até onde os avisos já foram enviados fica em pushServer/state. Ao reiniciar,
// o serviço envia o que chegou enquanto estava parado, sem repetir o resto.
import { FieldValue } from 'firebase-admin/firestore';

export const TYPE_INVITE = 'team_invite';
export const TASK_TYPES = ['task_assigned', 'task_status', 'task_comment'];

const STATE_DOC = 'pushServer/state';
const MAX_CONTENT_LENGTH = 500;

// O token deixou de valer (app desinstalado, dados apagados ou logout).
const STALE_TOKEN_ERRORS = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
]);

/**
 * Inicia o serviço. `db`, `auth` e `messaging` são instâncias do
 * firebase-admin. Devolve `ready` (resolvida quando as duas escutas
 * receberam o estado inicial) e `stop()`.
 */
export async function startPushServer({
  db,
  auth,
  messaging,
  log = console,
  retryDelayMs = 5_000,
  // A escuta das notificações é renovada a partir do ponto já enviado, para
  // não acumular na memória os avisos antigos.
  refreshMs = 60 * 60 * 1000,
}) {
  const stateRef = db.doc(STATE_DOC);
  let cursor = await loadCursor(stateRef);
  const knownInvites = new Map(); // teamId -> e-mails convidados já tratados
  const notificationsQueue = serialQueue(log);
  const teamsQueue = serialQueue(log);
  const timers = new Set();
  let stopped = false;
  let unsubscribeNotifications = () => {};
  let unsubscribeTeams = () => {};
  let notificationsReady;
  let teamsReady;
  const ready = Promise.all([
    new Promise((resolve) => { notificationsReady = resolve; }),
    new Promise((resolve) => { teamsReady = resolve; }),
  ]);

  function later(fn, ms) {
    const timer = setTimeout(() => { timers.delete(timer); fn(); }, ms);
    timers.add(timer);
  }

  // --- Avisos de tarefa ---

  function subscribeNotifications() {
    unsubscribeNotifications();
    if (stopped) return;
    unsubscribeNotifications = db.collectionGroup('notifications')
      .where('createdAt', '>=', cursor.time)
      .orderBy('createdAt')
      .onSnapshot(
        (snapshot) => notificationsQueue.run(async () => {
          await handleNotifications(snapshot);
          notificationsReady();
        }),
        (error) => {
          log.error('Escuta das notificações interrompida; tentando de novo.', error.message);
          later(subscribeNotifications, retryDelayMs);
        },
      );
  }

  async function handleNotifications(snapshot) {
    const added = snapshot.docChanges()
      .filter((change) => change.type === 'added')
      .map((change) => change.doc)
      .sort((a, b) => compareTimestamps(a.get('createdAt'), b.get('createdAt')));
    let advanced = false;

    for (const doc of added) {
      const createdAt = doc.get('createdAt');
      const order = compareTimestamps(createdAt, cursor.time);
      // Já tratado antes de a escuta ser renovada.
      if (order < 0 || (order === 0 && cursor.ids.has(doc.ref.path))) continue;

      const notification = doc.data();
      if (TASK_TYPES.includes(notification.type)) {
        const uid = doc.ref.parent.parent.id;
        try {
          await push(uid, doc.id, notification);
        } catch (error) {
          log.error('Falha ao enviar o aviso de tarefa.', { uid, notificationId: doc.id, error: error.message });
        }
      }
      if (order > 0) cursor = { time: createdAt, ids: new Set() };
      cursor.ids.add(doc.ref.path);
      advanced = true;
    }

    if (advanced) {
      await stateRef.set({
        notificationsCursor: cursor.time,
        notificationsCursorIds: [...cursor.ids],
        updatedAt: FieldValue.serverTimestamp(),
      }, { merge: true });
    }
  }

  // --- Convites ---

  function subscribeTeams() {
    unsubscribeTeams();
    if (stopped) return;
    unsubscribeTeams = db.collection('teams').onSnapshot(
      (snapshot) => teamsQueue.run(async () => {
        await handleTeams(snapshot);
        teamsReady();
      }),
      (error) => {
        log.error('Escuta das equipes interrompida; tentando de novo.', error.message);
        later(subscribeTeams, retryDelayMs);
      },
    );
  }

  // Na primeira leitura, todos os convites pendentes contam como novos: assim
  // os feitos com o serviço parado também são avisados. invite() não repete
  // o push de um aviso que já está pendente.
  async function handleTeams(snapshot) {
    for (const change of snapshot.docChanges()) {
      const teamId = change.doc.id;
      if (change.type === 'removed') {
        knownInvites.delete(teamId);
        continue;
      }
      const team = change.doc.data();
      const emails = team.invitedEmails ?? [];
      const previous = knownInvites.get(teamId) ?? new Set();
      const failed = new Set();
      await Promise.all(emails.filter((email) => !previous.has(email)).map(async (email) => {
        try {
          await invite(email, teamId, team);
        } catch (error) {
          // Fica fora da lista para tentar de novo na próxima alteração da equipe.
          failed.add(email);
          log.error('Falha ao registrar o convite.', { teamId, error: error.message });
        }
      }));
      knownInvites.set(teamId, new Set(emails.filter((email) => !failed.has(email))));
    }
  }

  async function invite(email, teamId, team) {
    let uid;
    try {
      uid = (await auth.getUserByEmail(email)).uid;
    } catch (error) {
      // Ainda sem conta: o app registra o aviso quando a pessoa se cadastrar.
      if (error.code === 'auth/user-not-found') return;
      throw error;
    }

    // Mesmo id e mesmos campos que o app usa (FirebaseNotificationRepository).
    const ref = db.doc(`users/${uid}/notifications/invite_${teamId}`);
    const content = `Você foi convidado(a) para entrar na equipe ${team.name}.`.slice(0, MAX_CONTENT_LENGTH);
    const notification = {
      type: TYPE_INVITE,
      content,
      seen: false,
      createdAt: FieldValue.serverTimestamp(),
      teamId,
      teamName: team.name,
      taskId: '',
      actorId: '',
    };
    const isNew = await db.runTransaction(async (transaction) => {
      const current = await transaction.get(ref);
      // Convite ainda pendente: já foi avisado (por este serviço ou pelo app).
      if (current.exists && current.get('type') === TYPE_INVITE) return false;
      if (current.exists) {
        // Convite já respondido antes: é um novo convite para a mesma equipe.
        transaction.update(ref, {
          type: TYPE_INVITE, content, seen: false, createdAt: FieldValue.serverTimestamp(), teamName: team.name,
        });
      } else {
        transaction.create(ref, notification);
      }
      return true;
    });
    if (isNew) await push(uid, ref.id, notification);
  }

  // --- Envio ---

  /** Envia o aviso para todos os aparelhos da conta e descarta os tokens vencidos. */
  async function push(uid, notificationId, notification) {
    const tokens = await db.collection(`users/${uid}/fcmTokens`).get();
    if (tokens.empty) return;

    // Só dados: o app confere se a conta conectada é a destinatária, monta a
    // notificação e abre a tela de notificações quando o usuário toca nela.
    const response = await messaging.sendEachForMulticast({
      tokens: tokens.docs.map((doc) => doc.id),
      data: {
        uid,
        notificationId,
        type: String(notification.type),
        title: String(notification.teamName || 'Syncro'),
        body: String(notification.content),
        teamId: String(notification.teamId ?? ''),
        taskId: String(notification.taskId ?? ''),
      },
      // Alta prioridade para chegar na hora mesmo com o aparelho em repouso.
      android: { priority: 'high' },
    });

    const stale = [];
    response.responses.forEach((result, index) => {
      if (result.success) return;
      if (STALE_TOKEN_ERRORS.has(result.error?.code)) stale.push(tokens.docs[index].ref);
      else log.warn('Falha ao enviar notificação.', { uid, notificationId, error: result.error?.message });
    });
    await Promise.all(stale.map((ref) => ref.delete()));
    if (response.successCount > 0) {
      log.info('Notificação enviada.', { uid, notificationId, aparelhos: response.successCount });
    }
  }

  // --- Ciclo de vida ---

  subscribeNotifications();
  subscribeTeams();
  const refreshTimer = setInterval(() => notificationsQueue.run(async () => subscribeNotifications()), refreshMs);

  async function stop() {
    stopped = true;
    clearInterval(refreshTimer);
    timers.forEach(clearTimeout);
    timers.clear();
    unsubscribeNotifications();
    unsubscribeTeams();
    await Promise.all([notificationsQueue.drain(), teamsQueue.drain()]);
  }

  return { ready, stop };
}

/** Lê o ponto de partida; na primeira execução, começa agora, sem avisos antigos. */
async function loadCursor(stateRef) {
  let state = await stateRef.get();
  if (!state.get('notificationsCursor')) {
    await stateRef.set({
      notificationsCursor: FieldValue.serverTimestamp(),
      notificationsCursorIds: [],
    }, { merge: true });
    state = await stateRef.get();
  }
  return {
    time: state.get('notificationsCursor'),
    ids: new Set(state.get('notificationsCursorIds') ?? []),
  };
}

function compareTimestamps(a, b) {
  return a.seconds - b.seconds || a.nanoseconds - b.nanoseconds;
}

/** Executa as tarefas uma de cada vez, na ordem em que chegam. */
function serialQueue(log) {
  let tail = Promise.resolve();
  return {
    run(task) {
      tail = tail.then(task).catch((error) => log.error('Erro ao processar alterações.', error));
      return tail;
    },
    drain: () => tail,
  };
}
