// Tarefas, responsáveis, comentários e histórico (FirebaseTaskRepository).
import {
  doc, collection, getDoc, getDocs, setDoc, updateDoc, deleteDoc, runTransaction,
  serverTimestamp, writeBatch, query, where, orderBy,
} from 'firebase/firestore';
import { setup, check } from './harness.mjs';

const env = await setup('demo-syncro-tasks');

const ctx = (uid) => env.authenticatedContext(uid, { email: `${uid}@example.com` }).firestore();
const alice = ctx('alice'); // criadora
const carol = ctx('carol'); // administradora
const dave = ctx('dave');   // membro, responsável pela tarefa t1
const frank = ctx('frank'); // membro sem tarefas atribuídas
const eve = ctx('eve');     // estranha

const names = { alice: 'Alice', carol: 'Carol', dave: 'Dave', frank: 'Frank', eve: 'Eve' };

// Equipe pronta (gravada sem regras, já que a criação foi testada à parte).
const teamId = 'team1';
await env.withSecurityRulesDisabled(async (c) => {
  const ids = ['alice', 'carol', 'dave', 'frank'];
  await setDoc(doc(c.firestore(), 'teams', teamId), {
    name: 'Equipe', description: '', code: 'TEAM2345', createdBy: 'alice',
    memberIds: ids, adminIds: ['carol'],
    memberNames: Object.fromEntries(ids.map((id) => [id, names[id]])),
    memberEmails: Object.fromEntries(ids.map((id) => [id, `${id}@example.com`])),
    invitedEmails: [], createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
  });
  await setDoc(doc(c.firestore(), 'teamCodes', 'TEAM2345'), {
    teamId, name: 'Equipe', description: '', createdAt: serverTimestamp(),
  });
});

const tasks = (db) => collection(db, 'teams', teamId, 'tasks');
const task = (db, id) => doc(db, 'teams', teamId, 'tasks', id);
const comments = (db, t) => collection(db, 'teams', teamId, 'tasks', t, 'comments');
const comment = (db, t, c) => doc(db, 'teams', teamId, 'tasks', t, 'comments', c);
const history = (db) => collection(db, 'teams', teamId, 'history');

// --- Mesmas operações do FirebaseTaskRepository: cada alteração grava o histórico no mesmo lote ---
function event(db, uid, type, o = {}) {
  return {
    type, taskId: o.taskId, taskTitle: o.taskTitle ?? 'Planejar sprint', commentId: o.commentId ?? '',
    field: o.field ?? '', oldValue: o.oldValue ?? '', newValue: o.newValue ?? '',
    userId: o.userId ?? uid, userName: names[uid], createdAt: serverTimestamp(),
  };
}
const responsibles = (ids) => ({ responsibleIds: ids, responsibleNames: Object.fromEntries(ids.map((id) => [id, names[id]])) });
const newTask = (uid, o = {}) => ({
  title: 'Planejar sprint', description: 'Definir prioridades', ...responsibles(['dave']),
  dueDate: '2030-01-15', status: 'pending', createdBy: uid,
  createdAt: serverTimestamp(), updatedAt: serverTimestamp(), ...o,
});
async function createTask(db, uid, o = {}, e = {}) {
  const ref = doc(tasks(db));
  const data = newTask(uid, o);
  const b = writeBatch(db);
  b.set(ref, data);
  if (!e.skip) b.set(doc(history(db)), event(db, uid, 'task_created', { taskId: ref.id, taskTitle: data.title, ...e }));
  await b.commit();
  return ref.id;
}
function setStatus(db, uid, id, from, to, e = {}) {
  const b = writeBatch(db);
  b.update(task(db, id), { status: to, updatedAt: serverTimestamp() });
  b.set(doc(history(db)), event(db, uid, 'task_status', { taskId: id, field: 'status', oldValue: from, newValue: to, ...e }));
  return b.commit();
}
// changes: [{ field, old, new }] -> um registro por campo alterado.
function editTask(db, uid, id, data, changes) {
  const b = writeBatch(db);
  b.update(task(db, id), { ...data, updatedAt: serverTimestamp() });
  for (const c of changes) {
    b.set(doc(history(db)), event(db, uid, 'task_updated', { taskId: id, field: c.field, oldValue: c.old, newValue: c.new }));
  }
  return b.commit();
}
async function deleteTask(db, uid, id, title) {
  const refs = (await getDocs(comments(db, id))).docs.map((d) => d.ref);
  for (let i = 0; i < refs.length; i += 10) {
    const b = writeBatch(db);
    refs.slice(i, i + 10).forEach((r) => b.delete(r));
    await b.commit();
  }
  const b = writeBatch(db);
  b.delete(task(db, id));
  b.set(doc(history(db)), event(db, uid, 'task_deleted', { taskId: id, taskTitle: title }));
  return b.commit();
}
const newComment = (uid, o = {}) => ({
  text: 'Comentário', history: [], authorId: uid, authorName: names[uid],
  createdAt: serverTimestamp(), updatedAt: serverTimestamp(), ...o,
});
async function addComment(db, uid, t, o = {}) {
  const ref = doc(comments(db, t));
  const data = newComment(uid, o);
  const b = writeBatch(db);
  b.set(ref, data);
  b.set(doc(history(db)), event(db, uid, 'comment_created', { taskId: t, commentId: ref.id, newValue: data.text }));
  await b.commit();
  return ref.id;
}
const editComment = (db, uid, t, c, text, o = {}) => runTransaction(db, async (tx) => {
  const ref = comment(db, t, c);
  const snap = await tx.get(ref);
  tx.update(ref, {
    text,
    history: o.history ?? [...snap.get('history'), { text: snap.get('text'), editedAt: snap.get('updatedAt') }],
    updatedAt: serverTimestamp(),
  });
  tx.set(doc(history(db)), event(db, uid, 'comment_updated', {
    taskId: t, commentId: c, field: 'text', oldValue: snap.get('text'), newValue: text,
  }));
});
const removeComment = (db, uid, t, c) => runTransaction(db, async (tx) => {
  const ref = comment(db, t, c);
  const snap = await tx.get(ref);
  tx.delete(ref);
  tx.set(doc(history(db)), event(db, uid, 'comment_deleted', { taskId: t, commentId: c, oldValue: snap.get('text') }));
});

