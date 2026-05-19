package com.voting

fun voterHtml() = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>Vote!</title>
    <style>
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            min-height: 100dvh;
            display: flex;
            align-items: center;
            justify-content: center;
            background: linear-gradient(135deg, #0f0c29, #302b63, #24243e);
            color: #fff;
            text-align: center;
            padding: 24px;
        }

        .screen {
            display: none;
            flex-direction: column;
            align-items: center;
            gap: 24px;
            width: 100%;
            max-width: 460px;
            animation: fadeIn 0.4s ease;
        }
        .screen.active { display: flex; }

        @keyframes fadeIn {
            from { opacity: 0; transform: translateY(16px); }
            to   { opacity: 1; transform: translateY(0); }
        }

        .emoji { font-size: 4.5rem; }

        h1 {
            font-size: clamp(1.8rem, 8vw, 2.6rem);
            font-weight: 800;
            line-height: 1.2;
        }

        p {
            font-size: clamp(1rem, 4.5vw, 1.25rem);
            opacity: 0.8;
            line-height: 1.6;
        }

        .vote-prompt {
            font-size: clamp(1.2rem, 5vw, 1.6rem);
            font-weight: 600;
            min-height: 2em;
        }

        /* Side-by-side: Red LEFT, Blue RIGHT */
        .buttons {
            display: flex;
            flex-direction: row;
            gap: 14px;
            width: 100%;
        }

        .vote-btn {
            flex: 1;
            padding: 36px 8px;
            border: none;
            border-radius: 20px;
            font-size: clamp(1.1rem, 5vw, 1.5rem);
            font-weight: 900;
            letter-spacing: 1px;
            cursor: pointer;
            transition: transform 0.15s ease, box-shadow 0.15s ease, opacity 0.15s ease, outline 0.15s ease;
            -webkit-tap-highlight-color: transparent;
            touch-action: manipulation;
            color: #fff;
            line-height: 1.4;
        }
        .vote-btn:active { transform: scale(0.93) !important; }

        .btn-red  { background: linear-gradient(145deg, #e53935, #b71c1c); box-shadow: 0 8px 40px rgba(183,28,28,0.55); }
        .btn-blue { background: linear-gradient(145deg, #1976d2, #0d47a1); box-shadow: 0 8px 40px rgba(21,101,192,0.55); }

        /* Vote selected / dimmed states */
        .vote-btn.selected {
            outline: 4px solid rgba(255,255,255,0.85);
            transform: scale(1.04);
        }
        .vote-btn.dimmed {
            opacity: 0.38;
            transform: scale(0.96);
        }

        .change-hint {
            font-size: clamp(0.85rem, 3.5vw, 1rem);
            opacity: 0.55;
            min-height: 1.4em;
        }

        .pulse { animation: pulse 2.2s ease-in-out infinite; }
        @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:0.45} }

        .dot-anim::after { content:''; animation: dots 1.4s steps(4,end) infinite; }
        @keyframes dots { 0%{content:''} 25%{content:'.'} 50%{content:'..'} 75%{content:'...'} }
    </style>
</head>
<body>
    <!-- Waiting -->
    <div class="screen active" id="waiting-screen">
        <div class="emoji pulse">⏳</div>
        <h1>Hold Tight!</h1>
        <p>The voting session hasn't started yet<span class="dot-anim"></span><br>Stay tuned!</p>
    </div>

    <!-- Voting: Red LEFT, Blue RIGHT, side by side -->
    <div class="screen" id="voting-screen">
        <p class="vote-prompt" id="vote-prompt">I am voting for...</p>
        <div class="buttons">
            <button class="vote-btn btn-red"  id="btn-red"  onclick="vote('red')">🔴<br>RED<br>TEAM</button>
            <button class="vote-btn btn-blue" id="btn-blue" onclick="vote('blue')">🔵<br>BLUE<br>TEAM</button>
        </div>
        <p class="change-hint" id="change-hint"></p>
    </div>

    <!-- Session ended, had voted -->
    <div class="screen" id="voted-screen">
        <div class="emoji">🎉</div>
        <h1>Thank You!</h1>
        <p>Thanks for voting!<br>See ya in the next round!</p>
    </div>

    <!-- Session ended, didn't vote -->
    <div class="screen" id="expired-screen">
        <div class="emoji">⏰</div>
        <h1>Too Slow!</h1>
        <p>You need to be quicker next time!<br>The time for voting has expired.</p>
    </div>

    <script>
        let currentSessionId    = null;
        let currentVote         = null; // 'red' | 'blue' | null
        let wasActivelyVoting   = false; // true only if voter saw the voting screen in this page session

        // ── Stable voter identity (survives refreshes) ──────────────────────
        function getVoterToken() {
            let t = localStorage.getItem('voterToken');
            if (!t) {
                t = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
                    const r = Math.random() * 16 | 0;
                    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
                });
                localStorage.setItem('voterToken', t);
            }
            return t;
        }

        // ── UI helpers ───────────────────────────────────────────────────────
        function show(id) {
            document.querySelectorAll('.screen').forEach(el => el.classList.toggle('active', el.id === id));
        }

        function updateVotingUI() {
            const redBtn  = document.getElementById('btn-red');
            const blueBtn = document.getElementById('btn-blue');
            const prompt  = document.getElementById('vote-prompt');
            const hint    = document.getElementById('change-hint');

            if (currentVote) {
                const teamName = currentVote === 'red' ? 'RED' : 'BLUE';
                prompt.textContent = 'You voted for ' + teamName + '.';
                hint.textContent   = 'Changed your mind? Tap the other button.';
                redBtn.classList.toggle('selected', currentVote === 'red');
                blueBtn.classList.toggle('selected', currentVote === 'blue');
                redBtn.classList.toggle('dimmed', currentVote !== 'red');
                blueBtn.classList.toggle('dimmed', currentVote !== 'blue');
            } else {
                prompt.textContent = 'I am voting for...';
                hint.textContent   = '';
                redBtn.classList.remove('selected', 'dimmed');
                blueBtn.classList.remove('selected', 'dimmed');
            }
        }

        // ── Vote submission ──────────────────────────────────────────────────
        function vote(team) {
            if (currentVote === team) return; // tapped same button — no-op

            const prevVote = currentVote;
            currentVote = team;
            // Optimistically update localStorage + UI
            localStorage.setItem('vote_' + currentSessionId, team);
            updateVotingUI();

            fetch('/api/vote', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ team: team, voterToken: getVoterToken() })
            }).then(res => {
                if (!res.ok) revertVote(prevVote); // session ended mid-tap
            }).catch(() => revertVote(prevVote));
        }

        function revertVote(prev) {
            currentVote = prev;
            if (prev) localStorage.setItem('vote_' + currentSessionId, prev);
            else      localStorage.removeItem('vote_' + currentSessionId);
            updateVotingUI();
        }

        // ── WebSocket ────────────────────────────────────────────────────────
        function connect() {
            const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
            const ws = new WebSocket(proto + '//' + location.host + '/ws/voter');

            ws.onmessage = function(event) {
                const msg = JSON.parse(event.data);
                if (msg.type !== 'state') return;

                switch (msg.state) {
                    case 'WAITING':
                        currentSessionId  = null;
                        currentVote       = null;
                        wasActivelyVoting = false;
                        updateVotingUI();
                        show('waiting-screen');
                        break;

                    case 'VOTING':
                        currentSessionId  = msg.sessionId;
                        wasActivelyVoting = true;
                        // Restore vote if they already voted in this session (refresh-safe)
                        currentVote = localStorage.getItem('vote_' + currentSessionId) || null;
                        show('voting-screen');
                        updateVotingUI();
                        break;

                    case 'ENDED':
                        // Only show "Too Slow" if the voter was actively on the voting screen.
                        // Fresh page loads during an ended session just show the waiting screen.
                        if (wasActivelyVoting) {
                            show(currentVote ? 'voted-screen' : 'expired-screen');
                        } else {
                            show('waiting-screen');
                        }
                        break;
                }
            };

            ws.onclose = function() { setTimeout(connect, 2000); };
            ws.onerror = function() { ws.close(); };
        }

        connect();
    </script>
