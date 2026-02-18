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

// --- NEW: Toggle Listeners (Instant Update) ---
['chk-fwd', 'chk-haz', 'chk-path', 'chk-reg'].forEach(id => {
    document.getElementById(id).addEventListener('change', () => {
        if (lastPacket) updateSVG(lastPacket.enriched);
    });
});

let lastPacket = null; // Store data for redraws

function sendCommand(action, val=null) {
    if (val) socket.emit('command', { action: action, value: val });
    else socket.emit('command', { action: action });
}

// --- 3. MAIN UPDATE LOOP ---
socket.on('update', (packet) => {
    lastPacket = packet;
    const data = packet.enriched;
    const regs = packet.registers;

    document.getElementById('cycle-display').innerText = data.cycle || 0;

    // A. Update Register Grid
    for(let i=0; i<32; i++) {
        const box = document.getElementById(`reg-box-${i}`);
        const valDiv = document.getElementById(`reg-val-${i}`);
        const oldVal = parseInt(valDiv.innerText, 16) || 0;
        const newVal = regs[i];

        valDiv.innerText = "0x" + newVal.toString(16);
        if (newVal !== oldVal) {
            box.style.background = "#555";
            setTimeout(() => box.style.background = "#2d2d30", 300);
        }
        // Remove old highlights
        box.classList.remove('active-read', 'active-write');
    }

    // B. Highlight Read Registers (Blue)
    if (data.id_info) {
        const rs1 = data.id_info.rs1;
        const rs2 = data.id_info.rs2;
        if (rs1 !== 0) document.getElementById(`reg-box-${rs1}`)?.classList.add('active-read');
        if (rs2 !== 0) document.getElementById(`reg-box-${rs2}`)?.classList.add('active-read');
    }

    // C. Update Lists & Visuals
    updateInstList(packet.enriched);
    updateHazardPanel(data);
    updateSVG(data);
});

// --- 4. HAZARD PANEL ---
function updateHazardPanel(data) {
    const hazList = document.getElementById('hazard-list');
    if (!hazList) return;

    let hazards = [];
    if (data.hazard) {
        if (data.hazard.if_stall) hazards.push("⚠️ IF STALL (Fetch Wait)");
        if (data.hazard.id_stall) hazards.push("⚠️ ID STALL (Load-Use)");
        if (data.hazard.flush)    hazards.push("🚿 FLUSH (Branch Mispredict)");
    }
    if (data.fwd) {
        if (data.fwd.a_sel == 1) hazards.push("⚡ FWD: MEM → EX (Op A)");
        if (data.fwd.a_sel == 2) hazards.push("⚡ FWD: WB → EX (Op A)");
        if (data.fwd.b_sel == 1) hazards.push("⚡ FWD: MEM → EX (Op B)");
        if (data.fwd.b_sel == 2) hazards.push("⚡ FWD: WB → EX (Op B)");
    }

    if (hazards.length === 0) {
        hazList.innerHTML = '<div style="color:#666; padding:5px;">No Active Hazards</div>';
    } else {
        hazList.innerHTML = hazards.map(h => `<div style="padding:5px; border-bottom:1px solid #444; font-weight:bold; color: #ffcc00;">${h}</div>`).join('');
    }
}

