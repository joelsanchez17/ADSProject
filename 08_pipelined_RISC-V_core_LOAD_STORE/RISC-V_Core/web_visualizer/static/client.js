const socket = io();

// --- SETUP REGISTER GRID ---
const regGrid = document.getElementById('reg-grid');
regGrid.innerHTML = ""; // Clear existing
for (let i = 0; i < 32; i++) {
    const div = document.createElement('div');
    div.className = 'reg-box';
    div.id = `reg-box-${i}`;
    // Use `x0`..`x31` naming
    div.innerHTML = `<span class="reg-name">x${i}</span><div class="reg-val" id="reg-val-${i}">0</div>`;
    regGrid.appendChild(div);
}

// Keyboard Nav
document.addEventListener('keydown', (e) => {
    if (e.key === "ArrowRight") sendCommand('step');
    if (e.key === "ArrowLeft")  sendCommand('back');
});

function sendCommand(action) {
    socket.emit('command', { action: action });
}

socket.on('update', (packet) => {
    const data = packet.enriched;
    const regs = packet.registers; // Array of 32 integers

    document.getElementById('cycle-display').innerText = data.cycle || 0;

    // 1. UPDATE REGISTER GRID
    if (regs) {
        for(let i=0; i<32; i++) {
            const valDiv = document.getElementById(`reg-val-${i}`);
            // Show Hex
            valDiv.innerText = "0x" + regs[i].toString(16);

            // Remove old highlights
            const box = document.getElementById(`reg-box-${i}`);
            box.classList.remove('active-write', 'active-read');
        }
    }

    // 2. HIGHLIGHT ACTIVE REGISTERS (Writeback)
    if (data.wb && data.wb.we && data.wb.rd !== 0) {
        const r = data.wb.rd;
        const box = document.getElementById(`reg-box-${r}`);
        if(box) box.classList.add('active-write');

        // Update Sidebar Writeback Details
        document.getElementById('wb-info').innerText =
            `x${r} = 0x${data.wb.wdata.toString(16)}`;
    } else {
        document.getElementById('wb-info').innerText = "--";
    }

    // 3. HIGHLIGHT READ REGISTERS (Decode)
    if (data.id_info) {
        const rs1 = data.id_info.rs1;
        const rs2 = data.id_info.rs2;
        if (rs1 !== 0) document.getElementById(`reg-box-${rs1}`)?.classList.add('active-read');
        if (rs2 !== 0) document.getElementById(`reg-box-${rs2}`)?.classList.add('active-read');
    }

    // 4. UPDATE SVG
    updateSVG(data, regs);
});

function updateSVG(data, regs) {
    const svgObj = document.getElementById('pipeline-svg');
    if (!svgObj.contentDocument) return;
    const svg = svgObj.contentDocument;

    const setText = (id, val) => { const e = svg.getElementById(id); if(e) e.textContent = val; };
    const setFill = (id, col) => { const e = svg.getElementById(id); if(e) e.style.fill = col; };
    const setStroke = (id, col, width) => {
        const e = svg.getElementById(id);
        if(e) { e.style.stroke = col; if(width) e.style.strokeWidth = width; }
    };

    // --- TEXT UPDATES ---
    if (data.pc_hex) {
        setText('txt-pc-if', data.pc_hex.if);
        setText('txt-pc-id', data.pc_hex.id);
        setText('txt-pc-ex', data.pc_hex.ex);
        setText('txt-pc-mem', data.pc_hex.mem);
        setText('txt-pc-wb', data.pc_hex.wb);
    }
    if (data.asm) {
        setText('txt-asm-if', data.asm.if);
        setText('txt-asm-id', data.asm.id);
        setText('txt-asm-ex', data.asm.ex);
        setText('txt-asm-mem', data.asm.mem);
        setText('txt-asm-wb', data.asm.wb);
    }

    // --- OPERANDS (The Fix!) ---
    // We use the ID stage info to look up the values in our Register Array
    if (data.id_info && regs) {
        // Note: Operands are displayed in EX stage usually, but they come from ID decoding
        const valA = regs[data.id_info.rs1];
        const valB = regs[data.id_info.rs2];
        setText('txt-op-a', `A: 0x${valA.toString(16)}`);
        setText('txt-op-b', `B: 0x${valB.toString(16)}`);
    }

    if (data.ex) {
        setText('txt-alu-ex', `Res: 0x${Number(data.ex.alu_result).toString(16)}`);
    }

    // --- WB WIRE ACTIVATION (The Fix!) ---
    // You need to add id="wire-wb-back" to the line in SVG connecting WB to RegFile
    // If it doesn't exist, we skip.
    const wbWire = svg.getElementById('wire-wb-back');

    if (data.wb && data.wb.we && data.wb.rd !== 0) {
        // Active Writeback
        setText('txt-wb-reg', `x${data.wb.rd} = 0x${data.wb.wdata.toString(16)}`);
        // Color the text Green
        setFill('txt-wb-reg', '#4ec9b0');

        // Color the Wire Green
        if(wbWire) {
             wbWire.style.stroke = "#4ec9b0";
             wbWire.style.strokeWidth = "3";
             wbWire.style.strokeDasharray = "none"; // Solid line
        }
    } else {
        setText('txt-wb-reg', "--");
        if(wbWire) {
             wbWire.style.stroke = "#444";
             wbWire.style.strokeWidth = "2";
             wbWire.style.strokeDasharray = "5,5"; // Dashed for idle
        }
    }

    // --- HAZARDS ---
    const stallColor = "#770000";
    ['if','id','ex','mem','wb'].forEach(s => setFill(`stage-${s}`, '#252526')); // Reset

    if (data.hazard) {
        if (data.hazard.if_stall) setFill('stage-if', stallColor);
        if (data.hazard.id_stall) setFill('stage-id', stallColor);
    }
}