// --- Criar tarefa (UC12) e atribuir responsáveis (UC14) ---
let t1, t2;
await check('criadora cria tarefa para um membro', true, async () => { t1 = await createTask(alice, 'alice'); });
await check('admin cria tarefa com vários responsáveis', true, async () => {
  t2 = await createTask(carol, 'carol', responsibles(['dave', 'frank']));
});
await check('membro comum não cria', false, () => createTask(dave, 'dave'));
await check('estranha não cria', false, () => createTask(eve, 'eve'));
await check('sem responsáveis é recusado', false, () => createTask(alice, 'alice', responsibles([])));
await check('responsável de fora da equipe é recusado', false, () => createTask(alice, 'alice', responsibles(['eve'])));
await check('nomes que não batem com os responsáveis são recusados', false, () => createTask(alice, 'alice', { responsibleIds: ['dave'], responsibleNames: { frank: 'Frank' } }));
await check('responsável repetido é recusado', false, () => createTask(alice, 'alice', { responsibleIds: ['dave', 'dave'], responsibleNames: { dave: 'Dave' } }));
await check('não cria já em andamento', false, () => createTask(alice, 'alice', { status: 'in_progress' }));
await check('não cria em nome de outro', false, () => createTask(alice, 'carol'));
await check('título vazio é recusado', false, () => createTask(alice, 'alice', { title: '' }));
await check('título com 101 caracteres é recusado', false, () => createTask(alice, 'alice', { title: 'a'.repeat(101) }));
await check('descrição vazia é recusada', false, () => createTask(alice, 'alice', { description: '' }));
await check('data fora do formato é recusada', false, () => createTask(alice, 'alice', { dueDate: '15/01/2030' }));
await check('campo extra é recusado', false, () => createTask(alice, 'alice', { extra: 1 }));
await check('histórico em nome de outro é recusado', false, () => createTask(alice, 'alice', {}, { userId: 'carol' }));
await check('registro de criação sem a tarefa é recusado', false, () => addDocTo(history(alice), event(alice, 'alice', 'task_created', { taskId: 'nao-existe' })));
await check('registro de criação de tarefa que já existia é recusado', false, () => addDocTo(history(alice), event(alice, 'alice', 'task_created', { taskId: t1 })));

async function addDocTo(col, data) {
  await setDoc(doc(col), data);
}

// --- Ler ---
await check('membro lista tarefas', true, () => getDocs(tasks(dave)));
await check('estranha não lista tarefas', false, () => getDocs(tasks(eve)));
await check('membro lê o histórico', true, () => getDocs(query(history(dave), orderBy('createdAt', 'desc'))));
await check('membro lê o histórico de uma tarefa', true, () => getDocs(query(history(dave), where('taskId', '==', t1))));
await check('estranha não lê o histórico', false, () => getDocs(history(eve)));

