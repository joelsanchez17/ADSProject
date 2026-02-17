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

function sendCommand(action, val=null) {
    if (val) socket.emit('command', { action: action, value: val });
    else socket.emit('command', { action: action });
}

// --- 3. MAIN UPDATE LOOP ---
socket.on('update', (packet) => {
    const data = packet.enriched;
    const regs = packet.registers;
    updateInstList(packet.enriched);
    document.getElementById('cycle-display').innerText = data.cycle || 0;

    console.log("DEBUG DATA:", {
            rs1: data.id?.rs1,
            rs2: data.id?.rs2,
            opA: data.ex?.val_a,
            opB: data.ex?.val_b
    });
    // A. Update Register Grid Values
    for(let i=0; i<32; i++) {
        const box = document.getElementById(`reg-box-${i}`);
        box.classList.remove('active-write', 'active-read');
        document.getElementById(`reg-val-${i}`).innerText = "0x" + regs[i].toString(16);
    }

    // B. Highlight Writeback (Green)
    if (data.wb && data.wb.we && data.wb.rd !== 0) {
        document.getElementById(`reg-box-${data.wb.rd}`)?.classList.add('active-write');
        document.getElementById('wb-info').innerText = `x${data.wb.rd} = 0x${data.wb.wdata.toString(16)}`;
    } else {
        document.getElementById('wb-info').innerText = "Idle";
    }

    // C. Highlight Read Registers (Blue)
    if (data.id) {
        const rs1 = Number(data.id.rs1);
        const rs2 = Number(data.id.rs2);
        if (rs1 !== 0) document.getElementById(`reg-box-${rs1}`)?.classList.add('active-read');
        if (rs2 !== 0) document.getElementById(`reg-box-${rs2}`)?.classList.add('active-read');
    }

    updateHazardPanel(data);
    updateSVG(data, regs);
});

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

let isRomLoaded = false;

function updateInstList(data) {
    const list = document.getElementById('inst-list');

    // 1. Build the list ONLY ONCE (when data.rom arrives)
    if (data.rom && !isRomLoaded) {
        list.innerHTML = ""; // Clear "Waiting..." text
        data.rom.forEach((hex, index) => {
            const pcVal = index * 4; // Assuming 4-byte instructions
            const pcHex = "0x" + pcVal.toString(16).padStart(8, '0');

            // Use our existing disassemble function!
            const asm = disassemble(hex);

            const row = document.createElement('div');
            row.id = `rom-addr-${pcHex}`; // Give it an ID to find it later
            row.className = "rom-row";
            row.style.borderBottom = "1px solid #333";
            row.style.padding = "2px 5px";
            row.style.cursor = "pointer";
            row.style.fontFamily = "monospace"; // Ensure alignment

            // NEW LAYOUT: PC | HEX | ASM
            row.innerHTML = `
                <span style="color:#666; font-size:10px; margin-right:10px; width:60px; display:inline-block;">${pcHex}</span>

                <span style="color:#4caf50; font-size:11px; margin-right:15px; width:70px; display:inline-block;">${hex}</span>

                <span style="color:#4ec9b0; font-size:12px;">${asm}</span>
            `;
            list.appendChild(row);
        });
        isRomLoaded = true; // Don't rebuild next cycle
    }

    // 2. Highlight Current PC (Fetch Stage or ID Stage)
    // First, remove old highlights
    const oldActive = list.querySelector('.active-row');
    if (oldActive) {
        oldActive.style.background = "transparent";
        oldActive.classList.remove('active-row');
    }

    // Get current ID Stage PC
    if (data.pc_hex && data.pc_hex.id) {
        // Normalizing the PC string (remove '0x' if needed)
        const currentPC = data.pc_hex.id.toLowerCase();

        const activeRow = document.getElementById(`rom-addr-${currentPC}`);
        if (activeRow) {
            activeRow.style.background = "#264f78"; // Highlight Blue
            activeRow.classList.add('active-row');

            // Auto-scroll to keep it visible
            activeRow.scrollIntoView({ behavior: "smooth", block: "center" });
        }
    }
}