// --- 5. VISUALIZATION UPDATER (With Toggles) ---
function updateSVG(data) {
    const svg = document.getElementById('pipeline-svg').contentDocument;
    if (!svg) return;

    // Read Toggle States
    const showFwd  = document.getElementById('chk-fwd').checked;
    const showHaz  = document.getElementById('chk-haz').checked;
    const showPath = document.getElementById('chk-path').checked;
    const showReg  = document.getElementById('chk-reg').checked;

    const setText = (id, val) => { const el = svg.getElementById(id); if (el) el.textContent = val; };
    const setFill = (id, color) => { const el = svg.getElementById(id); if (el) el.style.fill = color; };
    const setWire = (id, color, width, dash) => {
        const el = svg.getElementById(id);
        if (el) {
            el.style.stroke = color;
            el.style.strokeWidth = width;
            el.style.strokeDasharray = dash;
        }
    };

    // 1. HAZARD COLORS (Controlled by 'showHaz')
    ['if','id'].forEach(s => setFill(`stage-${s}`, '#252526')); // Default Dark
    if (showHaz && data.hazard) {
        if (data.hazard.if_stall || data.hazard.flush) setFill('stage-if', '#590000'); // Red
        if (data.hazard.id_stall || data.hazard.flush) setFill('stage-id', '#590000');
    }

    // 2. BRANCH/JUMP WIRE (Grouped with Datapath)
    if (showPath && data.ex && data.ex.pc_src === 1) {
        setWire('wire-branch', '#ff5500', '4', 'none');
        const target = Number(data.ex.pc_jb).toString(16);
        setText('txt-branch-target', `Taken: 0x${target}`);
        const txt = svg.getElementById('txt-branch-target');
        if(txt) txt.style.fill = '#ff5500';
    } else {
        setWire('wire-branch', '#444', '2', '5,5');
        setText('txt-branch-target', ``);
    }

    // 3. TEXT & HEX UPDATES (Always Active)
    if (data.asm) {
        ['if','id','ex','mem','wb'].forEach(s => setText(`txt-asm-${s}`, data.asm[s]));
        ['if','id','ex','mem','wb'].forEach(s => setText(`txt-pc-${s}`, data.pc_hex[s]));
    }
    if (data.instr) {
        ['if','id','ex','mem','wb'].forEach(s => {
            const rawVal = Number(data.instr[s]);
            const hexStr = "0x" + rawVal.toString(16).padStart(8, '0');
            setText(`txt-hex-${s}`, hexStr);
        });
    }

    // 4. STAGE DETAILS
    if (data.id_info) {
        setText('txt-rs1-id', `rs1: x${data.id_info.rs1}`);
        setText('txt-rs2-id', `rs2: x${data.id_info.rs2}`);
        setText('txt-rd-id', `->rd: x${data.id_info.rd}`);
    }
    if (data.ex) {
        setText('txt-op-a', `A: 0x${Number(data.ex.val_a).toString(16)}`);
        setText('txt-op-b', `B: 0x${Number(data.ex.val_b).toString(16)}`);
        setText('txt-alu-ex', `Res: 0x${Number(data.ex.alu_result).toString(16)}`);
    }
    if (data.mem) {
        if (data.mem.we) setText('txt-mem-status', `Store: 0x${Number(data.mem.wdata).toString(16)}`);
        else setText('txt-mem-status', `Load: 0x${Number(data.mem.addr).toString(16)}`);
    }

    // 5. WB WIRE & TEXT (Controlled by 'showReg')
    if (showReg && data.wb && data.wb.we && data.wb.rd !== 0) {
        setText('txt-wb-reg', `x${data.wb.rd} = 0x${data.wb.wdata.toString(16)}`);
        setFill('txt-wb-reg', '#4ec9b0');
        setWire('wire-wb-back', '#4ec9b0', '3', 'none');
        document.getElementById('wb-info').innerText = `x${data.wb.rd} = 0x${data.wb.wdata.toString(16)}`;
    } else {
        setText('txt-wb-reg', "--");
        setFill('txt-wb-reg', '#666');
        setWire('wire-wb-back', '#444', '2', '5,5');
        document.getElementById('wb-info').innerText = "Idle";
    }

    // 6. FORWARDING WIRES (Controlled by 'showFwd')
    const wireMemFwd = svg.getElementById('wire-mem-fwd');
    const wireWbFwd  = svg.getElementById('wire-wb-fwd');
    const txtFwd     = svg.getElementById('txt-fwd-status');
    const boxFwd     = svg.getElementById('box-fwd-unit');

    // Default Idle State
    if(wireMemFwd) { wireMemFwd.style.stroke = '#666'; wireMemFwd.style.strokeWidth='2'; wireMemFwd.style.strokeDasharray='4,4'; }
    if(wireWbFwd)  { wireWbFwd.style.stroke = '#666'; wireWbFwd.style.strokeWidth='2'; wireWbFwd.style.strokeDasharray='4,4'; }
    if(txtFwd)     { txtFwd.textContent = "FWD UNIT"; txtFwd.style.fill = "white"; txtFwd.style.fontWeight = "normal"; }
    if(boxFwd)     { boxFwd.style.stroke = "#fff"; }

    if (showFwd && data.fwd) {
        let active = false;
        if (data.fwd.a_sel == 1 || data.fwd.b_sel == 1) {
            if(wireMemFwd) { wireMemFwd.style.stroke = '#d65d0e'; wireMemFwd.style.strokeWidth = '4'; wireMemFwd.style.strokeDasharray = 'none'; }
            active = true;
        }
        if (data.fwd.a_sel == 2 || data.fwd.b_sel == 2) {
             if(wireWbFwd) { wireWbFwd.style.stroke = '#4ec9b0'; wireWbFwd.style.strokeWidth = '4'; wireWbFwd.style.strokeDasharray = 'none'; }
             active = true;
        }
        if (active && txtFwd) {
            let status = "FWD UNIT";
            if (data.fwd.a_sel === 1) status = "FWD: MEM→A";
            else if (data.fwd.a_sel === 2) status = "FWD: WB→A";
            else if (data.fwd.b_sel === 1) status = "FWD: MEM→B";
            else if (data.fwd.b_sel === 2) status = "FWD: WB→B";
            if (data.fwd.a_sel > 0 && data.fwd.b_sel > 0) status = "FWD: A & B";

            txtFwd.textContent = status;
            txtFwd.style.fill = '#fff';
            txtFwd.style.fontWeight = 'bold';
            if (boxFwd) boxFwd.style.stroke = '#d65d0e';
        }
    }

    // 7. INTERACTIVE DATAPATH (Controlled by 'showPath')
    const colIdle = '#333';
    const colEx   = '#C71585';
    const colMem  = '#d65d0e';
    const colWB   = '#4ec9b0';

    const setStageStroke = (id, active, color) => {
        const box = svg.getElementById(id);
        if (box) {
            const effectiveActive = showPath && active;
            box.style.stroke = effectiveActive ? color : colIdle;
            box.style.strokeWidth = effectiveActive ? '3' : '1';
            // Force opacity 1.0 if stalled so Red Fill shows through!
            const isStalled = showHaz && ((id === 'stage-if' && data.hazard.if_stall) || (id === 'stage-id' && data.hazard.id_stall));
            box.style.opacity = (effectiveActive || isStalled) ? '1.0' : '0.4';
        }
    };

    if (data.instr) {
        if(data.instr.ex) setStageStroke('stage-ex', getControlSignals(data.instr.ex).usesEx, colEx);
        if(data.instr.mem) setStageStroke('stage-mem', getControlSignals(data.instr.mem).usesMem, colMem);
        if(data.instr.wb) setStageStroke('stage-wb', getControlSignals(data.instr.wb).usesWB, colWB);
    }

    // 8. REGISTER FILE ANIMATION (Controlled by 'showReg')
    const rfBox = svg.getElementById('regfile-box');
    const rfTitle = svg.getElementById('txt-rf-title');
    const rfStatus = svg.getElementById('txt-rf-status');

    if (showReg && data.wb && data.wb.we && data.wb.rd !== 0) {
        if (rfBox) { rfBox.style.stroke = '#4ec9b0'; rfBox.style.strokeWidth = '3'; }
        if (rfTitle) rfTitle.style.fill = '#4ec9b0';
        if (rfStatus) {
            rfStatus.textContent = `Writing 0x${Number(data.wb.wdata).toString(16)} → x${data.wb.rd}`;
            rfStatus.style.fill = '#fff';
            rfStatus.style.fontWeight = 'bold';
        }
    } else {
        if (rfBox) { rfBox.style.stroke = '#007acc'; rfBox.style.strokeWidth = '2'; }
        if (rfTitle) rfTitle.style.fill = '#007acc';
        if (rfStatus) {
            rfStatus.textContent = "Read / Write";
            rfStatus.style.fill = '#888';
            rfStatus.style.fontWeight = 'normal';
        }
    }
}

