const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const html = fs.readFileSync(path.join(__dirname, '../Salom.Desktop/CallWeb/call.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function setup({ refuseVideo = false, rejectIce = false, multipleCameras = false } = {}) {
    const requests = [];
    const messages = [];
    const tracks = [];
    const candidates = [];
    const offers = [];
    const senders = [];
    const timers = new Map();
    let peer, nextTimer = 0;
    const elements = new Map();
    const context = vm.createContext({
        window: { chrome: { webview: { postMessage: value => messages.push(value) } }, addEventListener() {} },
        setTimeout(callback) { const id = ++nextTimer; timers.set(id, callback); return id; },
        clearTimeout(id) { timers.delete(id); },
        setInterval() { return ++nextTimer; },
        clearInterval() {},
        performance: { now: () => 1 },
        document: { getElementById(id) {
            if (!elements.has(id)) elements.set(id, { style: {}, classList: { toggle() {} } });
            return elements.get(id);
        } },
        MediaStream: class { addTrack() {} },
        RTCPeerConnection: class {
            constructor(options) { peer = this; this.options = options; this.signalingState = 'stable'; }
            addTrack(track) {
                const params = { encodings: [{}] };
                const sender = {
                    track, getParameters: () => params,
                    setParameters: settings => { sender.limits = settings; },
                    replaceTrack: replacement => { sender.track = replacement; }
                };
                senders.push(sender);
                return sender;
            }
            addTransceiver() {}
            createOffer(options) { offers.push(options); return { type: 'offer', sdp: 'offer-' + offers.length }; }
            createAnswer() { return { type: 'answer', sdp: 'test' }; }
            setLocalDescription(sdp) { this.signalingState = sdp.type === 'offer' ? 'have-local-offer' : 'stable'; }
            setRemoteDescription(sdp) { this.signalingState = sdp.type === 'offer' ? 'have-remote-offer' : 'stable'; }
            addIceCandidate(ice) {
                if (rejectIce && ice.candidate === 'bad') throw Error('Rejected candidate');
                candidates.push(ice.candidate);
            }
            close() {}
        },
        navigator: { mediaDevices: {
            async enumerateDevices() {
                return multipleCameras ? ['front', 'rear'].map(deviceId => ({kind: 'videoinput', deviceId})) : [];
            },
            async getUserMedia({ audio, video }) {
                requests.push({ audio, video });
                if (video && refuseVideo) throw Error('Camera denied');
                const kinds = [...(audio ? ['audio'] : []), ...(video ? ['video'] : [])];
                const current = kinds.map(kind => ({
                    kind, stopped: false, stop() { this.stopped = true; },
                    getSettings: () => ({deviceId: video.deviceId?.exact ?? 'front'})
                }));
                tracks.push(...current);
                return {
                    getTracks: () => current, getVideoTracks: () => current.filter(t => t.kind === 'video'),
                    addTrack: track => current.push(track), removeTrack: track => current.splice(current.indexOf(track), 1)
                };
            }
        } },
    });
    vm.runInContext(script, context);
    return { context, requests, messages, tracks, elements, candidates, offers, senders, timers, get peer() { return peer; } };
}

test('ordinary calling opens only the microphone, produces one offer and releases tracks', async () => {
    const call = setup();
    await call.context.salomStart('[]', true, false);
    await call.context.salomStart('[]', true, false);
    assert.equal(call.requests.length, 1);
    assert.equal(call.requests[0].video, false);
    assert.equal(call.requests[0].audio.noiseSuppression, true);
    assert.equal(call.requests[0].audio.echoCancellation, true);
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
    assert.equal(call.requests.length, 2);
    assert.equal(call.requests[0].video.width.ideal, 1280);
    assert.equal(call.requests[1].video, false);
    assert.equal(call.elements.get('camera').hidden, true);
    assert.equal(call.messages.at(-1).type, 'ready');
});

test('one rejected early ICE candidate cannot block the answer or later candidates', async () => {
    const call = setup({ rejectIce: true });
    await call.context.salomStart('[]', false, false);
    const receive = (kind, payload) => call.context.salomReceive(JSON.stringify({ kind, payload }));
    const candidate = value => ({ sdp_mid: '0', sdp_mline_index: 0, candidate: value });
    receive('ice', candidate('bad'));
    receive('ice', candidate('queued'));
    receive('offer', { type: 'offer', sdp: 'test' });
    receive('ice', candidate('late'));
    await vm.runInContext('signaling', call.context);
    assert.equal(call.messages.filter(m => m.kind === 'answer').length, 1);
    assert.deepEqual(call.candidates, ['queued', 'late']);
});

test('video limits leave WebRTC in control of adaptation and preserve the supplied TURN config', async () => {
    const call = setup();
    const ice = [{urls: ['turns:turn.cloudflare.com:443?transport=tcp'], username: 'temporary', credential: 'test'}];
    await call.context.salomStart(JSON.stringify(ice), true, true);
    assert.equal(call.requests[0].video.height.ideal, 720);
    assert.equal(call.requests[0].video.frameRate.ideal, 30);
    assert.equal(JSON.stringify(call.peer.options.iceServers), JSON.stringify(ice));
    assert.equal(call.senders[0].limits.encodings[0].priority, 'high');
    assert.equal(call.senders[1].limits.degradationPreference, 'balanced');
    assert.equal(call.senders[1].limits.encodings[0].maxBitrate, 2_000_000);
});

test('a connected caller retries ICE after a drop and accepts a fresh answer', async () => {
    const call = setup();
    await call.context.salomStart('[]', true, false);
    await call.context.salomCreateOffer();
    const answer = sdp => call.context.salomReceive(JSON.stringify({ kind: 'answer', payload: { type: 'answer', sdp } }));
    answer('first');
    await vm.runInContext('signaling', call.context);
    call.peer.connectionState = 'connected';
    call.peer.onconnectionstatechange();
    call.peer.connectionState = 'disconnected';
    call.peer.onconnectionstatechange();
    assert.equal(call.messages.at(-1).value, 'reconnecting');
    const [timer, attempt] = [...call.timers.entries()].at(-1);
    call.timers.delete(timer);
    await attempt();
    assert.equal(call.offers.length, 2);
    assert.equal(call.offers[1].iceRestart, true);
    answer('first');
    answer('restart');
    await vm.runInContext('signaling', call.context);
    assert.equal(call.peer.signalingState, 'stable');
    call.peer.connectionState = 'connected';
    call.peer.onconnectionstatechange();
    assert.equal(call.messages.at(-1).value, 'connected');
    call.context.salomStop();
    assert.equal(call.timers.size, 0);
});

test('switching the camera replaces only the video sender and releases the previous camera', async () => {
    const call = setup({ multipleCameras: true });
    await call.context.salomStart('[]', false, true);
    await call.context.refreshDevices();
    assert.equal(call.elements.get('switch-camera').hidden, false);
    await call.elements.get('switch-camera').onclick();
    assert.equal(call.requests.at(-1).video.deviceId.exact, 'rear');
    assert.equal(call.tracks[0].stopped, false);
    assert.equal(call.tracks[1].stopped, true);
    assert.equal(call.senders[1].track, call.tracks[2]);
    call.context.salomStop();
    assert.ok(call.tracks.every(track => track.stopped));
});
