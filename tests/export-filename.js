'use strict';

const fs = require('fs');
const path = require('path');
const source = fs.readFileSync(path.join(__dirname, '..', 'apps', 'automation_map.groovy'), 'utf8');

function extractFunction(name) {
    const start = source.indexOf('function ' + name + '(');
    if (start < 0) throw new Error('could not find function ' + name);
    const open = source.indexOf('{', start);
    let depth = 0;
    for (let i = open; i < source.length; i++) {
        if (source[i] === '{') depth++;
        else if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
    }
    throw new Error('unbalanced function ' + name);
}

const exportFilenameHubName = eval('(' + extractFunction('exportFilenameHubName') + ')');
const exportFilenameTimestamp = eval('(' + extractFunction('exportFilenameTimestamp') + ')');

const safe = exportFilenameHubName('Volos: Cove / C8?');
if (safe !== 'Volos Cove C8') throw new Error('unsafe hub-name result: ' + safe);
if (/[<>:"/\\|?*]/.test(safe)) throw new Error('forbidden filename character survived');

const stamp = exportFilenameTimestamp(new Date(2026, 9, 3, 8, 31, 45));
if (stamp !== '03-Oct-2026 at 08-31-45') throw new Error('unexpected timestamp: ' + stamp);

if (!source.includes("a.download = 'HAM Export for ' + exportFilenameHubName(HUB_NAME) + ' on ' + exportFilenameTimestamp(new Date()) + '.txt';")) {
    throw new Error('download filename is not wired to the tested helpers');
}

console.log('3 export filename assertions passed');
