const socket = io();

// Initialize Register Grid
const regGrid = document.getElementById('reg-grid');
for (let i = 0; i < 32; i++) {
    const div = document.createElement('div');
    div.className = 'reg-box';
    div.id = `reg-box-${i}`;
    div.innerHTML = `<span class="reg-name">x${i}</span><div class="reg-val" id="reg-val-${i}">0</div>`;
    regGrid.appendChild(div);
}

function sendCommand(action) {
    socket.emit('command', { action: action });
}

socket.on('update', (data) => {
    document.getElementById('cycle-display').innerText = data.cycle || 0;

    // Update Register Grid
    // Assuming data.regs is { 1: 10, 2: 5 ... }
    // You need to expose the full register file from Chisel for this!
    // If not available, we update based on WB history.
    if (data.wb && data.wb.we && data.wb.rd != 0) {
        const regId = data.wb.rd;
        const regBox = document.getElementById(`reg-box-${regId}`);
        const regVal = document.getElementById(`reg-val-${regId}`);

        // Flash Effect
        regBox.classList.add('active-write');
        regVal.innerText = data.wb.wdata;
        setTimeout(() => regBox.classList.remove('active-write'), 800);
    }

    // Update SVG
    updateSVG(data);
});

// Reuse your updateSVG function here...
function updateSVG(data) {
    const svgObj = document.getElementById('pipeline-svg');
    if (!svgObj.contentDocument) return;
    const svg = svgObj.contentDocument;

    // Helper: Find element by ID and set text/color
    const setText = (id, val) => { const e = svg.getElementById(id); if(e) e.textContent = val; };
    const setFill = (id, col) => { const e = svg.getElementById(id); if(e) e.style.fill = col; };

    // --- Update Stages with Dark Mode Colors ---
    if (data.asm) {
        setText('txt-asm-if', data.asm.if);
        setText('txt-asm-id', data.asm.id);
        setText('txt-asm-ex', data.asm.ex);
        setText('txt-asm-mem', data.asm.mem);
        setText('txt-asm-wb', data.asm.wb);
    }

    // Default Colors (Dark Mode style)
    const defFill = "#fff";
    const stallFill = "#ff4d4d"; // Bright Red
    const flushFill = "#ffa500"; // Orange

    ['if','id','ex','mem','wb'].forEach(s => setFill(`stage-${s}`, defFill));

    if (data.hazard) {
        if (data.hazard.if_stall) setFill('stage-if', stallFill);
        if (data.hazard.id_stall) setFill('stage-id', stallFill);
        if (data.hazard.flush) {
             setFill('stage-id', flushFill);
             setFill('stage-ex', flushFill);
        }
    }

    // Forwarding Arrows
    // ... (Use same logic as before, just ensure IDs match SVG)
}