// Uji batas keamanan metadata OTA tanpa jaringan/bridge Android nyata.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const assetsDir = path.join(__dirname, '../app/src/main/assets');
const html = fs.readFileSync(path.join(assetsDir, 'index.html'), 'utf8');
for (const [, script] of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)) new vm.Script(script);

// Hardening aset: halaman ber-bridge tidak boleh memuat jalur frame/eksekusi dinamis.
// CSP frame-src/object-src tidak menjangkau iframe srcdoc, jadi keberadaannya dijaga di sini.
const csp = html.match(/<meta http-equiv="Content-Security-Policy" content="([^"]+)"/i);
assert.ok(csp, 'CSP meta must be present');
for (const directive of ["frame-src 'none'", "object-src 'none'", "base-uri 'none'", "form-action 'none'"]) {
    assert.ok(csp[1].includes(directive), `CSP must contain ${directive}`);
}
const forbiddenAssetPatterns = [
    /<iframe\b/i,
    /\bsrcdoc\s*=/i,
    /\bnew\s+Worker\b/,
    /\bnew\s+SharedWorker\b/,
    /serviceWorker\s*\.\s*register/,
    /(^|[^.\w$])eval\s*\(/,
    /\bnew\s+Function\b/,
    /document\s*\.\s*write\s*\(/
];
for (const file of ['index.html', 'sync-secure.js', 'sync-transport.js']) {
    const text = fs.readFileSync(path.join(assetsDir, file), 'utf8');
    for (const pattern of forbiddenAssetPatterns) {
        assert.ok(!pattern.test(text), `${file} must not contain ${pattern}`);
    }
}
const start = html.indexOf('        const BUILTIN_GITHUB_REPO =');
const end = html.indexOf("        window.addEventListener('DOMContentLoaded'", start);
assert.ok(start > 0 && end > start, 'OTA section must be present');
const source = html.slice(start, end);
const repo = 'rakamartha/app-headunit';
const url = `https://github.com/${repo}/releases/download/v1.2-b11/PangLink-Companion-App.apk`;
const hash = 'a1'.repeat(32);

function sandbox() {
    const stored = new Map();
    const calls = [];
    const toasts = [];
    const nodes = new Map();
    const context = vm.createContext({
        window: { AndroidBridge: {
            getOtaRepository: () => repo,
            getAppVersion: () => '1.2',
            getAppVersionCode: () => 10,
            startUpdateDownloadWithHash: (...args) => calls.push(args),
            startUpdateDownload: () => { throw Error('Unverified legacy download must not run'); }
        } },
        navigator: { onLine: true },
        document: { getElementById: id => {
            if (!nodes.has(id)) nodes.set(id, { style: {}, classList: { add() {}, remove() {}, toggle() {} } });
            return nodes.get(id);
        } },
        localStorage: {
            getItem: key => stored.get(key) ?? null,
            setItem: (key, value) => stored.set(key, value),
            removeItem: key => stored.delete(key)
        },
        fetch: () => { throw Error('Unexpected network request'); },
        showToast: text => toasts.push(text),
        setTimeout: () => {},
        console
    });
    vm.runInContext(source, context);
    return { context, calls, stored, toasts, run: code => vm.runInContext(code, context) };
}

async function main() {
    {
        const s = sandbox();
        assert.equal(s.run(`isValidApkDownloadUrl(${JSON.stringify(url)})`), true);
        for (const bad of [
            url.replace('https:', 'http:'), url.replace(repo, 'other/repo'),
            url.replace('github.com', 'github.com.evil.example'),
            url.replace('github.com', 'user@github.com'),
            url.replace('v1.2-b11', '..'), url.replace('v1.2-b11', '%2e%2e'),
            url + '?redirect=other', url + '#fragment', url + '\n',
            'https://release-assets.githubusercontent.com/file.apk'
        ]) assert.equal(s.run(`isValidApkDownloadUrl(${JSON.stringify(bad)})`), false, bad);
        s.context.window.AndroidBridge.getOtaRepository = () => 'fork/panglink';
        assert.equal(s.run(`isValidApkDownloadUrl(${JSON.stringify(url)})`), false);
        assert.equal(s.run(`isValidApkDownloadUrl(${JSON.stringify(url.replace(repo, 'fork/panglink'))})`), true);
    }
    {
        const s = sandbox();
        s.run(`pendingOtaApkUrl = ${JSON.stringify(url)}; startOtaDownload()`);
        assert.equal(s.calls.length, 0, 'Missing checksum must not reach the native downloader');
        s.run(`pendingOtaSha256 = '${hash}'; startOtaDownload()`);
        assert.deepEqual(s.calls, [[url, hash]]);
    }
    {
        const s = sandbox();
        const release = { assets: [{ name: 'PangLink-Companion-App.apk', browser_download_url: url }] };
        s.context.fixture = release;
        s.context.fetch = async requested => {
            assert.equal(requested, url + '.sha256');
            return { ok: true, text: async () => hash + '  dist_apk/PangLink-Companion-App.apk\n' };
        };
        release.assets.push({ name: 'PangLink-Companion-App.apk.sha256', browser_download_url: url + '.sha256' });
        assert.equal(await s.run('fetchOtaChecksum(fixture)'), hash);
        release.assets[1].browser_download_url = 'https://example.com/untrusted.sha256';
        assert.equal(await s.run('fetchOtaChecksum(fixture)'), '');
        release.assets[0].digest = 'sha256:' + hash;
        assert.equal(await s.run('fetchOtaChecksum(fixture)'), hash);
    }
    {
        const s = sandbox();
        const release = { tag_name: 'v1.2-b11', assets: [{ name: 'PangLink-Companion-App.apk', browser_download_url: url }] };
        s.context.fetch = async requested => {
            assert.equal(requested, `https://api.github.com/repos/${repo}/releases/latest`);
            return { ok: true, status: 200, json: async () => release };
        };
        await s.run('checkForOtaUpdates(true)');
        assert.equal(s.run('otaBarState'), 'ERROR', 'Release without checksum must not be offered');
        assert.equal(s.run('pendingOtaInfo'), null);
        release.assets[0].digest = 'sha256:' + hash;
        await s.run('checkForOtaUpdates(true)');
        assert.equal(s.run('otaBarState'), 'UPDATE');
        assert.equal(s.run('pendingOtaSha256'), hash);
        release.tag_name = 'v1.2-b10';
        await s.run('checkForOtaUpdates(true)');
        assert.equal(s.run('otaBarState'), 'LATEST', 'Same version must not be offered');
    }
    {
        const s = sandbox();
        s.stored.set('gc_hp_last_ota_check_v1', String(Date.now()));
        s.stored.set('gc_hp_ota_release_v1', JSON.stringify({ version: '1.2', versionCode: 11, apkUrl: url }));
        s.run('scheduleAutoOtaCheck()');
        assert.equal(s.run('otaBarState'), 'IDLE');
        assert.equal(s.stored.has('gc_hp_ota_release_v1'), false, 'Legacy cache without checksum must be discarded');
    }
    console.log('PASS: inline JS syntax, asset hardening, and 5 OTA security scenarios');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
