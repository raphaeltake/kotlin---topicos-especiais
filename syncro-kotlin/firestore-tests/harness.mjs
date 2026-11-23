// Utilitários comuns aos testes das regras do Firestore.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  initializeTestEnvironment, assertSucceeds, assertFails,
} from '@firebase/rules-unit-testing';

const rules = readFileSync(fileURLToPath(new URL('../firestore.rules', import.meta.url)), 'utf8');

/** Cada suíte usa um projeto próprio, para que os dados de uma não afetem a outra. */
export async function setup(projectId) {
  const [host, port] = (process.env.FIRESTORE_EMULATOR_HOST ?? '127.0.0.1:8080').split(':');
  return initializeTestEnvironment({
    projectId,
    firestore: { rules, host, port: Number(port) },
  });
}

let passed = 0;
let failed = 0;

/** Executa fn e confere se as regras aceitaram (expectOk) ou recusaram a operação. */
export async function check(name, expectOk, fn) {
  try {
    await (expectOk ? assertSucceeds(fn()) : assertFails(fn()));
    passed++;
    console.log(`ok   ${name}`);
  } catch (error) {
    failed++;
    console.log(`FAIL ${name}: ${error.message}`);
  }
}

export function summary() {
  console.log(`\n${passed} ok, ${failed} falha(s)`);
  return failed;
}