// --- 6. INSTRUCTION LIST & HELPERS ---
let isRomLoaded = false;
function updateInstList(data) {
    const list = document.getElementById('inst-list');

    // FIX: Only build if we actually have data (prevents clearing list on empty updates)
    if (data.rom && data.rom.length > 0 && !isRomLoaded) {
        list.innerHTML = "";
        data.rom.forEach((hex, index) => {
            const pcVal = index * 4;
            const pcHex = "0x" + pcVal.toString(16).padStart(8, '0');
            const asm = disassemble(hex);
            const row = document.createElement('div');
            row.id = `rom-addr-${pcHex}`;
            row.style.borderBottom = "1px solid #333";
            row.style.padding = "2px 5px";
            row.style.fontFamily = "monospace";
            row.style.fontSize = "12px";
            row.innerHTML = `
                <span style="color:#666; display:inline-block; width:70px;">${pcHex}</span>
                <span style="color:#4caf50; display:inline-block; width:80px;">${hex}</span>
                <span style="color:#4ec9b0;">${asm}</span>
            `;
            list.appendChild(row);
        });
        isRomLoaded = true;
    }
    const oldActive = list.querySelector('.active-row');
    if (oldActive) { oldActive.style.background = "transparent"; oldActive.classList.remove('active-row'); }
    if (data.pc_hex && data.pc_hex.id) {
        const currentPC = data.pc_hex.id.toLowerCase();
        const activeRow = document.getElementById(`rom-addr-${currentPC}`);
        if (activeRow) {
            activeRow.style.background = "#264f78";
            activeRow.classList.add('active-row');
            activeRow.scrollIntoView({ behavior: "smooth", block: "center" });
        }
    }
}