</body>
</html>
""".trimIndent()

fun adminHtml(lanIp: String) = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Voting Admin</title>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
    <style>
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            background: #0d0d1a;
            color: #fff;
            text-align: center;
            padding: 32px 20px;
        }

        .screen {
            display: none;
            flex-direction: column;
            align-items: center;
            gap: 32px;
            width: 100%;
            max-width: 700px;
            animation: fadeIn 0.35s ease;
        }
        .screen.active { display: flex; }
        @keyframes fadeIn {
            from { opacity: 0; transform: translateY(12px); }
            to   { opacity: 1; transform: translateY(0); }
        }

        h1 {
            font-size: clamp(2rem, 5vw, 3rem);
            font-weight: 900;
            letter-spacing: 1px;
        }
        .subtitle {
            font-size: 1.1rem;
            opacity: 0.55;
            margin-top: -20px;
        }

        /* --- QR Code --- */
        #qrcode {
            background: #fff;
            padding: 20px;
            border-radius: 20px;
            display: inline-block;
            box-shadow: 0 0 60px rgba(255,255,255,0.12);
        }
        #qrcode img, #qrcode canvas { display: block; }
        .voter-url {
            font-size: 1rem;
            opacity: 0.5;
            font-family: monospace;
        }

        /* --- Buttons --- */
        .action-btn {
            padding: 22px 48px;
            border: none;
            border-radius: 16px;
            font-size: 1.5rem;
            font-weight: 800;
            letter-spacing: 1px;
            cursor: pointer;
            transition: transform 0.1s ease, box-shadow 0.1s ease;
            color: #fff;
        }
        .action-btn:active { transform: scale(0.97); }

        .btn-green  { background: linear-gradient(145deg, #2e7d32, #1b5e20); box-shadow: 0 6px 32px rgba(46,125,50,0.5); }
        .btn-orange { background: linear-gradient(145deg, #e65100, #bf360c); box-shadow: 0 6px 32px rgba(230,81,0,0.5); }
        .btn-purple { background: linear-gradient(145deg, #4527a0, #1a237e); box-shadow: 0 6px 32px rgba(69,39,160,0.4); }

        /* --- Live counts --- */
        .live-counts {
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 40px;
        }
        .live-count {
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 8px;
        }
        .live-count .number {
            font-size: clamp(4rem, 12vw, 7rem);
            font-weight: 900;
            line-height: 1;
            transition: transform 0.15s ease;
        }
        .live-count .number.bump { transform: scale(1.25); }
        .live-count .label {
            font-size: 1.3rem;
            font-weight: 700;
            letter-spacing: 3px;
            opacity: 0.7;
        }
        .blue-num { color: #42a5f5; text-shadow: 0 0 40px rgba(66,165,245,0.6); }
        .red-num  { color: #ef5350; text-shadow: 0 0 40px rgba(239,83,80,0.6); }
        .vs-text  { font-size: 2rem; font-weight: 900; opacity: 0.3; }

        /* --- Results bar chart --- */
        .chart-wrap {
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 16px;
            width: 100%;
            max-width: 500px;
        }
        .chart {
            display: flex;
            align-items: flex-end;
            justify-content: center;
            gap: 60px;
            height: 280px;
            width: 100%;
        }
        .bar-col {
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: flex-end;
            gap: 10px;
        }
        .bar-vote-count {
            font-size: 2.5rem;
            font-weight: 900;
        }
        .bar {
            width: 130px;
            height: 0;
            border-radius: 14px 14px 0 0;
            transition: height 1.3s cubic-bezier(0.34, 1.56, 0.64, 1);
        }
        .bar.blue { background: linear-gradient(180deg, #42a5f5, #0d47a1); box-shadow: 0 -8px 40px rgba(66,165,245,0.4); }
        .bar.red  { background: linear-gradient(180deg, #ef5350, #b71c1c); box-shadow: 0 -8px 40px rgba(239,83,80,0.4); }

        .chart-labels {
            display: flex;
            justify-content: center;
            gap: 60px;
            width: 100%;
        }
        .chart-label {
            width: 130px;
            text-align: center;
            font-size: 1.5rem;
            font-weight: 900;
            letter-spacing: 3px;
        }
        .chart-label.blue { color: #42a5f5; }
        .chart-label.red  { color: #ef5350; }

        .winner-text {
            font-size: clamp(1.8rem, 5vw, 2.6rem);
            font-weight: 900;
            line-height: 1.3;
        }
        .winner-blue { color: #42a5f5; }
        .winner-red  { color: #ef5350; }
        .winner-tie  { color: #ffd54f; }

        .conn-status {
            position: fixed;
            bottom: 16px;
            right: 20px;
            font-size: 0.75rem;
            opacity: 0.35;
        }
    </style>
</head>
<body>

    <!-- WAITING: QR + start button -->
    <div class="screen active" id="waiting-screen">
        <h1>Ready to Vote!</h1>
        <p class="subtitle">Scan the QR code to join</p>
        <div id="qrcode"></div>
        <p class="voter-url" id="voter-url-text"></p>
        <button class="action-btn btn-green" onclick="startSession()">▶&nbsp; BEGIN VOTING SESSION</button>
    </div>

    <!-- VOTING: live counts + end button — Red LEFT, Blue RIGHT -->
    <div class="screen" id="voting-screen">
        <h1>Voting in Progress</h1>
        <div class="live-counts">
            <div class="live-count">
                <span class="number red-num" id="live-red">0</span>
                <span class="label">RED</span>
            </div>
            <span class="vs-text">VS</span>
            <div class="live-count">
                <span class="number blue-num" id="live-blue">0</span>
                <span class="label">BLUE</span>
            </div>
        </div>
        <button class="action-btn btn-orange" onclick="endSession()">■&nbsp; END VOTING SESSION</button>
    </div>

    <!-- RESULTS — Red LEFT, Blue RIGHT -->
    <div class="screen" id="results-screen">
        <h1 class="winner-text" id="winner-text">🏆 Results!</h1>
        <div class="chart-wrap">
            <div class="chart">
                <div class="bar-col">
                    <span class="bar-vote-count" id="red-result-count" style="color:#ef5350">0</span>
                    <div class="bar red" id="red-bar"></div>
                </div>
                <div class="bar-col">
                    <span class="bar-vote-count" id="blue-result-count" style="color:#42a5f5">0</span>
                    <div class="bar blue" id="blue-bar"></div>
                </div>
            </div>
            <div class="chart-labels">
                <span class="chart-label red">RED</span>
                <span class="chart-label blue">BLUE</span>
            </div>
        </div>
        <button class="action-btn btn-purple" onclick="resetSession()">↩&nbsp; BACK TO NEW SESSION</button>
    </div>

    <div class="conn-status" id="conn-status">connecting…</div>

    <script>
        const MAX_BAR_H = 260;

        // Generate QR code on page load
        window.addEventListener('load', function() {
            const voterUrl = location.origin + '/';
            document.getElementById('voter-url-text').textContent = voterUrl;
            new QRCode(document.getElementById('qrcode'), {
                text: voterUrl,
                width: 280,
                height: 280,
                colorDark: '#000000',
                colorLight: '#ffffff',
                correctLevel: QRCode.CorrectLevel.H
            });
        });

        function show(id) {
            document.querySelectorAll('.screen').forEach(el => {
                el.classList.toggle('active', el.id === id);
            });
        }

        function updateLiveCounts(blue, red) {
            function bump(el, newVal) {
                el.textContent = newVal;
                el.classList.add('bump');
                setTimeout(() => el.classList.remove('bump'), 200);
            }
            bump(document.getElementById('live-blue'), blue);
            bump(document.getElementById('live-red'), red);
        }

        function animateCount(el, target) {
            const start = performance.now();
            const duration = 1000;
            function tick(now) {
                const p = Math.min((now - start) / duration, 1);
                el.textContent = Math.round(p * target);
                if (p < 1) requestAnimationFrame(tick);
            }
            requestAnimationFrame(tick);
        }

        function showResults(blue, red, winner) {
            show('results-screen');

            // Animate count numbers
            animateCount(document.getElementById('blue-result-count'), blue);
            animateCount(document.getElementById('red-result-count'), red);

            // Animate bars after a short delay
            setTimeout(function() {
                const total = (blue + red) || 1;
                document.getElementById('blue-bar').style.height = Math.max(6, (blue / total) * MAX_BAR_H) + 'px';
                document.getElementById('red-bar').style.height  = Math.max(6, (red  / total) * MAX_BAR_H) + 'px';
            }, 200);

            // Winner announcement
            const wEl = document.getElementById('winner-text');
            if (winner === 'TIE') {
                wEl.textContent = "It's a TIE! 🤝";
                wEl.className = 'winner-text winner-tie';
            } else {
                wEl.textContent = '🏆 The winner is the ' + winner + ' TEAM! Congrats!';
                wEl.className = 'winner-text ' + (winner === 'BLUE' ? 'winner-blue' : 'winner-red');
            }
        }

        function resetBars() {
            document.getElementById('blue-bar').style.height = '0';
            document.getElementById('red-bar').style.height  = '0';
            document.getElementById('blue-result-count').textContent = '0';
            document.getElementById('red-result-count').textContent  = '0';
        }

        function startSession() {
            fetch('/api/admin/start', { method: 'POST' });
        }
        function endSession() {
            fetch('/api/admin/end', { method: 'POST' });
        }
        function resetSession() {
            resetBars();
            fetch('/api/admin/reset', { method: 'POST' });
        }

        function connect() {
            const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
            const ws = new WebSocket(proto + '//' + location.host + '/ws/admin');
            const status = document.getElementById('conn-status');

            ws.onopen  = function() { status.textContent = 'connected'; };
            ws.onclose = function() { status.textContent = 'reconnecting…'; setTimeout(connect, 2000); };
            ws.onerror = function() { ws.close(); };

            ws.onmessage = function(event) {
                const msg = JSON.parse(event.data);
                switch (msg.type) {
                    case 'init':
                        // Restore correct screen on reconnect
                        if (msg.state === 'VOTING') {
                            show('voting-screen');
                            updateLiveCounts(msg.blueVotes || 0, msg.redVotes || 0);
                        } else if (msg.state === 'ENDED') {
                            const w = msg.blueVotes > msg.redVotes ? 'BLUE' : msg.redVotes > msg.blueVotes ? 'RED' : 'TIE';
                            showResults(msg.blueVotes || 0, msg.redVotes || 0, w);
                        } else {
                            show('waiting-screen');
                        }
                        break;
                    case 'started':
                        show('voting-screen');
                        updateLiveCounts(0, 0);
                        break;
                    case 'votes':
                        updateLiveCounts(msg.blueVotes || 0, msg.redVotes || 0);
                        break;
                    case 'ended':
                        showResults(msg.blueVotes || 0, msg.redVotes || 0, msg.winner || 'TIE');
                        break;
                    case 'reset':
                        show('waiting-screen');
                        break;
                }
            };
        }

        connect();
    </script>
</body>
</html>
""".trimIndent()
