/**
 * gama-websocket.js — WebSocket client for GAMA headless server
 * Handles: upload, load, step, play, pause, stop, expression evaluation
 */
var GamaWebSocket = (function() {
    var ws = null;
    var port = 6868;
    var callbackQueue = [];
    var connected = false;
    var listeners = {};
    var expId = null;
    var socketId = null;

    function on(event, fn) {
        if (!listeners[event]) listeners[event] = [];
        listeners[event].push(fn);
    }

    function emit(event, data) {
        var fns = listeners[event] || [];
        for (var i = 0; i < fns.length; i++) { fns[i](data); }
    }

    function connect(callback) {
        if (ws && ws.readyState <= 1) { if (callback) callback(); return; }
        ws = new WebSocket('ws://localhost:' + port);
        ws.onopen = function() {
            connected = true;
            socketId = '' + Math.floor(Math.random() * 1000000);
            emit('connected');
            if (callback) callback();
        };
        ws.onmessage = function(evt) {
            try {
                var msg = JSON.parse(evt.data);
                console.log('[WS] recv:', msg.type, 'queue:', callbackQueue.length);

                if (msg.type === 'ConnectionSuccessful') {
                    socketId = msg.content || socketId;
                    emit('ready', msg);
                    return;
                }

                // Route command responses to the oldest pending callback
                var isCommandResponse = (
                    msg.type === 'CommandExecutedSuccessfully' ||
                    msg.type === 'MalformedRequest' ||
                    msg.type === 'UnableToExecuteRequest' ||
                    msg.type === 'RuntimeError' ||
                    msg.type === 'GamaServerError'
                );

                if (isCommandResponse) {
                    if (callbackQueue.length > 0) {
                        var cb = callbackQueue.shift();
                        if (msg.type === 'CommandExecutedSuccessfully') {
                            cb(null, msg);
                        } else {
                            cb(msg.content || msg.type);
                        }
                    } else {
                        console.warn('[WS] No callback for', msg.type);
                    }
                    return;
                }

                // Async messages (images, output, etc.)
                emit('message', msg);
                if (msg.type === 'SimulationImage') emit('image', msg);
                else if (msg.type === 'SimulationOutput') emit('output', msg);
                else if (msg.type === 'SimulationEnded') emit('ended', msg);
                else if (msg.type === 'SimulationError') emit('simerror', msg);

            } catch(e) {
                console.error('WS parse error:', e);
            }
        };
        ws.onclose = function() {
            connected = false;
            callbackQueue = [];
            emit('disconnected');
        };
        ws.onerror = function(e) {
            emit('error', e);
        };
    }

    function send(cmd, params, callback) {
        if (!ws || ws.readyState !== 1) {
            console.warn('[WS] Not connected, cmd:', cmd);
            if (callback) callback('Not connected');
            return;
        }
        if (callback) callbackQueue.push(callback);
        var msg = Object.assign({ type: cmd, socket_id: socketId || '' }, params || {});
        console.log('[WS] send:', cmd, 'queue:', callbackQueue.length);
        ws.send(JSON.stringify(msg));
    }

    function upload(filePath, content, cb) {
        send('upload', { file: filePath, content: content }, cb);
    }

    function load(modelPath, experimentName, cb) {
        send('load', { model: modelPath, experiment: experimentName }, function(err, result) {
            if (!err && result) {
                expId = result.content || experimentName;
            }
            if (cb) cb(err, result);
        });
    }

    function step(nbStep, cb) {
        if (!expId) { if (cb) cb('No experiment loaded'); return; }
        send('step', { exp_id: expId, nb_step: nbStep || 1, sync: true }, cb);
    }

    function play(cb) {
        if (!expId) { if (cb) cb('No experiment loaded'); return; }
        send('play', { exp_id: expId }, cb);
    }

    function pause(cb) {
        if (!expId) { if (cb) cb('No experiment loaded'); return; }
        send('pause', { exp_id: expId }, cb);
    }

    function stop(cb) {
        if (!expId) { if (cb) cb('No experiment loaded'); return; }
        send('stop', { exp_id: expId }, cb);
    }

    function ask(agent, action, cb) {
        if (!expId) { if (cb) cb('No experiment loaded'); return; }
        send('ask', { exp_id: expId, agent: agent, action: action }, cb);
    }

    function evaluate(expr, cb) {
        if (!expId) { if (cb) cb('No experiment loaded'); return; }
        send('expression', { exp_id: expId, expr: expr }, function(err, result) {
            if (err) { if (cb) cb(err); return; }
            if (cb) cb(null, result ? result.content : null);
        });
    }

    function describe(cb) {
        send('describe', {}, cb);
    }

    function validate(cb) {
        send('validate', {}, cb);
    }

    function getExpId() { return expId; }
    function isConnected() { return connected; }
    function getSocketId() { return socketId; }

    return {
        connect: connect,
        on: on,
        upload: upload,
        load: load,
        step: step,
        play: play,
        pause: pause,
        stop: stop,
        ask: ask,
        evaluate: evaluate,
        describe: describe,
        validate: validate,
        getExpId: getExpId,
        isConnected: isConnected,
        getSocketId: getSocketId,
        send: send
    };
})();