// --- Situação (UC15): só responsáveis e administradores ---
await check('membro que não é responsável não inicia', false, () => setStatus(frank, 'frank', t1, 'pending', 'in_progress'));
await check('histórico com situação anterior errada é recusado', false, () => setStatus(dave, 'dave', t1, 'in_progress', 'in_progress'));
await check('responsável inicia a tarefa', true, () => setStatus(dave, 'dave', t1, 'pending', 'in_progress'));
await check('não volta para pendente', false, () => setStatus(dave, 'dave', t1, 'in_progress', 'pending'));
await check('não regrava a mesma situação', false, () => setStatus(dave, 'dave', t1, 'in_progress', 'in_progress'));
await check('não pula de pendente para finalizada', false, () => setStatus(carol, 'carol', t2, 'pending', 'finished'));
await check('admin inicia tarefa de outro responsável', true, () => setStatus(carol, 'carol', t2, 'pending', 'in_progress'));
await check('estranha não muda a situação', false, () => setStatus(eve, 'eve', t2, 'in_progress', 'finished'));

// --- Editar (UC12/UC14) ---
await check('membro comum não edita', false, () => editTask(dave, 'dave', t2, { title: 'X' }, [{ field: 'title', old: 'Planejar sprint', new: 'X' }]));
await check('admin edita título e responsáveis', true, () => editTask(carol, 'carol', t2,
  { title: 'Novo título', ...responsibles(['frank']) },
  [{ field: 'title', old: 'Planejar sprint', new: 'Novo título' }, { field: 'responsibles', old: 'Dave, Frank', new: 'Frank' }]));
await check('histórico com valor anterior errado é recusado', false, () => editTask(carol, 'carol', t2,
  { description: 'Outra' }, [{ field: 'description', old: 'Inventado', new: 'Outra' }]));
await check('admin não atribui a quem não é membro', false, () => editTask(carol, 'carol', t2, responsibles(['eve']), [{ field: 'responsibles', old: 'Frank', new: 'Eve' }]));
await check('não edita e muda a situação juntos', false, () => editTask(carol, 'carol', t2, { title: 'Y', status: 'finished' }, [{ field: 'title', old: 'Novo título', new: 'Y' }]));
await check('não muda o autor', false, () => editTask(carol, 'carol', t2, { createdBy: 'dave' }, []));
await check('registro de alteração sem a alteração é recusado', false, () => addDocTo(history(carol),
  event(carol, 'carol', 'task_updated', { taskId: t2, field: 'title', oldValue: 'Novo título', newValue: 'Falso' })));
await check('admin muda o prazo', true, () => editTask(carol, 'carol', t2, { dueDate: '2030-03-01' }, [{ field: 'dueDate', old: '2030-01-15', new: '2030-03-01' }]));

// --- Comentários (UC16) ---
let c1, c2;
await check('membro comenta', true, async () => { c1 = await addComment(dave, 'dave', t1); });
await check('admin comenta', true, async () => { c2 = await addComment(carol, 'carol', t1); });
await check('estranha não comenta', false, () => addComment(eve, 'eve', t1));
await check('não comenta em nome de outro', false, () => addComment(dave, 'dave', t1, { authorId: 'carol' }));
await check('não cria com histórico', false, () => addComment(dave, 'dave', t1, { history: [{ text: 'x', editedAt: null }] }));
await check('comentário em branco é recusado', false, () => addComment(dave, 'dave', t1, { text: '   ' }));
await check('membro lê comentários em ordem', true, () => getDocs(query(comments(dave, t1), orderBy('createdAt'))));
await check('estranha não lê comentários', false, () => getDocs(comments(eve, t1)));
await check('autor edita o próprio comentário', true, () => editComment(dave, 'dave', t1, c1, 'Editado'));
await check('autor edita de novo (histórico cresce)', true, () => editComment(dave, 'dave', t1, c1, 'Editado 2'));
await check('histórico adulterado é recusado', false, () => editComment(dave, 'dave', t1, c1, 'X', { history: [] }));
await check('texto igual não conta como edição', false, () => editComment(dave, 'dave', t1, c1, 'Editado 2'));
await check('membro não edita comentário de outro', false, () => editComment(dave, 'dave', t1, c2, 'Hack'));
await check('admin não edita comentário de outro', false, () => editComment(carol, 'carol', t1, c1, 'Moderado'));
await check('admin edita o próprio comentário', true, () => editComment(carol, 'carol', t1, c2, 'Editado pela admin'));
await check('versões anteriores guardadas em ordem, com data', true, () => env.withSecurityRulesDisabled(async (c) => {
  const versions = (await getDoc(comment(c.firestore(), t1, c1))).get('history');
  const texts = versions.map((v) => v.text);
  if (JSON.stringify(texts) !== JSON.stringify(['Comentário', 'Editado'])) throw new Error(JSON.stringify(texts));
  if (!versions.every((v) => v.editedAt?.toDate)) throw new Error('versão sem data');
}));
await check('membro não apaga comentário de outro', false, () => removeComment(dave, 'dave', t1, c2));
let c3;
await check('membro comenta de novo', true, async () => { c3 = await addComment(dave, 'dave', t1); });
await check('registro de exclusão sem excluir é recusado', false, () => addDocTo(history(dave),
  event(dave, 'dave', 'comment_deleted', { taskId: t1, commentId: c3, oldValue: 'Comentário' })));
