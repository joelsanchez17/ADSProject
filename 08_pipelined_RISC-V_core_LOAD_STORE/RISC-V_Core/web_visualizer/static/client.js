const socket = io();
const regGrid = document.getElementById('reg-grid');

// --- 1. INITIALIZE REGISTER GRID ---
regGrid.innerHTML = "";
for (let i = 0; i < 32; i++) {
    const div = document.createElement('div');
    div.className = 'reg-box';
    div.id = `reg-box-${i}`;
    div.innerHTML = `<span class="reg-name">x${i}</span><div class="reg-val" id="reg-val-${i}">0</div>`;
    regGrid.appendChild(div);
}

// --- 2. KEYBOARD CONTROLS ---
document.addEventListener('keydown', (e) => {
    if (e.key === "ArrowRight") sendCommand('step');
    if (e.key === "ArrowLeft")  sendCommand('back');
});

// --- TOGGLE LISTENERS ---
['chk-fwd', 'chk-haz', 'chk-path', 'chk-reg'].forEach(id => {
    const el = document.getElementById(id);
    if (el) {
        el.addEventListener('change', () => {
            if (lastPacket) updateSVG(lastPacket.enriched);
        });
    }
});

let lastPacket = null;

// --- 3. SEND COMMAND TO PYTHON ---
function sendCommand(action, val=null) {
    if (!window.currentSessionId) {
        console.warn("Cannot send command: No active session. Compile first!");
        return;
    }

    const payload = {
        action: action,
        session_id: window.currentSessionId
    };
    if (val) payload.value = val;

    socket.emit('command', payload);
}

// --- 4. RECEIVE DATA FROM PYTHON ---
socket.on('update', (packet) => {
    console.log("🔥 WEBSOCKET PACKET RECEIVED:", packet); // Prints to F12 Console!

    lastPacket = packet;
    const data = packet.enriched;
    const regs = packet.registers;

    updateSVG(data);
    updateRegisters(regs);
});

// --- 5. UPDATE THE SVG VISUALIZER ---
function updateSVG(data) {
    try {
        // THE FIX: Penetrate the <object> tag to access the SVG DOM
        let svgDoc = document;
        const svgObj = document.getElementById('pipeline-svg');
        if (svgObj && svgObj.contentDocument) {
            svgDoc = svgObj.contentDocument;
        }

        // Visual Flash: Blinks the background to prove it's working
        const bg = svgDoc.querySelector('rect');
        if (bg) {
            bg.setAttribute('fill', '#333333');
            setTimeout(() => bg.setAttribute('fill', '#1e1e1e'), 150);
        }

        // Helper function to safely set text
        const setText = (id, text, color) => {
            const el = svgDoc.getElementById(id);
            if (el) {
                el.textContent = text;
                if (color) el.setAttribute('fill', color);
            } else {
                console.warn(`Warning: Missing SVG element '${id}'`);
            }
        };

        // --- UPDATE IF STAGE ---
        setText('txt-pc-if', `PC: ${data.pc_hex?.if || '--'}`, '#bbb');
        setText('txt-asm-if', data.asm?.if || 'nop', '#4ec9b0');

        // --- UPDATE ID STAGE ---
        setText('txt-pc-id', data.pc_hex?.id || '--', '#666');
        setText('txt-asm-id', data.asm?.id || 'nop', '#4ec9b0');
        if (data.id_info) {
            setText('txt-rs1-id', `rs1: x${data.id_info.rs1}`, '#ccc');
            setText('txt-rs2-id', `rs2: x${data.id_info.rs2}`, '#ccc');
            setText('txt-rd-id', `->rd: x${data.id_info.rd}`, '#ccc');
        }

        // --- UPDATE EX STAGE ---
        setText('txt-pc-ex', data.pc_hex?.ex || '--', '#666');
        setText('txt-asm-ex', data.asm?.ex || 'nop', '#4ec9b0');
        setText('txt-op-a', `A: ${data.ex?.val_a || 0}`, '#aaa');
        setText('txt-op-b', `B: ${data.ex?.val_b || 0}`, '#aaa');
        setText('txt-alu-ex', `Res: ${data.ex?.alu_result || '0x0'}`, '#fff');

        // --- UPDATE MEM STAGE ---
        setText('txt-pc-mem', data.pc_hex?.mem || '--', '#666');
        setText('txt-asm-mem', data.asm?.mem || 'nop', '#4ec9b0');

        // --- UPDATE WB STAGE ---
        setText('txt-pc-wb', data.pc_hex?.wb || '--', '#666');
        setText('txt-asm-wb', data.asm?.wb || 'nop', '#4ec9b0');

    } catch (error) {
        console.error("❌ SVG Update CRASHED:", error);
    }
}

// --- 6. UPDATE REGISTER FILE ---
function updateRegisters(regs) {
    if (!regs) return;
    for (let i = 0; i < 32; i++) {
        const valEl = document.getElementById(`reg-val-${i}`);
        if (valEl && regs[i] !== undefined) {
            const hexVal = "0x" + (regs[i] >>> 0).toString(16);
            if (valEl.textContent !== hexVal && valEl.textContent !== "0") {
                valEl.style.color = "#f48771"; // Highlight changed registers
            } else {
                valEl.style.color = "#4ec9b0";
            }
            valEl.textContent = hexVal;
        }
    }
}

// --- 7. LIVE TERMINAL LOGS ---
socket.on('build_log', (data) => {
    const consoleOut = document.getElementById('console-output');
    if (consoleOut) {
        // Append the incoming line from Chisel/SBT
        consoleOut.innerHTML += `<span style="color: #d4d4d4;">${data.line}</span>`;
        // Automatically scroll to the bottom so you can watch it compile!
        consoleOut.scrollTop = consoleOut.scrollHeight;
    }
});