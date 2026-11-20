// Notificações (FirebaseNotificationRepository).
import {
  doc, collection, getDocs, setDoc, updateDoc, deleteDoc, serverTimestamp, query, orderBy, limit,
} from 'firebase/firestore';
import { setup, check } from './harness.mjs';

const env = await setup('demo-syncro-notifications');

const ctx = (uid) => env.authenticatedContext(uid, { email: `${uid}@example.com` }).firestore();
const alice = ctx('alice'); // criadora da equipe e da tarefa
const carol = ctx('carol'); // administradora
const dave = ctx('dave');   // membro responsável pela tarefa
const bob = ctx('bob');     // convidado
const eve = ctx('eve');     // estranha

const teamId = 'team1';
const taskId = 'task1';
await env.withSecurityRulesDisabled(async (c) => {
  const db = c.firestore();
  const ids = ['alice', 'carol', 'dave'];
  await setDoc(doc(db, 'teams', teamId), {
    name: 'Equipe', description: '', code: 'TEAM2345', createdBy: 'alice',
    memberIds: ids, adminIds: ['carol'],
    memberNames: { alice: 'Alice', carol: 'Carol', dave: 'Dave' },
    memberEmails: Object.fromEntries(ids.map((id) => [id, `${id}@example.com`])),
    invitedEmails: ['bob@example.com'], createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
  });
  await setDoc(doc(db, 'teams', teamId, 'tasks', taskId), {
    title: 'Planejar sprint', description: 'Definir prioridades',
    responsibleIds: ['dave'], responsibleNames: { dave: 'Dave' },
    dueDate: '2030-01-15', status: 'pending', createdBy: 'alice',
    createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
  });
});

const notifications = (db, uid) => collection(db, 'users', uid, 'notifications');
const notification = (db, uid, id) => doc(db, 'users', uid, 'notifications', id);
const notice = (actor, type, o = {}) => ({
  type, content: o.content ?? 'Aviso', seen: o.seen ?? false, createdAt: serverTimestamp(),
  teamId: o.teamId ?? teamId, teamName: o.teamName ?? 'Equipe', taskId: o.taskId ?? taskId,
  actorId: o.actorId ?? actor,
});
const send = (db, actor, to, type, o) => setDoc(doc(notifications(db, to)), notice(actor, type, o));
const inviteNotice = (o = {}) => notice('bob', 'team_invite', { taskId: '', ...o });

// --- Convite (UC17 A5): o próprio convidado registra o aviso ---
await check('convidado registra o aviso do convite', true, () => setDoc(notification(bob, 'bob', `invite_${teamId}`), inviteNotice()));
await check('aviso de convite com outro id é recusado', false, () => setDoc(notification(bob, 'bob', 'qualquer'), inviteNotice()));
await check('quem não foi convidado não registra convite', false, () => setDoc(notification(eve, 'eve', `invite_${teamId}`), notice('eve', 'team_invite', { taskId: '' })));
await check('não registra convite na lista de outra pessoa', false, () => setDoc(notification(bob, 'dave', `invite_${teamId}`), inviteNotice()));
await check('convite com nome de equipe falso é recusado', false, () => setDoc(notification(bob, 'bob', `invite_${teamId}`), inviteNotice({ teamName: 'Outra' })));
await check('convidado lê os próprios avisos', true, () => getDocs(query(notifications(bob, 'bob'), orderBy('createdAt', 'desc'), limit(50))));
await check('ninguém lê avisos de outra pessoa', false, () => getDocs(notifications(dave, 'bob')));
await check('marca como lido', true, () => updateDoc(notification(bob, 'bob', `invite_${teamId}`), { seen: true }));
await check('registra a resposta ao convite', true, () => updateDoc(notification(bob, 'bob', `invite_${teamId}`), { type: 'team_invite_accepted', seen: true }));
await check('reabre o aviso quando é convidado de novo', true, () => updateDoc(notification(bob, 'bob', `invite_${teamId}`),
  { type: 'team_invite', seen: false, createdAt: serverTimestamp() }));
await check('não altera o conteúdo', false, () => updateDoc(notification(bob, 'bob', `invite_${teamId}`), { content: 'Outro' }));
await check('não transforma convite em aviso de tarefa', false, () => updateDoc(notification(bob, 'bob', `invite_${teamId}`), { type: 'task_status' }));

// --- Tarefas (UC17): atribuição, situação e comentário ---
await check('criadora avisa o responsável da atribuição', true, () => send(alice, 'alice', 'dave', 'task_assigned'));
await check('não avisa atribuição a quem não é responsável', false, () => send(alice, 'alice', 'carol', 'task_assigned'));
await check('responsável avisa a autora da tarefa sobre a situação', true, () => send(dave, 'dave', 'alice', 'task_status'));
await check('não avisa situação a quem não participa da tarefa', false, () => send(dave, 'dave', 'carol', 'task_status'));
await check('admin avisa o responsável sobre um comentário', true, () => send(carol, 'carol', 'dave', 'task_comment'));
await check('estranha não envia avisos', false, () => send(eve, 'eve', 'dave', 'task_comment'));
await check('não avisa quem não é membro', false, () => send(alice, 'alice', 'eve', 'task_comment'));
await check('não envia aviso para si mesmo', false, () => send(dave, 'dave', 'dave', 'task_status'));
await check('não se passa por outra pessoa', false, () => send(alice, 'alice', 'dave', 'task_comment', { actorId: 'carol' }));
await check('não cria aviso já lido', false, () => send(alice, 'alice', 'dave', 'task_comment', { seen: true }));
await check('nome de equipe falso é recusado', false, () => send(alice, 'alice', 'dave', 'task_comment', { teamName: 'Outra' }));
await check('conteúdo com 501 caracteres é recusado', false, () => send(alice, 'alice', 'dave', 'task_comment', { content: 'a'.repeat(501) }));
await check('tarefa inexistente é recusada', false, () => send(alice, 'alice', 'dave', 'task_comment', { taskId: 'nao-existe' }));

// --- Leitura e limpeza pelo dono ---
let first;
await check('responsável lê os avisos', true, async () => { first = (await getDocs(notifications(dave, 'dave'))).docs[0].id; });
await check('outra pessoa não marca como lido', false, () => updateDoc(notification(alice, 'dave', first), { seen: true }));
await check('responsável marca como lido', true, () => updateDoc(notification(dave, 'dave', first), { seen: true }));
await check('aviso de tarefa não vira convite', false, () => updateDoc(notification(dave, 'dave', first), { type: 'team_invite' }));
await check('responsável apaga um aviso', true, () => deleteDoc(notification(dave, 'dave', first)));
await check('outra pessoa não apaga avisos', false, async () => {
  let id;
  await env.withSecurityRulesDisabled(async (c) => { id = (await getDocs(notifications(c.firestore(), 'alice'))).docs[0].id; });
  return deleteDoc(notification(dave, 'alice', id));
});

await env.cleanup();