function updateSVG(data, regs) {
    const svgObj = document.getElementById('pipeline-svg');
    if (!svgObj.contentDocument) return;
    const svg = svgObj.contentDocument;

    const setText = (id, val) => { const e = svg.getElementById(id); if(e) e.textContent = val; };
    const setFill = (id, col) => { const e = svg.getElementById(id); if(e) e.style.fill = col; };
    const setWire = (id, color, width, dash) => {
        const e = svg.getElementById(id);
        if(e) {
            e.style.stroke = color;
            e.style.strokeWidth = width;
            e.style.strokeDasharray = dash;
        }
    };
    // ==========================================
        // NEW: FORWARDING UNIT ANIMATION
        // ==========================================

        // 1. Reset everything to dim first
        const idleColor = '#444';
        const activeColor = '#007acc'; // Blue for MEM forwarding
        const wbColor = '#4ec9b0';     // Teal for WB forwarding

        setWire('wire-mem-fwd', idleColor, '2', '4,4'); // Dashed = idle
        setWire('wire-wb-fwd',  idleColor, '2', '4,4');
        setWire('wire-fwd-a',   idleColor, '2', 'none');
        setWire('wire-fwd-b',   idleColor, '2', 'none');

        // 2. Animate Forward A (Mux A)
        if (data.fwd) {
            if (data.fwd.a_sel === 1) {
                // 1 = Forward from MEM (Recall: MEM hazard)
                setWire('wire-mem-fwd', activeColor, '4', 'none'); // Solid line
                setWire('wire-fwd-a',   activeColor, '4', 'none');
            }
            else if (data.fwd.a_sel === 2) {
                // 2 = Forward from WB (Recall: WB hazard)
                setWire('wire-wb-fwd', wbColor, '4', 'none');
                setWire('wire-fwd-a',  wbColor, '4', 'none');
            }
        }

        // 3. Animate Forward B (Mux B)
        if (data.fwd) {
            if (data.fwd.b_sel === 1) {
                // 1 = Forward from MEM
                setWire('wire-mem-fwd', activeColor, '4', 'none');
                setWire('wire-fwd-b',   activeColor, '4', 'none');
            }
            else if (data.fwd.b_sel === 2) {
                // 2 = Forward from WB
                setWire('wire-wb-fwd', wbColor, '4', 'none');
                setWire('wire-fwd-b',  wbColor, '4', 'none');
            }
        }

    // ==========================================
        // 8. CONTROL HAZARDS (Branch/Jump & Flush)
        // ==========================================

        // A. FLUSH VISUALIZATION
        // If flush is active, IF and ID stages contain garbage/bubbles.
        if (data.hazard && data.hazard.flush === 1) {
            // Turn IF and ID stages RED
            setFill('stage-if', '#590000'); // Dark Red
            setFill('stage-id', '#590000');

            // Optional: Update text to clarify they are being flushed
            setText('txt-asm-if', '(FLUSHED)');
            setText('txt-asm-id', '(FLUSHED)');
        }

        // B. BRANCH/JUMP WIRE ANIMATION
        // Check if PC Source is taken (1 = Taken/Jump, 0 = Next PC)
        if (data.ex && data.ex.pc_src === 1) {

            // 1. Identify if it is a Branch or Jump based on opcode string
            // We use the 'asm' text from the EX stage to guess.
            const asm = data.asm && data.asm.ex ? data.asm.ex.toLowerCase() : "";
            let type = "REDIRECT";
            if (asm.startsWith('b')) type = "BRANCH"; // beq, bne, etc.
            if (asm.startsWith('j')) type = "JUMP";   // jal, jalr

            // 2. Light up the wire (Orange)
            setWire('wire-branch', '#ff5500', '4', 'none');

            // 3. Update Text Label at the bottom
            const targetAddr = Number(data.ex.pc_jb).toString(16);
            setText('txt-branch-target', `⚡ ${type} TAKEN: 0x${targetAddr}`);

            // Color the text
            const txt = svg.getElementById('txt-branch-target');
            if(txt) txt.style.fill = '#ff5500';

        } else {
            // Normal Operation
            setWire('wire-branch', '#333', '2', '5,5');
            setText('txt-branch-target', ``);
        }


    // 1. Text & PC Updates
        if (data.asm) {
            // Update Assembly Text (e.g., "addi x1, x0, 10")
            ['if','id','ex','mem','wb'].forEach(s => setText(`txt-asm-${s}`, data.asm[s]));

            // Update PC Text (e.g., "0x00000004")
            ['if','id','ex','mem','wb'].forEach(s => setText(`txt-pc-${s}`, data.pc_hex[s]));
        }

        // NEW: Update Hex Instruction Text (e.g., "0x00a00093")
        // This requires Step 1 (Python) to be fixed first!
        if (data.instr) {
            ['if','id','ex','mem','wb'].forEach(s => {
                const rawVal = Number(data.instr[s]);
                // Format to 8-digit Hex string
                const hexStr = "0x" + rawVal.toString(16).padStart(8, '0');
                setText(`txt-hex-${s}`, hexStr);
            });
        }

    // 2. ID STAGE: Update Decode Fields (rs1, rs2, rd)

        if (data.id_info) {
            const rs1 = data.id_info.rs1;
            const rs2 = data.id_info.rs2;
            const rd  = data.id_info.rd;

            setText('txt-rs1-id', `rs1: x${rs1}`);
            setText('txt-rs2-id', `rs2: x${rs2}`);
            setText('txt-rd-id', `->rd: x${rd}`);

            // Optional: Dim text if register is 0 (unused)
            setFill('txt-rs1-id', rs1 === 0 ? '#555' : '#ccc');
            setFill('txt-rs2-id', rs2 === 0 ? '#555' : '#ccc');
            setFill('txt-rd-id',  rd  === 0 ? '#555' : '#ccc');
        }
    // 3. EX STAGE: Show Actual Operands (from Hardware Wires)
   
    if (data.ex) {
        setText('txt-op-a', `OperandA: 0x${Number(data.ex.val_a).toString(16)}`);
        setText('txt-op-b', `OperandB: 0x${Number(data.ex.val_b).toString(16)}`);
        setText('txt-alu-ex', `AluResult: 0x${Number(data.ex.alu_result).toString(16)}`);
    }

    // 4. MEM STAGE
    if (data.mem.we) {
        setText('txt-mem-status', `Store: 0x${Number(data.mem.wdata).toString(16)}`);
    } else {
        setText('txt-mem-status', `Load Addr: 0x${Number(data.mem.addr).toString(16)}`);
    }

    // 5. WB WIRE ANIMATION
    if (data.wb && data.wb.we && data.wb.rd !== 0) {
        setText('txt-wb-reg', `x${data.wb.rd} = 0x${data.wb.wdata.toString(16)}`);
        setFill('txt-wb-reg', '#4ec9b0');
        setWire('wire-wb-back', '#4ec9b0', '3', 'none');
    } else {
        setText('txt-wb-reg', "--");
        setFill('txt-wb-reg', '#666');
        setWire('wire-wb-back', '#444', '2', '5,5');
    }

    // 6. FORWARDING WIRES
    setWire('fwd-mem-id', '#333', '3', '5,5');
    setWire('fwd-wb-id', '#333', '3', '5,5');
    if (data.fwd) {
        if (data.fwd.a_sel == 1 || data.fwd.b_sel == 1) setWire('fwd-mem-id', '#007acc', '5', 'none');
        if (data.fwd.a_sel == 2 || data.fwd.b_sel == 2) setWire('fwd-wb-id', '#4ec9b0', '5', 'none');
    }

    // 7. HAZARD COLORS
    ['if','id'].forEach(s => setFill(`stage-${s}`, '#252526'));
    if (data.hazard && data.hazard.if_stall) setFill('stage-if', '#770000');
    if (data.hazard && data.hazard.id_stall) setFill('stage-id', '#770000');

    // ==========================================
        // 9. INTERACTIVE DATAPATH (Active Stages)
        // ==========================================

        // Define the default "Idle" and "Active" colors
        const colIdle   = '#333';
            const colEx     = '#C71585'; // Magenta (ALU Operation)
            const colMem    = '#d65d0e'; // Orange (Memory)
            const colWB     = '#4ec9b0'; // Teal (Writeback)

            // 1. EX STAGE (New!)
            if (data.instr && data.instr.ex) {
                const ctrl = getControlSignals(data.instr.ex);
                const box = svg.getElementById('stage-ex');
                if (box) {
                    if (ctrl.usesEx) {
                        box.style.stroke = colEx;
                        box.style.strokeWidth = '3';
                        box.style.opacity = '1.0';
                    } else {
                        box.style.stroke = colIdle;
                        box.style.strokeWidth = '1';
                        box.style.opacity = '0.4';
                    }
                }
            }

            // 2. MEM STAGE (Existing - Keep logic but check ctrl.usesMem)
            if (data.instr && data.instr.mem) {
                const ctrl = getControlSignals(data.instr.mem);
                const box = svg.getElementById('stage-mem');
                if (box) {
                    if (ctrl.usesMem) {
                        box.style.stroke = colMem;
                        box.style.strokeWidth = '3';
                        box.style.opacity = '1.0';
                    } else {
                        box.style.stroke = colIdle;
                        box.style.strokeWidth = '1';
                        box.style.opacity = '0.4';
                    }
                }
            }

            // 3. WB STAGE (Updated)
            if (data.instr && data.instr.wb) {
                const ctrl = getControlSignals(data.instr.wb);
                const box = svg.getElementById('stage-wb');
                if (box) {
                    // Now checks writesToReg (rd != 0)
                    if (ctrl.usesWB) {
                        box.style.stroke = colWB;
                        box.style.strokeWidth = '3';
                        box.style.opacity = '1.0';
                    } else {
                        box.style.stroke = colIdle;
                        box.style.strokeWidth = '1';
                        box.style.opacity = '0.4';
                    }
                }
            }
}

