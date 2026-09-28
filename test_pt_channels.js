const fs = require('fs');
const https = require('https');

function fetchJson(url) {
    return new Promise((resolve, reject) => {
        https.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' } }, res => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try { resolve(JSON.parse(data)); } catch (e) { resolve(null); }
            });
        }).on('error', reject);
    });
}

function checkUrl(url, referer) {
    return new Promise(resolve => {
        try {
            const u = new URL(url);
            const req = https.request({
                method: 'HEAD',
                hostname: u.hostname,
                path: u.pathname + u.search,
                headers: {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                    'Referer': referer || 'https://dlive.sx/'
                },
                timeout: 5000
            }, res => {
                resolve({ status: res.statusCode, location: res.headers.location });
            });
            req.on('error', err => resolve({ status: 0, error: err.message }));
            req.on('timeout', () => { req.destroy(); resolve({ status: 408 }); });
            req.end();
        } catch (e) {
            resolve({ status: 0, error: e.message });
        }
    });
}

async function run() {
    console.log("=== 1. FETCHING TIMSTREAMS CHANNELS ===");
    const timst = await fetchJson('https://timst.top/api/channels');
    const timstPt = [];
    if (timst && timst.channels) {
        for (const ch of timst.channels) {
            const nameLower = ch.name.toLowerCase();
            const isPt = nameLower.includes('sport tv') || nameLower.includes('portugal') || nameLower.includes('benfica') || nameLower.includes('sic') || nameLower.includes('rtp') || nameLower.includes('tvi') || nameLower.includes('eleven');
            if (isPt) {
                timstPt.push(ch);
            }
        }
    }
    console.log(`Found ${timstPt.length} PT channels on TimStreams:`);
    for (const ch of timstPt) {
        console.log(`- ${ch.name} (Logo: ${ch.logo})`);
        for (const s of (ch.streams || [])) {
            const res = await checkUrl(s.url, 'https://timst.top/');
            console.log(`    Stream: ${s.name} -> ${s.url} => Status ${res.status} ${res.location ? '-> ' + res.location : ''}`);
        }
    }

    console.log("\n=== 2. READING LOCAL DADDYLIVE PT CHANNELS ===");
    const localChannels = JSON.parse(fs.readFileSync('app/src/main/assets/channels.json', 'utf8'));
    const ptChannels = localChannels.filter(c => c.isPt);
    console.log(`Found ${ptChannels.length} PT channels in DaddyLive catalog.`);

    // Sample test first 10 PT channels on DaddyLive stream server
    console.log("\n=== 3. TESTING DADDYLIVE PT STREAMS (SAMPLE) ===");
    for (const c of ptChannels.slice(0, 8)) {
        const streamPage = `https://dlive.sx/stream/stream-${c.id}.php`;
        const res = await checkUrl(streamPage, 'https://dlive.sx/');
        console.log(`- [${c.id}] ${c.name} (${streamPage}) => HTTP ${res.status}`);
    }
}

run();