function getControlSignals(hexStr) {
    const inst = Number(hexStr);
    if (isNaN(inst) || inst === 0) return { type: 'BUBBLE', usesEx: false, usesMem: false, usesWB: false };
    const opcode = inst & 0x7F;
    const rd     = (inst >> 7) & 0x1F;
    const writesToReg = (rd !== 0);
    if (inst === 0x13) return { type: 'NOP', usesEx: false, usesMem: false, usesWB: false };

    switch(opcode) {
        case 0x33: // R-Type
        case 0x13: // I-Type
        case 0x37: // LUI
        case 0x17: // AUIPC
            return { type: 'ALU', usesEx: true, usesMem: false, usesWB: writesToReg };
        case 0x03: // LOAD
            return { type: 'LOAD', usesEx: true, usesMem: true, usesWB: writesToReg };
        case 0x23: // STORE
            return { type: 'STORE', usesEx: true, usesMem: true, usesWB: false };
        case 0x63: // BRANCH
            return { type: 'BRANCH', usesEx: true, usesMem: false, usesWB: false };
        case 0x6F: // JAL
        case 0x67: // JALR
            return { type: 'JUMP', usesEx: true, usesMem: false, usesWB: writesToReg };
        default:
            return { type: 'UNKNOWN', usesEx: false, usesMem: false, usesWB: false };
    }
}

function disassemble(hexStr) {
    const inst = parseInt(hexStr, 16);
    if (inst === 0 || isNaN(inst)) return "nop";
    const opcode = inst & 0x7F;
    const rd = (inst >> 7) & 0x1F;
    const funct3 = (inst >> 12) & 0x7;
    const rs1 = (inst >> 15) & 0x1F;
    const rs2 = (inst >> 20) & 0x1F;
    const funct7 = (inst >> 25) & 0x7F;
    const regName = (r) => "x" + r;

    if (opcode === 0x33) {
        let op = "unknown";
        if (funct3 === 0x0) op = (funct7 === 0x20) ? "sub" : "add";
        else if (funct3 === 0x4) op = "xor";
        else if (funct3 === 0x6) op = "or";
        else if (funct3 === 0x7) op = "and";
        else if (funct3 === 0x1) op = "sll";
        else if (funct3 === 0x5) op = (funct7 === 0x20) ? "sra" : "srl";
        return `${op} ${regName(rd)}, ${regName(rs1)}, ${regName(rs2)}`;
    }
    if (opcode === 0x13) {
        let op = "addi";
        if (funct3 === 0x4) op = "xori";
        if (funct3 === 0x6) op = "ori";
        if (funct3 === 0x7) op = "andi";
        const imm = (inst >> 20);
        const simm = (imm & 0x800) ? (imm | 0xFFFFF000) : imm;
        return `${op} ${regName(rd)}, ${regName(rs1)}, ${simm}`;
    }
    if (opcode === 0x03) return `lw ${regName(rd)}, offset(${regName(rs1)})`;
    if (opcode === 0x23) return `sw ${regName(rs2)}, offset(${regName(rs1)})`;
    if (opcode === 0x63) {
        let op = (funct3 === 0x0) ? "beq" : "bne";
        return `${op} ${regName(rs1)}, ${regName(rs2)}, target`;
    }
    if (opcode === 0x37) return `lui ${regName(rd)}, imm`;
    if (opcode === 0x17) return `auipc ${regName(rd)}, imm`;
    if (opcode === 0x6F) return `jal ${regName(rd)}, target`;
    return "unknown";
}