// --- 5. HELPER: INSTRUCTION CONTROL SIGNALS ---
// Returns an object defining which stages this instruction uses
// --- 5. HELPER: INSTRUCTION CONTROL SIGNALS ---
function getControlSignals(hexStr) {
    const inst = Number(hexStr);
    if (isNaN(inst) || inst === 0) return { type: 'BUBBLE', usesEx: false, usesMem: false, usesWB: false };

    const opcode = inst & 0x7F;
    const rd     = (inst >> 7) & 0x1F;

    // Crucial Fix: If rd is 0, it's a NOP or a discard. Don't highlight Writeback.
    const writesToReg = (rd !== 0);

    // Standard NOP is 0x00000013 (addi x0, x0, 0)
    if (inst === 0x13) return { type: 'NOP', usesEx: false, usesMem: false, usesWB: false };

    switch(opcode) {
        case 0x33: // R-Type (ADD, SUB, XOR...)
        case 0x13: // I-Type (ADDI, ANDI...)
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


// --- 4. HELPER: RISC-V DISASSEMBLER ---
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

    // R-Type (ADD, SUB, XOR, OR, AND, SLT)
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

    // I-Type (ADDI, ANDI, ORI, XORI, LW)
    if (opcode === 0x13) { // OP-IMM
        let op = "addi";
        if (funct3 === 0x4) op = "xori";
        if (funct3 === 0x6) op = "ori";
        if (funct3 === 0x7) op = "andi";
        const imm = (inst >> 20); // Signed immediate (simplified)
        // Correct sign extension for 12-bit immediate
        const simm = (imm & 0x800) ? (imm | 0xFFFFF000) : imm;
        return `${op} ${regName(rd)}, ${regName(rs1)}, ${simm}`;
    }
    if (opcode === 0x03) { // LOAD
        return `lw ${regName(rd)}, offset(${regName(rs1)})`;
    }

    // S-Type (SW)
    if (opcode === 0x23) {
        return `sw ${regName(rs2)}, offset(${regName(rs1)})`;
    }

    // B-Type (BEQ, BNE)
    if (opcode === 0x63) {
        let op = (funct3 === 0x0) ? "beq" : "bne";
        return `${op} ${regName(rs1)}, ${regName(rs2)}, target`;
    }

    // U-Type (LUI, AUIPC) or J-Type (JAL)
    if (opcode === 0x37) return `lui ${regName(rd)}, imm`;
    if (opcode === 0x17) return `auipc ${regName(rd)}, imm`;
    if (opcode === 0x6F) return `jal ${regName(rd)}, target`;

    return "unknown";
}