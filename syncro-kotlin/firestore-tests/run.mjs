// Executa todas as suítes em sequência. Requer o emulador do Firestore
// (veja README.md).
import { summary } from './harness.mjs';

for (const suite of ['./teams.test.mjs', './tasks.test.mjs', './notifications.test.mjs']) {
  console.log(`\n# ${suite}`);
  await import(suite);
}
process.exit(summary() === 0 ? 0 : 1);
