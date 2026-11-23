// Equipes, códigos de convite e membros (FirebaseTeamRepository).
import {
  doc, collection, getDoc, getDocs, updateDoc, runTransaction, serverTimestamp,
  writeBatch, query, where, arrayUnion, arrayRemove, deleteField, FieldPath,
} from 'firebase/firestore';
import { setup, check } from './harness.mjs';

const env = await setup('demo-syncro-teams');

const ctx = (uid, email) => env.authenticatedContext(uid, { email }).firestore();
const alice = ctx('alice', 'alice@example.com');   // criadora
const bob = ctx('bob', 'Bob@Example.com');         // convidado (e-mail com maiúsculas no token)
const carol = ctx('carol', 'carol@example.com');   // entra por código, vira admin
const dave = ctx('dave', 'dave@example.com');      // entra por código, membro comum
const erin = ctx('erin', 'erin@example.com');      // convidada que recusa
const eve = ctx('eve', 'eve@example.com');         // estranha
const anon = env.unauthenticatedContext().firestore();

// --- Mesmas operações do FirebaseTeamRepository ---
function createTeam(db, uid, email, o = {}) {
  const teamRef = doc(collection(db, 'teams'));
  const code = o.code ?? 'ABCD2345';
  const name = o.name ?? 'Equipe Teste';
  const description = o.description ?? '';
  return runTransaction(db, async (tx) => {
    const codeSnap = await tx.get(doc(db, 'teamCodes', code));
    if (codeSnap.exists() && !o.ignoreCollision) throw new Error('collision');
    const now = serverTimestamp();
    tx.set(teamRef, {
      name, description, code, createdBy: uid,
      memberIds: o.memberIds ?? [uid], adminIds: o.adminIds ?? [],
      memberNames: o.memberNames ?? { [uid]: 'Nome' },
      memberEmails: o.memberEmails ?? { [uid]: email },
      invitedEmails: o.invitedEmails ?? [],
      createdAt: now, updatedAt: now,
    });
    if (!o.skipCode) {
      tx.set(doc(db, 'teamCodes', code), {
        teamId: teamRef.id, name: o.codeName ?? name, description, createdAt: now,
      });
    }
  }).then(() => teamRef.id);
}
const team = (db, id) => doc(db, 'teams', id);
const code = (db, c) => doc(db, 'teamCodes', c);
const editTeam = (db, id, c, name, description, o = {}) => {
  const b = writeBatch(db);
  b.update(team(db, id), { name, description, updatedAt: serverTimestamp() });
  if (!o.skipCode) b.update(code(db, c), { name: o.codeName ?? name, description });
  return b.commit();
};
const join = (db, id, uid, email, name = 'Novo', o = {}) => updateDoc(team(db, id),
  'memberIds', arrayUnion(uid),
  new FieldPath('memberNames', uid), name,
  new FieldPath('memberEmails', uid), o.storedEmail ?? email.toLowerCase(),
  'invitedEmails', arrayRemove(email.toLowerCase()));
const removeUser = (db, id, uid) => updateDoc(team(db, id),
  'memberIds', arrayRemove(uid), 'adminIds', arrayRemove(uid),
  new FieldPath('memberNames', uid), deleteField(),
  new FieldPath('memberEmails', uid), deleteField());
const setAdmin = (db, id, uid, admin) => updateDoc(team(db, id),
  'adminIds', admin ? arrayUnion(uid) : arrayRemove(uid));
const updateSelf = (db, id, uid, name, email) => updateDoc(team(db, id),
  new FieldPath('memberNames', uid), name, new FieldPath('memberEmails', uid), email);

// --- Criação (UC04) ---
let id;
await check('cria equipe com nome e descrição', true, async () => {
  id = await createTeam(alice, 'alice', 'alice@example.com', { code: 'TEAM2345', description: 'Desc' });
});
await check('código repetido é recusado', false, () => createTeam(eve, 'eve', 'eve@example.com', { code: 'TEAM2345', ignoreCollision: true }));
await check('sem login é recusado', false, () => createTeam(anon, 'x', 'x@example.com', { code: 'ANON2345' }));
await check('sem reservar código é recusado', false, () => createTeam(alice, 'alice', 'alice@example.com', { code: 'NOCD2345', skipCode: true }));
await check('código com nome diferente do da equipe é recusado', false, () => createTeam(alice, 'alice', 'alice@example.com', { code: 'DIFN2345', codeName: 'Outro' }));
await check('criar com outro membro é recusado', false, () => createTeam(alice, 'alice', 'alice@example.com', {
  code: 'MIDS2345', memberIds: ['alice', 'bob'], memberNames: { alice: 'A', bob: 'B' },
  memberEmails: { alice: 'alice@example.com', bob: 'bob@example.com' },
}));
await check('criar já com admin é recusado', false, () => createTeam(alice, 'alice', 'alice@example.com', { code: 'ADMN2345', adminIds: ['alice'] }));
await check('criar com convites é recusado', false, () => createTeam(alice, 'alice', 'alice@example.com', { code: 'INVT2345', invitedEmails: ['x@y.z'] }));
await check('memberNames sem o criador é recusado', false, () => createTeam(alice, 'alice', 'alice@example.com', { code: 'NAME2345', memberNames: { bob: 'B' } }));
await check('e-mail diferente do da conta é recusado', false, () => createTeam(alice, 'alice', 'outra@example.com', { code: 'MAIL2345' }));
await check('nome com espaços nas pontas é recusado', false, () => createTeam(alice, 'alice', 'alice@example.com', { code: 'SPAC2345', name: ' X ' }));
await check('nome com 101 caracteres é recusado', false, () => createTeam(alice, 'alice', 'alice@example.com', { code: 'LONG2345', name: 'a'.repeat(101) }));
await check('nome com 100 caracteres é aceito', true, () => createTeam(alice, 'alice', 'alice@example.com', { code: 'MAXN2345', name: 'a'.repeat(100) }));
await check('código fora do formato é recusado', false, () => createTeam(alice, 'alice', 'alice@example.com', { code: 'abc' }));

