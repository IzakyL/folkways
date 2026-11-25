import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../../', import.meta.url));
const args = process.argv.slice(2);
let count = 3;
if (args[0] === '--shards') {
    count = Number(args.splice(0, 2)[1]);
}
if (!Number.isSafeInteger(count) || count < 1 || count > 64
    || args.some(arg => !arg.startsWith('-P') || /^-P(suite|listTests|gameTestShard|gameTestDirectory)=/.test(arg))) {
    throw new Error('Usage: npm run test:airline:shards -- [--shards N] [-Pproperty=value ...]');
}

function run(command, options) {
    return new Promise((resolve, reject) => {
        const child = spawn(command[0], command.slice(1), options);
        children.add(child);
        if (options.stdio !== 'inherit') {
            console.log(`PROCESS pid=${child.pid} directory=${options.cwd}`);
        }
        child.once('error', reject);
        child.once('close', (code, signal) => {
            children.delete(child);
            resolve({ code, signal });
        });
    });
}

const children = new Set();
for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
        for (const child of children) {
            child.kill(signal);
        }
        process.exitCode = 1;
    });
}

await mkdir(path.join(root, 'build/airline-shards'), { recursive: true });
const directory = await mkdtemp(path.join(root, 'build/airline-shards/run-'));
console.log(`Logs: ${directory}`);
const prepared = await run(['./gradlew', '--offline', '--console=plain', '-Psuite=airline', ...args,
    'exportAirlineGameTestLaunch'], { cwd: root, stdio: 'inherit' });
if (prepared.code !== 0) {
    process.exit(1);
}
const launch = JSON.parse(await readFile(path.join(root, 'build/airline-gametest-launch.json'), 'utf8'));
// Each server uses about 6 GiB including native memory. Leave 2 GiB for the host, and run fewer shards
// rather than waiting forever when other processes hold memory.
const memory = await readFile('/proc/meminfo', 'utf8');
const availableGiB = Number(memory.match(/^MemAvailable:\s+(\d+)/m)[1]) / 1024 ** 2;
const fitting = Math.max(1, Math.floor((availableGiB - 2) / 6));
if (fitting < count) {
    console.log(`Only ${availableGiB.toFixed(1)} GiB available: running ${fitting} shards instead of ${count}`);
    count = fitting;
}
const results = await Promise.all(Array.from({ length: count }, async (_, index) => {
    const world = path.join(directory, `server-${index}`);
    await mkdir(world);
    await writeFile(path.join(world, 'eula.txt'), 'eula=true\n');
    const logPath = path.join(directory, `shard-${index}.log`);
    const log = createWriteStream(logPath);
    await new Promise((resolve, reject) => {
        log.once('open', resolve);
        log.once('error', reject);
    });
    const start = new Date();
    console.log(`START shard=${index}/${count} time=${start.toISOString()}`);
    const command = [...launch.command];
    command.splice(command.indexOf('-cp'), 0, `-Dfolkways.testShard=${index}/${count}`);
    const result = await run(command, { cwd: world, env: { ...process.env, ...launch.environment },
        stdio: ['ignore', log, log] });
    await new Promise(resolve => log.end(resolve));
    const end = new Date();
    let serverLog = '';
    try {
        await copyFile(path.join(world, 'logs/latest.log'), path.join(directory, `shard-${index}-latest.log`));
        serverLog = await readFile(path.join(world, 'logs/latest.log'), 'utf8');
    } catch (error) {
        if (error.code !== 'ENOENT') {
            throw error;
        }
    }
    const selection = serverLog.match(/GAMETEST_SHARD shard=\S+ batches=(\d+) tests=(\d+) weight=(\d+)/);
    const passed = result.code === 0 && (/All [0-9]+ required tests passed/.test(serverLog)
        || selection !== null && Number(selection[2]) === 0);
    const summary = { batches: selection ? Number(selection[1]) : null,
        tests: selection ? Number(selection[2]) : null, weight: selection ? Number(selection[3]) : null,
        shard: `${index}/${count}`, passed, ...result, start: start.toISOString(),
        end: end.toISOString(), seconds: (end - start) / 1000, log: logPath };
    console.log(`END ${JSON.stringify(summary)}`);
    await rm(world, { recursive: true, force: true });
    return summary;
}));
await writeFile(path.join(directory, 'summary.json'), JSON.stringify(results, null, 2) + '\n');
process.exitCode = results.every(result => result.passed) ? 0 : 1;
