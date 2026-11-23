const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const html = fs.readFileSync(path.join(__dirname, '../Salom.Desktop/CallWeb/call.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function setup({ refuseVideo = false } = {}) {
    const requests = [];
    const messages = [];
    const tracks = [];
    const elements = new Map();
    const context = vm.createContext({
        window: { chrome: { webview: { postMessage: value => messages.push(value) } }, addEventListener() {} },
        document: { getElementById(id) {
            if (!elements.has(id)) elements.set(id, {});
            return elements.get(id);
        } },
        MediaStream: class { addTrack() {} },
        RTCPeerConnection: class {
            addTrack() {}
            addTransceiver() {}
            createOffer() { return { type: 'offer', sdp: 'test' }; }
            setLocalDescription() {}
            close() {}
        },
        navigator: { mediaDevices: { async getUserMedia({ audio, video }) {
            requests.push({ audio, video });
            if (video && refuseVideo) throw Error('Camera denied');
            const current = (video ? ['audio', 'video'] : ['audio']).map(kind => ({
                kind, stopped: false, stop() { this.stopped = true; },
            }));
            tracks.push(...current);
            return { getTracks: () => current, getVideoTracks: () => current.filter(t => t.kind === 'video') };
        } } },
    });
    vm.runInContext(script, context);
    return { context, requests, messages, tracks, elements };
}

test('ordinary calling opens only the microphone, produces one offer and releases tracks', async () => {
    const call = setup();
    await call.context.salomStart('[]', true, false);
    await call.context.salomStart('[]', true, false);
    assert.deepEqual(call.requests, [{ audio: true, video: false }]);
    assert.equal(call.elements.get('camera').hidden, true);
    assert.equal(call.elements.get('remote').hidden, true);
    await Promise.all([call.context.salomCreateOffer(), call.context.salomCreateOffer()]);
    assert.equal(call.messages.filter(m => m.kind === 'offer').length, 1);
    assert.equal(call.context.salomStop(), true);
    assert.equal(call.context.salomStop(), false);
    assert.ok(call.tracks.every(track => track.stopped));
});

test('explicit video can continue with audio when the camera is denied', async () => {
    const call = setup({ refuseVideo: true });
    await call.context.salomStart('[]', true, true);
    assert.deepEqual(call.requests, [{ audio: true, video: true }, { audio: true, video: false }]);
    assert.equal(call.elements.get('camera').hidden, true);
    assert.equal(call.messages.at(-1).type, 'ready');
});