// --- Leitura ---
await check('membro lê a equipe', true, () => getDoc(team(alice, id)));
await check('lista "minhas equipes"', true, () => getDocs(query(collection(alice, 'teams'), where('memberIds', 'array-contains', 'alice'))));
await check('estranho não lê a equipe', false, () => getDoc(team(eve, id)));
await check('estranho não lista equipes dos outros', false, () => getDocs(query(collection(eve, 'teams'), where('memberIds', 'array-contains', 'alice'))));
await check('consulta o código exato (prévia com nome e descrição)', true, () => getDoc(code(eve, 'TEAM2345')));
await check('não lista códigos', false, () => getDocs(collection(eve, 'teamCodes')));

// --- Editar (só a criadora, UC04) ---
await check('criadora edita nome e descrição (e a prévia do código)', true, () => editTeam(alice, id, 'TEAM2345', 'Equipe X', 'Nova desc'));
await check('editar sem atualizar a prévia do código é recusado', false, () => editTeam(alice, id, 'TEAM2345', 'Equipe Y', 'Nova desc', { skipCode: true }));
await check('prévia diferente do nome é recusada', false, () => editTeam(alice, id, 'TEAM2345', 'Equipe Y', 'Nova desc', { codeName: 'Outro' }));
await check('estranho não edita', false, () => editTeam(eve, id, 'TEAM2345', 'Hack', ''));
await check('estranho não muda a prévia do código', false, () => updateDoc(code(eve, 'TEAM2345'), { name: 'Hack' }));
await check('não muda o código', false, () => updateDoc(team(alice, id), { code: 'ZZZZ2345' }));
await check('não muda a equipe apontada pelo código', false, () => updateDoc(code(alice, 'TEAM2345'), { teamId: 'outra' }));
await check('descrição com 1001 caracteres é recusada', false, () => editTeam(alice, id, 'TEAM2345', 'Equipe X', 'a'.repeat(1001)));

// --- Convites (UC08) ---
await check('criadora convida', true, () => updateDoc(team(alice, id), 'invitedEmails', arrayUnion('bob@example.com', 'erin@example.com')));
await check('convidar quem já é membro é recusado', false, () => updateDoc(team(alice, id), 'invitedEmails', arrayUnion('alice@example.com')));
await check('convidado lê a equipe', true, () => getDoc(team(bob, id)));
await check('convidado lista seus convites', true, () => getDocs(query(collection(bob, 'teams'), where('invitedEmails', 'array-contains', 'bob@example.com'))));
await check('estranho não lista convites dos outros', false, () => getDocs(query(collection(eve, 'teams'), where('invitedEmails', 'array-contains', 'bob@example.com'))));
await check('estranho não convida', false, () => updateDoc(team(eve, id), 'invitedEmails', arrayUnion('eve2@example.com')));

// --- Entrar (UC06) ---
await check('convidado aceita (entra e remove o convite)', true, () => join(bob, id, 'bob', 'Bob@Example.com', 'Bob'));
await check('membro entra por código', true, () => join(carol, id, 'carol', 'carol@example.com', 'Carol'));
await check('outro membro entra por código', true, () => join(dave, id, 'dave', 'dave@example.com', 'Dave'));
await check('entrar de novo não cria outro vínculo', true, async () => {
  await join(dave, id, 'dave', 'dave@example.com', 'Dave');
  const ids = (await getDoc(team(dave, id))).get('memberIds');
  if (ids.filter((m) => m === 'dave').length !== 1) throw new Error(JSON.stringify(ids));
});
await check('entrar de novo como admin é recusado', false, () => updateDoc(team(dave, id),
  'memberIds', arrayUnion('dave'), 'adminIds', arrayUnion('dave')));
