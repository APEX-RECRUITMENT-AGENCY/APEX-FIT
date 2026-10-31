const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const root = path.resolve(__dirname, '..');

function check(name, code, isModule) {
    // Write code to a temp file and check with node --check
    const tmpFile = path.join(root, `temp_check_${Date.now()}.js`);
    fs.writeFileSync(tmpFile, code, 'utf-8');
    try {
        execSync(`node --check "${tmpFile}"`, { stdio: 'pipe' });
        console.log(`OK   ${name}`);
        fs.unlinkSync(tmpFile);
        return true;
    } catch (err) {
        console.log(`FAIL ${name}`);
        console.log(err.stderr ? err.stderr.toString() : err.message);
        if (fs.existsSync(tmpFile)) fs.unlinkSync(tmpFile);
        return false;
    }
}

const html = fs.readFileSync(path.join(root, 'index.html'), 'utf-8');
const scriptMatches = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
if (scriptMatches.length === 0) {
    console.error('No inline script found in index.html');
    process.exit(1);
}
// Last script tag is the main app script
const inlineScript = scriptMatches[scriptMatches.length - 1][1];

let ok = check('index.html (inline script)', inlineScript, false);

const workerCode = fs.readFileSync(path.join(root, 'worker', 'worker.js'), 'utf-8');
ok = check('worker/worker.js', workerCode, true) && ok;

process.exit(ok ? 0 : 1);