await check('autor apaga o próprio comentário', true, () => removeComment(dave, 'dave', t1, c3));
await check('admin apaga comentário de outro', true, () => removeComment(carol, 'carol', t1, c2));

// --- Histórico não muda ---
await check('histórico registrou cada alteração', true, () => env.withSecurityRulesDisabled(async (c) => {
  const types = (await getDocs(query(collection(c.firestore(), 'teams', teamId, 'history'), where('taskId', '==', t1))))
    .docs.map((d) => d.get('type')).sort();
  const expected = ['comment_created', 'comment_created', 'comment_created', 'comment_deleted', 'comment_deleted',
    'comment_updated', 'comment_updated', 'comment_updated', 'task_created', 'task_status'];
  if (JSON.stringify(types) !== JSON.stringify(expected)) throw new Error(JSON.stringify(types));
}));
const anyEvent = async (db) => (await getDocs(history(db))).docs[0].id;
await check('ninguém altera um registro', false, async () => updateDoc(doc(history(alice), await anyEvent(alice)), { newValue: 'X' }));
await check('admin não apaga registros', false, async () => deleteDoc(doc(history(carol), await anyEvent(carol))));

// --- Tarefa finalizada ---
await check('responsável finaliza', true, () => setStatus(dave, 'dave', t1, 'in_progress', 'finished'));
await check('não reabre tarefa finalizada', false, () => setStatus(dave, 'dave', t1, 'finished', 'in_progress'));
await check('admin não edita tarefa finalizada', false, () => editTask(carol, 'carol', t1, { title: 'Depois' }, [{ field: 'title', old: 'Planejar sprint', new: 'Depois' }]));
await check('não comenta em tarefa finalizada', false, () => addComment(dave, 'dave', t1));
await check('autor não edita comentário de tarefa finalizada', false, () => editComment(dave, 'dave', t1, c1, 'Tarde'));

// --- Apagar tarefa ---
await check('membro comum não apaga tarefa', false, () => deleteTask(dave, 'dave', t2, 'Novo título'));
for (let i = 0; i < 12; i++) await addComment(dave, 'dave', t2, { text: `Comentário ${i}` });
await check('admin apaga tarefa com comentários (em lotes)', true, () => deleteTask(carol, 'carol', t2, 'Novo título'));
await check('histórico mantém o registro da tarefa apagada', true, () => getDocs(query(history(alice), where('taskId', '==', t2))).then((s) => {
  if (!s.docs.some((d) => d.get('type') === 'task_deleted')) throw new Error('sem task_deleted');
}));

// --- Apagar equipe leva tarefas, comentários e histórico junto ---
const t3 = await createTask(alice, 'alice');
await addComment(dave, 'dave', t3);
await check('criadora apaga histórico, tarefas, comentários, equipe e código', true, async () => {
  const refs = [];
  for (const t of (await getDocs(tasks(alice))).docs) {
    refs.push(...(await getDocs(comments(alice, t.id))).docs.map((d) => d.ref), t.ref);
  }
  refs.push(...(await getDocs(history(alice))).docs.map((d) => d.ref));
  for (let i = 0; i < refs.length; i += 10) {
    const b = writeBatch(alice);
    refs.slice(i, i + 10).forEach((r) => b.delete(r));
    await b.commit();
  }
  const b = writeBatch(alice);
  b.delete(doc(alice, 'teams', teamId));
  b.delete(doc(alice, 'teamCodes', 'TEAM2345'));
  return b.commit();
});
await check('nada sobrou da equipe', true, () => env.withSecurityRulesDisabled(async (c) => {
  const db = c.firestore();
  const left = (await getDocs(collection(db, 'teams', teamId, 'tasks'))).size
    + (await getDocs(collection(db, 'teams', teamId, 'history'))).size;
  if (left !== 0) throw new Error(`${left} documento(s)`);
}));

await env.cleanup();