await check('entrar com e-mail de outra pessoa é recusado', false, () => join(eve, id, 'eve', 'eve@example.com', 'Eve', { storedEmail: 'chefe@example.com' }));
await check('entrar já como admin é recusado', false, () => updateDoc(team(eve, id),
  'memberIds', arrayUnion('eve'), 'adminIds', arrayUnion('eve'),
  new FieldPath('memberNames', 'eve'), 'Eve', new FieldPath('memberEmails', 'eve'), 'eve@example.com'));
await check('entrar levando outra pessoa é recusado', false, () => updateDoc(team(eve, id),
  'memberIds', arrayUnion('eve', 'mallory'),
  new FieldPath('memberNames', 'eve'), 'Eve', new FieldPath('memberNames', 'mallory'), 'M',
  new FieldPath('memberEmails', 'eve'), 'eve@example.com', new FieldPath('memberEmails', 'mallory'), 'm@example.com'));
await check('entrar sem nome é recusado', false, () => join(eve, id, 'eve', 'eve@example.com', ''));
await check('entrar apagando convites dos outros é recusado', false, () => updateDoc(team(eve, id),
  'memberIds', arrayUnion('eve'), new FieldPath('memberNames', 'eve'), 'Eve',
  new FieldPath('memberEmails', 'eve'), 'eve@example.com', 'invitedEmails', []));

// --- Papéis (UC10) ---
await check('criadora promove membro a admin', true, () => setAdmin(alice, id, 'carol', true));
await check('admin promove outro membro', true, () => setAdmin(carol, id, 'bob', true));
await check('admin rebaixa outro admin', true, () => setAdmin(carol, id, 'bob', false));
await check('membro comum não se promove', false, () => setAdmin(dave, id, 'dave', true));
await check('admin não põe a criadora em adminIds', false, () => setAdmin(carol, id, 'alice', true));
await check('admin não promove quem não é membro', false, () => setAdmin(carol, id, 'eve', true));
await check('admin não renomeia membros', false, () => updateDoc(team(carol, id), new FieldPath('memberNames', 'dave'), 'X'));
await check('admin não troca o e-mail de membros', false, () => updateDoc(team(carol, id), new FieldPath('memberEmails', 'dave'), 'x@example.com'));
await check('admin não edita nome e descrição (só a criadora)', false, () => editTeam(carol, id, 'TEAM2345', 'Equipe Z', 'Desc'));
await check('admin convida', true, () => updateDoc(team(carol, id), 'invitedEmails', arrayUnion('frank@example.com')));

// --- Remover membros (UC11) ---
await check('membro comum não remove ninguém', false, () => removeUser(dave, id, 'bob'));
await check('admin não remove a criadora', false, () => removeUser(carol, id, 'alice'));
await check('admin remove membro', true, () => removeUser(carol, id, 'bob'));
await check('admin não adiciona membros', false, () => updateDoc(team(carol, id),
  'memberIds', arrayUnion('mallory'), new FieldPath('memberNames', 'mallory'), 'M',
  new FieldPath('memberEmails', 'mallory'), 'm@example.com'));
await check('removido não lê mais a equipe', false, () => getDoc(team(bob, id)));

// --- Sair (UC07) ---
await check('criadora não sai', false, () => removeUser(alice, id, 'alice'));
await check('membro sai', true, () => removeUser(dave, id, 'dave'));

// --- Recusar convite ---
await check('convidada não apaga convites dos outros', false, () => updateDoc(team(erin, id), 'invitedEmails', []));
await check('convidada recusa', true, () => updateDoc(team(erin, id), 'invitedEmails', arrayRemove('erin@example.com')));
await check('após recusar não lê a equipe', false, () => getDoc(team(erin, id)));

// --- Nome e e-mail do perfil ---
await check('membro atualiza o próprio nome', true, () => updateSelf(carol, id, 'carol', 'Carol S.', 'carol@example.com'));
await check('membro não grava e-mail diferente do da conta', false, () => updateSelf(carol, id, 'carol', 'Carol S.', 'falso@example.com'));
await check('membro não muda o nome de outro', false, () => updateDoc(team(carol, id), new FieldPath('memberNames', 'alice'), 'X'));

// --- Apagar ---
const deleteTeam = (db) => {
  const b = writeBatch(db);
  b.delete(team(db, id));
  b.delete(code(db, 'TEAM2345'));
  return b.commit();
};
await check('admin que não é criador não apaga', false, () => deleteTeam(carol));
await check('estranho não apaga o código', false, () => { const b = writeBatch(eve); b.delete(code(eve, 'TEAM2345')); return b.commit(); });
await check('criadora apaga a equipe e o código', true, () => deleteTeam(alice));
await check('equipe e código não existem mais', true, () => env.withSecurityRulesDisabled(async (c) => {
  const t = await getDoc(doc(c.firestore(), 'teams', id));
  const k = await getDoc(doc(c.firestore(), 'teamCodes', 'TEAM2345'));
  if (t.exists() || k.exists()) throw new Error('ainda existem');
}));

await check('perfil próprio continua legível', true, () => getDoc(doc(alice, 'users', 'alice')));
await check('perfil de outro continua bloqueado', false, () => getDoc(doc(alice, 'users', 'bob')));

await env.cleanup();
